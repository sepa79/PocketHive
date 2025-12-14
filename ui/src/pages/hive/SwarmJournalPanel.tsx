import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { getSwarmJournal, getSwarmJournalPage } from '../../lib/orchestratorApi'
import type { SwarmJournalEntry } from '../../types/orchestrator'

export interface SwarmJournalPanelProps {
  swarmId: string
}

export default function SwarmJournalPanel({ swarmId }: SwarmJournalPanelProps) {
  const navigate = useNavigate()
  const [entries, setEntries] = useState<SwarmJournalEntry[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false

    const load = async (showSpinner: boolean) => {
      if (cancelled) return
      if (showSpinner) {
        setLoading(true)
        setError(null)
      }
      try {
        let result: SwarmJournalEntry[] = []
        try {
          const page = await getSwarmJournalPage(swarmId, { limit: 50 })
          result = page?.items ? [...page.items].reverse() : []
        } catch (err) {
          const status = err instanceof Error ? (err as Error & { status?: number }).status : undefined
          if (status === 501) {
            result = await getSwarmJournal(swarmId)
          } else {
            throw err
          }
        }
        if (!cancelled) {
          setEntries(result)
        }
      } catch (err) {
        if (!cancelled) {
          const message =
            err instanceof Error && err.message
              ? err.message
              : 'Failed to load swarm journal'
          setError(message)
        }
      } finally {
        if (!cancelled && showSpinner) {
          setLoading(false)
        }
      }
    }

    void load(true)
    const timer = window.setInterval(() => {
      void load(false)
    }, 5000)

    return () => {
      cancelled = true
      window.clearInterval(timer)
    }
  }, [swarmId])

  if (loading && !entries.length && !error) {
    return (
      <div className="mt-3 rounded-md border border-white/10 bg-white/5 px-3 py-2 text-xs text-white/70">
        Loading journal…
      </div>
    )
  }

  if (error) {
    return (
      <div className="mt-3 rounded-md border border-red-500/30 bg-red-500/10 px-3 py-2 text-xs text-red-200">
        {error}
      </div>
    )
  }

  if (!entries.length) {
    return (
      <div className="mt-3 rounded-md border border-white/10 bg-white/5 px-3 py-2 text-xs text-white/60">
        Journal is empty so far for this swarm.
      </div>
    )
  }

  const signatureFor = (entry: SwarmJournalEntry): string | null => {
    if (entry.kind === 'event' && entry.type === 'alert') {
      const code = typeof entry.data?.code === 'string' ? entry.data.code : ''
      const message = typeof entry.data?.message === 'string' ? entry.data.message : ''
      return [
        'alert',
        entry.severity,
        entry.origin,
        entry.scope.role ?? '',
        entry.scope.instance ?? '',
        code,
        message,
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

  const grouped: Array<{ entry: SwarmJournalEntry; count: number }> = []
  for (const entry of entries) {
    const signature = signatureFor(entry)
    const last = grouped.length ? grouped[grouped.length - 1] : null
    const lastSignature = last ? signatureFor(last.entry) : null
    if (signature && last && lastSignature === signature) {
      last.count += 1
      continue
    }
    grouped.push({ entry, count: 1 })
  }

  const latest = grouped.slice(-20)

  const describeEntry = (entry: SwarmJournalEntry): string => {
    const prefix = entry.direction === 'LOCAL' ? 'local' : entry.direction.toLowerCase()
    if (entry.kind === 'signal') {
      return `${prefix} signal ${entry.type}`
    }
    if (entry.kind === 'outcome') {
      const status = typeof entry.data?.status === 'string' ? entry.data.status : null
      return status ? `${prefix} outcome ${entry.type} → ${status}` : `${prefix} outcome ${entry.type}`
    }
    if (entry.kind === 'event' && entry.type === 'alert') {
      const code = typeof entry.data?.code === 'string' ? entry.data.code : null
      const message = typeof entry.data?.message === 'string' ? entry.data.message : null
      if (code && message) return `${prefix} alert ${code}: ${message}`
      if (message) return `${prefix} alert: ${message}`
      return `${prefix} alert`
    }
    return `${prefix} ${entry.kind} ${entry.type}`
  }

  return (
    <div className="mt-3 rounded-md border border-white/10 bg-slate-950/60 px-3 py-2">
      <div className="mb-2 flex items-center justify-between text-xs text-white/60">
        <span className="font-semibold uppercase tracking-wide">
          Journal (last {latest.length})
        </span>
        <button
          type="button"
          onClick={() => navigate(`/hive/journal/${encodeURIComponent(swarmId)}`)}
          className="rounded border border-white/20 bg-white/5 px-2 py-0.5 text-[10px] font-medium text-white/80 hover:bg-white/10"
        >
          Open full view
        </button>
      </div>
      <ul className="space-y-1 max-h-48 overflow-y-auto text-xs">
        {latest.map((row, index) => {
          const entry = row.entry
          const ts = new Date(entry.timestamp).toLocaleTimeString()
          const suffix = row.count > 1 ? ` ×${row.count}` : ''
          return (
            <li key={entry.eventId ?? `${entry.timestamp}-${index}`} className="flex gap-2">
              <span className="shrink-0 text-white/40">{ts}</span>
              <span className="text-white/80 truncate">
                {describeEntry(entry)}
                {suffix}
              </span>
            </li>
          )
        })}
      </ul>
    </div>
  )
}
