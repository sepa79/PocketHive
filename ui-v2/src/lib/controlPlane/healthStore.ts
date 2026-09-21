/**
 * Responsibility: coordinate Control Plane readiness and expose connection health.
 * Must not: invent broker destinations or start with failed/stale connection information.
 * Contract: RESP-CONTROL-STOMP-INFO — docs/architecture/runtime-responsibilities.md#resp-control-stomp-info.
 */
import { subscribeSchemaState, type SchemaState } from './schemaRegistry'
import {
  startStompGateway,
  stopStompGateway,
  subscribeStompMessages,
  subscribeStompMetrics,
  subscribeStompState,
  type StompConnectionState,
} from './stompGateway'
import { loadControlPlaneConnectionInfo } from './connectionInfo'
import { applyStatusEnvelope, hasStatusSnapshot, requestEviction } from './stateStore'
import { requestControlPlaneRefresh } from './restGateway'
import {
  getControlPlaneSettings,
  subscribeControlPlaneSettings,
  type ControlPlaneSettings,
} from './settingsStore'

export type ControlPlaneHealth = {
  schemaStatus: SchemaState['status']
  schemaError?: string
  connectionInfoError?: string
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
let connectionRequest = 0
let schemaReady = false
let started = false
let evictionTimer: number | null = null
let lastRefreshAt = 0
let refreshInFlight: Promise<boolean> | null = null
let lastStompState: StompConnectionState = 'idle'
const REFRESH_MIN_INTERVAL_MS = 2_000
let lastSettings: ControlPlaneSettings | null = null

export function startControlPlaneHealth() {
  if (started) {
    return
  }
  started = true
  lastSettings = getControlPlaneSettings()
  subscribeSchemaState((state) => {
    schemaReady = state.status === 'ready'
    health = {
      ...health,
      schemaStatus: state.status,
      schemaError: state.error,
    }
    notify()
    if (state.status === 'ready') {
      void applySettings(lastSettings)
    } else {
      void applySettings(null)
    }
  })
  subscribeControlPlaneSettings((settings) => {
    lastSettings = settings
    if (!schemaReady) {
      return
    }
    void applySettings(settings)
  })
  subscribeStompState((state) => {
    health = { ...health, stompState: state }
    notify()
    if (schemaReady && state === 'connected' && lastStompState !== 'connected') {
      void queueRefresh()
    }
    lastStompState = state
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
        void queueRefresh()
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

async function applySettings(settings: ControlPlaneSettings | null) {
  const request = ++connectionRequest
  stopStompGateway()
  health = { ...health, connectionInfoError: undefined }
  notify()
  if (!settings || !settings.enabled) return
  try {
    const info = await loadControlPlaneConnectionInfo()
    if (request !== connectionRequest) return
    startStompGateway({
      url: settings.url,
      topics: [info.subscriptionDestination],
      destinationPrefix: info.destinationPrefix,
      connectHeaders: { login: settings.user, passcode: settings.passcode },
    })
  } catch (error) {
    if (request !== connectionRequest) return
    health = { ...health, connectionInfoError: error instanceof Error ? error.message : String(error) }
    notify()
  }
}

function queueRefresh() {
  const now = Date.now()
  if (now - lastRefreshAt < REFRESH_MIN_INTERVAL_MS) {
    return refreshInFlight ?? Promise.resolve(false)
  }
  if (refreshInFlight) {
    return refreshInFlight
  }
  lastRefreshAt = now
  refreshInFlight = requestControlPlaneRefresh().finally(() => {
    refreshInFlight = null
  })
  return refreshInFlight
}
