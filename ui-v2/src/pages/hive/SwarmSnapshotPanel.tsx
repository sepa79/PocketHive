import type { SwarmStateSnapshot } from '../../lib/SwarmStateSnapshot'

type Props = { snapshot: SwarmStateSnapshot; onOpenJournal: () => void }

/**
 * Responsibility: present the decoded swarm state and server observation freshness.
 * Must not: infer runtime metadata, freshness thresholds or permissions.
 * Contract: RESP-UI-SWARM-DETAIL — docs/architecture/runtime-responsibilities.md#resp-ui-swarm-detail.
 */
export function SwarmSnapshotPanel({ snapshot, onOpenJournal }: Props) {
  const fields = [
    ['Controller state', snapshot.controllerState],
    ['Workload state', snapshot.workloadState],
    ['Health', snapshot.health],
    ['Observed at', snapshot.observedAt ?? 'Not yet observed'],
    ['Template ID', snapshot.templateId ?? 'Not recorded'],
    ['Run ID', snapshot.runId],
    ['Controller image', snapshot.controllerImage ?? 'Not recorded'],
  ]
  return (
    <section className="card" style={{ marginTop: 10 }} aria-label="Runtime snapshot">
      <div className="row between">
        <div className="h2">Runtime snapshot</div>
        <div className="row">
          <button type="button" className="actionButton actionButtonGhost actionButtonTiny" onClick={onOpenJournal}>
            Open journal
          </button>
          <span className="muted" title="Observation freshness is reported by Orchestrator.">
            Observation: {snapshot.observedAt === null ? 'Not yet observed' : snapshot.observationStale ? 'Stale' : 'Fresh'}
          </span>
        </div>
      </div>
      <div className="kvGrid" style={{ marginTop: 10 }}>
        {fields.map(([label, value]) => (
          <div className="kv" key={label}>
            <div className="k">{label}</div>
            <div className="v">{value}</div>
          </div>
        ))}
      </div>
    </section>
  )
}
