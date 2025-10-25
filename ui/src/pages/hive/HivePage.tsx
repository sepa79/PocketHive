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

export default function HivePage() {
  const [components, setComponents] = useState<Component[]>([])
  const [selected, setSelected] = useState<Component | null>(null)
  const [search, setSearch] = useState('')
  const [showCreate, setShowCreate] = useState(false)
  const [activeSwarm, setActiveSwarm] = useState<string | null>(null)
  const [expandedSwarmId, setExpandedSwarmId] = useState<string | null>(null)
  const [now, setNow] = useState(() => Date.now())

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

  useEffect(() => {
    const id = window.setInterval(() => setNow(Date.now()), 1000)
    return () => window.clearInterval(id)
  }, [])

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

  const activeSwarmComponents: Component[] = activeSwarm
    ? grouped[activeSwarm] ?? []
    : []

  const swarmHealth = useMemo(() => {
    return Object.fromEntries(
      Object.entries(grouped).map(([id, comps]) => [
        id,
        deriveSwarmHealth(id, comps, now),
      ]),
    )
  }, [grouped, now])

  const shouldShowActiveSwarmList =
    !selected && expandedSwarmId === null && Boolean(activeSwarm)

  const selectedId = selected?.id

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
              selectedId={selectedId}
            />
            {Object.entries(grouped)
              .sort(([a], [b]) => a.localeCompare(b))
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
                      onFocusChange={(swarm, nextActive) =>
                        setActiveSwarm((current) => {
                          const normalized = swarm === 'default' ? 'default' : swarm
                          if (nextActive) {
                            return normalized
                          }
                          return current === normalized ? null : current
                        })
                      }
                      onRemove={(swarm) => {
                        setExpandedSwarmId((current) =>
                          current === swarm ? null : current,
                        )
                        setActiveSwarm((current) =>
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
          onSwarmSelect={(id) =>
            setActiveSwarm(id === 'default' ? 'default' : id)}
        />
      </div>
      <div className="hidden lg:flex w-1/3 xl:w-1/4 border-l border-white/10 overflow-hidden">
        {selected ? (
          <ComponentDetail component={selected} onClose={() => setSelected(null)} />
        ) : shouldShowActiveSwarmList ? (
          <div className="flex-1 p-4 overflow-y-auto">
            <ComponentList
              components={activeSwarmComponents}
              onSelect={(component) => setSelected(component)}
              selectedId={selectedId}
            />
          </div>
        ) : (
          <div className="p-4 text-white/50 overflow-y-auto">Select a component</div>
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
    title: `No components reporting for ${swarmId}`,
    pulseKey: 0,
  }
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

