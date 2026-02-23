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
const QUEUE_CACHE_STORAGE_KEY = 'pockethive.ui.topology.queue-cache.v1'

type QueueCacheEntry = {
  swarmId?: string
  queues: QueueInfo[]
  updatedAt: number
}

const queueCache: Record<string, QueueCacheEntry> = loadQueueCache()

function dropSwarmComponents(swarmId: string) {
  const normalized = swarmId.trim()
  if (!normalized) return
  let queueCacheChanged = false

  Object.entries(components).forEach(([key, comp]) => {
    const compSwarm = comp.swarmId?.trim()
    if (compSwarm && compSwarm === normalized) {
      delete components[key]
      delete componentErrors[key]
      if (key in queueCache) {
        delete queueCache[key]
        queueCacheChanged = true
      }
    }
  })

  Object.entries(syntheticComponents).forEach(([key, comp]) => {
    const compSwarm = comp.swarmId?.trim()
    if (compSwarm && compSwarm === normalized) {
      delete syntheticComponents[key]
      delete componentErrors[key]
      if (key in queueCache) {
        delete queueCache[key]
        queueCacheChanged = true
      }
    }
  })

  if (queueCacheChanged) {
    persistQueueCache()
  }
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
  const instance = parsed.scope.instance?.trim()
  if (!instance) return
  const at = Date.now()
  const error = {
    at,
    code: parsed.data.code,
    message: parsed.data.message,
    swarmId: parsed.scope.swarmId?.trim(),
    role: parsed.scope.role?.trim(),
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

function getBoolean(value: unknown): boolean | undefined {
  if (typeof value === 'boolean') return value
  if (typeof value === 'string') {
    const normalized = value.trim().toLowerCase()
    if (normalized === 'true') return true
    if (normalized === 'false') return false
  }
  return undefined
}

function handleLifecycleOutcome(raw: unknown): boolean {
  if (!isCommandOutcomeEnvelope(raw)) return false
  if (raw.type !== 'swarm-remove' && raw.type !== 'swarm-create') return false
  const swarmId = raw.scope.swarmId?.trim()
  if (!swarmId) return false

  if (raw.type === 'swarm-remove') {
    dropSwarmComponents(swarmId)
    notifyComponentListeners()
    emitTopology()
  }

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

function loadQueueCache(): Record<string, QueueCacheEntry> {
  const storage = getLocalStorage()
  if (!storage) return {}
  try {
    const raw = storage.getItem(QUEUE_CACHE_STORAGE_KEY)
    if (!raw) return {}
    const parsed = JSON.parse(raw)
    if (!isRecord(parsed)) return {}
    const out: Record<string, QueueCacheEntry> = {}
    Object.entries(parsed).forEach(([componentId, entry]) => {
      if (!isRecord(entry)) return
      const queuesRaw = entry['queues']
      if (!Array.isArray(queuesRaw)) return
      const queues: QueueInfo[] = []
      queuesRaw.forEach((queue) => {
        if (!isRecord(queue)) return
        const name = getString(queue['name'])
        const role = queue['role']
        if (!name) return
        if (role !== 'producer' && role !== 'consumer') return
        queues.push({ name, role })
      })
      if (queues.length === 0) return
      const swarmId = getString(entry['swarmId'])
      const updatedAt = typeof entry['updatedAt'] === 'number' ? entry['updatedAt'] : Date.now()
      out[componentId] = { swarmId, queues, updatedAt }
    })
    return out
  } catch {
    return {}
  }
}

function persistQueueCache() {
  const storage = getLocalStorage()
  if (!storage) return
  try {
    storage.setItem(QUEUE_CACHE_STORAGE_KEY, JSON.stringify(queueCache))
  } catch {
    // ignore storage errors
  }
}

function rememberQueues(componentId: string, swarmId: string, queues: QueueInfo[]) {
  const compact = queues
    .map((queue) => ({ name: queue.name, role: queue.role }))
    .filter((queue) => queue.name && (queue.role === 'producer' || queue.role === 'consumer'))
  if (compact.length === 0) return
  queueCache[componentId] = {
    swarmId,
    queues: compact,
    updatedAt: Date.now(),
  }
  persistQueueCache()
}

function restoreQueues(componentId: string, swarmId: string): QueueInfo[] | null {
  const cached = queueCache[componentId]
  if (!cached || cached.queues.length === 0) return null
  const cachedSwarmId = cached.swarmId?.trim()
  if (cachedSwarmId && cachedSwarmId !== swarmId.trim()) return null
  return cached.queues.map((queue) => ({ name: queue.name, role: queue.role }))
}

function getLocalStorage(): Storage | null {
  if (typeof globalThis === 'undefined') return null
  const storage = (globalThis as { localStorage?: Storage }).localStorage
  return storage ?? null
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

function mergeWorkerSnapshotData(
  component: Component,
  workerEntry: Record<string, unknown>,
  swarmId: string,
  timestamp: string,
  version: string,
  statusType: string,
) {
  const cfg = { ...(component.config || {}) }
  const configSection = workerEntry['config']
  if (isRecord(configSection)) {
    Object.entries(configSection).forEach(([key, value]) => {
      cfg[key] = value
    })
  }
  const dataSection = workerEntry['data']
  if (isRecord(dataSection)) {
    const configKeys = isRecord(configSection)
      ? new Set(Object.keys(configSection as Record<string, unknown>))
      : new Set<string>()
    Object.entries(dataSection).forEach(([key, value]) => {
      if (configKeys.has(key)) return
      cfg[key] = value
    })
  }
  const enabled = getBoolean(workerEntry['enabled'])
  if (typeof enabled === 'boolean') {
    cfg.enabled = enabled
  }
  if (Object.keys(cfg).length > 0) {
    component.config = cfg
  }
  component.swarmId = swarmId
  component.version = version
  component.status = statusType
  component.lastHeartbeat = new Date(timestamp).getTime()
}

function upsertWorkersFromControllerSnapshot(
  swarmId: string,
  timestamp: string,
  version: string,
  statusType: string,
  workers: unknown[],
) {
  workers.forEach((entry) => {
    if (!isRecord(entry)) return
    const role = getString(entry['role'])
    if (!role) return
    const id = `${role}-${swarmId}`
    const existing = components[id]
    const comp: Component =
      existing || {
        id,
        name: id,
        role,
        swarmId,
        lastHeartbeat: 0,
        queues: [],
      }
    comp.name = id
    comp.role = role
    mergeWorkerSnapshotData(comp, entry, swarmId, timestamp, version, statusType)

    const inQueue = getString(entry['inQueue'])
    const outQueue = getString(entry['outQueue'])
    const queues: QueueInfo[] = []
    if (inQueue) queues.push({ name: inQueue, role: 'consumer' })
    if (outQueue) queues.push({ name: outQueue, role: 'producer' })
    if (queues.length > 0) {
      comp.queues = queues
      rememberQueues(id, swarmId, queues)
    } else if (!comp.queues.length) {
      const restored = restoreQueues(id, swarmId)
      if (restored) {
        comp.queues = restored
      }
    }
    const existingError = componentErrors[id]
    if (existingError) {
      applyComponentError(comp, existingError)
    }
    components[id] = comp
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

export function requestStatusSnapshots(options?: { force?: boolean }): boolean {
  void options
  // Default UI credentials are read-only (`ph-observer`), so publishing control
  // signals via STOMP can cause broker disconnects. Keep this as a no-op.
  return false
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
        const swarmId = evt.scope.swarmId?.trim() ?? ''
        if (!swarmId) return
        if (!id) return
        const existing = components[id]
        const comp: Component =
          existing || {
            id,
            name: id,
            role: evt.scope.role ?? 'unknown',
            swarmId,
            lastHeartbeat: 0,
            queues: [],
          }
        comp.name = id
        comp.role = evt.scope.role ?? comp.role
        comp.swarmId = swarmId
        comp.version = evt.version
        comp.lastHeartbeat = new Date(evt.timestamp).getTime()
        comp.status = evt.type
        const existingError = componentErrors[id]
        if (existingError) {
          applyComponentError(comp, existingError)
        }
        const cfg = { ...(comp.config || {}) }
        let workerEnabled: boolean | undefined
        const data = evt.data
        if (data && typeof data === 'object') {
          const swarmStatus = getString((data as Record<string, unknown>)['swarmStatus'])
          const normalizedRole = (evt.scope.role ?? '').toLowerCase()
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
          const { workers, ...rest } = data as Record<string, unknown> & {
            workers?: unknown
          }
          if (Array.isArray(workers)) {
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
                  cfg[key] = value
                })
              }
              const dataSection = selected['data']
              if (isRecord(dataSection)) {
                const configKeys = isRecord(configSection)
                  ? new Set(Object.keys(configSection as Record<string, unknown>))
                  : new Set<string>()
                Object.entries(dataSection).forEach(([key, value]) => {
                  if (configKeys.has(key)) return
                  cfg[key] = value
                })
              }
              const workerEnabledCandidate = getBoolean(selected['enabled'])
              if (typeof workerEnabledCandidate === 'boolean') {
                workerEnabled = workerEnabledCandidate
              }
            }
            if (normalizedRole === 'swarm-controller') {
              upsertWorkersFromControllerSnapshot(
                swarmId,
                evt.timestamp,
                evt.version,
                evt.type,
                workers,
              )
            }
          }
          Object.entries(rest).forEach(([key, value]) => {
            if (key === 'enabled' || key === 'tps' || key === 'io' || key === 'context') {
              return
            }
            const existing = cfg[key]
            if (isRecord(existing) && !isRecord(value)) {
              return
            }
            cfg[key] = value
          })
          const startedAtIso = getString((data as Record<string, unknown>)['startedAt'])
          if (startedAtIso) {
            const ts = Date.parse(startedAtIso)
            if (!Number.isNaN(ts)) {
              comp.startedAt = ts
            }
          }
        }
        const aggregateEnabled =
          typeof workerEnabled === 'boolean'
            ? workerEnabled
            : typeof (data as Record<string, unknown>)['enabled'] === 'boolean'
            ? ((data as Record<string, unknown>)['enabled'] as boolean)
            : undefined
        if (typeof aggregateEnabled === 'boolean') cfg.enabled = aggregateEnabled
        if (Object.keys(cfg).length > 0) comp.config = cfg
        const extractedQueues = extractQueues(evt)
        if (extractedQueues) {
          const q: QueueInfo[] = []
          q.push(...extractedQueues.workIn.map((n) => ({ name: n, role: 'consumer' as const })))
          q.push(...extractedQueues.workOut.map((n) => ({ name: n, role: 'producer' as const })))
          q.push(...extractedQueues.controlIn.map((n) => ({ name: n, role: 'consumer' as const })))
          q.push(...extractedQueues.controlOut.map((n) => ({ name: n, role: 'producer' as const })))
          comp.queues = q
          rememberQueues(id, swarmId, q)
        } else if (!comp.queues.length) {
          const restored = restoreQueues(id, swarmId)
          if (restored) {
            comp.queues = restored
          }
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
