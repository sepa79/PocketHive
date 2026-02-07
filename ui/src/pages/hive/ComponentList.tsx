import type { Component, HealthStatus } from '../../types/hive'
import { componentHealth } from '../../lib/health'
import { sendConfigUpdate } from '../../lib/orchestratorApi'
import { useMemo, type MouseEvent } from 'react'
import { Play, Square } from 'lucide-react'
import { useSwarmMetadata } from '../../contexts/SwarmMetadataContext'

interface Props {
  components: Component[]
  selectedId?: string
  onSelect: (c: Component) => void
  showConnections?: boolean
}

type ConnectionLine = {
  queue: string
  peers: string[]
}

type ConnectionSummary = {
  incoming: ConnectionLine[]
  outgoing: ConnectionLine[]
}

export default function ComponentList({
  components,
  selectedId,
  onSelect,
  showConnections = false,
}: Props) {
  const { findSwarm } = useSwarmMetadata()

  const { connectionMap, highlight } = useMemo(() => {
    if (!showConnections) {
      return {
        connectionMap: new Map<string, ConnectionSummary>(),
        highlight: { incoming: new Set<string>(), outgoing: new Set<string>() },
      }
    }
    const queueMap = new Map<string, { prod: Set<string>; cons: Set<string> }>()
    components.forEach((comp) => {
      const role = comp.role?.trim().toLowerCase()
      if (role === 'swarm-controller') return
      comp.queues.forEach((q) => {
        const name = q.name?.trim()
        if (!name) return
        const entry = queueMap.get(name) ?? { prod: new Set<string>(), cons: new Set<string>() }
        if (q.role === 'producer') {
          entry.prod.add(comp.id)
        } else {
          entry.cons.add(comp.id)
        }
        queueMap.set(name, entry)
      })
    })

    const result = new Map<string, ConnectionSummary>()
    components.forEach((comp) => {
      const role = comp.role?.trim().toLowerCase()
      if (role === 'swarm-controller') return
      const incoming: ConnectionLine[] = []
      const outgoing: ConnectionLine[] = []
      queueMap.forEach((entry, queue) => {
      if (entry.cons.has(comp.id)) {
          const peers = Array.from(entry.prod)
            .filter((id) => id !== comp.id)
            .sort((a, b) => a.localeCompare(b))
          if (peers.length > 0) {
            incoming.push({ queue, peers })
          }
        }
        if (entry.prod.has(comp.id)) {
          const peers = Array.from(entry.cons)
            .filter((id) => id !== comp.id)
            .sort((a, b) => a.localeCompare(b))
          if (peers.length > 0) {
            outgoing.push({ queue, peers })
          }
        }
      })
      if (incoming.length > 0 || outgoing.length > 0) {
        incoming.sort((a, b) => a.queue.localeCompare(b.queue))
        outgoing.sort((a, b) => a.queue.localeCompare(b.queue))
        result.set(comp.id, { incoming, outgoing })
      }
    })
    const highlightIncoming = new Set<string>()
    const highlightOutgoing = new Set<string>()
    if (selectedId) {
      queueMap.forEach((entry) => {
        if (entry.cons.has(selectedId)) {
          entry.prod.forEach((id) => {
            if (id !== selectedId) highlightIncoming.add(id)
          })
        }
        if (entry.prod.has(selectedId)) {
          entry.cons.forEach((id) => {
            if (id !== selectedId) highlightOutgoing.add(id)
          })
        }
      })
    }
    return {
      connectionMap: result,
      highlight: { incoming: highlightIncoming, outgoing: highlightOutgoing },
    }
  }, [components, selectedId, showConnections])

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
    <ul className="space-y-2">
      {components.map((c) => {
        const roleRaw = c.role ?? ''
        const role = roleRaw.trim() || '—'
        const normalizedRole = roleRaw.trim().toLowerCase()
        const canToggle = Boolean(c.role?.trim())
        const enabled = c.config?.enabled !== false
        const errored = Boolean(c.lastErrorAt)
        const swarm = findSwarm(c.swarmId ?? null)
        const sutId = swarm?.sutId?.trim()
        const showSutBadge = sutId && normalizedRole === 'processor'
        const connections = connectionMap.get(c.id)
        const incomingHighlight = highlight.incoming.has(c.id)
        const outgoingHighlight = highlight.outgoing.has(c.id)
        let highlightClass = ''
        if (selectedId && selectedId !== c.id && !errored) {
          if (incomingHighlight && outgoingHighlight) {
            highlightClass = 'border-fuchsia-400/50 bg-fuchsia-500/10'
          } else if (incomingHighlight) {
            highlightClass = 'border-sky-400/50 bg-sky-500/10'
          } else if (outgoingHighlight) {
            highlightClass = 'border-amber-400/50 bg-amber-500/10'
          }
        }
        return (
          <li
            key={c.id}
            className={`p-2 rounded cursor-pointer border hover:border-white/20 ${
              errored ? 'border-red-500/40 bg-red-500/10' : 'border-transparent'
            } ${selectedId === c.id ? 'bg-white/10' : ''} ${highlightClass}`}
            onClick={() => {
              onSelect(c)
              // status refresh no longer supported
            }}
          >
            <div className="flex items-center justify-between gap-2">
              <div>
                <div className="font-medium flex items-center gap-2">
                  <span className={errored ? 'text-red-200' : ''}>{c.id}</span>
                  {showSutBadge && (
                    <span className="inline-flex items-center rounded-full bg-purple-500/20 border border-purple-400/40 px-1.5 py-0.5 text-[10px] uppercase tracking-wide text-purple-200">
                      SUT
                    </span>
                  )}
                </div>
                <div className="text-xs text-white/60 flex flex-wrap items-center gap-2">
                  <span>{role}</span>
                  {showConnections &&
                    connections?.incoming.map((line) => (
                      <span key={`in-${line.queue}`} className="text-sky-300/90">
                        in:{line.queue}&larr;{formatPeers(line.peers)}
                      </span>
                    ))}
                  {showConnections &&
                    connections?.outgoing.map((line) => (
                      <span key={`out-${line.queue}`} className="text-amber-300/90">
                        out:{line.queue}&rarr;{formatPeers(line.peers)}
                      </span>
                    ))}
                </div>
                {c.queues[0] && (
                  <div className="text-xs text-white/60">
                    {c.queues[0].name} • depth:{' '}
                    {c.queues[0].depth ?? '—'}
                  </div>
                )}
              </div>
              <div className="flex items-center gap-2">
                {canToggle && (
                  <button
                    type="button"
                    className="p-1 rounded bg-white/10 hover:bg-white/20"
                    onClick={(e) => toggle(e, c)}
                    aria-label={`${enabled ? 'Disable' : 'Enable'} ${c.id}`}
                  >
                    {enabled ? (
                      <Square className="h-4 w-4" />
                    ) : (
                      <Play className="h-4 w-4" />
                    )}
                  </button>
                )}
                <span
                  data-testid="component-status"
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

function formatPeers(peers: string[]): string {
  if (peers.length <= 2) {
    return peers.join(', ')
  }
  return `${peers.slice(0, 2).join(', ')} +${peers.length - 2}`
}
