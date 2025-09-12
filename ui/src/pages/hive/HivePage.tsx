import { useEffect, useState } from 'react'
import ComponentList from './ComponentList'
import ComponentDetail from './ComponentDetail'
import TopologyView from './TopologyView'
import SwarmCreateModal from './SwarmCreateModal'
import {
  subscribeComponents,
  requestStatusFull,
  startSwarm,
  stopSwarm,
} from '../../lib/stompClient'
import type { Component } from '../../types/hive'
import { subscribeLogs, type LogEntry } from '../../lib/logs'

export default function HivePage() {
  const [components, setComponents] = useState<Component[]>([])
  const [selected, setSelected] = useState<Component | null>(null)
  const [search, setSearch] = useState('')
  const [showCreate, setShowCreate] = useState(false)
  const [swarmMsg, setSwarmMsg] = useState<Record<string, string>>({})
  const [activeSwarm, setActiveSwarm] = useState<string | null>(null)
  const [ready, setReady] = useState<Record<string, boolean>>({})

  useEffect(() => {
    const unsub = subscribeComponents(setComponents)
    return () => unsub()
  }, [])

  useEffect(() => {
    return subscribeLogs('handshake', (entries: LogEntry[]) => {
      entries.forEach((e) => {
        const dest = e.destination
        let m: RegExpMatchArray | null
        if ((m = dest.match(/ev\.swarm-created\.([^/]+)$/))) {
          const id = m[1]
          setSwarmMsg((msg) => ({ ...msg, [id]: 'Swarm controller created' }))
        } else if ((m = dest.match(/ev\.swarm-ready\.([^/]+)$/))) {
          const id = m[1]
          setReady((r) => ({ ...r, [id]: true }))
          setSwarmMsg((msg) => ({ ...msg, [id]: 'Swarm ready' }))
        }
      })
    })
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
    const queen = comps.find((c) => c.name === 'swarm-controller')
    const status = (queen?.config?.swarmStatus as string | undefined)?.toLowerCase()
    if (status) return status
    const enabled = comps.map((c) => c.config?.enabled !== false)
    if (enabled.every(Boolean)) return 'running'
    if (enabled.every((e) => !e)) return 'stopped'
    return 'partial'
  }

  const handleStart = async (id: string) => {
    try {
      await startSwarm(id)
      const comps = components.filter((c) => c.swarmId === id)
      await Promise.all(comps.map((c) => requestStatusFull(c)))
      setSwarmMsg((m) => ({ ...m, [id]: 'Swarm started' }))
    } catch {
      setSwarmMsg((m) => ({ ...m, [id]: 'Failed to start swarm' }))
    }
  }

  const handleStop = async (id: string) => {
    try {
      await stopSwarm(id)
      const comps = components.filter((c) => c.swarmId === id)
      await Promise.all(comps.map((c) => requestStatusFull(c)))
      setSwarmMsg((m) => ({ ...m, [id]: 'Swarm stopped' }))
    } catch {
      setSwarmMsg((m) => ({ ...m, [id]: 'Failed to stop swarm' }))
    }
  }

  return (
    <div className="flex h-[calc(100vh-64px)] overflow-hidden">
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
                      <span className="text-xs text-white/60">Queen: {status}</span>
                      {status !== 'running' && ready[id] && (
                        <button
                          className="p-1 rounded bg-white/10 hover:bg-white/20 text-xs"
                          onClick={() => handleStart(id)}
                        >
                          Start
                        </button>
                      )}
                      {status !== 'stopped' && (
                        <button
                          className="p-1 rounded bg-white/10 hover:bg-white/20 text-xs"
                          onClick={() => handleStop(id)}
                        >
                          Stop
                        </button>
                      )}
                    </div>
                  </div>
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

