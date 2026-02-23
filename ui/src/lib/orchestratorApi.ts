import { apiFetch } from './api'
import { randomId } from './id'
import type { Component } from '../types/hive'
import type { SwarmSummary, BeeSummary, SwarmJournalEntry } from '../types/orchestrator'

export interface JournalCursor {
  ts: string
  id: number
}

export interface JournalPageResponse {
  items: SwarmJournalEntry[]
  nextCursor: JournalCursor | null
  hasMore: boolean
}

export interface JournalRunSummary {
  runId: string
  firstTs: string | null
  lastTs: string | null
  entries: number
  pinned?: boolean
}

interface SwarmManagersTogglePayload {
  idempotencyKey: string
  enabled: boolean
}

type ApiError = Error & { status?: number }

async function ensureOk(response: Response, fallback: string) {
  if (response.ok) return
  let message = ''
  try {
    const text = await response.text()
    if (text) {
      try {
        const data = JSON.parse(text) as { message?: unknown }
        if (data && typeof data === 'object' && typeof data.message === 'string') {
          message = data.message
        } else if (!message) {
          message = text
        }
      } catch {
        message = text
      }
    }
  } catch {
    // ignore body parsing errors
  }

  const error: ApiError = new Error(message || fallback)
  error.status = response.status
  throw error
}

export async function createSwarm(
  id: string,
  templateId: string,
  options?: { autoPullImages?: boolean; sutId?: string | null; variablesProfileId?: string | null },
) {
  const payload: Record<string, unknown> = {
    templateId,
    idempotencyKey: randomId(),
  }
  if (options && typeof options.autoPullImages === 'boolean') {
    payload.autoPullImages = options.autoPullImages
  }
  if (options && typeof options.sutId === 'string' && options.sutId.trim()) {
    payload.sutId = options.sutId.trim()
  }
  if (options && typeof options.variablesProfileId === 'string' && options.variablesProfileId.trim()) {
    payload.variablesProfileId = options.variablesProfileId.trim()
  }

  const body = JSON.stringify(payload)
  const response = await apiFetch(`/orchestrator/swarms/${id}/create`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body,
  })

  if (!response.ok) {
    let message = ''
    try {
      const text = await response.text()
      if (text) {
        try {
          const data = JSON.parse(text) as { message?: unknown }
          if (data && typeof data === 'object' && typeof data.message === 'string') {
            message = data.message
          }
        } catch {
          message = text
        }
      }
    } catch {
      // ignore body parsing errors
    }

    if (!message) {
      message = response.status === 409 ? 'Swarm already exists' : 'Failed to create swarm'
    }

    const error: ApiError = new Error(message)
    error.status = response.status
    throw error
  }
}

export async function startSwarm(id: string) {
  const payload: Record<string, unknown> = {
    idempotencyKey: randomId(),
  }
  const body = JSON.stringify(payload)
  const response = await apiFetch(`/orchestrator/swarms/${id}/start`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body,
  })
  await ensureOk(response, 'Failed to start swarm')
}

export async function stopSwarm(id: string) {
  const body = JSON.stringify({ idempotencyKey: randomId() })
  const response = await apiFetch(`/orchestrator/swarms/${id}/stop`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body,
  })
  await ensureOk(response, 'Failed to stop swarm')
}

export async function removeSwarm(id: string) {
  const body = JSON.stringify({ idempotencyKey: randomId() })
  const response = await apiFetch(`/orchestrator/swarms/${id}/remove`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body,
  })
  await ensureOk(response, 'Failed to remove swarm')
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

function asString(value: unknown): string | null {
  if (typeof value !== 'string') return null
  const trimmed = value.trim()
  return trimmed.length > 0 ? trimmed : null
}

function asStringArray(value: unknown): string[] | null {
  if (!Array.isArray(value)) return null
  const out: string[] = []
  for (const entry of value) {
    if (typeof entry !== 'string') continue
    const trimmed = entry.trim()
    if (trimmed.length === 0) continue
    if (!out.includes(trimmed)) out.push(trimmed)
  }
  return out.length > 0 ? out : null
}

