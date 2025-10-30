import { useEffect, useMemo, useState } from 'react'
import ComponentList from './ComponentList'
import ComponentDetail from './ComponentDetail'
import TopologyView from './TopologyView'
import SwarmCreateModal from './SwarmCreateModal'
import {
  subscribeComponents,
  upsertSyntheticComponent,
  removeSyntheticComponent,
} from '../../lib/stompClient'
import type { Component } from '../../types/hive'
import OrchestratorPanel from './OrchestratorPanel'
import { fetchWiremockComponent } from '../../lib/wiremockClient'
import SwarmRow from '../../components/hive/SwarmRow'
import { componentHealth } from '../../lib/health'
import { mapStatusToVisualState, type HealthVisualState } from './visualState'
import { useSwarmMetadata } from '../../contexts/SwarmMetadataContext'

const UNASSIGNED_SWARM_ID = '__unassigned__'

export default function HivePage() {
  const [components, setComponents] = useState<Component[]>([])
  const [selected, setSelected] = useState<Component | null>(null)
  const [search, setSearch] = useState('')
  const [showCreate, setShowCreate] = useState(false)
  const [activeSwarm, setActiveSwarm] = useState<string | null>(null)
  const [expandedSwarmId, setExpandedSwarmId] = useState<string | null>(null)
  const [contextSwarmId, setContextSwarmId] = useState<string | null>(null)
  const [now, setNow] = useState(() => Date.now())
  const { ensureSwarms } = useSwarmMetadata()

  useEffect(() => {
    // We rely on the control-plane event stream (`ev.status-*`) to keep the
    // component list current, so no manual `requestStatusFull` calls remain.
    const unsub = subscribeComponents(setComponents)
    return () => unsub()
  }, [])

  useEffect(() => {
    void ensureSwarms()
  }, [ensureSwarms])

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

  useEffect(() => {
    if (activeSwarm) {
      setContextSwarmId(activeSwarm)
    }
  }, [activeSwarm])

  useEffect(() => {
    const id = window.setInterval(() => setNow(Date.now()), 1000)
    return () => window.clearInterval(id)
  }, [])

  const isOrchestrator = (comp: Component) =>
    comp.role.trim().toLowerCase() === 'orchestrator'

  const orchestrator = components.find((c) => isOrchestrator(c)) ?? null

  const normalizeSwarmId = (comp: Component): string | null => {
    const value = comp.swarmId?.trim()
    if (!value) return null
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
      (activeSwarm === 'default'
        ? swarmKey === 'default'
        : activeSwarm === UNASSIGNED_SWARM_ID
        ? swarmKey === null
        : swarmKey === activeSwarm)
    return matchesSearch && matchesSwarm
  })

  const grouped = filtered.reduce<Record<string, Component[]>>((acc, c) => {
    const swarm = normalizeSwarmId(c)
    const key = swarm ?? UNASSIGNED_SWARM_ID
    acc[key] = acc[key] || []
    acc[key].push(c)
    return acc
  }, {})

  const assignedEntries = useMemo(
    () => Object.entries(grouped).filter(([id]) => id !== UNASSIGNED_SWARM_ID),
    [grouped],
  )

  const sortedAssignedEntries = useMemo(
    () => [...assignedEntries].sort(([a], [b]) => a.localeCompare(b)),
    [assignedEntries],
  )

  const swarmHealth = useMemo(() => {
    return Object.fromEntries(
      assignedEntries.map(([id, comps]) => [
        id,
        deriveSwarmHealth(id, comps, now),
      ]),
    )
  }, [assignedEntries, now])

  const shouldShowSwarmList =
    !selected && expandedSwarmId === null && Boolean(contextSwarmId)

  const selectedId = selected?.id

  const contextSwarmComponents: Component[] = contextSwarmId
    ? grouped[contextSwarmId] ?? []
    : []
  const contextSwarmLabel = contextSwarmId ? formatSwarmDisplayName(contextSwarmId) : ''
  const contextComponentLabel = formatComponentCount(contextSwarmComponents.length)

  return (
    <div className="flex h-full min-h-0 overflow-hidden">
      <div className="w-full lg:w-[360px] xl:w-[420px] border-r border-white/10 px-5 py-4 flex flex-col gap-4">
        <div className="flex items-center gap-3">
          <input
            className="flex-1 rounded-lg bg-white/5 px-3 py-2 text-white placeholder:text-white/40 focus:outline-none focus:ring-2 focus:ring-sky-300/50"
            placeholder="Search components"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
          />
          <button
            className="rounded-lg border border-white/15 bg-white/10 px-3 py-2 text-sm font-medium text-white/90 transition hover:bg-white/20 focus:outline-none focus:ring-2 focus:ring-sky-300/50"
            onClick={() => setShowCreate(true)}
          >
            Create Swarm
          </button>
        </div>
        <div className="flex-1 overflow-y-auto pr-1">
          <div className="space-y-4 pb-4">
            <OrchestratorPanel
              orchestrator={orchestrator}
              onSelect={(component) => setSelected(component)}
              selectedId={selectedId}
            />
            {sortedAssignedEntries
              .filter(([id]) => !activeSwarm || id === activeSwarm)
              .map(([id, comps]) => {
                const normalizedId = id === 'default' ? 'default' : id
                const isExpanded = expandedSwarmId === id
                const health = swarmHealth[id] ?? defaultSwarmHealth(id)
                return (
                  <div key={id} className="space-y-2">
                    <SwarmRow
                      swarmId={id}
                      isDefault={id === 'default'}
                      isActive={activeSwarm === normalizedId}
                      expanded={isExpanded}
                      isSelected={contextSwarmId === id}
                      componentCount={comps.length}
                      onFocusChange={(swarm, nextActive) =>
                        setActiveSwarm((current) => {
                          const normalized = swarm === 'default' ? 'default' : swarm
                          if (nextActive) {
                            return normalized
                          }
                          return current === normalized ? null : current
                        })
                      }
                      onSelect={(swarm) => {
                        const normalized = swarm === 'default' ? 'default' : swarm
                        setContextSwarmId(normalized)
                        setSelected(null)
                      }}
                      onRemove={(swarm) => {
                        setExpandedSwarmId((current) =>
                          current === swarm ? null : current,
                        )
                        setActiveSwarm((current) =>
                          current === (swarm === 'default' ? 'default' : swarm)
                            ? null
                            : current,
                        )
                        setSelected((current) => {
                          if (!current) return current
                          const currentSwarm = normalizeSwarmId(current)
                          const removedSwarm = swarm === 'default' ? 'default' : swarm
                          return currentSwarm === removedSwarm ? null : current
                        })
                        setContextSwarmId((current) =>
                          current === (swarm === 'default' ? 'default' : swarm)
                            ? null
                            : current,
                        )
                      }}
                      onToggleExpand={(swarm) =>
                        setExpandedSwarmId((current) =>
                          current === swarm ? null : swarm,
                        )
                      }
                      dataTestId={`swarm-group-${id}`}
                      healthState={health.state}
                      healthTitle={health.title}
                      statusKey={health.pulseKey}
                    >
                      {isExpanded && (
                        <ComponentList
                          components={comps}
                          onSelect={(c) => setSelected(c)}
                          selectedId={selectedId}
                        />
                      )}
                    </SwarmRow>
                  </div>
                )
              })}
            {!activeSwarm && (grouped[UNASSIGNED_SWARM_ID]?.length ?? 0) > 0 && (
              <div className="space-y-2" data-testid="swarm-group-unassigned">
                <div className="rounded-lg border border-white/15 bg-white/5 px-4 py-3 text-sm text-white/70">
                  <div className="mb-2 text-xs font-semibold uppercase tracking-wide text-white/50">
                    Unassigned components
                  </div>
                  <ComponentList
                    components={grouped[UNASSIGNED_SWARM_ID]}
                    onSelect={(component) => setSelected(component)}
                    selectedId={selectedId}
                  />
                </div>
              </div>
            )}
          </div>
        </div>
      </div>
      <div className="hidden md:flex flex-1 overflow-auto">
        <TopologyView
          selectedId={selectedId}
          onSelect={(id) => {
            const comp = components.find((c) => c.id === id)
            if (comp) setSelected(comp)
          }}
          swarmId={activeSwarm ?? undefined}
          onSwarmSelect={(id) => setActiveSwarm(id)}
        />
      </div>
      <div className="hidden lg:flex w-[360px] xl:w-[420px] border-l border-white/10 bg-slate-950/40 backdrop-blur-sm">
        {selected ? (
          <ComponentDetail component={selected} onClose={() => setSelected(null)} />
        ) : shouldShowSwarmList ? (
          <div className="flex-1 overflow-y-auto px-6 py-5" data-testid="swarm-context-panel">
            <div className="flex items-center justify-between gap-4 border-b border-white/10 pb-4">
              <div className="min-w-0">
                <div className="text-xs uppercase tracking-wide text-white/40">Swarm</div>
                <div className="mt-1 truncate text-sm font-semibold text-white/90">
                  {contextSwarmLabel}
                </div>
              </div>
              <span className="shrink-0 rounded-full border border-white/15 bg-white/10 px-3 py-1 text-xs font-semibold uppercase tracking-wide text-white/70">
                {contextComponentLabel}
              </span>
            </div>
            <div className="mt-4">
              <ComponentList
                components={contextSwarmComponents}
                onSelect={(component) => setSelected(component)}
                selectedId={selectedId}
              />
            </div>
          </div>
        ) : (
          <div
            className="flex-1 overflow-y-auto px-6 py-5 text-white/50"
            data-testid="swarm-context-placeholder"
          >
            Select a component
          </div>
        )}
      </div>
      {showCreate && <SwarmCreateModal onClose={() => setShowCreate(false)} />}
    </div>
  )
}

