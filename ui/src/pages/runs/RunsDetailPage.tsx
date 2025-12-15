import { useEffect, useMemo, useState } from 'react'
import { useLocation, useNavigate, useParams } from 'react-router-dom'
import {
  getSwarmJournal,
  getSwarmJournalPage,
  getSwarmJournalRuns,
  pinSwarmJournalRun,
} from '../../lib/orchestratorApi'
import type { JournalCursor, JournalRunSummary } from '../../lib/orchestratorApi'
import type { SwarmJournalEntry } from '../../types/orchestrator'
import { useUIStore } from '../../store'

type JournalRow = {
  entry: SwarmJournalEntry
  count: number
  firstTimestamp: string
  lastTimestamp: string
}

function buildQuery(params: Record<string, string | null | undefined>): string {
  const pairs: string[] = []
  for (const [key, value] of Object.entries(params)) {
    const trimmed = value?.trim()
    if (!trimmed) continue
    pairs.push(`${encodeURIComponent(key)}=${encodeURIComponent(trimmed)}`)
  }
  return pairs.length ? `?${pairs.join('&')}` : ''
}

export default function RunsDetailPage() {
  const { swarmId } = useParams<{ swarmId: string }>()
  const navigate = useNavigate()
  const location = useLocation()
  const [entries, setEntries] = useState<SwarmJournalEntry[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [showErrorsOnly, setShowErrorsOnly] = useState(false)
  const [search, setSearch] = useState('')
  const [correlationFilter, setCorrelationFilter] = useState('')
  const [runId, setRunId] = useState<string>('')
  const [runs, setRuns] = useState<JournalRunSummary[] | null>(null)
  const [pinMode, setPinMode] = useState<'SLIM' | 'FULL' | 'ERRORS_ONLY'>('SLIM')
  const [pinBusy, setPinBusy] = useState(false)
  const [expandedIndex, setExpandedIndex] = useState<number | null>(null)
  const [cursor, setCursor] = useState<JournalCursor | null>(null)
  const [hasMore, setHasMore] = useState(false)
  const [pagingSupported, setPagingSupported] = useState(true)
  const [loadingMore, setLoadingMore] = useState(false)
  const setToast = useUIStore((state) => state.setToast)

  useEffect(() => {
    if (!swarmId) return
    const params = new URLSearchParams(location.search)
    const nextRunId = params.get('runId')
    setRunId(nextRunId ? nextRunId : '')
  }, [location.search, swarmId])

  useEffect(() => {
    if (!swarmId) return
    let cancelled = false
    const loadRuns = async () => {
      try {
        const res = await getSwarmJournalRuns(swarmId)
        if (!cancelled) {
          setRuns(res)
        }
      } catch {
        if (!cancelled) {
          setRuns(null)
        }
      }
    }
    void loadRuns()
    const timer = window.setInterval(() => void loadRuns(), 30_000)
    return () => {
      cancelled = true
      window.clearInterval(timer)
    }
  }, [swarmId])

  useEffect(() => {
    if (!swarmId) return
    let cancelled = false

    const load = async (withSpinner: boolean) => {
      if (cancelled) return
      if (withSpinner) {
        setLoading(true)
        setError(null)
      }
      try {
        const correlationId = correlationFilter.trim() ? correlationFilter.trim() : null
        const resolvedRunId = runId.trim() ? runId.trim() : null
        try {
          const page = await getSwarmJournalPage(swarmId, { limit: 200, correlationId, runId: resolvedRunId })
          if (!cancelled) {
            setPagingSupported(true)
            const nextItems = page?.items ?? []
            setEntries((prev) => {
              if (withSpinner || !prev.length) {
                setHasMore(page?.hasMore ?? false)
                setCursor(page?.nextCursor ?? null)
                return nextItems
              }
              const seen = new Set<number>()
              for (const entry of prev) {
                if (typeof entry.eventId === 'number') {
                  seen.add(entry.eventId)
                }
              }
              const merged: SwarmJournalEntry[] = []
              for (const entry of nextItems) {
                if (typeof entry.eventId === 'number' && seen.has(entry.eventId)) continue
                merged.push(entry)
              }
              return merged.length ? [...merged, ...prev] : prev
            })
          }
        } catch (err) {
          const status = err instanceof Error ? (err as Error & { status?: number }).status : undefined
          if (status === 501) {
            const result = await getSwarmJournal(swarmId, { runId: resolvedRunId })
            if (!cancelled) {
              setPagingSupported(false)
              setEntries(result)
              setHasMore(false)
              setCursor(null)
            }
          } else {
            throw err
          }
        }
      } catch (err) {
        if (!cancelled) {
          const message = err instanceof Error && err.message ? err.message : 'Failed to load swarm journal'
          setError(message)
        }
      } finally {
        if (!cancelled && withSpinner) {
          setLoading(false)
        }
      }
    }

    setEntries([])
    setCursor(null)
    setHasMore(false)
    void load(true)
    const timer = window.setInterval(() => {
      void load(false)
    }, 5000)

    return () => {
      cancelled = true
      window.clearInterval(timer)
    }
  }, [swarmId, correlationFilter, runId])

  const grafanaUrl = useMemo(() => {
    const { protocol, hostname } = window.location
    const base = `${protocol}//${hostname}:3333/grafana/`
    const params = new URLSearchParams()
    params.set('var-scope', 'SWARM')
    if (swarmId) {
      params.set('var-swarmId', swarmId)
    }
    const resolvedRunId = runId.trim()
    if (resolvedRunId) {
      params.set('var-runId', resolvedRunId)
    }
    const corr = correlationFilter.trim()
    if (corr) {
      params.set('var-correlationId', corr)
    }
    return `${base}d/pockethive-journal/pockethive-journal?${params.toString()}`
  }, [correlationFilter, runId, swarmId])

  const describeSummary = (entry: SwarmJournalEntry): string => {
    if (entry.kind === 'signal') {
      return `signal ${entry.type}`
    }
    if (entry.kind === 'outcome') {
      const status = typeof entry.data?.status === 'string' ? entry.data.status : null
      return status ? `outcome ${entry.type} → ${status}` : `outcome ${entry.type}`
    }
    if (entry.kind === 'event' && entry.type === 'alert') {
      const code = typeof entry.data?.code === 'string' ? entry.data.code : null
      const message = typeof entry.data?.message === 'string' ? entry.data.message : null
      if (code && message) return `alert ${code}: ${message}`
      if (message) return `alert: ${message}`
      return 'alert'
    }
    if (entry.kind === 'local') {
      return entry.type
    }
    return `${entry.kind} ${entry.type}`
  }

  const filtered = useMemo(() => {
    const text = search.trim().toLowerCase()
    return entries.filter((entry) => {
      if (showErrorsOnly && entry.severity !== 'ERROR') return false
      if (!text) return true
      const haystack = [
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
  }, [entries, showErrorsOnly, search])

  const grouped = useMemo((): JournalRow[] => {
    const rows: JournalRow[] = []

    const signatureFor = (entry: SwarmJournalEntry): string | null => {
      if (entry.kind === 'event' && entry.type === 'alert') {
        const code = typeof entry.data?.code === 'string' ? entry.data.code : ''
        const message = typeof entry.data?.message === 'string' ? entry.data.message : ''
        const context = entry.data?.context
        const worker =
          context && typeof context === 'object' && typeof (context as Record<string, unknown>).worker === 'string'
            ? String((context as Record<string, unknown>).worker)
            : ''
        const phase =
          context && typeof context === 'object' && typeof (context as Record<string, unknown>).phase === 'string'
            ? String((context as Record<string, unknown>).phase)
            : ''
        return [
          'alert',
          entry.severity,
          entry.origin,
          entry.scope.role ?? '',
          entry.scope.instance ?? '',
          code,
          message,
          worker,
          phase,
        ].join('|')
      }
      if (entry.severity === 'ERROR') {
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

    for (const entry of filtered) {
      const signature = signatureFor(entry)
      const last = rows.length ? rows[rows.length - 1] : null
      const lastSignature = last ? signatureFor(last.entry) : null
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
  }, [filtered])

  const loadOlder = async () => {
    if (!swarmId || !pagingSupported || !cursor || loadingMore) return
    setLoadingMore(true)
    try {
      const correlationId = correlationFilter.trim() ? correlationFilter.trim() : null
      const resolvedRunId = runId.trim() ? runId.trim() : null
      const page = await getSwarmJournalPage(swarmId, {
        limit: 500,
        correlationId,
        runId: resolvedRunId,
        before: cursor,
      })
      const nextItems = page?.items ?? []
      setHasMore(page?.hasMore ?? false)
      setCursor(page?.nextCursor ?? null)
      setEntries((prev) => [...prev, ...nextItems])
    } catch (err) {
      const message =
        err instanceof Error && err.message ? err.message : 'Failed to load older journal entries'
      setError(message)
    } finally {
      setLoadingMore(false)
    }
  }

  if (!swarmId) {
    return <div className="px-6 py-4 text-sm text-white/70">Missing swarm id in route.</div>
  }

  const hiveJournalUrl = `/orchestrator/journal${buildQuery({
    swarmId,
    runId: runId.trim() ? runId.trim() : null,
    correlationId: correlationFilter.trim() ? correlationFilter.trim() : null,
  })}`

  return (
    <div className="flex h-full flex-col px-6 py-4">
      <div className="mb-4 flex items-center justify-between gap-4">
        <div className="min-w-0">
          <div className="text-xs uppercase tracking-wide text-white/40">Runs</div>
          <div className="mt-1 truncate text-lg font-semibold text-white/90">{swarmId}</div>
        </div>
        <div className="flex items-center gap-2">
          <button
            type="button"
            onClick={() => navigate('/swarms')}
            className="rounded-md border border-white/15 bg-white/5 px-3 py-1.5 text-xs font-medium text-white/80 hover:bg-white/10"
          >
            Back to Swarms
          </button>
          <button
            type="button"
            onClick={() => navigate('/hive')}
            className="rounded-md border border-white/15 bg-white/5 px-3 py-1.5 text-xs font-medium text-white/80 hover:bg-white/10"
          >
            Back to Hive
          </button>
          <button
            type="button"
            onClick={() => navigate(hiveJournalUrl)}
            className="rounded-md border border-white/15 bg-white/5 px-3 py-1.5 text-xs font-medium text-white/80 hover:bg-white/10"
          >
            Open Hive Journal
          </button>
          <a
            href={grafanaUrl}
            target="_blank"
            rel="noreferrer"
            className="rounded-md border border-amber-400/30 bg-amber-400/10 px-3 py-1.5 text-xs font-medium text-amber-100 hover:bg-amber-400/20"
          >
            Open in Grafana
          </a>
        </div>
      </div>

      <div className="mb-4 grid gap-3 text-xs md:grid-cols-[1fr_1fr_auto_auto]">
        {Array.isArray(runs) && runs.length > 0 && (
          <div className="md:col-span-2 flex items-center gap-2">
            <label className="text-white/50" htmlFor="journal-run">
              Run:
            </label>
            <select
              id="journal-run"
              value={runId}
              onChange={(e) => {
                const next = e.target.value
                const params = new URLSearchParams(location.search)
                if (next) {
                  params.set('runId', next)
                } else {
                  params.delete('runId')
                }
                navigate(`/runs/${encodeURIComponent(swarmId)}?${params.toString()}`)
              }}
              className="flex-1 rounded-lg border border-white/15 bg-white/5 px-3 py-2 text-xs text-white/80 focus:outline-none focus:ring-2 focus:ring-sky-300/50"
            >
              <option value="">(auto / latest)</option>
              {runs.map((r) => (
                <option key={r.runId} value={r.runId}>
                  {r.pinned ? '[PINNED] ' : ''}
                  {r.runId} ({r.entries})
                </option>
              ))}
            </select>
          </div>
        )}
        <div className="md:col-span-2 flex items-center gap-2">
          <label className="text-white/50" htmlFor="journal-pin-mode">
            Pin:
          </label>
          <select
            id="journal-pin-mode"
            value={pinMode}
            onChange={(e) => setPinMode(e.target.value as typeof pinMode)}
            className="rounded-lg border border-white/15 bg-white/5 px-3 py-2 text-xs text-white/80 focus:outline-none focus:ring-2 focus:ring-sky-300/50"
          >
            <option value="SLIM">SLIM (drop raw/extra)</option>
            <option value="FULL">FULL</option>
            <option value="ERRORS_ONLY">ERRORS_ONLY</option>
          </select>
          <button
            type="button"
            className="rounded-lg border border-white/15 bg-white/5 px-3 py-2 text-xs font-semibold text-white/80 hover:bg-white/10 disabled:opacity-50"
            disabled={pinBusy}
            onClick={async () => {
              if (!swarmId) return
              setPinBusy(true)
              try {
                const res = await pinSwarmJournalRun(swarmId, {
                  runId: runId.trim() ? runId.trim() : null,
                  mode: pinMode,
                })
                if (res && res.runId) {
                  const params = new URLSearchParams(location.search)
                  params.set('runId', res.runId)
                  navigate(`/runs/${encodeURIComponent(swarmId)}?${params.toString()}`)
                }
                setToast(`Pinned journal run (${pinMode})`)
              } catch (err) {
                const message =
                  err instanceof Error && err.message
                    ? `Failed to pin journal run: ${err.message}`
                    : 'Failed to pin journal run'
                setToast(message)
              } finally {
                setPinBusy(false)
              }
            }}
          >
            {pinBusy ? 'Pinning…' : 'Pin run'}
          </button>
        </div>
        <input
          type="text"
          placeholder="Search kind/type/origin/correlation/routing/data…"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          className="w-full rounded-md border border-white/15 bg-slate-950/80 px-3 py-1.5 text-xs text-white/90 placeholder:text-white/40 focus:border-amber-400/60 focus:outline-none"
        />
        <input
          type="text"
          placeholder="Filter correlationId (server-side)…"
          value={correlationFilter}
          onChange={(e) => setCorrelationFilter(e.target.value)}
          className="w-full rounded-md border border-white/15 bg-slate-950/80 px-3 py-1.5 text-xs text-white/90 placeholder:text-white/40 focus:border-amber-400/60 focus:outline-none"
        />
        <label className="flex items-center gap-2 text-white/70">
          <input
            type="checkbox"
            className="h-3 w-3 rounded border-white/30 bg-slate-950/80 text-amber-400 focus:ring-amber-400"
            checked={showErrorsOnly}
            onChange={(e) => setShowErrorsOnly(e.target.checked)}
          />
          Errors only
        </label>
        <div className="flex items-center justify-end text-white/50">
          {loading
            ? 'Refreshing…'
            : grouped.length !== filtered.length
              ? `${filtered.length} entries (${grouped.length} groups)`
              : `${filtered.length} entries`}
        </div>
      </div>

      {error && (
        <div className="mb-3 rounded-md border border-red-500/40 bg-red-500/10 px-3 py-2 text-xs text-red-100">
          {error}
        </div>
      )}

      {!error && filtered.length === 0 && !loading && (
        <div className="rounded-md border border-white/10 bg-white/5 px-3 py-2 text-xs text-white/70">
          Journal is empty so far for this swarm (or filtered out).
        </div>
      )}

      <div className="flex-1 overflow-y-auto rounded-md border border-white/10 bg-slate-950/70">
        <table className="min-w-full border-collapse text-xs">
          <thead className="sticky top-0 z-10 bg-slate-950/95">
            <tr className="border-b border-white/10 text-white/50">
              <th className="px-3 py-2 text-left font-medium">Time</th>
              <th className="px-3 py-2 text-left font-medium">Severity</th>
              <th className="px-3 py-2 text-left font-medium">Dir</th>
              <th className="px-3 py-2 text-left font-medium">Origin</th>
              <th className="px-3 py-2 text-left font-medium">Kind</th>
              <th className="px-3 py-2 text-left font-medium">Type</th>
              <th className="px-3 py-2 text-left font-medium">Summary</th>
            </tr>
          </thead>
          <tbody>
            {grouped.map((row, index) => {
              const entry = row.entry
              const ts = new Date(entry.timestamp).toLocaleTimeString()
              const isExpanded = expandedIndex === index
              const severityClass =
                entry.severity === 'ERROR'
                  ? 'text-red-300'
                  : entry.severity === 'WARN' || entry.severity === 'WARNING'
                    ? 'text-amber-300'
                    : 'text-emerald-300'
              return (
                <tr
                  key={entry.eventId ?? `${entry.timestamp}-${index}`}
                  className={`border-b border-white/5 cursor-pointer hover:bg-white/5 ${
                    isExpanded ? 'bg-white/5' : ''
                  }`}
                  onClick={() => setExpandedIndex(isExpanded ? null : index)}
                >
                  <td className="whitespace-nowrap px-3 py-1.5 align-top text-white/70">{ts}</td>
                  <td className={`whitespace-nowrap px-3 py-1.5 align-top ${severityClass}`}>
                    {entry.severity}
                  </td>
                  <td className="whitespace-nowrap px-3 py-1.5 align-top font-mono text-[11px] text-white/60">
                    {entry.direction}
                  </td>
                  <td className="whitespace-nowrap px-3 py-1.5 align-top text-white/70">
                    {entry.origin}
                  </td>
                  <td className="max-w-[220px] px-3 py-1.5 align-top font-mono text-[11px] text-white/80">
                    {entry.kind}
                  </td>
                  <td className="max-w-[220px] px-3 py-1.5 align-top font-mono text-[11px] text-white/80">
                    {entry.type}
                  </td>
                  <td className="px-3 py-1.5 align-top text-white/90">
                    <div className="line-clamp-2">
                      {describeSummary(entry)}
                      {row.count > 1 ? ` ×${row.count}` : ''}
                    </div>
                    {isExpanded && (
                      <div className="mt-1 rounded bg-slate-900/80 p-2 text-[11px] text-white/80">
                        {row.count > 1 && (
                          <div className="mb-2 text-white/60">
                            Grouped {row.count} entries (from{' '}
                            <span className="font-mono">
                              {new Date(row.firstTimestamp).toLocaleTimeString()}
                            </span>{' '}
                            to{' '}
                            <span className="font-mono">
                              {new Date(row.lastTimestamp).toLocaleTimeString()}
                            </span>
                            ).
                          </div>
                        )}
                        <div className="mb-1 flex flex-wrap gap-3 text-white/60">
                          {entry.correlationId && <span className="font-mono">corr={entry.correlationId}</span>}
                          {entry.idempotencyKey && <span className="font-mono">idem={entry.idempotencyKey}</span>}
                          <span className="font-mono">
                            scope={entry.scope.role ?? 'ALL'}/{entry.scope.instance ?? 'ALL'}
                          </span>
                          {entry.routingKey && <span className="font-mono">rk={entry.routingKey}</span>}
                        </div>
                        {typeof entry.data?.logRef === 'string' && entry.data.logRef.trim().length > 0 && (
                          <div className="mb-2">
                            <a
                              href={entry.data.logRef}
                              target="_blank"
                              rel="noreferrer"
                              className="text-amber-200 hover:underline"
                            >
                              Open stacktrace/logRef
                            </a>
                          </div>
                        )}
                        <div className="grid gap-2 md:grid-cols-3">
                          <div>
                            <div className="mb-1 text-white/60">data</div>
                            <pre className="max-h-40 overflow-y-auto whitespace-pre-wrap break-words text-[11px]">
                              {JSON.stringify(entry.data, null, 2)}
                            </pre>
                          </div>
                          <div>
                            <div className="mb-1 text-white/60">extra</div>
                            <pre className="max-h-40 overflow-y-auto whitespace-pre-wrap break-words text-[11px]">
                              {JSON.stringify(entry.extra, null, 2)}
                            </pre>
                          </div>
                          <div>
                            <div className="mb-1 text-white/60">raw</div>
                            <pre className="max-h-40 overflow-y-auto whitespace-pre-wrap break-words text-[11px]">
                              {JSON.stringify(entry.raw, null, 2)}
                            </pre>
                          </div>
                        </div>
                      </div>
                    )}
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>

      {pagingSupported && (
        <div className="mt-3 flex items-center justify-between text-xs text-white/60">
          <div>{hasMore ? 'More entries available.' : 'End of journal.'}</div>
          <button
            type="button"
            onClick={() => void loadOlder()}
            disabled={!hasMore || loadingMore || !cursor}
            className="rounded-md border border-white/15 bg-white/5 px-3 py-1.5 text-xs font-medium text-white/80 hover:bg-white/10 disabled:opacity-40"
          >
            {loadingMore ? 'Loading…' : 'Load older'}
          </button>
        </div>
      )}
    </div>
  )
}

