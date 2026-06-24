import type { JournalIssue } from '../../lib/journal'

export function SwarmIssueBox({
  issue,
  error,
  loading = false,
  onOpenJournal,
}: {
  issue: JournalIssue | null
  error: string | null
  loading?: boolean
  onOpenJournal: () => void
}) {
  if (error) {
    return (
      <div className="card journalIssueBox journalIssueBoxWarn">
        <div className="journalIssueHeader">
          <div className="h2">Diagnostics unavailable</div>
          <button type="button" className="actionButton actionButtonGhost actionButtonTiny" onClick={onOpenJournal}>
            Open journal
          </button>
        </div>
        <div className="muted">{error}</div>
      </div>
    )
  }

  if (!issue) {
    return loading ? (
      <div className="card journalIssueBox">
        <div className="muted">Loading diagnostics...</div>
      </div>
    ) : null
  }

  return (
    <div className="card journalIssueBox journalIssueBoxBad">
      <div className="journalIssueHeader">
        <div className="row" style={{ flexWrap: 'wrap' }}>
          <div className="h2">Latest issue</div>
          <span className="pill pillBad">{issue.severity}</span>
          {issue.phase ? <span className="pill pillWarn">phase {issue.phase}</span> : null}
          {issue.code ? <span className="pill pillInfo">{issue.code}</span> : null}
        </div>
        <button type="button" className="actionButton actionButtonGhost actionButtonTiny" onClick={onOpenJournal}>
          Open journal
        </button>
      </div>
      <div className="journalIssueMessage">{issue.message}</div>
      <div className="journalIssueMeta">
        <span>{issue.timestamp}</span>
        {issue.worker ? <span>worker {issue.worker}</span> : null}
      </div>
    </div>
  )
}
