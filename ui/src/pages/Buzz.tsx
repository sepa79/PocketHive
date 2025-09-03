import { useEffect, useState } from 'react'
import { subscribeLogs, type LogEntry } from '../lib/logs'

type Tab = 'in' | 'out' | 'other'

function LogView({ type }: { type: Tab }) {
  const [logs, setLogs] = useState<LogEntry[]>([])
  useEffect(() => subscribeLogs(type, setLogs), [type])
  return (
    <div className="space-y-1 text-xs font-mono break-all">
      {logs.map((l, i) => (
        <div key={i}>
          <span className="text-white/40 mr-1">{new Date(l.ts).toLocaleTimeString()}</span>
          {type === 'other' ? l.body : `[${l.destination}] ${l.body}`}
        </div>
      ))}
    </div>
  )
}

export default function Buzz() {
  const [tab, setTab] = useState<Tab>('in')
  return (
    <div className="card mt-6 p-4">
      <h2 className="kpi-title mb-2">Buzz</h2>
      <div className="mb-2 flex gap-2">
        <button
          className={`rounded px-2 py-1 text-sm ${tab === 'in' ? 'bg-white/20' : 'hover:bg-white/10'}`}
          onClick={() => setTab('in')}
        >
          IN
        </button>
        <button
          className={`rounded px-2 py-1 text-sm ${tab === 'out' ? 'bg-white/20' : 'hover:bg-white/10'}`}
          onClick={() => setTab('out')}
        >
          OUT
        </button>
        <button
          className={`rounded px-2 py-1 text-sm ${tab === 'other' ? 'bg-white/20' : 'hover:bg-white/10'}`}
          onClick={() => setTab('other')}
        >
          Other
        </button>
      </div>
      <div className="h-96 overflow-auto rounded border border-white/20 p-2">
        <LogView type={tab} />
      </div>
    </div>
  )
}
