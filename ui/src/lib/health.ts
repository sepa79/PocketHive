import type { Component, QueueInfo, HealthStatus } from '../types/hive'

export const thresholds = {
  heartbeatWarn: 10,
  heartbeatAlert: 30,
  depthWarn: 100,
  depthAlert: 1000,
  oldestWarn: 60,
  oldestAlert: 300,
}

export function heartbeatHealth(lastHeartbeat: number, now = Date.now()): HealthStatus {
  const age = (now - lastHeartbeat) / 1000
  if (age > thresholds.heartbeatAlert) return 'ALERT'
  if (age > thresholds.heartbeatWarn) return 'WARN'
  return 'OK'
}

export function queueHealth(q: QueueInfo): HealthStatus {
  if (q.depth != null) {
    if (q.depth > thresholds.depthAlert) return 'ALERT'
    if (q.depth > thresholds.depthWarn) return 'WARN'
  }
  if (q.oldestAgeSec != null) {
    if (q.oldestAgeSec > thresholds.oldestAlert) return 'ALERT'
    if (q.oldestAgeSec > thresholds.oldestWarn) return 'WARN'
  }
  return 'OK'
}

export function componentHealth(c: Component, now = Date.now()): HealthStatus {
  let h: HealthStatus = heartbeatHealth(c.lastHeartbeat, now)
  for (const q of c.queues) {
    h = combine(h, queueHealth(q))
    if (h === 'ALERT') break
  }
  return h
}

export function colorForHealth(h: HealthStatus) {
  switch (h) {
    case 'WARN':
      return 'bg-yellow-500'
    case 'ALERT':
      return 'bg-red-500'
    default:
      return 'bg-green-500'
  }
}

function combine(a: HealthStatus, b: HealthStatus): HealthStatus {
  if (a === 'ALERT' || b === 'ALERT') return 'ALERT'
  if (a === 'WARN' || b === 'WARN') return 'WARN'
  return 'OK'
}

