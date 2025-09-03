import { Client, type StompSubscription } from '@stomp/stompjs'
import type { Component } from '../types/hive'
import { isControlEvent, type ControlEvent } from '../types/control'
import { logIn, logOut } from './logs'

export type ComponentListener = (components: Component[]) => void

let client: Client | null = null
let controlSub: StompSubscription | null = null
let listeners: ComponentListener[] = []
let controlDestination = '/exchange/control/'
const components: Record<string, Component> = {}

export function setClient(newClient: Client | null, destination = controlDestination) {
  if (controlSub) {
    controlSub.unsubscribe()
    controlSub = null
  }
  client = newClient
  controlDestination = destination
  if (client) {
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
        if (evt.queues) {
          comp.queues = [
            ...(evt.queues.in?.map((q: string) => ({ name: q, role: 'consumer' as const })) ?? []),
            ...(evt.queues.out?.map((q: string) => ({ name: q, role: 'producer' as const })) ?? []),
          ]
        }
        components[id] = comp
        listeners.forEach((l) => l(Object.values(components)))
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

