import { Client, type StompSubscription } from '@stomp/stompjs'
import type { Component, QueueInfo } from '../types/hive'
import {
  isAlertEventEnvelope,
  isCommandOutcomeEnvelope,
  isStatusMetricEnvelope,
  type StatusMetricEnvelope,
} from '../types/control'
import { logIn, logError } from './logs'
import { useUIStore } from '../store'

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

interface WorkBindingEdge {
  edgeId?: string
  fromInstance?: string
  toInstance?: string
  queue?: string
  routingKey?: string
}

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
const workBindingsBySwarm: Record<string, WorkBindingEdge[]> = {}
const componentErrors: Record<
  string,
  { at: number; code?: string; message?: string; swarmId?: string; role?: string }
> = {}
let swarmMetadataRefreshHandler: ((swarmId: string) => void) | null = null
interface QueueMetrics {
  depth: number
  consumers: number
  oldestAgeSec?: number
}
const queueMetrics: Record<string, QueueMetrics> = {}
const IGNORED_SWARM_IDS = new Set(['hive'])
const nodePositions: Record<string, { x: number; y: number }> = {}

function dropSwarmComponents(swarmId: string) {
  const normalized = swarmId.trim()
  if (!normalized) return

  Object.entries(components).forEach(([key, comp]) => {
    const compSwarm = comp.swarmId?.trim()
    if (compSwarm && compSwarm === normalized) {
      delete components[key]
      delete componentErrors[key]
    }
  })

  Object.entries(syntheticComponents).forEach(([key, comp]) => {
    const compSwarm = comp.swarmId?.trim()
    if (compSwarm && compSwarm === normalized) {
      delete syntheticComponents[key]
      delete componentErrors[key]
    }
  })

  delete workBindingsBySwarm[normalized]
}

function applyComponentError(component: Component, error: { at: number; code?: string; message?: string }) {
  component.lastErrorAt = error.at
  if (typeof error.code === 'string' && error.code.trim().length > 0) {
    component.lastErrorCode = error.code
  }
  if (typeof error.message === 'string' && error.message.trim().length > 0) {
    component.lastErrorMessage = error.message
  }
}

function recordAlertForComponent(parsed: unknown) {
  if (!isAlertEventEnvelope(parsed)) return
  const instance = parsed.scope.instance.trim()
  if (!instance) return
  if (instance.toUpperCase() === 'ALL') return
  const at = Date.now()
  const error = {
    at,
    code: parsed.data.code,
    message: parsed.data.message,
    swarmId: parsed.scope.swarmId.trim(),
    role: parsed.scope.role.trim(),
  }
  componentErrors[instance] = error
  const component = components[instance]
  if (component) {
    applyComponentError(component, error)
    notifyComponentListeners()
    emitTopology()
  }
}

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

function parseIsoTimestamp(value: unknown): number | undefined {
  const iso = getString(value)
  if (!iso) return undefined
  const parsed = Date.parse(iso)
  if (!Number.isNaN(parsed)) return parsed
  const trimmed = iso.replace(/(\.\d{3})\d+(Z|[+-]\d{2}:\d{2})$/, '$1$2')
  const parsedTrimmed = Date.parse(trimmed)
  if (!Number.isNaN(parsedTrimmed)) return parsedTrimmed
  return undefined
}

