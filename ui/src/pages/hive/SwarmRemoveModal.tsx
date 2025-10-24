import { useState } from 'react'
import { removeSwarm } from '../../lib/orchestratorApi'

interface Props {
  swarmId: string
  onClose: () => void
  onRemoved: () => void
}

export default function SwarmRemoveModal({ swarmId, onClose, onRemoved }: Props) {
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const handleConfirm = async () => {
    setSubmitting(true)
    setError(null)
    try {
      await removeSwarm(swarmId)
      onRemoved()
    } catch {
      setError('Failed to remove swarm')
      setSubmitting(false)
    }
  }

  return (
    <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50">
      <div
        role="dialog"
        aria-modal="true"
        aria-labelledby="remove-swarm-title"
        className="bg-[#1a1d24] p-4 rounded w-80"
      >
        <h3 id="remove-swarm-title" className="text-lg mb-2">
          Remove swarm
        </h3>
        <p className="text-sm mb-4">
          This will request removal of swarm <span className="font-mono">{swarmId}</span>. Components belonging to the swarm
          will disappear once the orchestrator confirms the removal.
        </p>
        <div className="flex justify-end gap-2">
          <button
            type="button"
            onClick={onClose}
            disabled={submitting}
            className="rounded bg-white/10 hover:bg-white/20 disabled:hover:bg-white/10 px-2 py-1 text-sm"
          >
            Cancel
          </button>
          <button
            type="button"
            onClick={handleConfirm}
            disabled={submitting}
            aria-busy={submitting}
            className="rounded bg-red-500/80 hover:bg-red-500 disabled:hover:bg-red-500/80 px-2 py-1 text-sm"
          >
            {submitting ? 'Sending…' : 'Send Remove swarm'}
          </button>
        </div>
        {error && <div className="text-xs mt-2 text-red-300">{error}</div>}
      </div>
    </div>
  )
}
