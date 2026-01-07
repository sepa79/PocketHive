import type { ControlPlaneEnvelope } from './types'

type ScopeKey = string

type StatusSnapshot = {
  envelope: ControlPlaneEnvelope
  lastUpdatedAt: string
}

type StatusListener = (entries: StatusSnapshot[]) => void

const snapshots = new Map<ScopeKey, StatusSnapshot>()
const listeners = new Set<StatusListener>()
const RETENTION_MS = 30 * 60 * 1000

export function subscribeStatusSnapshots(listener: StatusListener) {
  listeners.add(listener)
  listener(Array.from(snapshots.values()))
  return () => listeners.delete(listener)
}

export function applyStatusEnvelope(envelope: ControlPlaneEnvelope) {
  if (envelope.kind !== 'metric') {
    return
  }
  if (envelope.type !== 'status-full' && envelope.type !== 'status-delta') {
    return
  }
  const key = scopeKey(envelope)
  const existing = snapshots.get(key)
  if (envelope.type === 'status-full') {
    snapshots.set(key, { envelope, lastUpdatedAt: envelope.timestamp })
    notify()
    return
  }
  if (!existing) {
    return
  }
  const merged = mergeDelta(existing.envelope, envelope)
  if (!merged) {
    return
  }
  snapshots.set(key, { envelope: merged, lastUpdatedAt: envelope.timestamp })
  notify()
}

export function requestEviction() {
  const now = Date.now()
  let changed = false
  for (const [key, snapshot] of snapshots.entries()) {
    const age = now - Date.parse(snapshot.lastUpdatedAt)
    if (Number.isFinite(age) && age > RETENTION_MS) {
      snapshots.delete(key)
      changed = true
    }
  }
  if (changed) {
    notify()
  }
}

export function hasStatusSnapshot(scope: { swarmId: string; role: string; instance: string }) {
  return snapshots.has(scopeKeyFromScope(scope))
}

function mergeDelta(full: ControlPlaneEnvelope, delta: ControlPlaneEnvelope) {
  if (!delta.data || typeof delta.data !== 'object') {
    return null
  }
  if (containsFullOnlyFields(delta.data)) {
    return null
  }
  return {
    ...full,
    timestamp: delta.timestamp,
    data: {
      ...full.data,
      ...delta.data,
    },
  }
}

function containsFullOnlyFields(data: Record<string, unknown>) {
  return 'config' in data || 'io' in data || 'startedAt' in data
}

function scopeKey(envelope: { scope: { swarmId: string; role: string; instance: string } }) {
  return scopeKeyFromScope(envelope.scope)
}

function scopeKeyFromScope(scope: { swarmId: string; role: string; instance: string }) {
  return `${scope.swarmId}:${scope.role}:${scope.instance}`
}

function notify() {
  const items = Array.from(snapshots.values())
  listeners.forEach((listener) => listener(items))
}
