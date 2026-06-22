import { useMemo, type ReactNode } from 'react'
import type { LabelMatchers } from '../../lib/prometheus'
import { usePrometheusRangeQuery } from '../../lib/prometheus'
import { MetricToolbar } from './MetricToolbar'
import { TimeseriesChart } from './TimeseriesChart'

export interface MetricDefinition {
  title: string
  query: string
  unit?: string
  description?: string
  labelMatchers?: LabelMatchers
}

export interface MetricCardProps {
  definition: MetricDefinition
  filters?: LabelMatchers
  lookbackMs?: number
  stepSeconds?: number
  toolbarActions?: ReactNode
  className?: string
}

export function MetricCard({
  definition,
  filters,
  lookbackMs,
  stepSeconds,
  toolbarActions,
  className,
}: MetricCardProps) {
  const mergedMatchers = useMemo(() => {
    if (!definition.labelMatchers && !filters) {
      return undefined
    }
    const merged = { ...(definition.labelMatchers ?? {}), ...(filters ?? {}) }
    const sortedEntries = Object.entries(merged).sort((a, b) => a[0].localeCompare(b[0]))
    return Object.fromEntries(sortedEntries)
  }, [definition.labelMatchers, filters])

  const queryResult = usePrometheusRangeQuery(definition.query, {
    lookbackMs,
    stepSeconds,
    labelMatchers: mergedMatchers,
  })

  const { data, isPending, isError, error } = queryResult
  const warnings = data?.warnings
  const hasData = (data?.series.length ?? 0) > 0

  const containerClass = ['card flex h-full flex-col gap-4 p-4', className].filter(Boolean).join(' ')

  return (
    <div className={containerClass}>
      <MetricToolbar
        title={definition.title}
        unit={definition.unit}
        subtitle={definition.description}
        actions={toolbarActions}
      />
      <div className="flex-1 overflow-visible">
        {isPending ? (
          <div className="flex h-full items-center justify-center text-sm text-neutral-400">
            Loading metrics…
          </div>
        ) : isError ? (
          <div className="text-sm text-red-300">{error?.message ?? 'Unable to load metrics'}</div>
        ) : !hasData ? (
          <div className="text-sm text-neutral-400">No samples returned for the selected range.</div>
        ) : (
          <TimeseriesChart data={data!.series} unit={definition.unit} />
        )}
      </div>
      {warnings && warnings.length > 0 ? (
        <div className="text-xs text-amber-300">{warnings.join(' • ')}</div>
      ) : null}
    </div>
  )
}
