import type { Component, HealthStatus } from '../../types/hive'
import { componentHealth } from '../../lib/health'
import { sendConfigUpdate } from '../../lib/orchestratorApi'
import type { MouseEvent } from 'react'
import { Play, Square } from 'lucide-react'

interface Props {
  components: Component[]
  selectedId?: string
  onSelect: (c: Component) => void
}

export default function ComponentList({ components, selectedId, onSelect }: Props) {
  const toggle = async (e: MouseEvent, comp: Component) => {
    e.stopPropagation()
    const enabled = comp.config?.enabled !== false
    try {
      await sendConfigUpdate(comp, { enabled: !enabled })
    } catch (error) {
      console.error('Failed to update component config:', error)
    }
  }
  return (
    <ul className="space-y-2 overflow-y-auto h-full">
      {components.map((c) => {
        const role = c.role.trim() || '—'
        return (
          <li
            key={c.id}
            className={`p-2 rounded cursor-pointer border border-transparent hover:border-white/20 ${
              selectedId === c.id ? 'bg-white/10' : ''
            }`}
            onClick={() => {
              onSelect(c)
              // status refresh no longer supported
            }}
          >
            <div className="flex items-center justify-between gap-2">
              <div>
                <div className="font-medium">{c.id}</div>
                <div className="text-xs text-white/60">{role}</div>
                {c.queues[0] && (
                  <div className="text-xs text-white/60">
                    {c.queues[0].name} • depth:{' '}
                    {c.queues[0].depth ?? '—'}
                  </div>
                )}
              </div>
              <div className="flex items-center gap-2">
                <button
                  className="p-1 rounded bg-white/10 hover:bg-white/20"
                  onClick={(e) => toggle(e, c)}
                >
                  {c.config?.enabled === false ? (
                    <Play className="h-4 w-4" />
                  ) : (
                    <Square className="h-4 w-4" />
                  )}
                </button>
                <span
                  className={`h-3 w-3 rounded-full ${color(componentHealth(c))}`}
                />
              </div>
            </div>
          </li>
        )
      })}
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