function handleLifecycleOutcome(raw: unknown): boolean {
  if (!isCommandOutcomeEnvelope(raw)) return false
  if (raw.type !== 'swarm-remove' && raw.type !== 'swarm-create') return false
  const swarmId = raw.scope.swarmId.trim()
  if (!swarmId) return false
  if (swarmId.toUpperCase() === 'ALL') return false
  const status = getString(raw.data.status)
  if (!status) return false
  const normalizedStatus = status.toUpperCase()

  if (raw.type === 'swarm-remove') {
    if (normalizedStatus !== 'REMOVED') return false
    dropSwarmComponents(swarmId)
    notifyComponentListeners()
    emitTopology()
  }

  if (raw.type === 'swarm-create' && normalizedStatus !== 'READY') return false

  if (swarmMetadataRefreshHandler) {
    swarmMetadataRefreshHandler(swarmId)
  }
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

function updateQueueMetrics(stats: Record<string, unknown> | undefined) {
  if (!stats) return
  Object.entries(stats).forEach(([queue, entry]) => {
    if (!entry) return
    if (!isRecord(entry)) return
    const depth = entry['depth']
    const consumers = entry['consumers']
    const oldestAgeSec = entry['oldestAgeSec']
    if (typeof depth !== 'number' || typeof consumers !== 'number') return
    const metric: QueueMetrics = { depth, consumers }
    if (typeof oldestAgeSec === 'number') metric.oldestAgeSec = oldestAgeSec
    queueMetrics[queue] = metric
  })
}

function extractQueueStats(evt: StatusMetricEnvelope): Record<string, unknown> | undefined {
  const io = evt.data['io']
  if (!isRecord(io)) return undefined
  const work = io['work']
  if (!isRecord(work)) return undefined
  const queueStats = work['queueStats']
  return isRecord(queueStats) ? queueStats : undefined
}

function extractQueues(evt: StatusMetricEnvelope): {
  workIn: string[]
  workOut: string[]
  controlIn: string[]
  controlOut: string[]
} | null {
  const io = evt.data['io']
  if (!isRecord(io)) return null

  const readQueues = (section: unknown): { in: string[]; out: string[] } => {
    if (!isRecord(section)) return { in: [], out: [] }
    const queues = section['queues']
    if (!isRecord(queues)) return { in: [], out: [] }
    const inList = Array.isArray(queues['in']) ? (queues['in'] as unknown[]) : []
    const outList = Array.isArray(queues['out']) ? (queues['out'] as unknown[]) : []
    const toStrings = (arr: unknown[]) =>
      arr.map((v) => getString(v)).filter((v): v is string => Boolean(v))
    return { in: toStrings(inList), out: toStrings(outList) }
  }

  const work = readQueues(io['work'])
  const control = readQueues(io['control'])
  return { workIn: work.in, workOut: work.out, controlIn: control.in, controlOut: control.out }
}

function extractWorkBindings(ctx: Record<string, unknown>): WorkBindingEdge[] | null {
  const bindings = ctx['bindings']
  if (!isRecord(bindings)) return null
  const work = bindings['work']
  if (!isRecord(work)) return null
  const edgesRaw = Array.isArray(work['edges']) ? (work['edges'] as unknown[]) : []
  const edges: WorkBindingEdge[] = []
  edgesRaw.forEach((entry) => {
    if (!isRecord(entry)) return
    const edgeRec = entry as Record<string, unknown>
    const from = isRecord(edgeRec['from']) ? (edgeRec['from'] as Record<string, unknown>) : undefined
    const to = isRecord(edgeRec['to']) ? (edgeRec['to'] as Record<string, unknown>) : undefined
    const fromInstance = from ? getString(from['instance']) : undefined
    const toInstance = to ? getString(to['instance']) : undefined
    const queue = to ? getString(to['queue']) : undefined
    const routingKey = from ? getString(from['routingKey']) : undefined
    const edgeId = getString(edgeRec['edgeId'])
    if (!fromInstance && !toInstance && !queue && !routingKey) return
    edges.push({ edgeId, fromInstance, toInstance, queue, routingKey })
  })
  return edges
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
  const componentSwarm = new Map<string, string>()
  Object.values(allComponents).forEach((comp) => {
    if (comp.swarmId) {
      componentSwarm.set(comp.id, comp.swarmId.trim())
    }
  })
  const resolvedBindingsBySwarm = new Map<string, TopologyEdge[]>()
  Object.entries(workBindingsBySwarm).forEach(([swarmId, bindings]) => {
    const resolved: TopologyEdge[] = []
    bindings.forEach((binding) => {
      const from = binding.fromInstance
      const to = binding.toInstance
      if (!from || !to) return
      if (!allComponents[from] || !allComponents[to]) return
      const queue = binding.queue || binding.routingKey || binding.edgeId
      if (!queue) return
      resolved.push({ from, to, queue })
    })
    if (resolved.length > 0) {
      resolvedBindingsBySwarm.set(swarmId, resolved)
    }
  })
  const swarmsWithResolvedBindings = new Set(resolvedBindingsBySwarm.keys())
  const edges: TopologyEdge[] = []
  Object.entries(queues).forEach(([name, { prod, cons }]) => {
    prod.forEach((p) => {
      cons.forEach((c) => {
        if (p === c) return
        const fromSwarm = componentSwarm.get(p)
        const toSwarm = componentSwarm.get(c)
        if (
          fromSwarm &&
          toSwarm &&
          fromSwarm === toSwarm &&
          swarmsWithResolvedBindings.has(fromSwarm)
        ) {
          return
        }
        edges.push({ from: p, to: c, queue: name })
      })
    })
  })
  resolvedBindingsBySwarm.forEach((bindings) => {
    edges.push(...bindings)
  })
  const orchestrators = Object.values(allComponents).filter((component) => {
    const role = component.role?.trim().toLowerCase()
    return role === 'orchestrator'
  })
  if (orchestrators.length > 0) {
    const controllers = Object.values(allComponents).filter((component) => {
      const role = component.role?.trim().toLowerCase()
      if (role !== 'swarm-controller') return false
      const swarmId = component.swarmId?.trim().toLowerCase()
      if (!swarmId) return false
      return !IGNORED_SWARM_IDS.has(swarmId)
    })
    orchestrators.forEach((orchestrator) => {
      controllers.forEach((controller) => {
        edges.push({ from: orchestrator.id, to: controller.id, queue: 'swarm-control' })
      })
    })
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
            if (/\/exchange\/ph\.control\/event\.alert\./.test(d)) {
              const { setToast } = useUIStore.getState()
              try {
                const parsed = JSON.parse(msg.body) as unknown
                if (isAlertEventEnvelope(parsed)) {
                  logError(d, parsed.data.message, 'hive', 'stomp', correlationId)
                  const swarm = parsed.scope.swarmId ? ` ${parsed.scope.swarmId}` : ''
                  setToast(`Error:${swarm} ${parsed.data.code}: ${parsed.data.message}`)
                  recordAlertForComponent(parsed)
                } else {
                  logError(d, msg.body, 'hive', 'stomp', correlationId)
                  setToast('Error: alert received')
                }
              } catch {
                logError(d, msg.body, 'hive', 'stomp', correlationId)
                setToast('Error: alert received')
              }
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
      if (destination && !/\/exchange\/ph\.control\/event\./.test(destination)) return
      try {
        const raw = JSON.parse(msg.body)
        if (handleLifecycleOutcome(raw)) return
        if (!isStatusMetricEnvelope(raw)) return
        const evt = raw
        const eventQueueStats = extractQueueStats(evt)
        const id = evt.scope.instance
        const swarmId = evt.scope.swarmId.trim()
        if (!swarmId) return
        if (!id) return
        const existing = components[id]
        const comp: Component =
          existing || {
            id,
            name: id,
            role: evt.scope.role,
            swarmId,
            lastHeartbeat: 0,
            queues: [],
          }
        comp.name = id
        comp.role = evt.scope.role
        comp.swarmId = swarmId
        comp.version = evt.version
        comp.lastHeartbeat = parseIsoTimestamp(evt.timestamp) ?? Date.now()
        comp.status = evt.type
        const existingError = componentErrors[id]
        if (existingError) {
          applyComponentError(comp, existingError)
        }
        const cfg = { ...(comp.config || {}) }
        const data = evt.data
        if (data && typeof data === 'object') {
          const dataRecord = data as Record<string, unknown>
          const normalizedRole = evt.scope.role.toLowerCase()
          const ctx = isRecord(dataRecord['context'])
            ? (dataRecord['context'] as Record<string, unknown>)
            : undefined
          const swarmStatus = ctx ? getString(ctx['swarmStatus']) : undefined
          if (
            normalizedRole === 'swarm-controller' &&
            swarmStatus &&
            swarmStatus.toUpperCase() === 'REMOVED'
          ) {
            dropSwarmComponents(swarmId)
            notifyComponentListeners()
            emitTopology()
            if (swarmMetadataRefreshHandler) {
              swarmMetadataRefreshHandler(swarmId)
            }
            return
          }

          const configSnapshot = isRecord(dataRecord['config'])
            ? (dataRecord['config'] as Record<string, unknown>)
            : undefined
          if (configSnapshot) {
            Object.entries(configSnapshot).forEach(([key, value]) => {
              const existing = cfg[key]
              if (isRecord(existing) && !isRecord(value)) {
                return
              }
              cfg[key] = value
            })
          }

        if (ctx) {
          Object.entries(ctx).forEach(([key, value]) => {
            const existing = cfg[key]
            if (isRecord(existing) && !isRecord(value)) {
              return
            }
            cfg[key] = value
          })

          if (evt.type === 'status-full' && normalizedRole === 'swarm-controller') {
            const bindings = extractWorkBindings(ctx)
            if (bindings) {
              workBindingsBySwarm[swarmId] = bindings
            } else {
              delete workBindingsBySwarm[swarmId]
            }
          }
        }

          Object.entries(dataRecord).forEach(([key, value]) => {
            if (
              key === 'enabled' ||
              key === 'tps' ||
              key === 'io' ||
              key === 'ioState' ||
              key === 'context' ||
              key === 'config' ||
              key === 'startedAt'
            ) {
              return
            }
            const existing = cfg[key]
            if (isRecord(existing) && !isRecord(value)) {
              return
            }
            cfg[key] = value
          })

          const startedAtMillis = parseIsoTimestamp(dataRecord['startedAt'])
          if (startedAtMillis !== undefined) {
            comp.startedAt = startedAtMillis
          }
        }
        const enabledFlag = (data as Record<string, unknown>)['enabled']
        if (typeof enabledFlag === 'boolean') cfg.enabled = enabledFlag
        if (Object.keys(cfg).length > 0) comp.config = cfg
        const extractedQueues = extractQueues(evt)
        if (extractedQueues) {
          const q: QueueInfo[] = []
          q.push(...extractedQueues.workIn.map((n) => ({ name: n, role: 'consumer' as const })))
          q.push(...extractedQueues.workOut.map((n) => ({ name: n, role: 'producer' as const })))
          q.push(...extractedQueues.controlIn.map((n) => ({ name: n, role: 'consumer' as const })))
          q.push(...extractedQueues.controlOut.map((n) => ({ name: n, role: 'producer' as const })))
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

export function setSwarmMetadataRefreshHandler(
  handler: ((swarmId: string) => void) | null,
) {
  swarmMetadataRefreshHandler = handler
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
  const normalized: Component = { ...component }
  syntheticComponents[component.id] = normalized
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

export function getNodePosition(id: string): { x: number; y: number } | undefined {
  const pos = nodePositions[id]
  if (!pos) return undefined
  return { x: pos.x, y: pos.y }
}