type SwarmHealthMeta = {
  state: HealthVisualState
  title: string
  pulseKey: number
}

function deriveSwarmHealth(
  swarmId: string,
  components: Component[],
  now: number,
): SwarmHealthMeta {
  if (components.length === 0) {
    return {
      state: 'missing',
      title: `No components reporting for ${swarmId}`,
      pulseKey: 0,
    }
  }

  let derivedState: HealthVisualState | null = null
  for (const component of components) {
    const mapped = mapStatusToVisualState(component.status)
    derivedState = pickHigherPriority(derivedState, mapped)
    if (derivedState === 'alert') break
  }

  if (!derivedState || derivedState === 'ok') {
    let aggregate: 'OK' | 'WARN' | 'ALERT' = 'OK'
    for (const component of components) {
      const health = componentHealth(component, now)
      aggregate = combineHealth(aggregate, health)
      if (aggregate === 'ALERT') break
    }

    if (aggregate === 'ALERT') {
      derivedState = 'alert'
    } else if (aggregate === 'WARN') {
      derivedState = 'warn'
    } else if (!derivedState) {
      derivedState = 'ok'
    }
  }

  const validHeartbeats = components
    .map((component) =>
      typeof component.lastHeartbeat === 'number'
        ? component.lastHeartbeat
        : Number.NEGATIVE_INFINITY,
    )
    .filter((heartbeat) => Number.isFinite(heartbeat))

  const latestHeartbeat = validHeartbeats.length
    ? Math.max(...validHeartbeats)
    : Number.NaN

  const secondsAgo = Number.isFinite(latestHeartbeat)
    ? Math.max(0, Math.floor((now - latestHeartbeat) / 1000))
    : null

  const statusLabel = stateLabel(derivedState ?? 'missing')
  const componentsLabel = components.length === 1 ? '1 component' : `${components.length} components`

  const heartbeatLabel =
    secondsAgo === null
      ? 'latest heartbeat timing unavailable'
      : `latest heartbeat ${secondsAgo}s ago`

  return {
    state: derivedState ?? 'missing',
    title: `${statusLabel} · ${componentsLabel} · ${heartbeatLabel}`,
    pulseKey: Number.isFinite(latestHeartbeat) ? latestHeartbeat : 0,
  }
}

