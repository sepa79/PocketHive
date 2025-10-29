import { useMemo } from 'react'
import { useQuery, UseQueryOptions } from '@tanstack/react-query'
import { apiFetch } from './api'
import { getConfig } from './config'

export type LabelMatchers = Record<string, string | undefined>

export interface PrometheusSample {
  /** Timestamp in milliseconds */
  timestamp: number
  /** Parsed numeric value (NaN handled as null) */
  value: number | null
  /** Raw string value as returned by Prometheus */
  rawValue: string
}

export interface PrometheusRangeSeries {
  metric: Record<string, string>
  samples: PrometheusSample[]
}

export interface PrometheusInstantVectorSample {
  metric: Record<string, string>
  sample: PrometheusSample
}

export interface PrometheusScalarResult {
  sample: PrometheusSample
}

interface PrometheusResponse<T> {
  status: 'success'
  data: T
  warnings?: string[]
}

interface PrometheusErrorResponse {
  status: 'error'
  errorType: string
  error: string
}

export interface PrometheusRangeQueryOptions {
  query: string
  start?: Date | number
  end?: Date | number
  stepSeconds?: number
  labelMatchers?: LabelMatchers
}

export interface PrometheusQueryOptions {
  query: string
  time?: Date | number
  labelMatchers?: LabelMatchers
}

export interface PrometheusSeriesOptions {
  metric: string
  labelMatchers?: LabelMatchers
  start?: Date | number
  end?: Date | number
}

export interface PrometheusRangeQueryResult {
  series: PrometheusRangeSeries[]
  warnings?: string[]
}

export interface PrometheusInstantQueryResult {
  resultType: 'vector' | 'scalar'
  vector?: PrometheusInstantVectorSample[]
  scalar?: PrometheusScalarResult
  warnings?: string[]
}

export type PrometheusSeriesResult = {
  series: Record<string, string>[]
  warnings?: string[]
}

const METRIC_REGEX = /([a-zA-Z_:][a-zA-Z0-9_:]*)(\{[^}]*\})?/g
const RESERVED_WORDS = new Set([
  'sum',
  'avg',
  'min',
  'max',
  'count',
  'stddev',
  'stdvar',
  'sum_over_time',
  'avg_over_time',
  'min_over_time',
  'max_over_time',
  'stddev_over_time',
  'stdvar_over_time',
  'quantile_over_time',
  'count_over_time',
  'count_values',
  'rate',
  'irate',
  'increase',
  'delta',
  'deriv',
  'resets',
  'last_over_time',
  'changes',
  'predict_linear',
  'holt_winters',
  'sort',
  'sort_desc',
  'topk',
  'bottomk',
  'label_replace',
  'label_join',
  'absent',
  'absent_over_time',
  'clamp_max',
  'clamp_min',
  'count_scalar',
  'sum_scalar',
  'group_left',
  'group_right',
  'ignoring',
  'on',
  'by',
  'without',
  'offset',
])

