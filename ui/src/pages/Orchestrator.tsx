import { useState } from 'react'
import { startSwarm } from '../lib/stompClient'

export default function Orchestrator() {
  const [swarmId, setSwarmId] = useState('')
  const [image, setImage] = useState('')

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!swarmId || !image) return
    await startSwarm(swarmId, image)
    setSwarmId('')
    setImage('')
  }

  return (
    <div className="card mt-6 p-4 h-[calc(100vh-112px)]">
      <h2 className="kpi-title mb-2">Orchestrator</h2>
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
          <label htmlFor="image" className="block text-sm mb-1">
            Image
          </label>
          <input
            id="image"
            value={image}
            onChange={(e) => setImage(e.target.value)}
            className="w-full rounded border border-white/20 bg-white/10 px-2 py-1 text-sm"
          />
        </div>
        <button
          type="submit"
          className="mt-2 rounded bg-white/20 hover:bg-white/30 px-2 py-1 text-sm"
        >
          Start
        </button>
      </form>
    </div>
  )
}
