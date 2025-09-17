import { useEffect, useState } from 'react'
import ComponentList from './ComponentList'
import ComponentDetail from './ComponentDetail'
import TopologyView from './TopologyView'
import SwarmCreateModal from './SwarmCreateModal'
import { subscribeComponents } from '../../lib/stompClient'
import { startSwarm, stopSwarm, removeSwarm } from '../../lib/orchestratorApi'
import { aggregateSwarmHealth, colorForHealth } from '../../lib/health'
import type { Component, HealthStatus } from '../../types/hive'

export default function HivePage() {
  const [components, setComponents] = useState<Component[]>([])
  const [selected, setSelected] = useState<Component | null>(null)
  const [search, setSearch] = useState('')
  const [showCreate, setShowCreate] = useState(false)
  const [swarmMsg, setSwarmMsg] = useState<Record<string, string>>({})
  const [activeSwarm, setActiveSwarm] = useState<string | null>(null)
  const [pendingRemoval, setPendingRemoval] = useState<string | null>(null)

  useEffect(() => {
    // We rely on the control-plane event stream (`ev.status-*`) to keep the
    // component list current, so no manual `requestStatusFull` calls remain.
    const unsub = subscribeComponents(setComponents)
    return () => unsub()
  }, [])

  useEffect(() => {
    if (selected) {
      const updated = components.find((c) => c.id === selected.id)
      if (updated) setSelected(updated)
    }
  }, [components, selected])

  useEffect(() => {
    setSelected(null)
  }, [activeSwarm])

  useEffect(() => {
    if (!pendingRemoval) return
    const exists = components.some(
      (component) => (component.swarmId || 'default') === pendingRemoval,
    )
    if (!exists) setPendingRemoval(null)
  }, [components, pendingRemoval])

  const filtered = components.filter(
    (c) =>
      (c.name.toLowerCase().includes(search.toLowerCase()) ||
        c.id.toLowerCase().includes(search.toLowerCase())) &&
      (!activeSwarm ||
        (activeSwarm === 'default'
          ? !c.swarmId
          : c.swarmId === activeSwarm)),
  )

  const grouped = filtered.reduce<Record<string, Component[]>>((acc, c) => {
    const swarm = c.swarmId || 'default'
    acc[swarm] = acc[swarm] || []
    acc[swarm].push(c)
    return acc
  }, {})

  const swarmStatus = (comps: Component[]) => {
    if (comps.length === 0) return 'removed'
    const controller = comps.find((c) => c.name === 'swarm-controller')
    const status = (controller?.config?.swarmStatus as string | undefined)?.toLowerCase()
    if (status) {
      if (status.includes('remov')) return status
      return status
    }
    const enabled = comps.map((c) => c.config?.enabled !== false)
    if (enabled.length === 0) return 'unknown'
    if (enabled.every(Boolean)) return 'running'
    if (enabled.every((e) => !e)) return 'stopped'
    return 'partial'
  }

  const handleStart = async (id: string) => {
    try {
      await startSwarm(id)
      // Success toasts are immediate, but topology updates wait for the next
      // `ev.status-*` event emitted by the swarm-controller.
      setSwarmMsg((m) => ({ ...m, [id]: 'Swarm started' }))
    } catch {
      setSwarmMsg((m) => ({ ...m, [id]: 'Failed to start swarm' }))
    }
  }

  const handleStop = async (id: string) => {
    try {
      await stopSwarm(id)
      // Refresh still relies on incoming status events instead of ad-hoc
      // `status-request` calls.
      setSwarmMsg((m) => ({ ...m, [id]: 'Swarm stopped' }))
    } catch {
      setSwarmMsg((m) => ({ ...m, [id]: 'Failed to stop swarm' }))
    }
  }

  const handleRemove = (id: string) => {
    setPendingRemoval(id)
  }

  const confirmRemove = async (id: string) => {
    try {
      await removeSwarm(id)
      setSwarmMsg((m) => ({ ...m, [id]: 'Swarm removal requested' }))
      setActiveSwarm((current) => (current === id ? null : current))
      setSelected((prev) => (prev && prev.swarmId === id ? null : prev))
    } catch {
      setSwarmMsg((m) => ({ ...m, [id]: 'Failed to remove swarm' }))
    } finally {
      setPendingRemoval(null)
    }
  }

  return (
    <div className="flex h-full min-h-0 overflow-hidden">
      <div className="w-full md:w-1/3 xl:w-1/4 border-r border-white/10 p-4 flex flex-col">
        <div className="flex items-center gap-2">
          <input
            className="flex-1 rounded bg-white/5 p-2 text-white"
            placeholder="Search components"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
          />
          <button
            className="rounded bg-white/20 hover:bg-white/30 px-2 py-1 text-sm"
            onClick={() => setShowCreate(true)}
          >
            Create Swarm
          </button>
        </div>
        <div className="mt-4 flex-1 overflow-y-auto">
          {activeSwarm && (
            <button
              className="mb-2 text-xs underline"
              onClick={() => setActiveSwarm(null)}
            >
              Back to all swarms
            </button>
          )}
          {Object.entries(grouped)
            .sort(([a], [b]) => a.localeCompare(b))
            .filter(([id]) => !activeSwarm || id === activeSwarm)
            .map(([id, comps]) => {
              const status = swarmStatus(comps)
              const rollup = aggregateSwarmHealth(comps)
              const canStart = ['stopped', 'partial', 'error'].includes(status)
              const canStop = ['running', 'partial'].includes(status)
              const canRemove =
                id !== 'default' && !status.startsWith('remov') && comps.length > 0
              return (
                <div
                  key={id}
                  className="mb-4 border border-white/20 rounded p-2"
                >
                    <div className="flex items-center justify-between mb-1">
                      <div
                        className="font-medium cursor-pointer"
                        onClick={() =>
                          !activeSwarm &&
                          setActiveSwarm(id === 'default' ? 'default' : id)
                        }
                      >
                        {id}
                      </div>
                      <div className="flex items-center gap-2">
                        <span className="text-xs text-white/60">Marshall: {status}</span>
                        {canStart && (
                          <button
                            className="p-1 rounded bg-white/10 hover:bg-white/20 text-xs"
                            onClick={() => handleStart(id)}
                          >
                            Start
                          </button>
                        )}
                        {canStop && (
                          <button
                            className="p-1 rounded bg-white/10 hover:bg-white/20 text-xs"
                            onClick={() => handleStop(id)}
                          >
                            Stop
                          </button>
                        )}
                        {canRemove && pendingRemoval !== id && (
                          <button
                            className="p-1 rounded bg-red-500/20 hover:bg-red-500/30 text-xs"
                            onClick={() => handleRemove(id)}
                          >
                            Remove
                          </button>
                        )}
                      </div>
                    </div>
                    <div className="mb-1 flex items-center gap-2 text-xs text-white/60">
                      <span
                        className={`h-2.5 w-2.5 rounded-full ${colorForHealth(rollup.overall)}`}
                        aria-label={`Overall health ${rollup.overall.toLowerCase()}`}
                      />
                      <span>Health: {formatHealthSummary(comps.length, rollup.counts)}</span>
                    </div>
                    {pendingRemoval === id && (
                      <div className="mb-2 rounded border border-red-500/40 bg-red-500/10 p-2 text-xs text-red-200">
                        <div>Removing this swarm deletes all resources.</div>
                        <div className="mt-2 flex gap-2">
                          <button
                            className="rounded bg-red-600 px-2 py-1 text-xs"
                            onClick={() => confirmRemove(id)}
                          >
                            Confirm remove
                          </button>
                          <button
                            className="rounded bg-white/10 px-2 py-1 text-xs"
                            onClick={() => setPendingRemoval(null)}
                          >
                            Cancel
                          </button>
                        </div>
                      </div>
                    )}
                      {swarmMsg[id] && (
                        <div className="text-xs text-white/70 mb-1">{swarmMsg[id]}</div>
                      )}
                      <ComponentList
                        components={comps}
                        onSelect={(c) => setSelected(c)}
                        selectedId={selected?.id}
                      />
                </div>
              )
            })}
        </div>
      </div>
      <div className="hidden md:flex flex-1 overflow-auto">
        <TopologyView
          selectedId={selected?.id}
          onSelect={(id) => {
            const comp = components.find((c) => c.id === id)
            if (comp) setSelected(comp)
          }}
          swarmId={activeSwarm ?? undefined}
          onSwarmSelect={(id) =>
            setActiveSwarm(id === 'default' ? 'default' : id)}
        />
      </div>
      <div className="hidden lg:flex w-1/3 xl:w-1/4 border-l border-white/10 overflow-hidden">
        {selected ? (
          <ComponentDetail component={selected} onClose={() => setSelected(null)} />
        ) : (
          <div className="p-4 text-white/50 overflow-y-auto">Select a component</div>
        )}
      </div>
      {showCreate && <SwarmCreateModal onClose={() => setShowCreate(false)} />}
    </div>
  )
}

function formatHealthSummary(
  total: number,
  counts: Record<HealthStatus, number>,
) {
  if (total === 0) return 'No components'
  const segments: string[] = []
  if (counts.ALERT) {
    segments.push(`${counts.ALERT} alert${counts.ALERT === 1 ? '' : 's'}`)
  }
  if (counts.WARN) {
    segments.push(`${counts.WARN} warning${counts.WARN === 1 ? '' : 's'}`)
  }
  if (counts.OK) {
    segments.push(`${counts.OK} healthy`)
  }
  if (segments.length === 0) return 'No health data'
  return segments.join(' • ')
}

