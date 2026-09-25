/**
 * Responsibility: present loading and retryable permission observation errors.
 * Must not: treat failed observations as permission denials.
 * Contract: RESP-UI-GLOBAL-ACCESS — docs/architecture/runtime-responsibilities.md#resp-ui-global-access.
 */
export function AccessObservationNotice({ status, error, retry }: {
  status: 'idle' | 'loading' | 'ready' | 'error'; error: string | null; retry: () => Promise<void>
}) {
  if (status === 'loading') return <div className="card" role="status">Loading permissions…</div>
  if (status !== 'error') return null
  return <div className="card" role="alert">
    <div>Permissions unavailable: {error}</div>
    <button type="button" className="actionButton" onClick={() => void retry()}>Retry permissions</button>
  </div>
}
