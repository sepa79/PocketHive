export type JournalCursor = {
  ts: string
  id: number
}

export type SwarmJournalEntry = {
  eventId?: number | null
  timestamp: string
  swarmId: string
  runId?: string | null
  severity: string
  direction: 'IN' | 'OUT' | 'LOCAL'
  kind: string
  type: string
  origin: string
  scope: {
    swarmId: string
    role: string | null
    instance: string | null
  }
  correlationId: string | null
  idempotencyKey: string | null
  routingKey: string | null
  data: Record<string, unknown> | null
  raw: Record<string, unknown> | null
  extra: Record<string, unknown> | null
}

export type JournalPageResponse = {
  items: SwarmJournalEntry[]
  nextCursor: JournalCursor | null
  hasMore: boolean
}

export type JournalRow = {
  entry: SwarmJournalEntry
  count: number
  firstTimestamp: string
  lastTimestamp: string
}

export type JournalIssue = {
  entry: SwarmJournalEntry
  severity: string
  phase: string | null
  code: string | null
  message: string
  worker: string | null
  timestamp: string
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

function asString(value: unknown): string | null {
  return typeof value === 'string' && value.trim().length > 0 ? value.trim() : null
}

export function normalizeJournalEntry(input: unknown): SwarmJournalEntry | null {
  if (!isRecord(input)) return null
  const timestamp = asString(input.timestamp)
  const swarmId = asString(input.swarmId)
  const severity = asString(input.severity)
  const direction = asString(input.direction)
  const kind = asString(input.kind)
  const type = asString(input.type)
  const origin = asString(input.origin)
  const scope = isRecord(input.scope) ? input.scope : null
  const scopeSwarmId = asString(scope?.swarmId) ?? swarmId
  if (!timestamp || !swarmId || !severity || !direction || !kind || !type || !origin || !scopeSwarmId) {
    return null
  }

  const eventId = typeof input.eventId === 'number' && Number.isFinite(input.eventId) ? input.eventId : null
  const data = isRecord(input.data) ? input.data : null
  const raw = isRecord(input.raw) ? input.raw : null
  const extra = isRecord(input.extra) ? input.extra : null
  const normalizedDirection = direction.toUpperCase()
  if (normalizedDirection !== 'IN' && normalizedDirection !== 'OUT' && normalizedDirection !== 'LOCAL') {
    return null
  }

  return {
    eventId,
    timestamp,
    swarmId,
    runId: asString(input.runId),
    severity,
    direction: normalizedDirection,
    kind,
    type,
    origin,
    scope: {
      swarmId: scopeSwarmId,
      role: asString(scope?.role),
      instance: asString(scope?.instance),
    },
    correlationId: asString(input.correlationId),
    idempotencyKey: asString(input.idempotencyKey),
    routingKey: asString(input.routingKey),
    data,
    raw,
    extra,
  }
}

export function formatJournalSummary(entry: SwarmJournalEntry): string {
  if (entry.kind === 'signal') {
    return `signal ${entry.type}`
  }
  if (entry.kind === 'outcome') {
    const status = asString(entry.data?.status)
    return status ? `outcome ${entry.type} -> ${status}` : `outcome ${entry.type}`
  }
  if (entry.kind === 'event' && entry.type === 'alert') {
    const code = asString(entry.data?.code)
    const message = asString(entry.data?.message)
    if (code && message) return `alert ${code}: ${message}`
    if (message) return `alert: ${message}`
    return 'alert'
  }
  if (entry.kind === 'local') {
    return entry.type
  }
  return `${entry.kind} ${entry.type}`
}

function entrySignature(entry: SwarmJournalEntry): string | null {
  if (entry.kind === 'event' && entry.type === 'alert') {
    const context = isRecord(entry.data?.context) ? entry.data?.context : null
    return [
      'alert',
      entry.severity,
      entry.origin,
      entry.scope.role ?? '',
      entry.scope.instance ?? '',
      asString(entry.data?.code) ?? '',
      asString(entry.data?.message) ?? '',
      asString(context?.worker) ?? '',
      asString(context?.phase) ?? '',
    ].join('|')
  }
  if (entry.severity.toUpperCase() === 'ERROR') {
    return [
      'error',
      entry.kind,
      entry.type,
      entry.origin,
      entry.scope.role ?? '',
      entry.scope.instance ?? '',
    ].join('|')
  }
  return null
}

export function groupJournalEntries(entries: SwarmJournalEntry[]): JournalRow[] {
  const rows: JournalRow[] = []
  for (const entry of entries) {
    const signature = entrySignature(entry)
    const last = rows.length > 0 ? rows[rows.length - 1] : null
    const lastSignature = last ? entrySignature(last.entry) : null
    if (signature && last && lastSignature === signature) {
      last.count += 1
      last.firstTimestamp = entry.timestamp
      continue
    }
    rows.push({
      entry,
      count: 1,
      firstTimestamp: entry.timestamp,
      lastTimestamp: entry.timestamp,
    })
  }
  return rows
}

export function filterJournalEntries(
  entries: SwarmJournalEntry[],
  options: { search: string; errorsOnly: boolean },
): SwarmJournalEntry[] {
  const text = options.search.trim().toLowerCase()
  return entries.filter((entry) => {
    if (options.errorsOnly && entry.severity.toUpperCase() !== 'ERROR') {
      return false
    }
    if (!text) return true
    const haystack = [
      entry.swarmId,
      entry.runId ?? '',
      entry.kind,
      entry.type,
      entry.origin,
      entry.direction,
      entry.severity,
      entry.correlationId ?? '',
      entry.idempotencyKey ?? '',
      entry.routingKey ?? '',
      JSON.stringify(entry.data ?? {}),
      JSON.stringify(entry.extra ?? {}),
      JSON.stringify(entry.raw ?? {}),
    ]
      .join(' ')
      .toLowerCase()
    return haystack.includes(text)
  })
}

export function extractJournalIssue(entry: SwarmJournalEntry): JournalIssue | null {
  const severity = entry.severity.toUpperCase()
  if (entry.kind === 'event' && entry.type === 'alert') {
    const context = isRecord(entry.data?.context) ? entry.data?.context : null
    const message = asString(entry.data?.message) ?? 'Alert'
    return {
      entry,
      severity,
      phase: asString(context?.phase),
      code: asString(entry.data?.code),
      worker: asString(context?.worker),
      message,
      timestamp: entry.timestamp,
    }
  }

  if (entry.kind === 'outcome') {
    const status = asString(entry.data?.status)
    if (status && status.toUpperCase() === 'FAILED') {
      return {
        entry,
        severity: severity === 'ERROR' ? severity : 'ERROR',
        phase: entry.type.replace(/^swarm-/, ''),
        code: null,
        worker: null,
        message: `Outcome ${entry.type} failed`,
        timestamp: entry.timestamp,
      }
    }
  }

  if (severity === 'ERROR') {
    return {
      entry,
      severity,
      phase: null,
      code: null,
      worker: null,
      message: formatJournalSummary(entry),
      timestamp: entry.timestamp,
    }
  }

  return null
}

export function latestJournalIssue(entries: SwarmJournalEntry[]): JournalIssue | null {
  for (const entry of entries) {
    const issue = extractJournalIssue(entry)
    if (issue) return issue
  }
  return null
}

export function journalSeverityTone(severity: string): 'bad' | 'warn' | 'info' {
  const normalized = severity.trim().toUpperCase()
  if (normalized === 'ERROR') return 'bad'
  if (normalized === 'WARN' || normalized === 'WARNING') return 'warn'
  return 'info'
}
