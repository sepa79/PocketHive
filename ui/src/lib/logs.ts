import { useUIStore } from '../store'

export type LogEntry = {
  ts: number
  destination: string
  body: string
}

type LogType = 'in' | 'out' | 'other'

type Listener = (logs: LogEntry[]) => void

const logs: Record<LogType, LogEntry[]> = {
  in: [],
  out: [],
  other: [],
}

const listeners: Record<LogType, Listener[]> = {
  in: [],
  out: [],
  other: [],
}

function addLog(type: LogType, entry: LogEntry) {
  const arr = logs[type]
  arr.push(entry)
  const maxLogs = useUIStore.getState().messageLimit
  if (arr.length > maxLogs) {
    arr.splice(0, arr.length - maxLogs)
  }
  listeners[type].forEach((l) => l([...arr]))
}

export function logIn(destination: string, body: string) {
  addLog('in', { ts: Date.now(), destination, body })
}

export function logOut(destination: string, body: string) {
  addLog('out', { ts: Date.now(), destination, body })
}

export function logOther(message: string) {
  addLog('other', { ts: Date.now(), destination: '', body: message })
}

export function subscribeLogs(type: LogType, fn: Listener) {
  listeners[type].push(fn)
  fn([...logs[type]])
  return () => {
    listeners[type] = listeners[type].filter((l) => l !== fn)
  }
}
