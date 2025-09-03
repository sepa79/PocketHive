import { Client, type StompSubscription } from '@stomp/stompjs'
import type { Component } from '../types/hive'

export type ComponentListener = (components: Component[]) => void

let client: Client | null = null
let controlSub: StompSubscription | null = null
let listeners: ComponentListener[] = []

export function setClient(newClient: Client | null) {
  if (controlSub) {
    controlSub.unsubscribe()
    controlSub = null
  }
  client = newClient
  if (client) {
    controlSub = client.subscribe('/exchange/control', (msg) => {
      try {
        const body = JSON.parse(msg.body) as Component[]
        listeners.forEach((l) => l(body))
      } catch {
        // ignore parsing errors
      }
    })
  }
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