function normalizeBee(input: unknown): BeeSummary | null {
  if (!isRecord(input)) return null
  const record = input as Record<string, unknown>
  const role = asString(record['role'])
  if (!role) return null
  const image = asString(record['image'])
  return { role, image }
}

function normalizeSwarmSummary(input: unknown): SwarmSummary | null {
  if (!isRecord(input)) return null
  const record = input as Record<string, unknown>
  const id = asString(record['id'])
  const status = asString(record['status'])
  if (!id || !status) return null
  const beesValue = Array.isArray(record['bees']) ? (record['bees'] as unknown[]) : []
  const bees = beesValue
    .map((entry) => normalizeBee(entry))
    .filter((entry): entry is BeeSummary => Boolean(entry))

  const sutId = asString(record['sutId'])

  return {
    id,
    status,
    health: asString(record['health']),
    heartbeat: asString(record['heartbeat']),
    workEnabled: record['workEnabled'] === true,
    controllerEnabled: record['controllerEnabled'] === true,
    templateId: asString(record['templateId']),
    controllerImage: asString(record['controllerImage']),
    sutId,
    stackName: asString(record['stackName']),
    bees,
  }
}

function normalizeJournalEntry(input: unknown): SwarmJournalEntry | null {
  if (!isRecord(input)) return null
  const record = input as Record<string, unknown>
  const eventId = record['eventId']
  const parsedEventId =
    typeof eventId === 'number' && Number.isFinite(eventId) ? eventId : null
  let timestamp = asString(record['timestamp'])
  if (!timestamp) {
    const rawTs = record['timestamp']
    if (typeof rawTs === 'number' && Number.isFinite(rawTs)) {
      // Backend may serialise Instants as epoch seconds; normalise to ISO string
      const millis = rawTs * 1000
      timestamp = new Date(millis).toISOString()
    }
  }
  const swarmId = asString(record['swarmId'])
  const runId = asString(record['runId'])
  const severity = asString(record['severity'])
  const direction = asString(record['direction'])
  const kind = asString(record['kind'])
  const type = asString(record['type'])
  const origin = asString(record['origin'])
  const scopeValue = record['scope']
  const scopeRecord = scopeValue && typeof scopeValue === 'object' ? (scopeValue as Record<string, unknown>) : null
  const scopeSwarmId = scopeRecord ? asString(scopeRecord['swarmId']) : null
  const scopeRole = scopeRecord ? asString(scopeRecord['role']) : null
  const scopeInstance = scopeRecord ? asString(scopeRecord['instance']) : null

  if (
    !timestamp ||
    !swarmId ||
    !severity ||
    (direction !== 'IN' && direction !== 'OUT' && direction !== 'LOCAL') ||
    !kind ||
    !type ||
    !origin ||
    !scopeSwarmId
  ) {
    return null
  }
  const correlationId = asString(record['correlationId'])
  const idempotencyKey = asString(record['idempotencyKey'])
  const routingKey = asString(record['routingKey'])
  const dataValue = record['data']
  const rawValue = record['raw']
  const extraValue = record['extra']
  const data = dataValue && typeof dataValue === 'object' ? (dataValue as Record<string, unknown>) : null
  const raw = rawValue && typeof rawValue === 'object' ? (rawValue as Record<string, unknown>) : null
  const extra = extraValue && typeof extraValue === 'object' ? (extraValue as Record<string, unknown>) : null
  return {
    eventId: parsedEventId,
    timestamp,
    swarmId,
    runId,
    severity,
    direction,
    kind,
    type,
    origin,
    scope: { swarmId: scopeSwarmId, role: scopeRole, instance: scopeInstance },
    correlationId,
    idempotencyKey,
    routingKey,
    data,
    raw,
    extra,
  }
}