function defaultSwarmHealth(swarmId: string): SwarmHealthMeta {
  return {
    state: 'missing',
    title: `No components reporting for ${formatSwarmDisplayName(swarmId)}`,
    pulseKey: 0,
  }
}

function formatSwarmDisplayName(id: string): string {
  if (id === 'default') return 'Services'
  if (id === UNASSIGNED_SWARM_ID) return 'Unassigned components'
  return id
}

function formatComponentCount(count: number): string {
  if (!Number.isFinite(count)) return '0 components'
  const normalized = Math.max(0, Math.floor(count))
  return normalized === 1 ? '1 component' : `${normalized} components`
}

function pickHigherPriority(
  current: HealthVisualState | null,
  next: HealthVisualState | null,
): HealthVisualState | null {
  if (!next) return current
  if (!current) return next
  const order: Record<HealthVisualState, number> = {
    missing: 0,
    disabled: 1,
    ok: 2,
    warn: 3,
    alert: 4,
  }
  return order[next] >= order[current] ? next : current
}

function combineHealth(
  current: 'OK' | 'WARN' | 'ALERT',
  next: 'OK' | 'WARN' | 'ALERT',
): 'OK' | 'WARN' | 'ALERT' {
  if (current === 'ALERT' || next === 'ALERT') return 'ALERT'
  if (current === 'WARN' || next === 'WARN') return 'WARN'
  return 'OK'
}

function stateLabel(state: HealthVisualState): string {
  switch (state) {
    case 'alert':
      return 'Alert'
    case 'warn':
      return 'Warning'
    case 'disabled':
      return 'Disabled'
    case 'ok':
      return 'Healthy'
    default:
      return 'Missing'
  }
}

