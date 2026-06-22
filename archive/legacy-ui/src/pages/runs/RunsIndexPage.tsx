import { useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { getAllSwarmJournalRuns, type SwarmRunSummary } from '../../lib/orchestratorApi'

export default function RunsIndexPage() {
  const navigate = useNavigate()
  const [runs, setRuns] = useState<SwarmRunSummary[] | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [search, setSearch] = useState('')
  const [pinnedOnly, setPinnedOnly] = useState(false)
  const [groupByScenario, setGroupByScenario] = useState(true)
  const [range, setRange] = useState<'1d' | '7d' | '30d' | 'all'>('1d')
  const [limit, setLimit] = useState<number>(500)

  useEffect(() => {
    let cancelled = false
    const load = async (withSpinner: boolean) => {
      if (withSpinner) {
        setLoading(true)
      }
      setError(null)
      try {
        const afterTs =
          range === 'all'
            ? null
            : new Date(
                Date.now() - (range === '1d' ? 24 : range === '7d' ? 7 * 24 : 30 * 24) * 60 * 60 * 1000,
              ).toISOString()
        const res = await getAllSwarmJournalRuns({ limit, pinned: pinnedOnly, afterTs })
        if (!cancelled) {
          setRuns(res)
        }
      } catch (e) {
        if (!cancelled) {
          setRuns([])
          setError(e instanceof Error ? e.message : 'Failed to load journal runs')
        }
      } finally {
        if (!cancelled) {
          setLoading(false)
        }
      }
    }
    void load(true)
    return () => {
      cancelled = true
    }
  }, [limit, pinnedOnly, range])

  const filtered = useMemo(() => {
    const term = search.trim().toLowerCase()
    const list = Array.isArray(runs) ? runs : []
    return list
      .filter((run) => {
        if (!term) return true
        const tags = run.tags?.join(' ') ?? ''
        return (
          run.runId.toLowerCase().includes(term) ||
          run.swarmId.toLowerCase().includes(term) ||
          (run.scenarioId ?? '').toLowerCase().includes(term) ||
          (run.testPlan ?? '').toLowerCase().includes(term) ||
          (run.description ?? '').toLowerCase().includes(term) ||
          tags.toLowerCase().includes(term)
        )
      })
  }, [pinnedOnly, runs, search])

  const grouped = useMemo(() => {
    if (!groupByScenario) {
      return [{ scenarioId: null as string | null, runs: filtered }]
    }
    const byScenario = new Map<string, SwarmRunSummary[]>()
    for (const run of filtered) {
      const key = run.scenarioId ?? '—'
      const bucket = byScenario.get(key) ?? []
      bucket.push(run)
      byScenario.set(key, bucket)
    }
    const groups = Array.from(byScenario.entries()).map(([scenarioId, groupRuns]) => {
      const lastTs = groupRuns.reduce<number>((max, entry) => {
        const ts = entry.lastTs ? new Date(entry.lastTs).getTime() : 0
        return ts > max ? ts : max
      }, 0)
      return { scenarioId, runs: groupRuns, lastTs }
    })
    groups.sort((a, b) => b.lastTs - a.lastTs)
    return groups.map((group) => ({ scenarioId: group.scenarioId, runs: group.runs }))
  }, [filtered, groupByScenario])

  const columnCount = groupByScenario ? 8 : 9

  return (
    <div className="p-6 space-y-4">
      <div className="space-y-1">
        <h1 className="text-xl font-semibold text-white">Journal</h1>
        <p className="text-white/60 text-sm">Hive journal + swarm run history (runId-first).</p>
      </div>

      <div className="flex flex-wrap items-center gap-3">
        <button
          className="rounded bg-blue-600 px-3 py-2 text-sm disabled:opacity-50"
          onClick={() => navigate('/journal/hive')}
        >
          Open Hive journal
        </button>
        <div className="inline-flex overflow-hidden rounded border border-white/15 bg-white/5">
          <button
            type="button"
            className={`px-3 py-2 text-xs font-semibold ${
              range === '1d' ? 'bg-white/10 text-white' : 'text-white/70 hover:bg-white/10'
            }`}
            onClick={() => setRange('1d')}
          >
            1d
          </button>
          <button
            type="button"
            className={`px-3 py-2 text-xs font-semibold ${
              range === '7d' ? 'bg-white/10 text-white' : 'text-white/70 hover:bg-white/10'
            }`}
            onClick={() => setRange('7d')}
          >
            7d
          </button>
          <button
            type="button"
            className={`px-3 py-2 text-xs font-semibold ${
              range === '30d' ? 'bg-white/10 text-white' : 'text-white/70 hover:bg-white/10'
            }`}
            onClick={() => setRange('30d')}
          >
            30d
          </button>
          <button
            type="button"
            className={`px-3 py-2 text-xs font-semibold ${
              range === 'all' ? 'bg-white/10 text-white' : 'text-white/70 hover:bg-white/10'
            }`}
            onClick={() => setRange('all')}
          >
            All
          </button>
        </div>
        <div className="inline-flex overflow-hidden rounded border border-white/15 bg-white/5">
          <button
            type="button"
            className={`px-3 py-2 text-xs font-semibold ${
              limit === 200 ? 'bg-white/10 text-white' : 'text-white/70 hover:bg-white/10'
            }`}
            onClick={() => setLimit(200)}
          >
            200
          </button>
          <button
            type="button"
            className={`px-3 py-2 text-xs font-semibold ${
              limit === 500 ? 'bg-white/10 text-white' : 'text-white/70 hover:bg-white/10'
            }`}
            onClick={() => setLimit(500)}
          >
            500
          </button>
          <button
            type="button"
            className={`px-3 py-2 text-xs font-semibold ${
              limit === 2000 ? 'bg-white/10 text-white' : 'text-white/70 hover:bg-white/10'
            }`}
            onClick={() => setLimit(2000)}
          >
            2000
          </button>
        </div>
        <input
          className="rounded bg-white/10 px-3 py-2 text-white w-64"
          placeholder="Filter runId / scenario / tags / swarm…"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
        />
        <div className="inline-flex overflow-hidden rounded border border-white/15 bg-white/5">
          <button
            type="button"
            className={`px-3 py-2 text-xs font-semibold ${
              groupByScenario ? 'bg-white/10 text-white' : 'text-white/70 hover:bg-white/10'
            }`}
            onClick={() => setGroupByScenario(true)}
          >
            Group: Scenario
          </button>
          <button
            type="button"
            className={`px-3 py-2 text-xs font-semibold ${
              groupByScenario ? 'text-white/70 hover:bg-white/10' : 'bg-white/10 text-white'
            }`}
            onClick={() => setGroupByScenario(false)}
          >
            Flat
          </button>
        </div>
        <div className="inline-flex overflow-hidden rounded border border-white/15 bg-white/5">
          <button
            type="button"
            className={`px-3 py-2 text-xs font-semibold ${
              pinnedOnly ? 'text-white/70 hover:bg-white/10' : 'bg-white/10 text-white'
            }`}
            onClick={() => setPinnedOnly(false)}
          >
            Latest
          </button>
          <button
            type="button"
            className={`px-3 py-2 text-xs font-semibold ${
              pinnedOnly ? 'bg-white/10 text-white' : 'text-white/70 hover:bg-white/10'
            }`}
            onClick={() => setPinnedOnly(true)}
          >
            Pinned
          </button>
        </div>
        {loading && <span className="text-xs text-white/60">Loading…</span>}
        {runs === null && !loading && <span className="text-xs text-white/60">Journal runs unavailable.</span>}
      </div>

      <div className="overflow-auto border border-white/10 rounded">
        <table className="min-w-full text-sm">
          <thead className="bg-white/5 text-white/70">
            <tr>
              {!groupByScenario && <th className="text-left px-3 py-2">Scenario</th>}
              <th className="text-left px-3 py-2">RunId</th>
              <th className="text-left px-3 py-2">Swarm</th>
              <th className="text-left px-3 py-2">Test plan</th>
              <th className="text-left px-3 py-2">Tags</th>
              <th className="text-left px-3 py-2">First</th>
              <th className="text-left px-3 py-2">Last</th>
              <th className="text-left px-3 py-2">Entries</th>
              <th className="text-left px-3 py-2">Pinned</th>
            </tr>
          </thead>
          <tbody>
            {error && (
              <tr>
                <td className="px-3 py-4 text-center text-red-300" colSpan={columnCount}>
                  {error}
                </td>
              </tr>
            )}
            {grouped.flatMap((group) => {
              const header =
                groupByScenario && group.scenarioId !== null ? (
                  <tr key={`scenario-${group.scenarioId}`} className="border-t border-white/10 bg-white/5">
                    <td className="px-3 py-2 text-white/80 font-mono text-[12px]" colSpan={columnCount}>
                      scenarioId={group.scenarioId} ({group.runs.length})
                    </td>
                  </tr>
                ) : null
              const rows = group.runs.map((run) => (
                <tr
                  key={`${run.swarmId}-${run.runId}`}
                  className="border-t border-white/10 hover:bg-white/5 cursor-pointer"
                  onClick={() =>
                    navigate(
                      `/journal/swarms/${encodeURIComponent(run.swarmId)}?runId=${encodeURIComponent(run.runId)}`,
                    )
                  }
                >
                  {!groupByScenario && (
                    <td className="px-3 py-2 text-white/80 font-mono text-[12px]">
                      {run.scenarioId ?? '—'}
                    </td>
                  )}
                  <td className="px-3 py-2 text-white font-mono text-[12px]">{run.runId}</td>
                  <td
                    className="px-3 py-2 text-white/80 font-mono text-[12px]"
                    title={`swarmId=${run.swarmId}`}
                  >
                    {run.swarmId}
                  </td>
                  <td className="px-3 py-2 text-white/80">{run.testPlan ?? '—'}</td>
                  <td className="px-3 py-2 text-white/80" title={run.tags?.join(', ') ?? ''}>
                    {run.tags?.join(', ') ?? '—'}
                  </td>
                  <td className="px-3 py-2 text-white/80">
                    {run.firstTs ? new Date(run.firstTs).toLocaleString() : '—'}
                  </td>
                  <td className="px-3 py-2 text-white/80">
                    {run.lastTs ? new Date(run.lastTs).toLocaleString() : '—'}
                  </td>
                  <td className="px-3 py-2 text-white/80">{run.entries}</td>
                  <td className="px-3 py-2 text-white/80">{run.pinned ? 'yes' : ''}</td>
                </tr>
              ))
              return header ? [header, ...rows] : rows
            })}
            {!error && filtered.length === 0 && (
              <tr>
                <td className="px-3 py-4 text-center text-white/60" colSpan={columnCount}>
                  No journal runs.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  )
}