async function parseSwarmSummaries(response: Response): Promise<SwarmSummary[]> {
  try {
    const payload = await response.json()
    if (!Array.isArray(payload)) {
      return []
    }
    return payload
      .map((entry) => normalizeSwarmSummary(entry))
      .filter((entry): entry is SwarmSummary => Boolean(entry))
  } catch {
    return []
  }
}

async function parseSwarmSummary(response: Response): Promise<SwarmSummary | null> {
  try {
    const payload = await response.json()
    return normalizeSwarmSummary(payload)
  } catch {
    return null
  }
}

export async function listSwarms(): Promise<SwarmSummary[]> {
  const response = await apiFetch('/orchestrator/swarms', {
    headers: { Accept: 'application/json' },
  })
  await ensureOk(response, 'Failed to load swarm metadata')
  return parseSwarmSummaries(response)
}

export async function refreshSwarmRegistry(): Promise<void> {
  const body = JSON.stringify({ idempotencyKey: randomId() })
  const response = await apiFetch('/orchestrator/swarms/refresh', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body,
  })
  await ensureOk(response, 'Failed to trigger swarm refresh')
}

export async function getSwarm(id: string): Promise<SwarmSummary | null> {
  const response = await apiFetch(`/orchestrator/swarms/${id}`, {
    headers: { Accept: 'application/json' },
  })
  if (response.status === 404) {
    return null
  }
  await ensureOk(response, 'Failed to load swarm metadata')
  return parseSwarmSummary(response)
}

export async function getSwarmJournal(id: string, options?: { runId?: string | null }): Promise<SwarmJournalEntry[]> {
  const query = buildQuery({ runId: options?.runId ?? undefined })
  const response = await apiFetch(`/orchestrator/swarms/${id}/journal${query}`, {
    headers: { Accept: 'application/json' },
  })
  if (response.status === 404) {
    return []
  }
  await ensureOk(response, 'Failed to load swarm journal')
  try {
    const payload = await response.json()
    if (!Array.isArray(payload)) {
      return []
    }
    return payload
      .map((entry) => normalizeJournalEntry(entry))
      .filter((entry): entry is SwarmJournalEntry => Boolean(entry))
  } catch {
    return []
  }
}

function buildQuery(params: Record<string, string | number | null | undefined>): string {
  const parts: string[] = []
  for (const [key, value] of Object.entries(params)) {
    if (value === null || value === undefined) continue
    const text = typeof value === 'number' ? String(value) : value
    if (!text.trim()) continue
    parts.push(`${encodeURIComponent(key)}=${encodeURIComponent(text)}`)
  }
  return parts.length ? `?${parts.join('&')}` : ''
}

export async function getSwarmJournalPage(
  id: string,
  options?: {
    limit?: number
    correlationId?: string | null
    runId?: string | null
    before?: JournalCursor | null
  },
): Promise<JournalPageResponse | null> {
  const limit = options?.limit
  const correlationId = options?.correlationId ?? null
  const runId = options?.runId ?? null
  const before = options?.before ?? null
  const query = buildQuery({
    limit,
    correlationId: correlationId ?? undefined,
    runId: runId ?? undefined,
    beforeTs: before?.ts ?? undefined,
    beforeId: before?.id ?? undefined,
  })
  const response = await apiFetch(`/orchestrator/swarms/${id}/journal/page${query}`, {
    headers: { Accept: 'application/json' },
  })
  if (response.status === 404) {
    return null
  }
  await ensureOk(response, 'Failed to load swarm journal page')
  let payload: unknown
  try {
    payload = await response.json()
  } catch {
    return { items: [], nextCursor: null, hasMore: false }
  }
  if (!isRecord(payload)) {
    return { items: [], nextCursor: null, hasMore: false }
  }
  const record = payload as Record<string, unknown>
  const itemsValue = Array.isArray(record['items']) ? (record['items'] as unknown[]) : []
  const items = itemsValue
    .map((entry) => normalizeJournalEntry(entry))
    .filter((entry): entry is SwarmJournalEntry => Boolean(entry))

  const cursorValue = record['nextCursor']
  let nextCursor: JournalCursor | null = null
  if (cursorValue && typeof cursorValue === 'object') {
    const cursorRecord = cursorValue as Record<string, unknown>
    const ts = asString(cursorRecord['ts'])
    const idValue = cursorRecord['id']
    const cursorId = typeof idValue === 'number' && Number.isFinite(idValue) ? idValue : null
    if (ts && cursorId !== null) {
      nextCursor = { ts, id: cursorId }
    }
  }
  const hasMore = record['hasMore'] === true
  return { items, nextCursor, hasMore }
}

