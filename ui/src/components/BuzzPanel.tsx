import { useEffect, useRef, useState } from 'react'
import { PanelLeft, PanelRight, PanelBottom } from 'lucide-react'
import { subscribeLogs, type LogEntry, type LogChannel, type LogSource } from '../lib/logs'
import { useConfig } from '../lib/config'
import { useUIStore } from '../store'

function LogView({
  sourceFilter,
  channelFilter,
  typeFilter,
}: {
  sourceFilter: 'all' | LogSource
  channelFilter: 'all' | LogChannel
  typeFilter: 'all' | 'error'
}) {
  const [logs, setLogs] = useState<LogEntry[]>([])
  useEffect(() => subscribeLogs(setLogs), [])
  const colors = useRef<Record<string, string>>({})
  const palette = ['bg-white/10', 'bg-white/20', 'bg-white/30', 'bg-white/40']
  const colorFor = (id: string) => {
    if (!colors.current[id]) {
      const idx = Object.keys(colors.current).length % palette.length
      colors.current[id] = palette[idx]
    }
    return colors.current[id]
  }
  const filtered = logs.filter(
    (l) =>
      (sourceFilter === 'all' || l.source === sourceFilter) &&
      (channelFilter === 'all' || l.channel === channelFilter) &&
      (typeFilter === 'all' || l.type === typeFilter),
  )
  return (
    <div className="space-y-1 text-xs font-mono break-all">
      {filtered.map((l, i) => (
        <div key={i} className={l.correlationId ? colorFor(l.correlationId) : ''}>
          <span className="text-white/40 mr-1">{new Date(l.ts).toLocaleTimeString()}</span>
          <span className="text-white/60 mr-1">[{l.source}/{l.channel}]</span>
          {l.destination && <span className="text-white/60 mr-1">[{l.destination}]</span>}
          {l.body}
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

export default function BuzzPanel() {
  const [tab, setTab] = useState<'logs' | 'config'>('logs')
  const [sourceFilter, setSourceFilter] = useState<'all' | LogSource>('all')
  const [channelFilter, setChannelFilter] = useState<'all' | LogChannel>('all')
  const [typeFilter, setTypeFilter] = useState<'all' | 'error'>('all')
  const { messageLimit, setMessageLimit, buzzDock, setBuzzDock } = useUIStore()
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
    <div className="flex h-full flex-col p-4">
      <div className="mb-2 flex items-center gap-2">
        <h2 className="kpi-title mb-0">Buzz</h2>
        <div className="ml-auto flex gap-1">
          <button
            className={`rounded p-1 ${buzzDock === 'left' ? 'bg-white/20' : 'hover:bg-white/10'}`}
            onClick={() => setBuzzDock('left')}
            aria-label="Dock left"
          >
            <PanelLeft className="h-4 w-4" />
          </button>
          <button
            className={`rounded p-1 ${buzzDock === 'right' ? 'bg-white/20' : 'hover:bg-white/10'}`}
            onClick={() => setBuzzDock('right')}
            aria-label="Dock right"
          >
            <PanelRight className="h-4 w-4" />
          </button>
          <button
            className={`rounded p-1 ${buzzDock === 'bottom' ? 'bg-white/20' : 'hover:bg-white/10'}`}
            onClick={() => setBuzzDock('bottom')}
            aria-label="Dock bottom"
          >
            <PanelBottom className="h-4 w-4" />
          </button>
        </div>
      </div>
      <div className="mb-2 flex items-center gap-2">
        <button
          className={`rounded px-2 py-1 text-sm ${tab === 'logs' ? 'bg-white/20' : 'hover:bg-white/10'}`}
          onClick={() => setTab('logs')}
        >
          Logs
        </button>
        <button
          className={`rounded px-2 py-1 text-sm ${tab === 'config' ? 'bg-white/20' : 'hover:bg-white/10'}`}
          onClick={() => setTab('config')}
        >
          Config
        </button>
        <div className="ml-auto flex items-center gap-1 text-xs">
          {tab === 'logs' && (
            <>
              <select
                value={sourceFilter}
                onChange={(e) => setSourceFilter(e.target.value as 'all' | LogSource)}
                className="rounded border border-white/20 bg-white/10 px-1 py-0.5"
              >
                <option value="all">All sources</option>
                <option value="ui">UI</option>
                <option value="hive">Hive</option>
              </select>
              <select
                value={channelFilter}
                onChange={(e) => setChannelFilter(e.target.value as 'all' | LogChannel)}
                className="rounded border border-white/20 bg-white/10 px-1 py-0.5"
              >
                <option value="all">All channels</option>
                <option value="stomp">STOMP</option>
                <option value="rest">REST</option>
                <option value="internal">Internal</option>
              </select>
              <select
                value={typeFilter}
                onChange={(e) => setTypeFilter(e.target.value as 'all' | 'error')}
                className="rounded border border-white/20 bg-white/10 px-1 py-0.5"
              >
                <option value="all">All logs</option>
                <option value="error">Errors only</option>
              </select>
            </>
          )}
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
        {tab === 'config' ? (
          <ConfigView />
        ) : (
          <LogView sourceFilter={sourceFilter} channelFilter={channelFilter} typeFilter={typeFilter} />
        )}
      </div>
    </div>
  )
}
