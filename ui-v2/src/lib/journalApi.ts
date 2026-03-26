import type { JournalCursor, JournalPageResponse, SwarmJournalEntry } from './journal'
import { normalizeJournalEntry } from './journal'

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

function asString(value: unknown): string | null {
  return typeof value === 'string' && value.trim().length > 0 ? value.trim() : null
}

function buildQuery(params: Record<string, string | number | null | undefined>): string {
  const parts: string[] = []
  for (const [key, value] of Object.entries(params)) {
    if (value === null || value === undefined) continue
    const text = typeof value === 'number' ? String(value) : value
    if (!text.trim()) continue
    parts.push(`${encodeURIComponent(key)}=${encodeURIComponent(text)}`)
  }
  return parts.length > 0 ? `?${parts.join('&')}` : ''
}

async function readErrorMessage(response: Response): Promise<string> {
  try {
    const text = await response.text()
    if (!text) return `HTTP ${response.status}`
    try {
      const parsed = JSON.parse(text) as { message?: unknown }
      if (parsed && typeof parsed.message === 'string') {
        return parsed.message
      }
    } catch {
      return text
    }
    return text
  } catch {
    return `HTTP ${response.status}`
  }
}

async function loadJournalPage(url: string, failureMessage: string): Promise<JournalPageResponse | null> {
  const response = await fetch(url, { headers: { Accept: 'application/json' } })
  if (response.status === 404) {
    return null
  }
  if (!response.ok) {
    throw new Error(`${failureMessage}: ${await readErrorMessage(response)}`)
  }

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
  const itemsValue = Array.isArray(record.items) ? (record.items as unknown[]) : []
  const items = itemsValue
    .map((entry) => normalizeJournalEntry(entry))
    .filter((entry): entry is SwarmJournalEntry => entry !== null)

  const cursorValue = isRecord(record.nextCursor) ? record.nextCursor : null
  const ts = asString(cursorValue?.ts)
  const id = typeof cursorValue?.id === 'number' && Number.isFinite(cursorValue.id) ? cursorValue.id : null
  const nextCursor: JournalCursor | null = ts && id !== null ? { ts, id } : null
  const hasMore = record.hasMore === true

  return { items, nextCursor, hasMore }
}

export async function getSwarmJournalPage(
  swarmId: string,
  options?: {
    limit?: number
    correlationId?: string | null
    runId?: string | null
    before?: JournalCursor | null
  },
): Promise<JournalPageResponse | null> {
  const query = buildQuery({
    limit: options?.limit ?? undefined,
    correlationId: options?.correlationId ?? undefined,
    runId: options?.runId ?? undefined,
    beforeTs: options?.before?.ts ?? undefined,
    beforeId: options?.before?.id ?? undefined,
  })
  return loadJournalPage(
    `/orchestrator/api/swarms/${encodeURIComponent(swarmId)}/journal/page${query}`,
    'Failed to load swarm journal page',
  )
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
  return loadJournalPage(`/orchestrator/api/journal/hive/page${query}`, 'Failed to load hive journal page')
}