function normalizeRunSummary(input: unknown): JournalRunSummary | null {
  if (!isRecord(input)) return null
  const record = input as Record<string, unknown>
  const runId = asString(record['runId'])
  if (!runId) return null
  const firstTs = asString(record['firstTs'])
  const lastTs = asString(record['lastTs'])
  const entriesValue = record['entries']
  const entries =
    typeof entriesValue === 'number' && Number.isFinite(entriesValue) ? entriesValue : null
  const pinned = record['pinned'] === true
  return {
    runId,
    firstTs,
    lastTs,
    entries: entries !== null ? entries : 0,
    pinned,
  }
}

export async function getSwarmJournalRuns(id: string): Promise<JournalRunSummary[] | null> {
  const response = await apiFetch(`/orchestrator/swarms/${id}/journal/runs`, {
    headers: { Accept: 'application/json' },
  })
  if (response.status === 404) {
    return []
  }
  if (response.status === 501) {
    return null
  }
  await ensureOk(response, 'Failed to load journal runs')
  try {
    const payload = await response.json()
    if (!Array.isArray(payload)) return []
    return payload
      .map((entry) => normalizeRunSummary(entry))
      .filter((entry): entry is JournalRunSummary => Boolean(entry))
  } catch {
    return []
  }
}

export type SwarmRunSummary = {
  swarmId: string
  runId: string
  firstTs?: string | null
  lastTs?: string | null
  entries: number
  pinned: boolean
  scenarioId?: string | null
  testPlan?: string | null
  tags?: string[] | null
  description?: string | null
}

function normalizeSwarmRunSummary(input: unknown): SwarmRunSummary | null {
  if (!isRecord(input)) return null
  const record = input as Record<string, unknown>
  const swarmId = asString(record['swarmId'])
  const runId = asString(record['runId'])
  if (!swarmId || !runId) return null
  const firstTs = asString(record['firstTs'])
  const lastTs = asString(record['lastTs'])
  const entriesValue = record['entries']
  const entries =
    typeof entriesValue === 'number' && Number.isFinite(entriesValue) ? entriesValue : null
  const pinned = record['pinned'] === true
  const scenarioId = asString(record['scenarioId'])
  const testPlan = asString(record['testPlan'])
  const tags = asStringArray(record['tags'])
  const description = asString(record['description'])
  return {
    swarmId,
    runId,
    firstTs,
    lastTs,
    entries: entries !== null ? entries : 0,
    pinned,
    scenarioId,
    testPlan,
    tags,
    description,
  }
}

export async function getAllSwarmJournalRuns(options?: {
  limit?: number
  pinned?: boolean
  afterTs?: string | null
}): Promise<SwarmRunSummary[] | null> {
  const query = buildQuery({
    limit: options?.limit ?? undefined,
    pinned: options?.pinned === true ? 'true' : undefined,
    afterTs: options?.afterTs ?? undefined,
  })
  const response = await apiFetch(`/orchestrator/journal/swarm/runs${query}`, {
    headers: { Accept: 'application/json' },
  })
  if (response.status === 404) {
    return []
  }
  if (response.status === 501) {
    return null
  }
  await ensureOk(response, 'Failed to load journal runs')
  try {
    const payload = await response.json()
    if (!Array.isArray(payload)) return []
    return payload
      .map((entry) => normalizeSwarmRunSummary(entry))
      .filter((entry): entry is SwarmRunSummary => Boolean(entry))
  } catch {
    return []
  }
}