function buildPrometheusUrl(path: string, params: Record<string, string | number | undefined>): string {
  const base = getConfig().prometheus
  const url = new URL(path.replace(/^\//, ''), base)
  Object.entries(params).forEach(([key, value]) => {
    if (value === undefined || value === null || value === '') {
      return
    }
    url.searchParams.append(key, String(value))
  })
  return url.toString()
}

function mergeLabelMatchers(labelMatchers?: LabelMatchers): string | undefined {
  if (!labelMatchers) {
    return undefined
  }
  const entries = Object.entries(labelMatchers)
    .filter(([, value]) => value !== undefined && value !== '')
    .map(([key, value]) => `${key}="${value!.replace(/"/g, '\\"')}"`)

  return entries.length > 0 ? entries.join(',') : undefined
}

function applyLabelMatchersToQuery(query: string, labelMatchers?: LabelMatchers): string {
  const matcher = mergeLabelMatchers(labelMatchers)
  if (!matcher) {
    return query
  }

  if (query.includes('{{labelMatchers}}')) {
    return query.replaceAll('{{labelMatchers}}', matcher)
  }

  let applied = false
  return query.replace(METRIC_REGEX, (fullMatch, metric: string, existingSelectors: string | undefined, index: number) => {
    if (applied || RESERVED_WORDS.has(metric)) {
      return fullMatch
    }
    const nextChar = query[index + fullMatch.length]
    if (existingSelectors) {
      const content = existingSelectors.slice(1, -1).trim()
      const combined = [content, matcher].filter(Boolean).join(',')
      applied = true
      return `${metric}{${combined}}`
    }
    if (nextChar !== '(' && nextChar !== '=') {
      applied = true
      return `${metric}{${matcher}}`
    }
    return fullMatch
  })
}

function normaliseSample(value: [number | string, string]): PrometheusSample {
  const timestampSeconds = typeof value[0] === 'string' ? parseFloat(value[0]) : value[0]
  const rawValue = value[1]
  const parsedValue = Number(rawValue)
  return {
    timestamp: Math.round(timestampSeconds * 1000),
    rawValue,
    value: Number.isFinite(parsedValue) ? parsedValue : null,
  }
}

async function readPrometheus<T>(path: string, params: Record<string, string | number | undefined>): Promise<PrometheusResponse<T>> {
  const url = buildPrometheusUrl(path, params)
  const response = await apiFetch(url, { cache: 'no-store' })
  if (!response.ok) {
    throw new Error(`Prometheus request failed with status ${response.status}`)
  }
  const payload = (await response.json()) as PrometheusResponse<T> | PrometheusErrorResponse
  if (payload.status === 'error') {
    throw new Error(payload.error)
  }
  return payload
}

export async function prometheusRangeQuery(options: PrometheusRangeQueryOptions): Promise<PrometheusRangeQueryResult> {
  const { query, start, end, stepSeconds = 30, labelMatchers } = options
  const finalQuery = applyLabelMatchersToQuery(query, labelMatchers)
  const params: Record<string, string | number | undefined> = {
    query: finalQuery,
    step: stepSeconds,
    start: start instanceof Date ? Math.floor(start.getTime() / 1000) : start,
    end: end instanceof Date ? Math.floor(end.getTime() / 1000) : end,
  }
  const payload = await readPrometheus<{ resultType: 'matrix'; result: Array<{ metric: Record<string, string>; values: [number, string][] }> }>('/api/v1/query_range', params)
  return {
    series: payload.data.result.map((entry) => ({
      metric: entry.metric,
      samples: entry.values.map(normaliseSample),
    })),
    warnings: payload.warnings,
  }
}

export async function prometheusInstantQuery(options: PrometheusQueryOptions): Promise<PrometheusInstantQueryResult> {
  const { query, time, labelMatchers } = options
  const finalQuery = applyLabelMatchersToQuery(query, labelMatchers)
  const params: Record<string, string | number | undefined> = {
    query: finalQuery,
    time: time instanceof Date ? Math.floor(time.getTime() / 1000) : time,
  }

  const payload = await readPrometheus<{
    resultType: 'vector' | 'scalar'
    result:
      | Array<{ metric: Record<string, string>; value: [number, string] }>
      | [number, string]
  }>('/api/v1/query', params)

  if (payload.data.resultType === 'scalar') {
    return {
      resultType: 'scalar',
      scalar: {
        sample: normaliseSample(payload.data.result as [number, string]),
      },
      warnings: payload.warnings,
    }
  }

  const vector = payload.data.result as Array<{ metric: Record<string, string>; value: [number, string] }>
  return {
    resultType: 'vector',
    vector: vector.map((item) => ({
      metric: item.metric,
      sample: normaliseSample(item.value),
    })),
    warnings: payload.warnings,
  }
}

export async function prometheusSeries(options: PrometheusSeriesOptions): Promise<PrometheusSeriesResult> {
  const { metric, labelMatchers, start, end } = options
  const matcher = mergeLabelMatchers(labelMatchers)
  const matchValue = matcher ? `${metric}{${matcher}}` : metric
  const params: Record<string, string | number | undefined> = {
    'match[]': matchValue,
    start: start instanceof Date ? Math.floor(start.getTime() / 1000) : start,
    end: end instanceof Date ? Math.floor(end.getTime() / 1000) : end,
  }

  const payload = await readPrometheus<Record<string, string>[]>('/api/v1/series', params)
  return {
    series: payload.data,
    warnings: payload.warnings,
  }
}

export interface UsePrometheusRangeQueryOptions {
  lookbackMs?: number
  stepSeconds?: number
  end?: Date
  enabled?: boolean
  labelMatchers?: LabelMatchers
  queryOptions?: Omit<UseQueryOptions<PrometheusRangeQueryResult, Error>, 'queryKey' | 'queryFn'>
}

export function usePrometheusRangeQuery(
  query: string,
  options: UsePrometheusRangeQueryOptions = {},
) {
  const {
    lookbackMs = 30 * 60 * 1000,
    stepSeconds = 30,
    end,
    enabled = true,
    labelMatchers,
    queryOptions,
  } = options

  const range = useMemo(() => {
    const endDate = end ?? new Date()
    const startDate = new Date(endDate.getTime() - lookbackMs)
    return { start: startDate, end: endDate }
  }, [end, lookbackMs])
  const labelMatcherKey = useMemo(() => JSON.stringify(labelMatchers ?? {}), [labelMatchers])

  return useQuery<PrometheusRangeQueryResult, Error>({
    queryKey: [
      'prometheus',
      'range',
      query,
      range.start.getTime(),
      range.end.getTime(),
      stepSeconds,
      labelMatcherKey,
    ],
    queryFn: () =>
      prometheusRangeQuery({
        query,
        start: range.start,
        end: range.end,
        stepSeconds,
        labelMatchers,
      }),
    refetchInterval: 10_000,
    enabled,
    ...queryOptions,
  })
}

export interface UsePrometheusLabelValuesOptions {
  lookbackMs?: number
  enabled?: boolean
  labelMatchers?: LabelMatchers
  queryOptions?: Omit<UseQueryOptions<string[], Error>, 'queryKey' | 'queryFn'>
}

export function usePrometheusLabelValues(
  metric: string,
  label: string,
  options: UsePrometheusLabelValuesOptions = {},
) {
  const { lookbackMs = 30 * 60 * 1000, enabled = true, labelMatchers, queryOptions } = options
  const labelMatcherKey = useMemo(() => JSON.stringify(labelMatchers ?? {}), [labelMatchers])

  return useQuery<string[], Error>({
    queryKey: ['prometheus', 'labelValues', metric, label, lookbackMs, labelMatcherKey],
    queryFn: async () => {
      const end = new Date()
      const start = new Date(end.getTime() - lookbackMs)
      const { series } = await prometheusSeries({
        metric,
        labelMatchers,
        start,
        end,
      })
      const values = series
        .map((item) => item[label])
        .filter((value): value is string => Boolean(value))
      return Array.from(new Set(values)).sort((a, b) => a.localeCompare(b))
    },
    refetchInterval: 60_000,
    enabled,
    ...queryOptions,
  })
}
