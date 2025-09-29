import { useUIStore } from '@ph/shell'

export type LogSource = 'hive' | 'ui'
export type LogChannel = 'stomp' | 'rest' | 'internal'
export type LogType = 'in' | 'out' | 'other' | 'error'

export interface LogEntry {
  ts: number
  destination: string
  body: string
  type: LogType
  source: LogSource
  channel: LogChannel
  correlationId?: string
}

type Listener = (logs: LogEntry[]) => void

const logs: LogEntry[] = []
let listeners: Listener[] = []

function addLog(entry: LogEntry) {
  logs.push(entry)
  const maxLogs = useUIStore.getState().messageLimit
  if (logs.length > maxLogs) {
    logs.splice(0, logs.length - maxLogs)
  }
  listeners.forEach((l) => l([...logs]))
}

export function logIn(
  destination: string,
  body: string,
  source: LogSource,
  channel: LogChannel,
  correlationId?: string,
) {
  addLog({ ts: Date.now(), destination, body, type: 'in', source, channel, correlationId })
}

export function logOut(
  destination: string,
  body: string,
  source: LogSource,
  channel: LogChannel,
  correlationId?: string,
) {
  addLog({ ts: Date.now(), destination, body, type: 'out', source, channel, correlationId })
}

export function logOther(
  message: string,
  source: LogSource = 'ui',
  channel: LogChannel = 'internal',
  correlationId?: string,
) {
  addLog({ ts: Date.now(), destination: '', body: message, type: 'other', source, channel, correlationId })
}

export function logError(
  destination: string,
  body: string,
  source: LogSource,
  channel: LogChannel,
  correlationId?: string,
) {
  addLog({ ts: Date.now(), destination, body, type: 'error', source, channel, correlationId })
}

export function subscribeLogs(fn: Listener) {
  listeners.push(fn)
  fn([...logs])
  return () => {
    listeners = listeners.filter((l) => l !== fn)
  }
}

export function resetLogs() {
  logs.splice(0, logs.length)
  listeners.forEach((l) => l([]))
}
