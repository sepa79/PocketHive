import { useEffect, useState } from 'react'
import { subscribeLogs, type LogEntry } from '../lib/logs'

interface SwarmState {
  created?: number
  ready?: number
}

export default function SwarmDebugPanel() {
  const [swarms, setSwarms] = useState<Record<string, SwarmState>>({})

  useEffect(() => {
    return subscribeLogs('handshake', (entries: LogEntry[]) => {
      setSwarms((prev) => {
        const next = { ...prev }
        entries.forEach((e) => {
          const ts = e.ts
          const d = e.destination
          let m: RegExpMatchArray | null
          if ((m = d.match(/ev\.swarm-created\.([^/]+)$/))) {
            const id = m[1]
            next[id] = { ...(next[id] || {}), created: ts }
          } else if ((m = d.match(/ev\.swarm-ready\.([^/]+)$/))) {
            const id = m[1]
            next[id] = { ...(next[id] || {}), ready: ts }
          }
        })
        return { ...next }
      })
    })
  }, [])

  return (
    <div className="mt-4 space-y-2 text-xs font-mono">
      {Object.entries(swarms).map(([id, s]) => {
        return (
          <div key={id} className="p-2 rounded border border-white/20">
            <div className="font-bold mb-1">Swarm {id}</div>
            <ul className="space-y-0.5">
              <li>created: {s.created ? new Date(s.created).toLocaleTimeString() : 'pending'}</li>
              {s.created && (
                <li>ready: {s.ready ? new Date(s.ready).toLocaleTimeString() : 'pending'}</li>
              )}
            </ul>
          </div>
        )
      })}
    </div>
  )
}
