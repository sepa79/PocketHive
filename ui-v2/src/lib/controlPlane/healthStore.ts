import { subscribeSchemaState, type SchemaState } from './schemaRegistry'
import {
  startStompGateway,
  stopStompGateway,
  subscribeStompMessages,
  subscribeStompMetrics,
  subscribeStompState,
  type StompConnectionState,
} from './stompGateway'
import { CONTROL_PLANE_STOMP_PASSCODE, CONTROL_PLANE_STOMP_USER } from './config'
import { CONTROL_PLANE_TOPICS } from './subscriptions'
import { applyStatusEnvelope, hasStatusSnapshot, requestEviction } from './stateStore'

export type ControlPlaneHealth = {
  schemaStatus: SchemaState['status']
  schemaError?: string
  stompState: StompConnectionState
  invalidCount: number
}

type HealthListener = (health: ControlPlaneHealth) => void

let health: ControlPlaneHealth = {
  schemaStatus: 'idle',
  stompState: 'idle',
  invalidCount: 0,
}

const listeners = new Set<HealthListener>()
let schemaReady = false
let started = false
let evictionTimer: number | null = null

export function startControlPlaneHealth(url: string) {
  if (started) {
    return
  }
  started = true
  subscribeSchemaState((state) => {
    schemaReady = state.status === 'ready'
    health = {
      ...health,
      schemaStatus: state.status,
      schemaError: state.error,
    }
    notify()
    if (state.status === 'ready') {
      startStompGateway({
        url,
        topics: CONTROL_PLANE_TOPICS,
        connectHeaders: {
          login: CONTROL_PLANE_STOMP_USER,
          passcode: CONTROL_PLANE_STOMP_PASSCODE,
        },
      })
    } else {
      stopStompGateway()
    }
  })
  subscribeStompState((state) => {
    health = { ...health, stompState: state }
    notify()
  })
  subscribeStompMetrics((metrics) => {
    health = { ...health, invalidCount: metrics.invalidCount }
    notify()
  })
  subscribeStompMessages((message) => {
    if (!schemaReady || message.errors.length > 0 || !message.envelope) {
      return
    }
    if (message.envelope.kind === 'metric') {
      const scope = message.envelope.scope
      if (
        message.envelope.type === 'status-delta' &&
        !hasStatusSnapshot({
          swarmId: scope.swarmId,
          role: scope.role,
          instance: scope.instance,
        })
      ) {
        // TODO: trigger status-full refresh when REST integration is wired
        return
      }
      applyStatusEnvelope(message.envelope)
    }
  })
  if (!evictionTimer) {
    evictionTimer = window.setInterval(() => {
      requestEviction()
    }, 60_000)
  }
}

export function subscribeControlPlaneHealth(listener: HealthListener) {
  listeners.add(listener)
  listener(health)
  return () => listeners.delete(listener)
}

function notify() {
  listeners.forEach((listener) => listener(health))
}
