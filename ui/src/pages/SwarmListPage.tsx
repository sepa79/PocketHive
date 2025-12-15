import { useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { subscribeComponents } from '../lib/stompClient'
import type { Component } from '../types/hive'
import { useSwarmMetadata } from '../contexts/SwarmMetadataContext'
import { startSwarm, stopSwarm } from '../lib/orchestratorApi'
import ComponentDetail from './hive/ComponentDetail'
import { sendConfigUpdate } from '../lib/orchestratorApi'
import { Play, Square } from 'lucide-react'

interface RoleCount {
  role: string
  total: number
  enabled: number
}

interface ScenarioSummary {
  last: string
  next: string
  countdown: string | null
  runs?: string
}

interface GuardSummary {
  active?: boolean
  problem?: string
}

interface SwarmRow {
  id: string
  controllerStatus: string
  heartbeat: string
  enabled: string
  templateId: string
  sutId: string
  stackName: string
  roles: RoleCount[]
  queues: { depth: number; consumers: number }
  scenario?: ScenarioSummary
  guard?: GuardSummary
  controller?: Component
}

function timeAgo(ts: number, now: number): string {
  if (!Number.isFinite(ts)) return '—'
  const diffSec = Math.max(0, Math.floor((now - ts) / 1000))
  if (diffSec < 60) return `${diffSec}s ago`
  const mins = Math.floor(diffSec / 60)
  if (mins < 60) return `${mins}m ago`
  const hours = Math.floor(mins / 60)
  return `${hours}h ago`
}

function formatEnabled(flag: unknown): string {
  if (flag === true) return 'true'
  if (flag === false) return 'false'
  return '—'
}

function aggregate(components: Component[], now: number, meta: ReturnType<typeof useSwarmMetadata>): SwarmRow[] {
  const grouped = new Map<string, Component[]>()
  components.forEach((comp) => {
    const swarm = comp.swarmId?.trim()
    if (!swarm) return
    if (!grouped.has(swarm)) grouped.set(swarm, [])
    grouped.get(swarm)!.push(comp)
  })

  const rows: SwarmRow[] = []
  grouped.forEach((list, swarmId) => {
    const controller = list.find((c) => c.role?.trim().toLowerCase() === 'swarm-controller')
    const controllerCfg = controller && typeof controller.config === 'object' ? (controller.config as Record<string, unknown>) : undefined
    const scenario =
      controllerCfg && controllerCfg.scenario && typeof controllerCfg.scenario === 'object'
        ? (controllerCfg.scenario as Record<string, unknown>)
        : undefined
    const guard =
      controllerCfg && controllerCfg.bufferGuard && typeof controllerCfg.bufferGuard === 'object'
        ? (controllerCfg.bufferGuard as Record<string, unknown>)
        : undefined

    const roleCounts = new Map<string, { total: number; enabled: number }>()
    let depth = 0
    let consumers = 0
    list.forEach((comp) => {
      const role = comp.role?.trim().toLowerCase()
      if (role && role !== 'swarm-controller') {
        const entry = roleCounts.get(role) ?? { total: 0, enabled: 0 }
        entry.total += 1
        if (comp.config && typeof comp.config === 'object' && (comp.config as Record<string, unknown>).enabled === true) {
          entry.enabled += 1
        }
        roleCounts.set(role, entry)
      }
      comp.queues.forEach((q) => {
        if (typeof q.depth === 'number') depth += q.depth
        if (typeof q.consumers === 'number') consumers += q.consumers
      })
    })

    let scenarioSummary: ScenarioSummary | undefined
    if (scenario) {
      const lastId = typeof scenario.lastStepId === 'string' ? scenario.lastStepId : undefined
      const lastName = typeof scenario.lastStepName === 'string' ? scenario.lastStepName : undefined
      const nextId = typeof scenario.nextStepId === 'string' ? scenario.nextStepId : undefined
      const nextName = typeof scenario.nextStepName === 'string' ? scenario.nextStepName : undefined
      const elapsed = typeof scenario.elapsedMillis === 'number' ? scenario.elapsedMillis : undefined
      const nextDue = typeof scenario.nextDueMillis === 'number' ? scenario.nextDueMillis : undefined
      const runsRemaining = typeof scenario.runsRemaining === 'number' ? scenario.runsRemaining : undefined
      const totalRuns = typeof scenario.totalRuns === 'number' ? scenario.totalRuns : undefined
      let countdown: string | null = null
      if (elapsed !== undefined && nextDue !== undefined && nextDue > elapsed) {
        const remainingSec = (nextDue - elapsed) / 1000
        countdown = `${remainingSec.toFixed(remainingSec >= 10 ? 0 : 1)}s`
      }
      scenarioSummary = {
        last: lastId || lastName ? `${lastId ?? '—'}${lastName ? ` (${lastName})` : ''}` : '—',
        next: nextId || nextName ? `${nextId ?? '—'}${nextName ? ` (${nextName})` : ''}` : '—',
        countdown,
        runs:
          totalRuns !== undefined
            ? `${runsRemaining !== undefined ? `${runsRemaining}/` : ''}${totalRuns}`
            : runsRemaining !== undefined
            ? `${runsRemaining}`
            : undefined,
      }
    }

    const metaEntry = meta.findSwarm(swarmId)
    rows.push({
      id: swarmId,
      controllerStatus: (controllerCfg && typeof controllerCfg.swarmStatus === 'string' && controllerCfg.swarmStatus) || '—',
      heartbeat: controller ? timeAgo(controller.lastHeartbeat, now) : '—',
      enabled: controllerCfg ? formatEnabled((controllerCfg as Record<string, unknown>).enabled) : '—',
      templateId: metaEntry?.templateId ?? '—',
      sutId: metaEntry?.sutId ?? '—',
      stackName: metaEntry?.stackName ?? '—',
      roles: Array.from(roleCounts.entries()).map(([role, counts]) => ({
        role,
        total: counts.total,
        enabled: counts.enabled,
      })),
      queues: { depth, consumers },
      scenario: scenarioSummary,
      guard: guard
        ? {
            active: typeof guard.active === 'boolean' ? guard.active : undefined,
            problem: typeof guard.problem === 'string' ? guard.problem : undefined,
          }
        : undefined,
      controller,
    })
  })

  return rows.sort((a, b) => a.id.localeCompare(b.id))
}

export default function SwarmListPage() {
  const navigate = useNavigate()
  const [components, setComponents] = useState<Component[]>([])
  const [search, setSearch] = useState('')
  const [now, setNow] = useState(() => Date.now())
  const [selectedComponent, setSelectedComponent] = useState<Component | null>(null)
  const [selection, setSelection] = useState<Set<string>>(new Set())
  const [statusFilter, setStatusFilter] = useState<'all' | 'running' | 'stopped' | 'failed'>('all')
  const [enabledFilter, setEnabledFilter] = useState<'all' | 'true' | 'false'>('all')
  const [busyAction, setBusyAction] = useState<string | null>(null)
  const [sortKey, setSortKey] = useState<'swarm' | 'status' | 'heartbeat' | 'queues'>('swarm')
  const [sortDir, setSortDir] = useState<1 | -1>(1)
  const meta = useSwarmMetadata()

  useEffect(() => {
    const unsub = subscribeComponents(setComponents)
    return () => unsub()
  }, [])

  useEffect(() => {
    void meta.ensureSwarms()
  }, [meta])

  useEffect(() => {
    const id = window.setInterval(() => setNow(Date.now()), 1000)
    return () => window.clearInterval(id)
  }, [])

  const rows = useMemo(() => aggregate(components, now, meta), [components, now, meta])
  const filtered = useMemo(() => {
    const term = search.trim().toLowerCase()
    return rows
      .filter((row) => {
        const status = row.controllerStatus?.toLowerCase?.() ?? ''
        if (statusFilter === 'running' && status !== 'running') return false
        if (statusFilter === 'stopped' && status !== 'stopped') return false
        if (statusFilter === 'failed' && status !== 'failed') return false
        if (enabledFilter !== 'all') {
          const enabledVal = row.enabled.toLowerCase()
          if (enabledFilter === 'true' && enabledVal !== 'true') return false
          if (enabledFilter === 'false' && enabledVal !== 'false') return false
        }
        if (!term) return true
        return (
          row.id.toLowerCase().includes(term) ||
          row.templateId.toLowerCase().includes(term) ||
          row.sutId?.toLowerCase?.().includes(term)
        )
      })
      .sort((a, b) => {
        const dir = sortDir
        switch (sortKey) {
          case 'status':
            return a.controllerStatus.localeCompare(b.controllerStatus) * dir
          case 'heartbeat':
            return a.heartbeat.localeCompare(b.heartbeat) * dir
          case 'queues':
            return (a.queues.depth - b.queues.depth || a.queues.consumers - b.queues.consumers) * dir
          case 'swarm':
          default:
            return a.id.localeCompare(b.id) * dir
        }
      })
  }, [rows, search, statusFilter, enabledFilter, sortKey, sortDir])

  const toggleSelect = (swarmId: string) => {
    setSelection((prev) => {
      const next = new Set(prev)
      if (next.has(swarmId)) {
        next.delete(swarmId)
      } else {
        next.add(swarmId)
      }
      return next
    })
  }

  const bulkAction = async (action: 'start' | 'stop') => {
    if (selection.size === 0) return
    setBusyAction(action)
    const tasks = Array.from(selection).map((swarmId) =>
      (action === 'start' ? startSwarm(swarmId) : stopSwarm(swarmId)).catch(() => null),
    )
    await Promise.allSettled(tasks)
    setBusyAction(null)
  }

  const handleScenarioReset = async (controller?: Component) => {
    if (!controller) return
    try {
      await sendConfigUpdate(controller, { scenario: { reset: true } })
    } catch {
      // swallow; per-row action is best-effort
    }
  }

  const toggleSort = (key: typeof sortKey) => {
    if (sortKey === key) {
      setSortDir((prev) => (prev === 1 ? -1 : 1))
    } else {
      setSortKey(key)
      setSortDir(1)
    }
  }

  const drawerWidth = 420

  return (
    <div
      className="p-4 space-y-4"
      style={selectedComponent ? { marginRight: drawerWidth } : undefined}
    >
      <div className="flex items-center justify-between gap-4">
        <div className="space-y-1">
          <h1 className="text-xl font-semibold text-white">Swarms</h1>
          <p className="text-white/60 text-sm">Tabular view for large fleets.</p>
          <div className="flex gap-2 text-xs text-white/60">
            <label className="flex items-center gap-1">
              Status
              <select
                className="bg-white/10 rounded px-2 py-1 text-white"
                value={statusFilter}
                onChange={(e) => setStatusFilter(e.target.value as typeof statusFilter)}
              >
                <option value="all">all</option>
                <option value="running">running</option>
                <option value="stopped">stopped</option>
                <option value="failed">failed</option>
              </select>
            </label>
            <label className="flex items-center gap-1">
              Enabled
              <select
                className="bg-white/10 rounded px-2 py-1 text-white"
                value={enabledFilter}
                onChange={(e) => setEnabledFilter(e.target.value as typeof enabledFilter)}
              >
                <option value="all">all</option>
                <option value="true">true</option>
                <option value="false">false</option>
              </select>
            </label>
          </div>
        </div>
        <div className="flex items-center gap-3">
          <input
            className="rounded bg-white/10 px-3 py-1 text-white w-64"
            placeholder="Search swarm id / template / sut"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
          />
          <button
            className="rounded bg-green-600 px-3 py-1 text-sm disabled:opacity-50"
            onClick={() => bulkAction('start')}
            disabled={selection.size === 0 || busyAction !== null}
          >
            Start selected
          </button>
          <button
            className="rounded bg-red-600 px-3 py-1 text-sm disabled:opacity-50"
            onClick={() => bulkAction('stop')}
            disabled={selection.size === 0 || busyAction !== null}
          >
            Stop selected
          </button>
        </div>
      </div>
      <div className="overflow-auto border border-white/10 rounded">
        <table className="min-w-full text-sm">
          <thead className="bg-white/5 text-white/70">
            <tr>
              <th className="px-3 py-2 w-20">Actions</th>
              <th className="text-left px-3 py-2 cursor-pointer" onClick={() => toggleSort('swarm')}>
                Swarm
              </th>
              <th className="text-left px-3 py-2 cursor-pointer" onClick={() => toggleSort('status')}>
                Status
              </th>
              <th className="text-left px-3 py-2 cursor-pointer" onClick={() => toggleSort('heartbeat')}>
                Heartbeat
              </th>
              <th className="text-left px-3 py-2">Enabled</th>
              <th className="text-left px-3 py-2">Template</th>
              <th className="text-left px-3 py-2">SUT</th>
              <th className="text-left px-3 py-2">Roles</th>
              <th className="text-left px-3 py-2 cursor-pointer" onClick={() => toggleSort('queues')}>
                Queues
              </th>
              <th className="text-left px-3 py-2">Scenario</th>
              <th className="text-left px-3 py-2">Guard</th>
              <th className="px-3 py-2 w-32">Actions</th>
            </tr>
          </thead>
          <tbody>
            {filtered.map((row) => (
              <tr
                key={row.id}
                className="border-t border-white/10 hover:bg-white/5 cursor-pointer"
                onClick={() => {
                  setSelectedComponent(row.controller ?? null)
                }}
              >
                <td className="px-3 py-2">
                  <div className="flex items-center gap-2">
                    <input
                      type="checkbox"
                      checked={selection.has(row.id)}
                      onChange={(e) => {
                        e.stopPropagation()
                        toggleSelect(row.id)
                      }}
                    />
                    <div className="flex gap-2">
                      <button
                        className="p-1 rounded bg-white/10 hover:bg-white/20"
                        title="Start swarm"
                        onClick={(e) => {
                          e.stopPropagation()
                          void startSwarm(row.id)
                        }}
                      >
                        <Play className="h-4 w-4" />
                      </button>
                      <button
                        className="p-1 rounded bg-white/10 hover:bg-white/20"
                        title="Stop swarm"
                        onClick={(e) => {
                          e.stopPropagation()
                          void stopSwarm(row.id)
                        }}
                      >
                        <Square className="h-4 w-4" />
                      </button>
                    </div>
                  </div>
                </td>
                <td className="px-3 py-2 text-white">{row.id}</td>
                <td className="px-3 py-2 text-white/80">{row.controllerStatus}</td>
                <td className="px-3 py-2 text-white/80">{row.heartbeat}</td>
                <td className="px-3 py-2 text-white/80">{row.enabled}</td>
                <td className="px-3 py-2 text-white/80">{row.templateId || '—'}</td>
                <td className="px-3 py-2 text-white/80">{row.sutId || '—'}</td>
                <td className="px-3 py-2 text-white/80">
                  {row.roles.length === 0
                    ? '—'
                    : row.roles
                        .map((r) => `${r.role} ${r.enabled}/${r.total}`)
                        .join(', ')}
                </td>
                <td className="px-3 py-2 text-white/80">
                  depth {row.queues.depth} · consumers {row.queues.consumers}
                </td>
                <td className="px-3 py-2 text-white/80">
                  {row.scenario ? (
                    <div className="space-y-0.5">
                      <div>Last: {row.scenario.last}</div>
                      <div>
                        Next: {row.scenario.next}
                        {row.scenario.countdown ? ` (${row.scenario.countdown})` : ''}
                      </div>
                      {row.scenario.runs ? <div>Runs: {row.scenario.runs}</div> : null}
                    </div>
                  ) : (
                    '—'
                  )}
                </td>
                <td className="px-3 py-2 text-white/80">
                  {row.guard ? (
                    <>
                      {row.guard.active !== undefined ? (row.guard.active ? 'active' : 'inactive') : '—'}
                      {row.guard.problem ? ` • ${row.guard.problem}` : ''}
                    </>
                  ) : (
                    '—'
                  )}
                </td>
                <td className="px-3 py-2 text-white/80">
                  <div className="flex flex-wrap gap-2">
                    <button
                      className="rounded bg-slate-700 px-2 py-1 text-xs hover:bg-slate-600"
                      onClick={(e) => {
                        e.stopPropagation()
                        navigate(`/runs/${encodeURIComponent(row.id)}`)
                      }}
                    >
                      Runs
                    </button>
                    <button
                      className="rounded bg-white/10 px-2 py-1 text-xs hover:bg-white/20"
                      onClick={(e) => {
                        e.stopPropagation()
                        navigate(`/orchestrator/journal?swarmId=${encodeURIComponent(row.id)}`)
                      }}
                    >
                      Hive journal
                    </button>
                    <button
                      className="rounded bg-blue-600 px-2 py-1 text-xs disabled:opacity-50"
                      onClick={(e) => {
                        e.stopPropagation()
                        handleScenarioReset(row.controller)
                      }}
                      disabled={!row.controller}
                    >
                      Reset plan
                    </button>
                  </div>
                </td>
              </tr>
            ))}
            {filtered.length === 0 && (
              <tr>
                <td className="px-3 py-4 text-center text-white/60" colSpan={12}>
                  No swarms match the filter.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
      {selectedComponent && (
        <div
          className="fixed right-0 top-0 h-full bg-[#0c1016] border-l border-white/10 shadow-lg overflow-auto z-20"
          style={{ width: drawerWidth }}
        >
          <ComponentDetail
            component={selectedComponent}
            onClose={() => {
              setSelectedComponent(null)
            }}
          />
        </div>
      )}
    </div>
  )
}
