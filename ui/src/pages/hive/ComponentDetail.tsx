import { useState } from 'react'
import type { Component } from '../../types/hive'
import { sendConfigUpdate } from '../../lib/stompClient'
import QueuesPanel from './QueuesPanel'
import { heartbeatHealth, colorForHealth } from '../../lib/health'

interface Props {
  component: Component
  onClose: () => void
}

export default function ComponentDetail({ component, onClose }: Props) {
  const [toast, setToast] = useState<string | null>(null)

  const handleConfig = async () => {
    try {
      await sendConfigUpdate(component.id, {})
      setToast('Config update sent')
    } catch {
      setToast('Config update failed')
    }
    setTimeout(() => setToast(null), 3000)
  }

  const health = heartbeatHealth(component.lastHeartbeat)

  return (
    <div className="flex-1 p-4 overflow-y-auto relative">
      <button className="absolute top-2 right-2" onClick={onClose}>
        ×
      </button>
      <h2 className="text-xl mb-2 flex items-center gap-2">
        {component.name}
        <span className={`h-3 w-3 rounded-full ${colorForHealth(health)}`} />
      </h2>
      <div className="space-y-1 text-sm mb-4">
        <div>Version: {component.version ?? '—'}</div>
        <div>Uptime: {formatSeconds(component.uptimeSec)}</div>
        <div>Last heartbeat: {timeAgo(component.lastHeartbeat)}</div>
        <div>Env: {component.env ?? '—'}</div>
        <div>Status: {component.status ?? '—'}</div>
      </div>
      <div className="p-4 border border-white/10 rounded mb-4 text-sm text-white/60">
        component-specific controls go here
      </div>
      <button
        className="mb-4 rounded bg-blue-600 px-3 py-1 text-sm"
        onClick={handleConfig}
      >
        Send config.update
      </button>
      {toast && (
        <div className="fixed bottom-4 right-4 bg-black/80 text-white px-4 py-2 rounded">
          {toast}
        </div>
      )}
      <h3 className="text-lg mb-2">Queues</h3>
      <QueuesPanel queues={component.queues} />
    </div>
  )
}

function formatSeconds(sec?: number) {
  if (sec == null) return '—'
  const h = Math.floor(sec / 3600)
  const m = Math.floor((sec % 3600) / 60)
  const s = sec % 60
  return `${h}h ${m}m ${s}s`
}

function timeAgo(ts: number) {
  const diff = Math.floor((Date.now() - ts) / 1000)
  return `${diff}s ago`
}

