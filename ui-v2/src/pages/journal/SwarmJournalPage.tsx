import { useCallback, useEffect, useMemo, useState } from 'react'
import { useLocation, useNavigate, useParams } from 'react-router-dom'
import { JournalEntriesCard } from '../../components/journal/JournalEntriesCard'
import { getSwarmJournalPage } from '../../lib/journalApi'
import type { JournalCursor, SwarmJournalEntry } from '../../lib/journal'

function normalizeQueryParam(value: string | null): string {
  const trimmed = value?.trim()
  return trimmed ? trimmed : ''
}

export function SwarmJournalPage() {
  const { swarmId } = useParams<{ swarmId: string }>()
  const navigate = useNavigate()
  const location = useLocation()
  const resolvedSwarmId = swarmId?.trim() ?? ''
  const [entries, setEntries] = useState<SwarmJournalEntry[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [search, setSearch] = useState('')
  const [errorsOnly, setErrorsOnly] = useState(false)
  const [runId, setRunId] = useState('')
  const [draftRunId, setDraftRunId] = useState('')
  const [cursor, setCursor] = useState<JournalCursor | null>(null)
  const [hasMore, setHasMore] = useState(false)
  const [loadingMore, setLoadingMore] = useState(false)

  useEffect(() => {
    const params = new URLSearchParams(location.search)
    const nextRunId = normalizeQueryParam(params.get('runId'))
    setRunId(nextRunId)
    setDraftRunId(nextRunId)
  }, [location.search])

  const applyFilters = useCallback(() => {
    const params = new URLSearchParams(location.search)
    const nextRunId = draftRunId.trim()
    if (nextRunId) params.set('runId', nextRunId)
    else params.delete('runId')
    navigate(`/journal/swarms/${encodeURIComponent(resolvedSwarmId)}${params.toString() ? `?${params.toString()}` : ''}`)
  }, [draftRunId, location.search, navigate, resolvedSwarmId])

  const loadLatest = useCallback(async () => {
    if (!resolvedSwarmId) return
    setLoading(true)
    setError(null)
    try {
      const page = await getSwarmJournalPage(resolvedSwarmId, {
        runId: runId || null,
        limit: 100,
      })
      setEntries(page?.items ?? [])
      setCursor(page?.nextCursor ?? null)
      setHasMore(page?.hasMore ?? false)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load swarm journal')
    } finally {
      setLoading(false)
    }
  }, [resolvedSwarmId, runId])

  useEffect(() => {
    void loadLatest()
    const handle = window.setInterval(() => {
      void loadLatest()
    }, 5000)
    return () => window.clearInterval(handle)
  }, [loadLatest])

  const loadOlder = useCallback(async () => {
    if (!resolvedSwarmId || !cursor || loadingMore) return
    setLoadingMore(true)
    try {
      const page = await getSwarmJournalPage(resolvedSwarmId, {
        runId: runId || null,
        limit: 300,
        before: cursor,
      })
      setEntries((prev) => [...prev, ...(page?.items ?? [])])
      setCursor(page?.nextCursor ?? null)
      setHasMore(page?.hasMore ?? false)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load older swarm journal entries')
    } finally {
      setLoadingMore(false)
    }
  }, [cursor, loadingMore, resolvedSwarmId, runId])

  const subtitle = useMemo(() => {
    if (runId) return `Journal for swarm '${resolvedSwarmId}' and run '${runId}'.`
    return `Recent journal events for swarm '${resolvedSwarmId}'.`
  }, [resolvedSwarmId, runId])

  return (
    <div className="page">
      <div className="row between journalPageHeader">
        <div>
          <h1 className="h1">Swarm Journal</h1>
          <div className="muted">{subtitle}</div>
        </div>
        <div className="row">
          <button type="button" className="actionButton actionButtonGhost" onClick={() => navigate('/journal/hive')}>
            Open hive journal
          </button>
          <button type="button" className="actionButton actionButtonGhost" onClick={() => navigate(`/hive/${encodeURIComponent(resolvedSwarmId)}`)}>
            Open swarm
          </button>
        </div>
      </div>

      <div className="card journalToolbar">
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
        title="Swarm journal"
        subtitle={subtitle}
        entries={entries}
        loading={loading}
        error={error}
        search={search}
        errorsOnly={errorsOnly}
        hasMore={hasMore}
        loadingMore={loadingMore}
        emptyMessage="Journal is empty so far for this swarm."
        onSearchChange={setSearch}
        onErrorsOnlyChange={setErrorsOnly}
        onLoadMore={loadOlder}
      />
    </div>
  )
}
