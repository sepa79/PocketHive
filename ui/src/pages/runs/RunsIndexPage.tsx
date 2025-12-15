import { useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useSwarmMetadata } from '../../contexts/SwarmMetadataContext'

export default function RunsIndexPage() {
  const navigate = useNavigate()
  const { ensureSwarms, swarms } = useSwarmMetadata()
  const [search, setSearch] = useState('')
  const [swarmId, setSwarmId] = useState('')

  useEffect(() => {
    void ensureSwarms()
  }, [ensureSwarms])

  const filtered = useMemo(() => {
    const term = search.trim().toLowerCase()
    if (!term) return swarms
    return swarms.filter((swarm) => swarm.id.toLowerCase().includes(term))
  }, [search, swarms])

  const open = (value: string) => {
    const trimmed = value.trim()
    if (!trimmed) return
    navigate(`/runs/${encodeURIComponent(trimmed)}`)
  }

  return (
    <div className="p-6 space-y-4">
      <div className="space-y-1">
        <h1 className="text-xl font-semibold text-white">Runs</h1>
        <p className="text-white/60 text-sm">Pick a swarm to browse its journal runs.</p>
      </div>

      <div className="flex flex-wrap items-center gap-3">
        <input
          className="rounded bg-white/10 px-3 py-2 text-white w-64"
          placeholder="Enter swarm id…"
          value={swarmId}
          onChange={(e) => setSwarmId(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') open(swarmId)
          }}
        />
        <button
          className="rounded bg-blue-600 px-3 py-2 text-sm disabled:opacity-50"
          onClick={() => open(swarmId)}
          disabled={!swarmId.trim()}
        >
          Open
        </button>
        <input
          className="rounded bg-white/10 px-3 py-2 text-white w-64"
          placeholder="Filter swarms…"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
        />
      </div>

      <div className="overflow-auto border border-white/10 rounded">
        <table className="min-w-full text-sm">
          <thead className="bg-white/5 text-white/70">
            <tr>
              <th className="text-left px-3 py-2">Swarm</th>
              <th className="px-3 py-2 w-32">Actions</th>
            </tr>
          </thead>
          <tbody>
            {filtered.map((swarm) => (
              <tr key={swarm.id} className="border-t border-white/10 hover:bg-white/5">
                <td className="px-3 py-2 text-white">{swarm.id}</td>
                <td className="px-3 py-2">
                  <button
                    className="rounded bg-blue-600 px-2 py-1 text-xs"
                    onClick={() => open(swarm.id)}
                  >
                    Open runs
                  </button>
                </td>
              </tr>
            ))}
            {filtered.length === 0 && (
              <tr>
                <td className="px-3 py-4 text-center text-white/60" colSpan={2}>
                  No swarms.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  )
}

