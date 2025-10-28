import { Client, type StompSubscription } from '@stomp/stompjs'
import type { CapabilityManifest } from '../types/capabilities'
import type { Component, QueueInfo } from '../types/hive'
import { isControlEvent, type ControlEvent } from '../types/control'
import { logIn, logError } from './logs'
import { useUIStore } from '../store'
import { normalizeManifests } from './capabilities'

export type ComponentListener = (components: Component[]) => void
export interface TopologyNode {
  id: string
  type: string
  x?: number
  y?: number
  enabled?: boolean
  swarmId?: string
}
export interface TopologyEdge { from: string; to: string; queue: string }
export interface Topology { nodes: TopologyNode[]; edges: TopologyEdge[] }
export type TopologyListener = (topology: Topology) => void

interface WrappedClient extends Client {
  _phWrapped?: boolean
}

let client: WrappedClient | null = null
let controlSub: StompSubscription | null = null
let listeners: ComponentListener[] = []
let topoListeners: TopologyListener[] = []
let controlDestination = '/exchange/ph.control/#'
const components: Record<string, Component> = {}
const syntheticComponents: Record<string, Component> = {}
interface QueueMetrics {
  depth: number
  consumers: number
  oldestAgeSec?: number
}
const queueMetrics: Record<string, QueueMetrics> = {}
const nodePositions: Record<string, { x: number; y: number }> = {}

function getMergedComponents(): Record<string, Component> {
  const merged: Record<string, Component> = { ...components }
  Object.entries(syntheticComponents).forEach(([id, comp]) => {
    merged[id] = comp
  })
  return merged
}

