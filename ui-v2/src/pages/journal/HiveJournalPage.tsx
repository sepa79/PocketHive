import { useCallback, useEffect, useMemo, useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { JournalEntriesCard } from '../../components/journal/JournalEntriesCard'
import { getHiveJournalPage } from '../../lib/journalApi'
import type { JournalCursor, SwarmJournalEntry } from '../../lib/journal'

function normalizeQueryParam(value: string | null): string {
  const trimmed = value?.trim()
  return trimmed ? trimmed : ''
}

export function HiveJournalPage() {
  const navigate = useNavigate()
  const location = useLocation()
  const [entries, setEntries] = useState<SwarmJournalEntry[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [search, setSearch] = useState('')
  const [errorsOnly, setErrorsOnly] = useState(false)
  const [swarmId, setSwarmId] = useState('')
  const [runId, setRunId] = useState('')
  const [draftSwarmId, setDraftSwarmId] = useState('')
  const [draftRunId, setDraftRunId] = useState('')
  const [cursor, setCursor] = useState<JournalCursor | null>(null)
  const [hasMore, setHasMore] = useState(false)
  const [loadingMore, setLoadingMore] = useState(false)

  useEffect(() => {
    const params = new URLSearchParams(location.search)
    const nextSwarmId = normalizeQueryParam(params.get('swarmId'))
    const nextRunId = normalizeQueryParam(params.get('runId'))
    setSwarmId(nextSwarmId)
    setRunId(nextRunId)
    setDraftSwarmId(nextSwarmId)
    setDraftRunId(nextRunId)
  }, [location.search])

  const applyFilters = useCallback(() => {
    const params = new URLSearchParams(location.search)
    const nextSwarmId = draftSwarmId.trim()
    const nextRunId = draftRunId.trim()
    if (nextSwarmId) params.set('swarmId', nextSwarmId)
    else params.delete('swarmId')
    if (nextRunId) params.set('runId', nextRunId)
    else params.delete('runId')
    navigate(`/journal/hive${params.toString() ? `?${params.toString()}` : ''}`)
  }, [draftRunId, draftSwarmId, location.search, navigate])

  const loadLatest = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const page = await getHiveJournalPage({
        swarmId: swarmId || null,
        runId: runId || null,
        limit: 100,
      })
      setEntries(page?.items ?? [])
      setCursor(page?.nextCursor ?? null)
      setHasMore(page?.hasMore ?? false)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load hive journal')
    } finally {
      setLoading(false)
    }
  }, [runId, swarmId])

  useEffect(() => {
    void loadLatest()
    const handle = window.setInterval(() => {
      void loadLatest()
    }, 5000)
    return () => window.clearInterval(handle)
  }, [loadLatest])

  const loadOlder = useCallback(async () => {
    if (!cursor || loadingMore) return
    setLoadingMore(true)
    try {
      const page = await getHiveJournalPage({
        swarmId: swarmId || null,
        runId: runId || null,
        limit: 300,
        before: cursor,
      })
      setEntries((prev) => [...prev, ...(page?.items ?? [])])
      setCursor(page?.nextCursor ?? null)
      setHasMore(page?.hasMore ?? false)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load older journal entries')
    } finally {
      setLoadingMore(false)
    }
  }, [cursor, loadingMore, runId, swarmId])

  const subtitle = useMemo(() => {
    if (swarmId && runId) return `Hive journal filtered by swarm '${swarmId}' and run '${runId}'.`
    if (swarmId) return `Hive journal filtered by swarm '${swarmId}'.`
    if (runId) return `Hive journal filtered by run '${runId}'.`
    return 'Recent hive-wide control-plane and orchestration events.'
  }, [runId, swarmId])

  return (
    <div className="page">
      <div>
        <h1 className="h1">Hive Journal</h1>
        <div className="muted">Paged hive-wide journal view for current diagnostics.</div>
      </div>

      <div className="card journalToolbar">
        <label className="field">
          <span className="fieldLabel">Swarm ID</span>
          <input
            className="textInput textInputCompact"
            value={draftSwarmId}
            onChange={(event) => setDraftSwarmId(event.currentTarget.value)}
            placeholder="Optional swarm id"
          />
        </label>
        <label className="field">
          <span className="fieldLabel">Run ID</span>
          <input
            className="textInput textInputCompact"
            value={draftRunId}
            onChange={(event) => setDraftRunId(event.currentTarget.value)}
            placeholder="Optional run id"
          />
        </label>
        <div className="journalToolbarActions">
          <button type="button" className="actionButton" onClick={applyFilters}>
            Apply
          </button>
          <button type="button" className="actionButton actionButtonGhost" onClick={() => void loadLatest()}>
            Refresh
          </button>
        </div>
      </div>

      <JournalEntriesCard
        title="Hive journal"
        subtitle={subtitle}
        entries={entries}
        loading={loading}
        error={error}
        search={search}
        errorsOnly={errorsOnly}
        hasMore={hasMore}
        loadingMore={loadingMore}
        emptyMessage="Hive journal is empty so far."
        onSearchChange={setSearch}
        onErrorsOnlyChange={setErrorsOnly}
        onLoadMore={loadOlder}
        onOpenSwarmJournal={(targetSwarmId, targetRunId) => {
          const params = new URLSearchParams()
          if (targetRunId) params.set('runId', targetRunId)
          navigate(`/journal/swarms/${encodeURIComponent(targetSwarmId)}${params.toString() ? `?${params.toString()}` : ''}`)
        }}
      />
    </div>
  )
}
