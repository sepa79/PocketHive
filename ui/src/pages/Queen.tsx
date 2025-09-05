import { useState } from 'react'
import { startSwarm } from '../lib/stompClient'
import { templates } from '../lib/templates'

export default function Queen() {
  const [swarmId, setSwarmId] = useState('')
  const [templateId, setTemplateId] = useState('')

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!swarmId || !templateId) return
    const template = templates.find((t) => t.id === templateId)
    if (!template) return
    await startSwarm(swarmId, template.image)
    setSwarmId('')
    setTemplateId('')
  }

  return (
    <div className="card mt-6 p-4 h-[calc(100vh-112px)]">
      <h2 className="kpi-title mb-2">Queen</h2>
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
          <label htmlFor="template" className="block text-sm mb-1">
            Template
          </label>
          <select
            id="template"
            value={templateId}
            onChange={(e) => setTemplateId(e.target.value)}
            className="w-full rounded border border-white/20 bg-white/10 px-2 py-1 text-sm"
          >
            <option value="">Select template</option>
            {templates.map((t) => (
              <option key={t.id} value={t.id}>
                {t.name}
              </option>
            ))}
          </select>
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
