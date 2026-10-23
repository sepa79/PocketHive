type Props = {
  loading: boolean
  error: string | null
  onRetry: () => void | Promise<void>
}

/**
 * Responsibility: present runtime read progress and retryable failure across Hive detail tabs.
 * Must not: fetch runtime state, retain old detail values or decide command permissions.
 * Contract: RESP-UI-SWARM-DETAIL — docs/architecture/runtime-responsibilities.md#resp-ui-swarm-detail.
 */
export function SwarmDetailStatus({ loading, error, onRetry }: Props) {
  if (error) return (
    <div className="card swarmMessage" role="alert" style={{ marginTop: 8 }}>
      <div>Unable to load runtime snapshot: {error}</div>
      <button
        type="button"
        className="actionButton actionButtonGhost"
        onClick={() => void onRetry()}
        disabled={loading}
      >
        Retry runtime snapshot
      </button>
    </div>
  )
  if (loading) return (
    <div className="muted" role="status" style={{ marginTop: 8 }}>Loading runtime snapshot…</div>
  )
  return null
}
