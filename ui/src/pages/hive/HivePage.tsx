import { useEffect, useState } from 'react'
import ComponentList from './ComponentList'
import ComponentDetail from './ComponentDetail'
import TopologyView from './TopologyView'
import SwarmCreateModal from './SwarmCreateModal'
import SwarmRemoveModal from './SwarmRemoveModal'
import {
  subscribeComponents,
  upsertSyntheticComponent,
  removeSyntheticComponent,
} from '../../lib/stompClient'
import type { Component } from '../../types/hive'
import OrchestratorPanel from './OrchestratorPanel'
import { fetchWiremockComponent } from '../../lib/wiremockClient'

export default function HivePage() {
  const [components, setComponents] = useState<Component[]>([])
  const [selected, setSelected] = useState<Component | null>(null)
  const [search, setSearch] = useState('')
  const [showCreate, setShowCreate] = useState(false)
  const [activeSwarm, setActiveSwarm] = useState<string | null>(null)
  const [swarmPendingRemoval, setSwarmPendingRemoval] = useState<string | null>(null)

  useEffect(() => {
    // We rely on the control-plane event stream (`ev.status-*`) to keep the
    // component list current, so no manual `requestStatusFull` calls remain.
    const unsub = subscribeComponents(setComponents)
    return () => unsub()
  }, [])

  useEffect(() => {
    let cancelled = false
    let timer: number | undefined

    const poll = async () => {
      try {
        const component = await fetchWiremockComponent()
        if (cancelled) return
        if (component) {
          upsertSyntheticComponent(component)
        } else {
          removeSyntheticComponent('wiremock')
        }
      } catch {
        if (!cancelled) {
          removeSyntheticComponent('wiremock')
        }
      }
    }

    void poll()
    timer = window.setInterval(() => {
      void poll()
    }, 5000)

    return () => {
      cancelled = true
      if (timer) window.clearInterval(timer)
      removeSyntheticComponent('wiremock')
    }
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

  const isOrchestrator = (comp: Component) =>
    comp.role.trim().toLowerCase() === 'orchestrator'

  const orchestrator = components.find((c) => isOrchestrator(c)) ?? null

  const normalizeSwarmId = (comp: Component) => {
    const value = comp.swarmId?.trim()
    if (!value) return 'default'
    return value
  }

  const filtered = components.filter((c) => {
    if (isOrchestrator(c)) return false
    const haystack = search.toLowerCase()
    const matchesSearch =
      c.name.toLowerCase().includes(haystack) ||
      c.id.toLowerCase().includes(haystack) ||
      c.role.toLowerCase().includes(haystack)
    const swarmKey = normalizeSwarmId(c)
    const matchesSwarm =
      !activeSwarm ||
      (activeSwarm === 'default' ? swarmKey === 'default' : swarmKey === activeSwarm)
    return matchesSearch && matchesSwarm
  })

  const grouped = filtered.reduce<Record<string, Component[]>>((acc, c) => {
    const swarm = normalizeSwarmId(c)
    acc[swarm] = acc[swarm] || []
    acc[swarm].push(c)
    return acc
  }, {})

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
          <div className="space-y-4">
            <OrchestratorPanel
              orchestrator={orchestrator}
              onSelect={(component) => setSelected(component)}
              selectedId={selected?.id}
            />
            {activeSwarm && (
              <button
                className="text-xs underline"
                onClick={() => setActiveSwarm(null)}
              >
                Back to all swarms
              </button>
            )}
            {Object.entries(grouped)
              .sort(([a], [b]) => a.localeCompare(b))
              .filter(([id]) => !activeSwarm || id === activeSwarm)
              .map(([id, comps]) => {
                return (
                  <div
                    key={id}
                    className="border border-white/20 rounded p-2"
                    data-testid={`swarm-group-${id}`}
                  >
                    <div className="flex items-center mb-1 justify-between gap-2">
                      <div
                        className="font-medium cursor-pointer"
                        onClick={() =>
                          !activeSwarm &&
                          setActiveSwarm(id === 'default' ? 'default' : id)
                        }
                      >
                        {id}
                      </div>
                      {id !== 'default' && (
                        <button
                          type="button"
                          className="text-xs text-red-300 hover:text-red-200"
                          onClick={() => setSwarmPendingRemoval(id)}
                        >
                          Remove swarm
                        </button>
                      )}
                    </div>
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
      {swarmPendingRemoval && (
        <SwarmRemoveModal
          swarmId={swarmPendingRemoval}
          onClose={() => setSwarmPendingRemoval(null)}
          onRemoved={() => {
            const removed = swarmPendingRemoval
            setSwarmPendingRemoval(null)
            if (removed) {
              setActiveSwarm((current) => (current === removed ? null : current))
            }
          }}
        />
      )}
    </div>
  )
}