function notifyComponentListeners() {
  const merged = Object.values(getMergedComponents())
  listeners.forEach((l) => l(merged))
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function getString(value: unknown): string | undefined {
  if (typeof value !== 'string') return undefined
  const trimmed = value.trim()
  return trimmed.length > 0 ? trimmed : undefined
}

function getBoolean(value: unknown): boolean | undefined {
  if (typeof value === 'boolean') return value
  if (typeof value === 'string') {
    const normalized = value.trim().toLowerCase()
    if (normalized === 'true') return true
    if (normalized === 'false') return false
  }
  return undefined
}

function normalizeCapability(value: unknown): CapabilityManifest | null {
  if (value === null || value === undefined) return null
  if (Array.isArray(value)) {
    const [first] = normalizeManifests(value)
    return first ?? null
  }
  const [first] = normalizeManifests([value])
  return first ?? null
}

function applyCapabilities(component: Component, value: unknown) {
  if (value === null) {
    component.capabilities = null
    return
  }
  const manifest = normalizeCapability(value)
  if (manifest) {
    component.capabilities = manifest
  }
}

interface LifecycleConfirmation {
  signal: string
  scope?: Record<string, unknown>
}

function isLifecycleConfirmation(raw: unknown): raw is LifecycleConfirmation {
  if (!isRecord(raw)) return false
  const { signal, scope } = raw as {
    signal?: unknown
    scope?: unknown
  }
  if (typeof signal !== 'string') return false
  if (scope !== undefined && !isRecord(scope)) return false
  return true
}

function handleSwarmRemoveConfirmation(raw: unknown): boolean {
  if (!isLifecycleConfirmation(raw)) return false
  if (raw.signal !== 'swarm-remove') return false
  const scope = raw.scope
  if (!scope) return false
  const swarmId = getString(scope['swarmId'])
  if (!swarmId) return false
  Object.entries(components).forEach(([key, comp]) => {
    if (comp.swarmId === swarmId) {
      delete components[key]
    }
  })
  notifyComponentListeners()
  emitTopology()
  return true
}

function enrichQueue(queue: QueueInfo): QueueInfo {
  const stats = queueMetrics[queue.name]
  if (!stats) {
    return { name: queue.name, role: queue.role }
  }
  const enriched: QueueInfo = {
    name: queue.name,
    role: queue.role,
    depth: stats.depth,
    consumers: stats.consumers,
  }
  if (typeof stats.oldestAgeSec === 'number') {
    enriched.oldestAgeSec = stats.oldestAgeSec
  }
  return enriched
}

function applyQueueMetrics() {
  Object.values(getMergedComponents()).forEach((component) => {
    component.queues = component.queues.map((queue) => enrichQueue(queue))
  })
}

function updateQueueMetrics(stats: ControlEvent['queueStats']) {
  if (!stats) return
  Object.entries(stats).forEach(([queue, entry]) => {
    if (!entry) return
    const { depth, consumers, oldestAgeSec } = entry
    if (typeof depth !== 'number' || typeof consumers !== 'number') return
    const metric: QueueMetrics = { depth, consumers }
    if (typeof oldestAgeSec === 'number') metric.oldestAgeSec = oldestAgeSec
    queueMetrics[queue] = metric
  })
}

function buildTopology(allComponents: Record<string, Component> = getMergedComponents()): Topology {
  const queues: Record<string, { prod: Set<string>; cons: Set<string> }> = {}
  Object.values(allComponents).forEach((comp) => {
    comp.queues.forEach((q) => {
      const entry = queues[q.name] || { prod: new Set<string>(), cons: new Set<string>() }
      if (q.role === 'producer') entry.prod.add(comp.id)
      else entry.cons.add(comp.id)
      queues[q.name] = entry
    })
  })
  const edges: TopologyEdge[] = []
  Object.entries(queues).forEach(([name, { prod, cons }]) => {
    prod.forEach((p) => {
      cons.forEach((c) => {
        if (p !== c) edges.push({ from: p, to: c, queue: name })
      })
    })
  })
  const processorTarget = allComponents['processor']
  if (processorTarget) {
    edges.push({ from: 'processor', to: 'sut', queue: 'sut' })
    if (allComponents['wiremock']) {
      edges.push({ from: 'processor', to: 'wiremock', queue: 'sut' })
    }
  }
  const seen = new Set<string>()
  const uniq = edges.filter((e) => {
    const key = `${e.from}|${e.to}|${e.queue}`
    if (seen.has(key)) return false
    seen.add(key)
    return true
  })
  const nodes: TopologyNode[] = Object.values(allComponents).map((c) => ({
    id: c.id,
    type: c.role || c.name,
    x: nodePositions[c.id]?.x,
    y: nodePositions[c.id]?.y,
    enabled: c.config?.enabled !== false,
    swarmId: c.swarmId,
  }))
  if (processorTarget) {
    nodes.push({
      id: 'sut',
      type: 'sut',
      x: nodePositions['sut']?.x,
      y: nodePositions['sut']?.y,
      enabled: true,
    })
  }
  return { nodes, edges: uniq }
}

function emitTopology() {
  const topo = buildTopology()
  topoListeners.forEach((l) => l(topo))
}

export function setClient(newClient: Client | null, destination = controlDestination) {
  if (controlSub) {
    controlSub.unsubscribe()
    controlSub = null
  }
  client = newClient
  controlDestination = destination
  if (client) {
    const wrapped = client._phWrapped
    if (!wrapped) {
      const origSubscribe = client.subscribe.bind(client)
      client.subscribe = ((dest, callback, headers) => {
        return origSubscribe(
          dest,
          (msg) => {
            const d = msg.headers.destination || dest
            const correlationId = msg.headers['x-correlation-id']
            logIn(d, msg.body, 'hive', 'stomp', correlationId)
            if (/\/exchange\/ph\.control\/(?:ev|sig)\.(?:error\..*|.*\.error)/.test(d)) {
              logError(d, msg.body, 'hive', 'stomp', correlationId)
              const { setToast } = useUIStore.getState()
              const evt = d.split('/').pop() || ''
              const name = evt.replace(/^(?:ev|sig)\./, '').replace(/\./g, ' ')
              const suffix = msg.body ? `: ${msg.body}` : ''
              setToast(`Error: ${name}${suffix}`)
            }
            callback(msg)
          },
          headers,
        )
      }) as typeof client.subscribe
      client._phWrapped = true
    }

    controlSub = client.subscribe(controlDestination, (msg) => {
      const destination = msg.headers.destination as string | undefined
      if (destination && !/\/exchange\/ph\.control\/ev\./.test(destination)) return
      try {
        const raw = JSON.parse(msg.body)
        if (handleSwarmRemoveConfirmation(raw)) return
        if (!isControlEvent(raw)) return
        const evt = raw as ControlEvent
        const eventQueueStats = evt.queueStats
        const id = evt.instance
        const swarmId = id.split('-')[0]
        const comp: Component = components[id] || {
          id,
          name: id,
          role: evt.role,
          swarmId,
          lastHeartbeat: 0,
          queues: [],
        }
        comp.name = id
        comp.role = evt.role
        comp.swarmId = swarmId
        comp.version = evt.version
        comp.lastHeartbeat = new Date(evt.timestamp).getTime()
        comp.status = evt.kind
        const cfg = { ...(comp.config || {}) }
        delete (cfg as Record<string, unknown>).capabilities
        let workerEnabled: boolean | undefined
        const eventCapabilities = (evt as Record<string, unknown>)['capabilities']
        if (eventCapabilities !== undefined) {
          applyCapabilities(comp, eventCapabilities)
        }
        const data = evt.data
        if (data && typeof data === 'object') {
          const { workers, ...rest } = data as Record<string, unknown> & {
            workers?: unknown
          }
          Object.entries(rest).forEach(([key, value]) => {
            if (key === 'enabled') return
            if (key === 'capabilities') {
              applyCapabilities(comp, value)
              return
            }
            cfg[key] = value
          })
          if (Array.isArray(workers)) {
            const normalizedRole = evt.role?.toLowerCase?.()
            const workerEntries = workers.filter(isRecord)
            const selected =
              normalizedRole && normalizedRole.length > 0
                ? workerEntries.find((entry) => {
                    const roleValue = getString(entry['role'])
                    return roleValue !== undefined && roleValue.toLowerCase() === normalizedRole
                  })
                : undefined
              if (selected) {
                const configSection = selected['config']
                if (isRecord(configSection)) {
                  Object.entries(configSection).forEach(([key, value]) => {
                    if (key === 'capabilities') {
                      applyCapabilities(comp, value)
                      return
                    }
                    cfg[key] = value
                  })
                }
                const dataSection = selected['data']
                if (isRecord(dataSection)) {
                  Object.entries(dataSection).forEach(([key, value]) => {
                    if (key === 'capabilities') {
                      applyCapabilities(comp, value)
                      return
                    }
                    cfg[key] = value
                  })
                }
                const workerEnabledCandidate = getBoolean(selected['enabled'])
                if (typeof workerEnabledCandidate === 'boolean') {
                workerEnabled = workerEnabledCandidate
              }
            }
          }
        }
        const aggregateEnabled =
          typeof workerEnabled === 'boolean'
            ? workerEnabled
            : typeof evt.enabled === 'boolean'
            ? evt.enabled
            : undefined
        if (typeof aggregateEnabled === 'boolean') cfg.enabled = aggregateEnabled
        const cfgKeys = Object.keys(cfg)
        if (cfgKeys.length > 0) {
          comp.config = cfg
        } else if ('config' in comp) {
          delete (comp as Record<string, unknown>).config
        }
        if (evt.queues) {
          const q: QueueInfo[] = []
          q.push(...(evt.queues.work?.in?.map((n) => ({ name: n, role: 'consumer' as const })) ?? []))
          q.push(...(evt.queues.work?.out?.map((n) => ({ name: n, role: 'producer' as const })) ?? []))
          q.push(...(evt.queues.control?.in?.map((n) => ({ name: n, role: 'consumer' as const })) ?? []))
          q.push(...(evt.queues.control?.out?.map((n) => ({ name: n, role: 'producer' as const })) ?? []))
          comp.queues = q
        }
        components[id] = comp
        updateQueueMetrics(eventQueueStats)
        applyQueueMetrics()
        notifyComponentListeners()
        emitTopology()
      } catch {
        // ignore parsing errors
      }
    })
  }
}

export function subscribeComponents(fn: ComponentListener) {
  listeners.push(fn)
  fn(Object.values(getMergedComponents()))
  return () => {
    listeners = listeners.filter((l) => l !== fn)
  }
}

export function subscribeTopology(fn: TopologyListener) {
  topoListeners.push(fn)
  fn(buildTopology())
  return () => {
    topoListeners = topoListeners.filter((l) => l !== fn)
  }
}

export function upsertSyntheticComponent(component: Component) {
  syntheticComponents[component.id] = component
  applyQueueMetrics()
  notifyComponentListeners()
  emitTopology()
}

export function removeSyntheticComponent(id: string) {
  if (!(id in syntheticComponents)) return
  delete syntheticComponents[id]
  notifyComponentListeners()
  emitTopology()
}

export function updateNodePosition(id: string, x: number, y: number) {
  nodePositions[id] = { x, y }
  emitTopology()
}

