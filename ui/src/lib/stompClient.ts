import { Client } from '@stomp/stompjs'
import type { Component } from '../types/hive'

export type ComponentListener = (components: Component[]) => void

let client: Client | null = null
let listeners: ComponentListener[] = []
let connected = false

export function connect() {
  if (connected) return
  const url = import.meta.env.VITE_STOMP_URL
  if (!url) {
    console.warn('STOMP URL not configured')
    return
  }
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
  connected = true
}

export function subscribeComponents(fn: ComponentListener) {
  listeners.push(fn)
  return () => {
    listeners = listeners.filter((l) => l !== fn)
  }
}

export async function sendConfigUpdate(id: string, config: unknown) {
  return new Promise<void>((resolve, reject) => {
    if (!client || !client.active) {
      reject(new Error('STOMP client not connected'))
      return
    }
    client.publish({
      destination: `/app/config.update.${id}`,
      body: JSON.stringify(config),
    })
    resolve()
  })
}

