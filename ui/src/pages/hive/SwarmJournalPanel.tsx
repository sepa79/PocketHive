import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { getSwarmJournal } from '../../lib/orchestratorApi'
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
    let timer: number | undefined

    const load = async (showSpinner: boolean) => {
      if (cancelled) return
      if (showSpinner) {
        setLoading(true)
        setError(null)
      }
      try {
        const result = await getSwarmJournal(swarmId)
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
    timer = window.setInterval(() => {
      void load(false)
    }, 5000)

    return () => {
      cancelled = true
      if (timer !== undefined) {
        window.clearInterval(timer)
      }
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

  const latest = entries.slice(-20)

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
        {latest.map((entry, index) => {
          const ts = new Date(entry.timestamp).toLocaleTimeString()
          return (
            <li key={`${entry.timestamp}-${index}`} className="flex gap-2">
              <span className="shrink-0 text-white/40">{ts}</span>
              <span className="text-white/80 truncate">
                {describeEntry(entry)}
              </span>
            </li>
          )
        })}
      </ul>
    </div>
  )
}
