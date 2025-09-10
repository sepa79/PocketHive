import { useEffect, useState } from 'react'
import { subscribeLogs, type LogEntry } from '../lib/logs'
import { useConfig } from '../lib/config'
import { useUIStore } from '../store'

type LogTab = 'in' | 'out' | 'other' | 'handshake'
type Tab = LogTab | 'config'

function LogView({ type }: { type: LogTab }) {
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

function ConfigView() {
  const cfg = useConfig()
  return (
    <div className="space-y-1 text-xs font-mono break-all">
      {Object.entries(cfg).map(([k, v]) => (
        <div key={k}>
          <span className="text-white/40 mr-1">{k}:</span>
          {v}
        </div>
      ))}
    </div>
  )
}

export default function Buzz() {
  const [tab, setTab] = useState<Tab>('in')
  const { messageLimit, setMessageLimit } = useUIStore()
  const [inputValue, setInputValue] = useState(messageLimit.toString())

  const handleLimitChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const value = e.target.value
    setInputValue(value)
    const num = parseInt(value)
    if (!isNaN(num)) {
      setMessageLimit(num)
    }
  }

  return (
    <div className="card mt-6 p-4 h-[calc(100vh-112px)] flex flex-col">
      <h2 className="kpi-title mb-2">Buzz</h2>
      <div className="mb-2 flex gap-2 items-center">
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
        <button
          className={`rounded px-2 py-1 text-sm ${tab === 'handshake' ? 'bg-white/20' : 'hover:bg-white/10'}`}
          onClick={() => setTab('handshake')}
        >
          Handshake
        </button>
        <button
          className={`rounded px-2 py-1 text-sm ${tab === 'config' ? 'bg-white/20' : 'hover:bg-white/10'}`}
          onClick={() => setTab('config')}
        >
          Config
        </button>
        <div className="ml-auto flex items-center gap-1 text-xs">
          <span className="text-white/60">Limit:</span>
          <input
            type="number"
            min="10"
            max="500"
            value={inputValue}
            onChange={handleLimitChange}
            className="w-12 rounded border border-white/20 bg-white/10 px-1 py-0.5 text-center text-xs"
          />
        </div>
      </div>
      <div className="flex-1 overflow-auto rounded border border-white/20 p-2">
        {tab === 'config' ? <ConfigView /> : <LogView type={tab} />}
      </div>
    </div>
  )
}
