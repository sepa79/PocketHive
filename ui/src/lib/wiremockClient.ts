import { apiFetch } from './api'
import type { Component } from '../types/hive'

interface WiremockRequestSummary {
  id?: string
  method?: string
  url?: string
  status?: number
  loggedDate?: number
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

function normaliseRequest(entry: unknown): WiremockRequestSummary | null {
  if (!isRecord(entry)) return null
  const id = typeof entry['id'] === 'string' ? entry['id'] : undefined
  const loggedDate =
    typeof entry['loggedDate'] === 'number'
      ? entry['loggedDate']
      : typeof entry['loggedDate'] === 'string'
      ? Number.parseInt(entry['loggedDate'] as string, 10)
      : undefined
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
  const base = '/wiremock/__admin'
  const [health, count, recent, unmatched] = await Promise.all([
    fetchJson(`${base}/health`),
    fetchJson(`${base}/requests/count`),
    fetchJson(`${base}/requests?limit=${limit}`),
    fetchJson(`${base}/requests/unmatched`),
  ])

  const healthStatus = extractHealthStatus(health || undefined)
  const version = extractVersion(health || undefined)
  const totalRequests = isRecord(count) && typeof count['count'] === 'number' ? count['count'] : undefined
  const recentRequestsRaw =
    isRecord(recent) && Array.isArray(recent['requests']) ? recent['requests'] : []
  const unmatchedRequestsRaw =
    isRecord(unmatched) && Array.isArray(unmatched['requests']) ? unmatched['requests'] : []

  const recentRequests = recentRequestsRaw
    .map((entry) => normaliseRequest(entry))
    .filter((entry): entry is WiremockRequestSummary => entry !== null)
  const unmatchedRequests = unmatchedRequestsRaw
    .map((entry) => normaliseRequest(entry))
    .filter((entry): entry is WiremockRequestSummary => entry !== null)

  if (!healthStatus && typeof totalRequests !== 'number' && recentRequests.length === 0) {
    return null
  }

  const config: Record<string, unknown> = {
    healthStatus: healthStatus ?? 'UNKNOWN',
    recentRequests,
    unmatchedRequests,
  }
  if (typeof totalRequests === 'number') {
    config.requestCount = totalRequests
  }
  if (version) {
    config.version = version
  }

  return {
    id: 'wiremock',
    name: 'WireMock',
    role: 'wiremock',
    lastHeartbeat: Date.now(),
    status: healthStatus,
    queues: [],
    config,
  }
}
