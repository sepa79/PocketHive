import type { Component, HealthStatus } from '../../types/hive'
import { componentHealth } from '../../lib/health'
import { sendConfigUpdate, requestStatus } from '../../lib/stompClient'
import type { MouseEvent } from 'react'

interface Props {
  components: Component[]
  selectedId?: string
  onSelect: (c: Component) => void
}

export default function ComponentList({ components, selectedId, onSelect }: Props) {
  const toggle = async (e: MouseEvent, id: string, enabled: boolean) => {
    e.stopPropagation()
    try {
      await sendConfigUpdate(id, { enabled })
      await requestStatus(id)
    } catch {
      // ignore errors for now
    }
  }
  return (
    <ul className="space-y-2 overflow-y-auto h-full">
      {components.map((c) => (
        <li
          key={c.id}
          className={`p-2 rounded cursor-pointer border border-transparent hover:border-white/20 ${
            selectedId === c.id ? 'bg-white/10' : ''
          }`}
          onClick={() => {
            onSelect(c)
            requestStatus(c.id).catch(() => {})
          }}
        >
          <div className="flex items-center justify-between gap-2">
            <div>
              <div className="font-medium">{c.name}</div>
              <div className="text-xs text-white/50">{c.id}</div>
              {c.queues[0] && (
                <div className="text-xs text-white/60">
                  {c.queues[0].name} • depth:{' '}
                  {c.queues[0].depth ?? '—'}
                </div>
              )}
            </div>
            <div className="flex items-center gap-2">
              <button
                className="text-xs rounded bg-green-600 px-2 py-1"
                onClick={(e) => toggle(e, c.id, true)}
              >
                Start
              </button>
              <button
                className="text-xs rounded bg-red-600 px-2 py-1"
                onClick={(e) => toggle(e, c.id, false)}
              >
                Stop
              </button>
              <span
                className={`h-3 w-3 rounded-full ${color(componentHealth(c))}`}
              />
            </div>
          </div>
        </li>
      ))}
    </ul>
  )
}

function color(h: HealthStatus) {
  switch (h) {
    case 'WARN':
      return 'bg-yellow-500'
    case 'ALERT':
      return 'bg-red-500'
    default:
      return 'bg-green-500'
  }
}

