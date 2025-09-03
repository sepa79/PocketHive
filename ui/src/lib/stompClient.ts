import { Client } from '@stomp/stompjs'
import type { Component } from '../types/hive'

export type ComponentListener = (components: Component[]) => void

let client: Client | null = null
let listeners: ComponentListener[] = []
let mockComponents: Component[] | null = null
let connected = false

export function connect() {
  if (connected) return
  const url = import.meta.env.VITE_STOMP_URL
  if (url) {
    client = new Client({ brokerURL: url })
    client.onConnect = () => {
      client?.subscribe('/exchange/control', (msg) => {
        try {
          const body = JSON.parse(msg.body) as Component[]
          listeners.forEach((l) => l(body))
        } catch {
          // ignore parsing errors
        }
      })
    }
    client.activate()
  } else {
    mockComponents = createMock()
    setInterval(() => {
      mockComponents?.forEach((c) => {
        c.lastHeartbeat = Date.now()
        c.queues.forEach((q) => {
          if (typeof q.depth === 'number') {
            const delta = Math.floor(Math.random() * 20 - 10)
            q.depth = Math.max(0, q.depth + delta)
          }
        })
      })
      listeners.forEach((l) => l(mockComponents!))
    }, 2000)
  }
  connected = true
}

export function subscribeComponents(fn: ComponentListener) {
  listeners.push(fn)
  if (mockComponents) fn(mockComponents)
  return () => {
    listeners = listeners.filter((l) => l !== fn)
  }
}

export async function sendConfigUpdate(id: string, config: unknown) {
  const url = import.meta.env.VITE_STOMP_URL
  if (!url) {
    return new Promise<void>((resolve) => setTimeout(resolve, 300))
  }
  return new Promise<void>((resolve) => {
    client?.publish({
      destination: `/app/config.update.${id}`,
      body: JSON.stringify(config),
    })
    resolve()
  })
}

function createMock(): Component[] {
  const now = Date.now()
  return [
    {
      id: 'generator',
      name: 'Generator',
      version: '1.0.0',
      uptimeSec: 1234,
      lastHeartbeat: now,
      env: 'dev',
      status: 'running',
      queues: [
        { name: 'logs.q', role: 'producer', depth: 23, consumers: 0 },
        { name: 'input.q', role: 'consumer', depth: 5, consumers: 1 },
      ],
    },
    {
      id: 'processor',
      name: 'Processor',
      version: '1.1.0',
      uptimeSec: 2345,
      lastHeartbeat: now,
      env: 'dev',
      status: 'running',
      queues: [
        { name: 'work.q', role: 'consumer', depth: 0, consumers: 2 },
      ],
    },
    {
      id: 'sut',
      name: 'SUT',
      version: '1.2.0',
      uptimeSec: 3456,
      lastHeartbeat: now,
      env: 'dev',
      status: 'idle',
      queues: [
        { name: 'results.q', role: 'producer', depth: 0, consumers: 0 },
      ],
    },
  ]
}

