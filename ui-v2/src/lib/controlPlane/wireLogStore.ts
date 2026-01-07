import { decodeControlPlaneEnvelope } from './decoder'
import type {
  ControlPlaneDecoderError,
  ControlPlaneEnvelope,
  ControlPlaneSource,
} from './types'

const MAX_ENTRIES = 5000
const MAX_BYTES = 10 * 1024 * 1024
const encoder = new TextEncoder()

export type WireLogEntry = {
  id: string
  receivedAt: string
  source: ControlPlaneSource
  routingKey?: string
  payload: string
  envelope?: ControlPlaneEnvelope
  errors: ControlPlaneDecoderError[]
}

type WireLogListener = (entries: WireLogEntry[]) => void

let entries: WireLogEntry[] = []
let totalBytes = 0
const listeners = new Set<WireLogListener>()

export function subscribeWireLog(listener: WireLogListener) {
  listeners.add(listener)
  listener(entries)
  return () => listeners.delete(listener)
}

export function getWireLogEntries() {
  return entries
}

export function clearWireLog() {
  entries = []
  totalBytes = 0
  notify()
}

export function recordWireLog(source: ControlPlaneSource, routingKey: string | undefined, payload: string) {
  const decoded = decodeControlPlaneEnvelope({ source, routingKey, payload })
  const receivedAt = decoded.ok ? decoded.envelope.timestamp : decoded.error.receivedAt
  const entry: WireLogEntry = {
    id: crypto.randomUUID(),
    receivedAt,
    source,
    routingKey,
    payload,
    envelope: decoded.ok ? decoded.envelope : undefined,
    errors: decoded.ok ? [] : [decoded.error],
  }
  appendEntry(entry)
  return entry
}

export function exportWireLogJsonl() {
  return entries
    .map((entry) =>
      JSON.stringify({
        receivedAt: entry.receivedAt,
        source: entry.source,
        routingKey: entry.routingKey,
        payload: entry.payload,
        envelope: entry.envelope ?? null,
        errors: entry.errors,
      }),
    )
    .join('\n')
}

function appendEntry(entry: WireLogEntry) {
  const entryBytes = measureEntry(entry)
  entries = [...entries, entry]
  totalBytes += entryBytes
  trimEntries()
  notify()
}

function trimEntries() {
  while (entries.length > MAX_ENTRIES || totalBytes > MAX_BYTES) {
    const removed = entries.shift()
    if (removed) {
      totalBytes -= measureEntry(removed)
    }
  }
}

function measureEntry(entry: WireLogEntry) {
  const payloadBytes = encoder.encode(entry.payload).length
  const envelopeBytes = entry.envelope ? encoder.encode(JSON.stringify(entry.envelope)).length : 0
  const errorBytes = entry.errors.length
    ? encoder.encode(JSON.stringify(entry.errors)).length
    : 0
  return payloadBytes + envelopeBytes + errorBytes
}

function notify() {
  listeners.forEach((listener) => listener(entries))
}
