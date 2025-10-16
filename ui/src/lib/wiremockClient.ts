import { apiFetch } from './api'
import { getConfig } from './config'
import type { Component } from '../types/hive'

export interface WiremockRequestSummary {
  id?: string
  method?: string
  url?: string
  status?: number
  loggedDate?: number
}

export interface WiremockScenarioSummary {
  id?: string
  name: string
  state: string
  completed?: boolean
}

export interface WiremockComponentConfig extends Record<string, unknown> {
  healthStatus: string
  version?: string
  requestCount?: number
  requestCountError?: boolean
  stubCount?: number
  stubCountError?: boolean
  unmatchedCount?: number
  recentRequests: WiremockRequestSummary[]
  recentRequestsError?: boolean
  unmatchedRequests: WiremockRequestSummary[]
  unmatchedRequestsError?: boolean
  scenarios: WiremockScenarioSummary[]
  scenariosError?: boolean
  adminUrl: string
  lastUpdatedTs: number
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function extractHealthStatus(health: unknown): string | undefined {
  if (typeof health === 'string') return health
  if (!isRecord(health)) return undefined
  const direct = health['status']
  if (typeof direct === 'string') return direct
  if (isRecord(direct) && typeof direct['status'] === 'string') {
    return direct['status'] as string
  }
  return undefined
}

function extractVersion(health: unknown): string | undefined {
  if (!isRecord(health)) return undefined
  const version = health['version']
  if (typeof version === 'string') return version
  if (isRecord(version) && typeof version['version'] === 'string') {
    return version['version'] as string
  }
  return undefined
}

function parseLoggedDate(entry: Record<string, unknown>): number | undefined {
  const raw = entry['loggedDate']
  if (typeof raw === 'number') {
    return raw
  }
  if (typeof raw === 'string' && raw.length > 0) {
    const numeric = Number(raw)
    if (!Number.isNaN(numeric)) {
      return numeric
    }
    const parsed = Date.parse(raw)
    if (!Number.isNaN(parsed)) {
      return parsed
    }
  }
  const loggedDateString = entry['loggedDateString']
  if (typeof loggedDateString === 'string' && loggedDateString.length > 0) {
    const parsed = Date.parse(loggedDateString)
    if (!Number.isNaN(parsed)) {
      return parsed
    }
  }
  return undefined
}

function normaliseRequest(entry: unknown): WiremockRequestSummary | null {
  if (!isRecord(entry)) return null
  const id = typeof entry['id'] === 'string' ? entry['id'] : undefined
  const loggedDate = parseLoggedDate(entry)
  const request = isRecord(entry['request']) ? entry['request'] : undefined
  const response = isRecord(entry['response']) ? entry['response'] : undefined
  const method = request && typeof request['method'] === 'string' ? request['method'] : undefined
  const url = request && typeof request['url'] === 'string' ? request['url'] : undefined
  const status = response && typeof response['status'] === 'number' ? response['status'] : undefined
  return {
    id,
    method,
    url,
    status,
    loggedDate,
  }
}

function normaliseScenario(entry: unknown): WiremockScenarioSummary | null {
  if (!isRecord(entry)) return null
  const name = typeof entry['name'] === 'string' ? entry['name'] : undefined
  const state = typeof entry['state'] === 'string' ? entry['state'] : undefined
  if (!name || !state) return null
  const scenario: WiremockScenarioSummary = { name, state }
  if (typeof entry['id'] === 'string') scenario.id = entry['id']
  if (typeof entry['completed'] === 'boolean') scenario.completed = entry['completed']
  return scenario
}

async function fetchJson(path: string): Promise<unknown | null> {
  try {
    const response = await apiFetch(path)
    if (!response.ok) return null
    return await response.json()
  } catch {
    return null
  }
}

export async function fetchWiremockComponent(limit = 25): Promise<Component | null> {
  const configUrl = getConfig().wiremock
  const base =
    typeof configUrl === 'string' && configUrl.length > 0 ? configUrl.replace(/\/+$/, '') : '/wiremock/__admin'
  const [health, count, recent, unmatched, mappings, scenarios] = await Promise.all([
    fetchJson(`${base}/health`),
    fetchJson(`${base}/requests/count`),
    fetchJson(`${base}/requests?limit=${limit}`),
    fetchJson(`${base}/requests/unmatched`),
    fetchJson(`${base}/mappings`),
    fetchJson(`${base}/scenarios`),
  ])

  const healthStatus = extractHealthStatus(health || undefined)
  const version = extractVersion(health || undefined)
  const totalRequests = isRecord(count) && typeof count['count'] === 'number' ? count['count'] : undefined
  const recentRequestsRaw =
    isRecord(recent) && Array.isArray(recent['requests']) ? recent['requests'] : []
  const recentMeta = isRecord(recent) && isRecord(recent['meta']) ? (recent['meta'] as Record<string, unknown>) : undefined
  const recentTotal = recentMeta && typeof recentMeta['total'] === 'number' ? recentMeta['total'] : undefined
  const unmatchedRequestsRaw =
    isRecord(unmatched) && Array.isArray(unmatched['requests']) ? unmatched['requests'] : []

  const recentRequests = recentRequestsRaw
    .map((entry) => normaliseRequest(entry))
    .filter((entry): entry is WiremockRequestSummary => entry !== null)
  const unmatchedRequests = unmatchedRequestsRaw
    .map((entry) => normaliseRequest(entry))
    .filter((entry): entry is WiremockRequestSummary => entry !== null)

  const scenariosRaw =
    isRecord(scenarios) && Array.isArray(scenarios['scenarios']) ? scenarios['scenarios'] : []
  const scenarioSummaries = scenariosRaw
    .map((entry) => normaliseScenario(entry))
    .filter((entry): entry is WiremockScenarioSummary => entry !== null)

  let stubCount: number | undefined
  if (isRecord(mappings) && isRecord(mappings['meta'])) {
    const meta = mappings['meta'] as Record<string, unknown>
    if (typeof meta['total'] === 'number') {
      stubCount = meta['total']
    }
  }
  const unmatchedMetaTotal =
    isRecord(unmatched) && isRecord(unmatched['meta'])
      ? (unmatched['meta'] as Record<string, unknown>)['total']
      : undefined
  const unmatchedTotal =
    typeof unmatchedMetaTotal === 'number' ? unmatchedMetaTotal : unmatchedRequests.length

  const config: WiremockComponentConfig = {
    healthStatus: healthStatus ?? 'UNKNOWN',
    recentRequests,
    recentRequestsError: recent === null,
    unmatchedRequests,
    unmatchedRequestsError: unmatched === null,
    unmatchedCount: unmatchedTotal,
    scenarios: scenarioSummaries,
    scenariosError: scenarios === null,
    adminUrl: `${base}/`,
    lastUpdatedTs: Date.now(),
  }

  if (typeof totalRequests === 'number') {
    config.requestCount = totalRequests
  } else if (typeof recentTotal === 'number') {
    config.requestCount = recentTotal
  } else if (count === null) {
    config.requestCountError = true
  }

  if (typeof stubCount === 'number') {
    config.stubCount = stubCount
  } else if (mappings === null) {
    config.stubCountError = true
  }

  if (version) {
    config.version = version
  }

  const now = Date.now()

  return {
    id: 'wiremock',
    name: 'WireMock',
    role: 'wiremock',
    swarmId: 'default',
    lastHeartbeat: healthStatus ? now : 0,
    status: healthStatus ?? 'ALERT',
    queues: [],
    config,
  }
}
