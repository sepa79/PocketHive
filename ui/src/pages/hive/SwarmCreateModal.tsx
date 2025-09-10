import { useEffect, useState } from 'react'
import { createSwarm } from '../../lib/stompClient'

interface Scenario {
  id: string
  name: string
  image: string
}

interface Props {
  onClose: () => void
}

export default function SwarmCreateModal({ onClose }: Props) {
  const [swarmId, setSwarmId] = useState('')
  const [scenarioId, setScenarioId] = useState('')
  const [scenarios, setScenarios] = useState<Scenario[]>([])
  const [message, setMessage] = useState<string | null>(null)

  useEffect(() => {
    fetch('/scenarios')
      .then((r) => r.json())
      .then((data: Scenario[]) => setScenarios(data))
      .catch(() => setScenarios([]))
  }, [])

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!swarmId.trim() || !scenarioId) {
      setMessage('Swarm ID and scenario required')
      return
    }
    if (!/^[a-zA-Z0-9-]+$/.test(swarmId)) {
      setMessage('Invalid swarm ID')
      return
    }
    const scenario = scenarios.find((s) => s.id === scenarioId)
    if (!scenario) {
      setMessage('Scenario not found')
      return
    }
    try {
      await createSwarm(swarmId.trim(), scenario.image, scenario.id)
      setMessage('Swarm created')
      setSwarmId('')
      setScenarioId('')
    } catch {
      setMessage('Failed to create swarm')
    }
  }

  return (
    <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50">
      <div className="bg-[#1a1d24] p-4 rounded w-80">
        <h3 className="text-lg mb-2">Create Swarm</h3>
        <form onSubmit={handleSubmit} className="space-y-2">
          <div>
            <label htmlFor="swarmId" className="block text-sm mb-1">
              Swarm ID
            </label>
            <input
              id="swarmId"
              value={swarmId}
              onChange={(e) => setSwarmId(e.target.value)}
              className="w-full rounded border border-white/20 bg-white/10 px-2 py-1 text-sm"
            />
          </div>
          <div>
            <label htmlFor="scenario" className="block text-sm mb-1">
              Scenario
            </label>
            <select
              id="scenario"
              value={scenarioId}
              onChange={(e) => setScenarioId(e.target.value)}
              className="w-full rounded border border-white/20 bg-white/10 px-2 py-1 text-sm"
            >
              <option value="">Select scenario</option>
              {scenarios.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.name}
                </option>
              ))}
            </select>
          </div>
          <div className="flex justify-end gap-2 pt-2">
            <button
              type="button"
              onClick={onClose}
              className="rounded bg-white/10 hover:bg-white/20 px-2 py-1 text-sm"
            >
              Close
            </button>
            <button
              type="submit"
              className="rounded bg-white/20 hover:bg-white/30 px-2 py-1 text-sm"
            >
              Create
            </button>
          </div>
        </form>
        {message && <div className="text-xs mt-2 text-white/70">{message}</div>}
      </div>
    </div>
  )
}
