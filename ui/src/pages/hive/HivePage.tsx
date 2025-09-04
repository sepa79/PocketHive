import { useEffect, useState } from 'react'
import ComponentList from './ComponentList'
import ComponentDetail from './ComponentDetail'
import TopologyView from './TopologyView'
import { subscribeComponents } from '../../lib/stompClient'
import { componentHealth } from '../../lib/health'
import type { Component, HealthStatus } from '../../types/hive'

export default function HivePage() {
  const [components, setComponents] = useState<Component[]>([])
  const [selected, setSelected] = useState<Component | null>(null)
  const [search, setSearch] = useState('')
  const [typeFilter, setTypeFilter] = useState('all')
  const [healthFilter, setHealthFilter] = useState<HealthStatus | 'all'>('all')

  useEffect(() => {
    const unsub = subscribeComponents(setComponents)
    return () => unsub()
  }, [])

  useEffect(() => {
    if (selected) {
      const updated = components.find((c) => c.id === selected.id)
      if (updated) setSelected(updated)
    }
  }, [components, selected])

  const types = Array.from(new Set(components.map((c) => c.name)))

  const filtered = components
    .filter(
      (c) =>
        c.name.toLowerCase().includes(search.toLowerCase()) ||
        c.id.toLowerCase().includes(search.toLowerCase()),
    )
    .filter((c) => typeFilter === 'all' || c.name === typeFilter)
    .filter(
      (c) =>
        healthFilter === 'all' || componentHealth(c) === healthFilter,
    )

  const visibleIds = filtered.map((c) => c.id)

  useEffect(() => {
    if (selected && !visibleIds.includes(selected.id)) {
      setSelected(null)
    }
  }, [visibleIds, selected])

  return (
    <div className="h-[calc(100vh-64px)] flex flex-col">
      <div className="p-4 border-b border-white/10 flex gap-4">
        <select
          className="rounded bg-white/5 p-2 text-white border border-white/10 [color-scheme:dark]"
          value={typeFilter}
          onChange={(e) => setTypeFilter(e.target.value)}
        >
          <option value="all">All Types</option>
          {types.map((t) => (
            <option key={t} value={t}>
              {t}
            </option>
          ))}
        </select>
        <select
          className="rounded bg-white/5 p-2 text-white border border-white/10 [color-scheme:dark]"
          value={healthFilter}
          onChange={(e) => setHealthFilter(e.target.value as HealthStatus | 'all')}
        >
          <option value="all">All Health</option>
          <option value="OK">OK</option>
          <option value="WARN">WARN</option>
          <option value="ALERT">ALERT</option>
        </select>
      </div>
      <div className="flex flex-1 overflow-hidden">
        <div className="w-full md:w-1/3 xl:w-1/4 border-r border-white/10 p-4 flex flex-col">
          <input
            className="w-full rounded bg-white/5 p-2 text-white"
            placeholder="Search components"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
          />
          <div className="mt-4 flex-1 overflow-hidden">
            <ComponentList
              components={filtered}
              onSelect={(c) => setSelected(c)}
              selectedId={selected?.id}
            />
          </div>
        </div>
        <div className="hidden md:flex flex-1 overflow-auto">
          <TopologyView
            selectedId={selected?.id}
            visibleIds={visibleIds}
            onSelect={(id) => {
              const comp = components.find((c) => c.id === id)
              if (comp) setSelected(comp)
            }}
          />
        </div>
        <div className="hidden lg:flex w-1/3 xl:w-1/4 border-l border-white/10 overflow-hidden">
          {selected ? (
            <ComponentDetail component={selected} onClose={() => setSelected(null)} />
          ) : (
            <div className="p-4 text-white/50 overflow-y-auto">Select a component</div>
          )}
        </div>
      </div>
    </div>
  )
}

