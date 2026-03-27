import { Fragment, useMemo, useState } from 'react'
import {
  filterJournalEntries,
  formatJournalSummary,
  groupJournalEntries,
  journalSeverityTone,
  type SwarmJournalEntry,
} from '../../lib/journal'

function formatTimestamp(value: string): string {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleString()
}

function renderJson(value: unknown): string {
  if (value === null || value === undefined) return 'null'
  try {
    return JSON.stringify(value, null, 2)
  } catch {
    return String(value)
  }
}

export function JournalEntriesCard({
  title,
  subtitle,
  entries,
  loading,
  error,
  search,
  errorsOnly,
  hasMore,
  loadingMore,
  emptyMessage,
  onSearchChange,
  onErrorsOnlyChange,
  onLoadMore,
  onOpenSwarmJournal,
}: {
  title: string
  subtitle: string
  entries: SwarmJournalEntry[]
  loading: boolean
  error: string | null
  search: string
  errorsOnly: boolean
  hasMore: boolean
  loadingMore: boolean
  emptyMessage: string
  onSearchChange: (value: string) => void
  onErrorsOnlyChange: (value: boolean) => void
  onLoadMore?: (() => void) | null
  onOpenSwarmJournal?: ((swarmId: string, runId?: string | null) => void) | null
}) {
  const [expandedKey, setExpandedKey] = useState<string | null>(null)

  const filtered = useMemo(
    () => filterJournalEntries(entries, { search, errorsOnly }),
    [entries, errorsOnly, search],
  )
  const rows = useMemo(() => groupJournalEntries(filtered), [filtered])
  const showSwarmColumn = Boolean(onOpenSwarmJournal)
  const summaryLabel =
    rows.length !== filtered.length ? `${filtered.length} entries (${rows.length} groups)` : `${filtered.length} entries`

  function formatTime(value: string): string {
    const date = new Date(value)
    if (Number.isNaN(date.getTime())) return value
    return date.toLocaleTimeString()
  }

  function renderDetails(entry: SwarmJournalEntry): string {
    const parts = []
    if (entry.correlationId) parts.push(`corr=${entry.correlationId}`)
    if (entry.idempotencyKey) parts.push(`idem=${entry.idempotencyKey}`)
    parts.push(`scope=${entry.scope.role ?? 'ALL'}/${entry.scope.instance ?? 'ALL'}`)
    if (entry.routingKey) parts.push(`rk=${entry.routingKey}`)
    return parts.join('  ')
  }

  return (
    <div className="card journalCard">
      <div className="journalHeader">
        <div>
          <div className="h2">{title}</div>
          <div className="muted">{subtitle}</div>
        </div>
        <div className="journalFilters">
          <input
            className="textInput textInputCompact"
            type="text"
            value={search}
            onChange={(event) => onSearchChange(event.currentTarget.value)}
            placeholder="Search journal..."
          />
          <label className="journalToggle">
            <input
              type="checkbox"
              checked={errorsOnly}
              onChange={(event) => onErrorsOnlyChange(event.currentTarget.checked)}
            />
            <span>Errors only</span>
          </label>
          <div className="journalCount">{loading ? 'Refreshing…' : summaryLabel}</div>
        </div>
      </div>

      {loading && entries.length === 0 ? <div className="muted">Loading journal…</div> : null}
      {error ? <div className="journalError">{error}</div> : null}
      {!loading && !error && rows.length === 0 ? <div className="muted">{emptyMessage}</div> : null}

      {rows.length > 0 ? (
        <div className="journalTableWrap">
          <table className="journalTable">
            <thead>
              <tr>
                <th>Time</th>
                {showSwarmColumn ? <th>Swarm</th> : null}
                <th>Severity</th>
                <th>Dir</th>
                <th>Origin</th>
                <th>Kind</th>
                <th>Type</th>
                <th>Summary</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((row, index) => {
                const entry = row.entry
                const tone = journalSeverityTone(entry.severity)
                const key = String(entry.eventId ?? `${entry.timestamp}-${index}`)
                const expanded = expandedKey === key
                const severityClass =
                  tone === 'bad'
                    ? 'journalSeverityBad'
                    : tone === 'warn'
                      ? 'journalSeverityWarn'
                      : 'journalSeverityInfo'
                const colSpan = showSwarmColumn ? 8 : 7
                return (
                  <Fragment key={key}>
                    <tr
                      className={expanded ? 'journalTableRow journalTableRowExpanded' : 'journalTableRow'}
                      onClick={() => setExpandedKey(expanded ? null : key)}
                    >
                      <td className="journalTimeCell">{formatTime(entry.timestamp)}</td>
                      {showSwarmColumn ? (
                        <td className="journalMonoCell">
                          <button
                            type="button"
                            className="journalLinkButton"
                            onClick={(event) => {
                              event.stopPropagation()
                              onOpenSwarmJournal?.(entry.swarmId, entry.runId)
                            }}
                          >
                            {entry.swarmId}
                          </button>
                        </td>
                      ) : null}
                      <td className={severityClass}>{entry.severity}</td>
                      <td className="journalMonoCell">{entry.direction}</td>
                      <td>{entry.origin}</td>
                      <td className="journalMonoCell">{entry.kind}</td>
                      <td className="journalMonoCell">{entry.type}</td>
                      <td className="journalSummaryCell">
                        {formatJournalSummary(entry)}
                        {row.count > 1 ? <span className="journalRepeat"> ×{row.count}</span> : null}
                      </td>
                    </tr>
                    {expanded ? (
                      <tr className="journalDetailsRow">
                        <td colSpan={colSpan}>
                          <div className="journalDetails">
                            {row.count > 1 ? (
                              <div className="journalDetailMeta">
                                Grouped {row.count} entries from {formatTimestamp(row.firstTimestamp)} to{' '}
                                {formatTimestamp(row.lastTimestamp)}.
                              </div>
                            ) : null}
                            <div className="journalDetailMeta">{renderDetails(entry)}</div>
                            <div className="journalDetailsGrid">
                              <div>
                                <div className="journalDetailLabel">data</div>
                                <pre className="codePre journalCode">{renderJson(entry.data)}</pre>
                              </div>
                              <div>
                                <div className="journalDetailLabel">extra</div>
                                <pre className="codePre journalCode">{renderJson(entry.extra)}</pre>
                              </div>
                              <div>
                                <div className="journalDetailLabel">raw</div>
                                <pre className="codePre journalCode">{renderJson(entry.raw)}</pre>
                              </div>
                            </div>
                          </div>
                        </td>
                      </tr>
                    ) : null}
                  </Fragment>
                )
              })}
            </tbody>
          </table>
        </div>
      ) : null}

      {hasMore && onLoadMore ? (
        <div className="journalFooter">
          <button
            type="button"
            className="actionButton actionButtonGhost"
            onClick={onLoadMore}
            disabled={loadingMore}
          >
            {loadingMore ? 'Loading older…' : 'Load older'}
          </button>
        </div>
      ) : null}
    </div>
  )
}
