import { recordWireLog } from './wireLogStore'
import type { ControlPlaneDecoderError, ControlPlaneEnvelope } from './types'

const BACKOFF_DELAYS = [1000, 2000, 4000, 8000, 16000, 30000]

export type StompConnectionState = 'idle' | 'connecting' | 'connected' | 'reconnecting' | 'offline'

export type StompMessage = {
  routingKey: string
  envelope?: ControlPlaneEnvelope
  errors: ControlPlaneDecoderError[]
}

export type StompGatewayMetrics = {
  invalidCount: number
}

type StompListener = (message: StompMessage) => void
type StompStateListener = (state: StompConnectionState) => void

type StompGatewayOptions = {
  url: string
  topics: string[]
  connectHeaders?: Record<string, string>
}

let socket: WebSocket | null = null
let state: StompConnectionState = 'idle'
let backoffIndex = 0
let heartbeatTimer: number | null = null
let pendingConnect = false
let subscriptions: string[] = []
let invalidCount = 0
const listeners = new Set<StompListener>()
const stateListeners = new Set<StompStateListener>()
const metricsListeners = new Set<(metrics: StompGatewayMetrics) => void>()

export function startStompGateway(options: StompGatewayOptions) {
  subscriptions = options.topics
  if (socket || pendingConnect) {
    return
  }
  connect(options)
}

export function stopStompGateway() {
  pendingConnect = false
  if (socket) {
    socket.close()
    socket = null
  }
  setState('offline')
}

export function subscribeStompMessages(listener: StompListener) {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

export function subscribeStompState(listener: StompStateListener) {
  stateListeners.add(listener)
  listener(state)
  return () => stateListeners.delete(listener)
}

export function subscribeStompMetrics(listener: (metrics: StompGatewayMetrics) => void) {
  metricsListeners.add(listener)
  listener({ invalidCount })
  return () => metricsListeners.delete(listener)
}

function connect(options: StompGatewayOptions) {
  pendingConnect = true
  setState(state === 'connected' ? 'reconnecting' : 'connecting')
  const ws = new WebSocket(options.url)
  socket = ws
  ws.addEventListener('open', () => {
    backoffIndex = 0
    pendingConnect = false
    sendFrame(
      ws,
      'CONNECT',
      {
        'accept-version': '1.2',
        host: '/',
        'heart-beat': '10000,10000',
        ...options.connectHeaders,
      },
      '',
    )
  })
  ws.addEventListener('message', (event) => {
    handleFrame(ws, event.data as string)
  })
  ws.addEventListener('close', () => {
    socket = null
    stopHeartbeat()
    scheduleReconnect(options)
  })
  ws.addEventListener('error', () => {
    socket = null
    stopHeartbeat()
    scheduleReconnect(options)
  })
}

function scheduleReconnect(options: StompGatewayOptions) {
  if (!pendingConnect && state !== 'offline') {
    setState('reconnecting')
  }
  if (state === 'offline') {
    return
  }
  const delay = BACKOFF_DELAYS[Math.min(backoffIndex, BACKOFF_DELAYS.length - 1)]
  backoffIndex += 1
  pendingConnect = true
  window.setTimeout(() => {
    if (state === 'offline') {
      return
    }
    connect(options)
  }, jitter(delay))
}

function handleFrame(ws: WebSocket, raw: string) {
  const frames = raw.split('\u0000').filter(Boolean)
  for (const frame of frames) {
    const parsed = parseFrame(frame)
    if (!parsed) {
      continue
    }
    if (parsed.command === 'CONNECTED') {
      setState('connected')
      startHeartbeat(ws)
      subscribeTopics(ws)
      continue
    }
    if (parsed.command === 'MESSAGE') {
      const destination = parsed.headers.destination ?? ''
      const entry = recordWireLog('stomp', destination, parsed.body)
      if (entry.errors.length > 0) {
        invalidCount += entry.errors.length
        notifyMetrics()
      }
      listeners.forEach((listener) =>
        listener({
          routingKey: destination,
          envelope: entry.envelope,
          errors: entry.errors,
        }),
      )
      continue
    }
    if (parsed.command === 'ERROR') {
      const entry = recordWireLog('stomp', parsed.headers.destination, parsed.body)
      if (entry.errors.length > 0) {
        invalidCount += entry.errors.length
        notifyMetrics()
      }
      listeners.forEach((listener) =>
        listener({
          routingKey: parsed.headers.destination ?? 'ERROR',
          envelope: entry.envelope,
          errors: entry.errors,
        }),
      )
    }
  }
}

function subscribeTopics(ws: WebSocket) {
  subscriptions.forEach((topic, index) => {
    sendFrame(
      ws,
      'SUBSCRIBE',
      {
        id: `sub-${index}`,
        destination: topic,
        ack: 'auto',
      },
      '',
    )
  })
}

function sendFrame(ws: WebSocket, command: string, headers: Record<string, string>, body: string) {
  const lines = [command]
  Object.entries(headers).forEach(([key, value]) => {
    lines.push(`${key}:${value}`)
  })
  lines.push('', body)
  ws.send(`${lines.join('\n')}\u0000`)
}

function parseFrame(frame: string) {
  const [headerSection, body = ''] = frame.split('\n\n')
  const lines = headerSection.split('\n')
  const command = lines.shift()
  if (!command) {
    return null
  }
  const headers: Record<string, string> = {}
  for (const line of lines) {
    const separator = line.indexOf(':')
    if (separator > -1) {
      headers[line.slice(0, separator)] = line.slice(separator + 1)
    }
  }
  return { command, headers, body }
}

function setState(next: StompConnectionState) {
  state = next
  stateListeners.forEach((listener) => listener(state))
}

function startHeartbeat(ws: WebSocket) {
  stopHeartbeat()
  heartbeatTimer = window.setInterval(() => {
    if (ws.readyState === WebSocket.OPEN) {
      ws.send('\n')
    }
  }, 10000)
}

function stopHeartbeat() {
  if (heartbeatTimer) {
    window.clearInterval(heartbeatTimer)
    heartbeatTimer = null
  }
}

function jitter(baseDelay: number) {
  return baseDelay + Math.floor(Math.random() * 500)
}

function notifyMetrics() {
  metricsListeners.forEach((listener) => listener({ invalidCount }))
}
