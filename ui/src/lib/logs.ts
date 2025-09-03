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

const MAX_LOGS = 200

function addLog(type: LogType, entry: LogEntry) {
  const arr = logs[type]
  arr.push(entry)
  if (arr.length > MAX_LOGS) arr.shift()
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
