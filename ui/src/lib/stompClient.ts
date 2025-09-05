import { Client, type StompSubscription } from '@stomp/stompjs'
import type { Component } from '../types/hive'
import { isControlEvent, type ControlEvent } from '../types/control'
import { logIn, logOut } from './logs'

export type ComponentListener = (components: Component[]) => void
export interface TopologyNode {
  id: string
  type: string
  x?: number
  y?: number
  enabled?: boolean
}
export interface TopologyEdge { from: string; to: string; queue: string }
export interface Topology { nodes: TopologyNode[]; edges: TopologyEdge[] }
export type TopologyListener = (topology: Topology) => void

let client: Client | null = null
let controlSub: StompSubscription | null = null
let listeners: ComponentListener[] = []
let topoListeners: TopologyListener[] = []
let controlDestination = '/exchange/ph.control/ev.#'
const components: Record<string, Component> = {}
const nodePositions: Record<string, { x: number; y: number }> = {}

function buildTopology(): Topology {
  const queues: Record<string, { prod: Set<string>; cons: Set<string> }> = {}
  Object.values(components).forEach((comp) => {
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
  if (components['processor']) {
    edges.push({ from: 'processor', to: 'sut', queue: 'sut' })
  }
  const seen = new Set<string>()
  const uniq = edges.filter((e) => {
    const key = `${e.from}|${e.to}|${e.queue}`
    if (seen.has(key)) return false
    seen.add(key)
    return true
  })
  const nodes: TopologyNode[] = Object.values(components).map((c) => ({
    id: c.id,
    type: c.name,
    x: nodePositions[c.id]?.x,
    y: nodePositions[c.id]?.y,
    enabled: c.config?.enabled !== false,
  }))
  if (components['processor']) {
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
    const wrapped = (client as any)._phWrapped
    if (!wrapped) {
      const origPublish = client.publish.bind(client)
      client.publish = ((params) => {
        logOut(params.destination, params.body ?? '')
        origPublish(params)
      }) as typeof client.publish

      const origSubscribe = client.subscribe.bind(client)
      client.subscribe = ((dest, callback, headers) => {
        return origSubscribe(
          dest,
          (msg) => {
            logIn(msg.headers.destination || dest, msg.body)
            callback(msg)
          },
          headers,
        )
      }) as typeof client.subscribe
      ;(client as any)._phWrapped = true
    }

    controlSub = client.subscribe(controlDestination, (msg) => {
      try {
        const raw = JSON.parse(msg.body)
        if (!isControlEvent(raw)) return
        const evt = raw as ControlEvent
        const id = evt.instance
        const comp: Component = components[id] || {
          id,
          name: evt.role,
          lastHeartbeat: 0,
          queues: [],
        }
        comp.name = evt.role
        comp.version = evt.version
        comp.lastHeartbeat = new Date(evt.timestamp).getTime()
        comp.status = evt.kind
        const cfg = { ...(comp.config || {}) }
        if (evt.data) Object.assign(cfg, evt.data)
        if (typeof evt.enabled === 'boolean') cfg.enabled = evt.enabled
        if (Object.keys(cfg).length > 0) comp.config = cfg
        if (evt.queues || evt.inQueue) {
          const q: { name: string; role: 'producer' | 'consumer' }[] = []
          if (evt.queues) {
            q.push(...(evt.queues.work?.in?.map((n) => ({ name: n, role: 'consumer' as const })) ?? []))
            q.push(...(evt.queues.work?.out?.map((n) => ({ name: n, role: 'producer' as const })) ?? []))
            q.push(...(evt.queues.control?.in?.map((n) => ({ name: n, role: 'consumer' as const })) ?? []))
            q.push(...(evt.queues.control?.out?.map((n) => ({ name: n, role: 'producer' as const })) ?? []))
          }
          if (evt.inQueue?.name) {
            q.push({ name: evt.inQueue.name, role: 'consumer' })
          }
          comp.queues = q
        }
        components[id] = comp
        listeners.forEach((l) => l(Object.values(components)))
        emitTopology()
      } catch {
        // ignore parsing errors
      }
    })
  }
}

export function subscribeComponents(fn: ComponentListener) {
  listeners.push(fn)
  fn(Object.values(components))
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

export function updateNodePosition(id: string, x: number, y: number) {
  nodePositions[id] = { x, y }
  emitTopology()
}

export async function sendConfigUpdate(component: Component, config: unknown) {
  return new Promise<void>((resolve, reject) => {
    if (!client || !client.active) {
      reject(new Error('STOMP client not connected'))
      return
    }
    const { id, name } = component
    const payload = { type: 'config-update', role: name, instance: id, data: config }
    client.publish({
      destination: `/exchange/ph.control/sig.config-update.${name}.${id}`,
      body: JSON.stringify(payload),
    })
    resolve()
  })
}

export async function requestStatusFull(component: Component) {
  return new Promise<void>((resolve, reject) => {
    if (!client || !client.active) {
      reject(new Error('STOMP client not connected'))
      return
    }
    const { id, name } = component
    const payload = { type: 'status-request', role: name, instance: id }
    client.publish({
      destination: `/exchange/ph.control/sig.status-request.${name}.${id}`,
      body: JSON.stringify(payload),
    })
    resolve()
  })
}

export async function startSwarm(id: string, image: string) {
  return new Promise<void>((resolve, reject) => {
    if (!client || !client.active) {
      reject(new Error('STOMP client not connected'))
      return
    }
    client.publish({
      destination: `/exchange/ph.control/sig.swarm-create.${id}`,
      body: image,
    })
    resolve()
  })
}

