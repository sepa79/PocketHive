import { Suspense, lazy, useEffect, useMemo, useRef, useState } from 'react'
import {
  clearWireLog,
  exportWireLogJsonl,
  subscribeWireLog,
  type WireLogEntry,
} from '../lib/controlPlane/wireLogStore'
import type { ControlPlaneEnvelope } from '../lib/controlPlane/types'
import { useTopBarToolbar } from '../components/TopBarContext'

const MonacoEditor = lazy(() => import('@monaco-editor/react'))

function resolveMonacoTheme() {
  return document.documentElement.dataset.theme === 'light' ? 'vs' : 'vs-dark'
}

function formatFileName() {
  const now = new Date()
  const pad = (value: number) => String(value).padStart(2, '0')
  const stamp = `${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}-${pad(
    now.getHours(),
  )}${pad(now.getMinutes())}${pad(now.getSeconds())}`
  return `wire-log-${stamp}.jsonl`
}

function downloadJsonl() {
  const payload = exportWireLogJsonl()
  const blob = new Blob([payload], { type: 'application/jsonl' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = formatFileName()
  link.click()
  URL.revokeObjectURL(url)
}

export function WireLogPage() {
  const [entries, setEntries] = useState<WireLogEntry[]>([])
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [tail, setTail] = useState(true)
  const [showFilters, setShowFilters] = useState(false)
  const [sourceFilter, setSourceFilter] = useState<'all' | 'stomp' | 'rest'>('all')
  const [kindFilter, setKindFilter] = useState<'all' | 'signal' | 'outcome' | 'metric' | 'event' | 'invalid'>('all')
  const [typeFilter, setTypeFilter] = useState('')
  const [routingFilter, setRoutingFilter] = useState('')
  const [searchFilter, setSearchFilter] = useState('')
  const [swarmFilter, setSwarmFilter] = useState('')
  const [roleFilter, setRoleFilter] = useState('')
  const [instanceFilter, setInstanceFilter] = useState('')
  const [correlationFilter, setCorrelationFilter] = useState('')
  const [errorsOnly, setErrorsOnly] = useState(false)
  const listRef = useRef<HTMLDivElement | null>(null)

  useEffect(() => {
    const unsubscribe = subscribeWireLog(setEntries)
    return () => {
      unsubscribe()
    }
  }, [])

  useEffect(() => {
    const list = listRef.current
    if (!list || !tail) return
    list.scrollTop = list.scrollHeight
  }, [entries, tail])

  useEffect(() => {
    const root = document.documentElement
    const observer = new MutationObserver(() => {
      setMonacoTheme(resolveMonacoTheme())
    })
    observer.observe(root, { attributes: true, attributeFilter: ['data-theme'] })
    return () => observer.disconnect()
  }, [])

  const newest = useMemo(() => [...entries].reverse(), [entries])
  const filtered = useMemo(
    () =>
      newest.filter((entry) => {
        if (sourceFilter !== 'all' && entry.source !== sourceFilter) return false
        const kind = entry.envelope?.kind ?? 'invalid'
        if (kindFilter !== 'all' && kind !== kindFilter) return false
        if (typeFilter && !entry.envelope?.type?.includes(typeFilter)) return false
        if (routingFilter && !(entry.routingKey ?? '').includes(routingFilter)) return false
        if (searchFilter && !matchesSearch(entry, searchFilter)) return false
        if (swarmFilter && entry.envelope?.scope?.swarmId !== swarmFilter) return false
        if (roleFilter && entry.envelope?.scope?.role !== roleFilter) return false
        if (instanceFilter && entry.envelope?.scope?.instance !== instanceFilter) return false
        if (correlationFilter && entry.envelope?.correlationId !== correlationFilter) return false
        if (errorsOnly && !isErrorEntry(entry)) return false
        return true
      }),
    [
      newest,
      sourceFilter,
      kindFilter,
      typeFilter,
      routingFilter,
      searchFilter,
      swarmFilter,
      roleFilter,
      instanceFilter,
      correlationFilter,
      errorsOnly,
    ],
  )
  const invalidCount = useMemo(
    () => entries.reduce((sum, entry) => sum + entry.errors.length, 0),
    [entries],
  )

  const errorCount = useMemo(() => entries.filter((entry) => isErrorEntry(entry)).length, [entries])
  const outcomeCount = useMemo(
    () => entries.filter((entry) => entry.envelope?.kind === 'outcome').length,
    [entries],
  )
  const alertCount = useMemo(
    () => entries.filter((entry) => entry.envelope?.kind === 'event').length,
    [entries],
  )
  const selectedEntry = useMemo(
    () => (selectedId ? entries.find((entry) => entry.id === selectedId) ?? null : null),
    [entries, selectedId],
  )
  const payloadText = selectedEntry ? prettyPayload(selectedEntry.payload) : ''
  const [monacoTheme, setMonacoTheme] = useState(resolveMonacoTheme)
  const topBarToolbar = useMemo(
    () => (
      <div className="topBarToolbar">
        <div className="wireLogStats">
          <span className="pill pillInfo">{entries.length} total</span>
          <span className="pill pillInfo">{filtered.length} filtered</span>
          <span className={invalidCount > 0 ? 'pill pillBad' : 'pill pillOk'}>
            {invalidCount > 0 ? `${invalidCount} invalid` : 'valid'}
          </span>
          <span className="pill pillInfo">{errorCount} errors</span>
          <span className="pill pillInfo">
            {alertCount}/{outcomeCount} alerts/outcomes
          </span>
        </div>
        <button type="button" className="actionButton" onClick={downloadJsonl}>
          Export JSONL
        </button>
        <button type="button" className="actionButton actionButtonDanger" onClick={clearWireLog}>
          Clear
        </button>
        <select
          className="textInput textInputCompact"
          value={sourceFilter}
          onChange={(event) => setSourceFilter(event.target.value as 'all' | 'stomp' | 'rest')}
        >
          <option value="all">All sources</option>
          <option value="stomp">STOMP</option>
          <option value="rest">REST</option>
        </select>
        <select
          className="textInput textInputCompact"
          value={kindFilter}
          onChange={(event) =>
            setKindFilter(
              event.target.value as 'all' | 'signal' | 'outcome' | 'metric' | 'event' | 'invalid',
            )
          }
        >
          <option value="all">All kinds</option>
          <option value="signal">signal</option>
          <option value="outcome">outcome</option>
          <option value="metric">metric</option>
          <option value="event">event</option>
          <option value="invalid">invalid</option>
        </select>
        <input
          className="textInput textInputCompact"
          value={searchFilter}
          onChange={(event) => setSearchFilter(event.target.value)}
          placeholder="Search routing/type/origin"
        />
        <button
          type="button"
          className={errorsOnly ? 'actionButton' : 'actionButton actionButtonGhost'}
          onClick={() => setErrorsOnly((prev) => !prev)}
        >
          Errors
        </button>
        <button
          type="button"
          className={tail ? 'actionButton' : 'actionButton actionButtonGhost'}
          onClick={() => setTail((prev) => !prev)}
        >
          {tail ? 'Freeze' : 'Follow'}
        </button>
        <button
          type="button"
          className="actionButton actionButtonGhost"
          onClick={() => setShowFilters((prev) => !prev)}
        >
          {showFilters ? 'Hide filters' : 'Filters'}
        </button>
      </div>
    ),
    [
      alertCount,
      entries.length,
      errorCount,
      errorsOnly,
      filtered.length,
      invalidCount,
      kindFilter,
      outcomeCount,
      searchFilter,
      showFilters,
      sourceFilter,
      tail,
    ],
  )

  useTopBarToolbar(topBarToolbar)

  const handleCopyPayload = async () => {
    if (!selectedEntry) return
    try {
      await navigator.clipboard.writeText(payloadText)
    } catch (error) {
      console.warn('Failed to copy wire log payload', error)
    }
  }

  return (
    <div className="page">
      {showFilters ? (
        <div className="wireLogFiltersPanel">
          <label className="field">
            <span className="fieldLabel">Type</span>
            <input
              className="textInput textInputCompact"
              value={typeFilter}
              onChange={(event) => setTypeFilter(event.target.value)}
              placeholder="status-delta"
            />
          </label>
          <label className="field">
            <span className="fieldLabel">Routing key</span>
            <input
              className="textInput textInputCompact"
              value={routingFilter}
              onChange={(event) => setRoutingFilter(event.target.value)}
              placeholder="event.metric.status"
            />
          </label>
          <label className="field">
            <span className="fieldLabel">Swarm</span>
            <input
              className="textInput textInputCompact"
              value={swarmFilter}
              onChange={(event) => setSwarmFilter(event.target.value)}
              placeholder="swarmId"
            />
          </label>
          <label className="field">
            <span className="fieldLabel">Role</span>
            <input
              className="textInput textInputCompact"
              value={roleFilter}
              onChange={(event) => setRoleFilter(event.target.value)}
              placeholder="role"
            />
          </label>
          <label className="field">
            <span className="fieldLabel">Instance</span>
            <input
              className="textInput textInputCompact"
              value={instanceFilter}
              onChange={(event) => setInstanceFilter(event.target.value)}
              placeholder="instance"
            />
          </label>
          <label className="field">
            <span className="fieldLabel">CorrelationId</span>
            <input
              className="textInput textInputCompact"
              value={correlationFilter}
              onChange={(event) => setCorrelationFilter(event.target.value)}
              placeholder="correlationId"
            />
          </label>
        </div>
      ) : null}

      {filtered.length === 0 ? (
        <div className="card" style={{ marginTop: 12 }}>
          <div className="muted">No messages captured yet.</div>
        </div>
      ) : (
        <div
          className="wireLogTable"
          ref={listRef}
          onScroll={(event) => {
            const target = event.currentTarget
            const atBottom = target.scrollTop + target.clientHeight >= target.scrollHeight - 8
            if (!atBottom && tail) {
              setTail(false)
            }
          }}
        >
          <div className="wireLogHeader">
            <div>Time</div>
            <div>Kind/Type</div>
            <div>Scope</div>
            <div>Status</div>
            <div>Origin</div>
            <div>Routing</div>
          </div>
          {filtered.map((entry) => {
            const envelope = entry.envelope
            const kind = envelope?.kind ?? 'invalid'
            const type = envelope?.type ?? ''
            const scope = envelope?.scope
            const status = pickStatus(envelope, entry)
            const origin = envelope?.origin ?? 'n/a'
            const selected = selectedId === entry.id
            return (
              <div key={entry.id} className="wireLogRowGroup">
                <button
                  type="button"
                  className={`wireLogRow ${selected ? 'wireLogRowSelected' : ''}`}
                  onClick={() => setSelectedId(selected ? null : entry.id)}
                >
                  <span className="wireLogTime">{entry.receivedAt}</span>
                  <span className={`chip chip-${kind}`}>
                    {kind}
                    {type ? `/${type}` : ''}
                  </span>
                  <span className="wireLogScope">
                    {scope ? `${scope.swarmId}/${scope.role}/${scope.instance}` : 'n/a'}
                  </span>
                  <span className={entry.errors.length > 0 ? 'wireLogStatus bad' : 'wireLogStatus'}>
                    {status}
                  </span>
                  <span className="wireLogOrigin">{origin}</span>
                  <span className="wireLogRouting">{entry.routingKey ?? 'n/a'}</span>
                  {envelope?.correlationId ? (
                    <span
                      className="wireLogCorrelation"
                      style={{ background: colorForCorrelation(envelope.correlationId) }}
                    />
                  ) : null}
                </button>
              </div>
            )
          })}
        </div>
      )}

      {selectedEntry ? (
        <div className="modalBackdrop" onClick={() => setSelectedId(null)}>
          <div
            className="modal"
            onClick={(event) => {
              event.stopPropagation()
            }}
          >
            <div className="modalHeader">
              <div>
                <div className="h2">Wire Log entry</div>
                <div className="muted">
                  {selectedEntry.receivedAt} · {selectedEntry.routingKey ?? 'n/a'}
                </div>
              </div>
              <div className="row">
                <button
                  type="button"
                  className="iconButton iconButtonBare"
                  title="Copy payload"
                  aria-label="Copy payload"
                  onClick={handleCopyPayload}
                >
                  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                    <rect x="9" y="9" width="11" height="11" rx="2" stroke="currentColor" strokeWidth="1.6" />
                    <rect x="4" y="4" width="11" height="11" rx="2" stroke="currentColor" strokeWidth="1.6" />
                  </svg>
                </button>
                <button type="button" className="actionButton" onClick={() => setSelectedId(null)}>
                  Close
                </button>
              </div>
            </div>
            {selectedEntry.errors.length > 0 ? (
              <div className="modalSection">
                <div className="h2">Validation errors</div>
                <ul className="list">
                  {selectedEntry.errors.map((error, index) => (
                    <li key={`${selectedEntry.id}-error-${index}`}>
                      {error.errorCode}: {error.message}
                    </li>
                  ))}
                </ul>
              </div>
            ) : null}
            <div className="modalSection">
              <div className="h2">Payload</div>
              <Suspense
                fallback={
                  <div className="monacoFallback">
                    <div className="muted">Loading editor…</div>
                  </div>
                }
              >
                <MonacoEditor
                  height="min(60vh, 520px)"
                  language="json"
                  value={payloadText}
                  theme={monacoTheme}
                  options={{
                    readOnly: true,
                    minimap: { enabled: false },
                    fontSize: 12,
                    lineNumbers: 'off',
                    scrollBeyondLastLine: false,
                    wordWrap: 'on',
                  }}
                  className="monacoSurface"
                />
              </Suspense>
            </div>
          </div>
        </div>
      ) : null}
    </div>
  )
}

function isErrorEntry(entry: WireLogEntry) {
  if (entry.errors.length > 0) {
    return true
  }
  const envelope = entry.envelope
  if (!envelope || typeof envelope.data !== 'object' || envelope.data === null) {
    return false
  }
  if (envelope.kind === 'event') {
    const level = (envelope.data as Record<string, unknown>).level
    return typeof level === 'string' && level.toLowerCase() === 'error'
  }
  if (envelope.kind === 'outcome') {
    const status = (envelope.data as Record<string, unknown>).status
    return typeof status === 'string' && status.toUpperCase() === 'FAILED'
  }
  return false
}

function pickStatus(envelope: ControlPlaneEnvelope | undefined, entry: WireLogEntry) {
  if (entry.errors.length > 0) {
    return 'invalid'
  }
  if (!envelope || typeof envelope.data !== 'object' || envelope.data === null) {
    return ''
  }
  const data = envelope.data as Record<string, unknown>
  if (envelope.kind === 'outcome' && typeof data.status === 'string') {
    return data.status
  }
  if (envelope.kind === 'event' && typeof data.level === 'string') {
    return data.level
  }
  if (envelope.kind === 'metric' && typeof data.enabled === 'boolean') {
    return data.enabled ? 'enabled' : 'disabled'
  }
  return ''
}

function matchesSearch(entry: WireLogEntry, search: string) {
  const term = search.trim().toLowerCase()
  if (!term) return true
  const envelope = entry.envelope
  const pieces = [
    entry.routingKey ?? '',
    envelope?.kind ?? '',
    envelope?.type ?? '',
    envelope?.origin ?? '',
    envelope?.correlationId ?? '',
  ]
  if (envelope?.scope) {
    pieces.push(envelope.scope.swarmId, envelope.scope.role, envelope.scope.instance)
  }
  if (envelope?.data && typeof envelope.data === 'object') {
    const data = envelope.data as Record<string, unknown>
    if (typeof data.message === 'string') pieces.push(data.message)
  }
  return pieces.join(' ').toLowerCase().includes(term)
}

function prettyPayload(payload: string) {
  try {
    return JSON.stringify(JSON.parse(payload), null, 2)
  } catch {
    return payload
  }
}

function colorForCorrelation(value: string) {
  let hash = 0
  for (let i = 0; i < value.length; i += 1) {
    hash = (hash * 31 + value.charCodeAt(i)) % 360
  }
  return `hsl(${hash} 70% 55% / 0.6)`
}