export async function pinSwarmJournalRun(
  id: string,
  options?: { runId?: string | null; mode?: 'FULL' | 'SLIM' | 'ERRORS_ONLY'; name?: string | null },
): Promise<{ captureId: string | null; swarmId: string; runId: string; mode: string; inserted: number; entries: number } | null> {
  const body = JSON.stringify({
    runId: options?.runId ?? null,
    mode: options?.mode ?? null,
    name: options?.name ?? null,
  })
  const response = await apiFetch(`/orchestrator/swarms/${id}/journal/pin`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
    body,
  })
  await ensureOk(response, 'Failed to pin journal run')
  try {
    const payload = (await response.json()) as Record<string, unknown>
    const captureId = asString(payload['captureId'])
    const swarmId = asString(payload['swarmId']) ?? id
    const runId = asString(payload['runId']) ?? ''
    const mode = asString(payload['mode']) ?? ''
    const insertedValue = payload['inserted']
    const entriesValue = payload['entries']
    const inserted = typeof insertedValue === 'number' && Number.isFinite(insertedValue) ? insertedValue : 0
    const entries = typeof entriesValue === 'number' && Number.isFinite(entriesValue) ? entriesValue : 0
    return { captureId, swarmId, runId, mode, inserted, entries }
  } catch {
    return null
  }
}

export async function getHiveJournalPage(options?: {
  swarmId?: string | null
  runId?: string | null
  correlationId?: string | null
  limit?: number
  before?: JournalCursor | null
}): Promise<JournalPageResponse | null> {
  const query = buildQuery({
    swarmId: options?.swarmId ?? undefined,
    runId: options?.runId ?? undefined,
    correlationId: options?.correlationId ?? undefined,
    limit: options?.limit ?? undefined,
    beforeTs: options?.before?.ts ?? undefined,
    beforeId: options?.before?.id ?? undefined,
  })
  const response = await apiFetch(`/orchestrator/journal/hive/page${query}`, {
    headers: { Accept: 'application/json' },
  })
  if (response.status === 404) {
    return null
  }
  await ensureOk(response, 'Failed to load hive journal page')
  let payload: unknown
  try {
    payload = await response.json()
  } catch {
    return { items: [], nextCursor: null, hasMore: false }
  }
  if (!isRecord(payload)) {
    return { items: [], nextCursor: null, hasMore: false }
  }
  const record = payload as Record<string, unknown>
  const itemsValue = Array.isArray(record['items']) ? (record['items'] as unknown[]) : []
  const items = itemsValue
    .map((entry) => normalizeJournalEntry(entry))
    .filter((entry): entry is SwarmJournalEntry => Boolean(entry))

  const cursorValue = record['nextCursor']
  let nextCursor: JournalCursor | null = null
  if (cursorValue && typeof cursorValue === 'object') {
    const cursorRecord = cursorValue as Record<string, unknown>
    const ts = asString(cursorRecord['ts'])
    const idValue = cursorRecord['id']
    const cursorId = typeof idValue === 'number' && Number.isFinite(idValue) ? idValue : null
    if (ts && cursorId !== null) {
      nextCursor = { ts, id: cursorId }
    }
  }
  const hasMore = record['hasMore'] === true
  return { items, nextCursor, hasMore }
}

async function setSwarmManagersEnabled(enabled: boolean) {
  const payload: SwarmManagersTogglePayload = {
    idempotencyKey: randomId(),
    enabled,
  }
  await apiFetch('/orchestrator/swarm-managers/enabled', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
}

export async function enableSwarmManagers() {
  await setSwarmManagersEnabled(true)
}

export async function disableSwarmManagers() {
  await setSwarmManagersEnabled(false)
}

export async function sendConfigUpdate(component: Component, config: unknown) {
  const payload: Record<string, unknown> = {
    idempotencyKey: randomId(),
    patch: config,
  }
  if (component.swarmId) {
    payload.swarmId = component.swarmId
  }
  await apiFetch(`/orchestrator/components/${component.role}/${component.id}/config`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
}
