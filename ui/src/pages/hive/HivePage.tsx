import { useEffect, useState } from 'react'
import ComponentList from './ComponentList'
import ComponentDetail from './ComponentDetail'
import TopologyView from './TopologyView'
import { subscribeComponents } from '../../lib/stompClient'
import type { Component } from '../../types/hive'

export default function HivePage() {
  const [components, setComponents] = useState<Component[]>([])
  const [selected, setSelected] = useState<Component | null>(null)
  const [search, setSearch] = useState('')
  const [view, setView] = useState<'list' | 'topology'>('list')

  useEffect(() => {
    const unsub = subscribeComponents(setComponents)
    return () => unsub()
  }, [])

  const filtered = components.filter((c) =>
    c.name.toLowerCase().includes(search.toLowerCase()) ||
    c.id.toLowerCase().includes(search.toLowerCase()),
  )

  return (
    <div className="flex flex-col h-[calc(100vh-64px)]">
      <div className="flex gap-2 p-2 border-b border-white/10">
        <button
          className={`px-3 py-1 rounded ${view === 'list' ? 'bg-white/20' : 'bg-white/5'}`}
          onClick={() => setView('list')}
        >
          List
        </button>
        <button
          className={`px-3 py-1 rounded ${view === 'topology' ? 'bg-white/20' : 'bg-white/5'}`}
          onClick={() => setView('topology')}
        >
          Topology
        </button>
      </div>
      {view === 'list' ? (
        <div className="flex flex-1">
          <div className="w-full md:w-1/3 border-r border-white/10 p-4">
            <input
              className="w-full mb-4 rounded bg-white/5 p-2 text-white"
              placeholder="Search components"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
            />
            <ComponentList
              components={filtered}
              onSelect={(c) => setSelected(c)}
              selectedId={selected?.id}
            />
          </div>
          {selected && (
            <ComponentDetail component={selected} onClose={() => setSelected(null)} />
          )}
        </div>
      ) : (
        <div className="flex-1">
          <TopologyView />
        </div>
      )}
    </div>
  )
}

