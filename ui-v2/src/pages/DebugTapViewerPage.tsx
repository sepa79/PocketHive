import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link, useParams } from 'react-router-dom'

type DebugTapSample = {
  id: string
  receivedAt: string
  sizeBytes: number
  payload: string
}

type DebugTap = {
  tapId: string
  swarmId: string
  role: string
  direction: 'IN' | 'OUT'
  ioName: string
  exchange: string
  routingKey: string
  queue: string
  maxItems: number
  ttlSeconds: number
  createdAt: string
  lastReadAt: string
  samples: DebugTapSample[]
}

type TapMeta = Omit<DebugTap, 'samples' | 'lastReadAt'>

const ORCHESTRATOR_BASE = '/orchestrator/api'

function compactOneLine(value: string, maxLen: number): string {
  const single = value.replace(/\s+/g, ' ').trim()
  if (single.length <= maxLen) return single
  return `${single.slice(0, Math.max(0, maxLen - 1))}…`
}

async function readErrorMessage(response: Response): Promise<string> {
  try {
    const contentType = response.headers.get('content-type') ?? ''
    if (contentType.includes('application/json')) {
      const payload = (await response.json()) as unknown
      if (payload && typeof payload === 'object' && 'message' in payload) {
        const maybeMessage = (payload as Record<string, unknown>).message
        if (typeof maybeMessage === 'string' && maybeMessage.trim().length > 0) {
          return maybeMessage.trim()
        }
      }
      return JSON.stringify(payload)
    }
    const text = await response.text()
    return text.trim().length > 0 ? text.trim() : `${response.status} ${response.statusText}`
  } catch {
    return `${response.status} ${response.statusText}`
  }
}

function tryPrettyJson(value: string): string | null {
  const trimmed = value.trim()
  if (!trimmed) return null
  if (!(trimmed.startsWith('{') || trimmed.startsWith('['))) return null
  try {
    const parsed = JSON.parse(trimmed)
    return JSON.stringify(parsed, null, 2)
  } catch {
    return null
  }
}

type WorkItemEnvelope = {
  version?: string
  headers?: Record<string, unknown>
  messageId?: string
  contentType?: string
  observability?: Record<string, unknown>
  steps?: Array<{
    index?: number
    payload?: string
    payloadEncoding?: string
    headers?: Record<string, unknown>
  }>
}

function parseWorkItemEnvelope(payload: string): WorkItemEnvelope | null {
  const trimmed = payload.trim()
  if (!trimmed.startsWith('{')) return null
  try {
    const parsed = JSON.parse(trimmed) as unknown
    if (!parsed || typeof parsed !== 'object') return null
    return parsed as WorkItemEnvelope
  } catch {
    return null
  }
}

function safeStringify(value: unknown): string {
  try {
    return JSON.stringify(value, null, 2)
  } catch {
    return String(value)
  }
}

function metaFromTap(tap: DebugTap): TapMeta {
  const { samples: _samples, lastReadAt: _lastReadAt, ...meta } = tap
  return meta
}

function metaSignature(meta: TapMeta | null): string {
  if (!meta) return ''
  return [
    meta.tapId,
    meta.swarmId,
    meta.role,
    meta.direction,
    meta.ioName,
    meta.exchange,
    meta.routingKey,
    meta.queue,
    meta.maxItems,
    meta.ttlSeconds,
    meta.createdAt,
  ].join('|')
}

function mergeSamples(existing: DebugTapSample[], incoming: DebugTapSample[], limit: number): DebugTapSample[] {
  if (!incoming.length) return existing
  if (!existing.length) return incoming.slice(-limit)
  const seen = new Set(existing.map((s) => s.id))
  const appended: DebugTapSample[] = []
  for (const sample of incoming) {
    if (!seen.has(sample.id)) appended.push(sample)
  }
  if (!appended.length) return existing
  return [...existing, ...appended].slice(-limit)
}

export function DebugTapViewerPage() {
  const { tapId: tapIdParam } = useParams<{ tapId: string }>()
  const tapId = (tapIdParam ?? '').trim()

  const [meta, setMeta] = useState<TapMeta | null>(null)
  const [samples, setSamples] = useState<DebugTapSample[]>([])
  const [activeSampleId, setActiveSampleId] = useState<string | null>(null)

  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [status, setStatus] = useState<{ kind: 'ok' | 'err'; text: string; title?: string } | null>(null)
  const [autoRefresh, setAutoRefresh] = useState(false)
  const [envelopeOpen, setEnvelopeOpen] = useState(true)

  const [fetchCount, setFetchCount] = useState<number>(10)

  const lastMetaSigRef = useRef<string>('')
  const samplesByIdRef = useRef<Map<string, DebugTapSample>>(new Map())
  const isClosedRef = useRef(false)
  const inFlightRef = useRef(false)

  const fetchTap = useCallback(
    async (drain: number): Promise<{ tap: DebugTap; httpStatus: number }> => {
      if (!tapId) throw new Error('Missing tapId.')
      const response = await fetch(
        `${ORCHESTRATOR_BASE}/debug/taps/${encodeURIComponent(tapId)}?drain=${encodeURIComponent(String(drain))}`,
        { headers: { Accept: 'application/json' } },
      )
      const httpStatus = response.status
      if (!response.ok) throw new Error(await readErrorMessage(response))
      return { tap: (await response.json()) as DebugTap, httpStatus }
    },
    [tapId],
  )

  const applyTap = useCallback((tap: DebugTap): number => {
    let addedSamples = 0

    const nextMeta = metaFromTap(tap)
    const nextMetaSig = metaSignature(nextMeta)
    if (nextMetaSig && nextMetaSig !== lastMetaSigRef.current) {
      lastMetaSigRef.current = nextMetaSig
      setMeta(nextMeta)
    }

    const limit = Math.max(1, Math.min(200, nextMeta.maxItems || 10))
    if (tap.samples && tap.samples.length) {
      // Use a ref map to avoid churn when server re-sends the full sample list.
      let changed = false
      for (const s of tap.samples) {
        if (!samplesByIdRef.current.has(s.id)) {
          samplesByIdRef.current.set(s.id, s)
          addedSamples++
          changed = true
        }
      }
      if (changed) {
        setSamples((prev) => mergeSamples(prev, tap.samples, limit))
      }
    }
    return addedSamples
  }, [])

  const refresh = useCallback(
    async (drain: number) => {
      if (!tapId) return
      if (isClosedRef.current) return
      if (inFlightRef.current) return
      inFlightRef.current = true
      setBusy(true)
      setError(null)
      try {
        const { tap, httpStatus } = await fetchTap(drain)
        const added = applyTap(tap)
        setStatus({
          kind: 'ok',
          text: `OK ${httpStatus} · drain ${drain} · +${added} · samples ${tap.samples?.length ?? 0}/${tap.maxItems} · lastRead ${tap.lastReadAt}`,
        })
      } catch (err) {
        const message = err instanceof Error ? err.message : 'Failed to read tap.'
        setError(message)
        setStatus({ kind: 'err', text: `ERR · ${compactOneLine(message, 160)}`, title: message })
      } finally {
        setBusy(false)
        inFlightRef.current = false
      }
    },
    [applyTap, fetchTap, tapId],
  )

  const closeTap = useCallback(async () => {
    if (!tapId) return
    if (inFlightRef.current) return
    inFlightRef.current = true
    setBusy(true)
    setError(null)
    try {
      const response = await fetch(`${ORCHESTRATOR_BASE}/debug/taps/${encodeURIComponent(tapId)}`, { method: 'DELETE' })
      if (!response.ok) throw new Error(await readErrorMessage(response))
      isClosedRef.current = true
      const closed = (await response.json()) as DebugTap
      const closedMeta = metaFromTap(closed)
      setMeta(closedMeta)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to close tap.')
    } finally {
      setBusy(false)
      inFlightRef.current = false
    }
  }, [tapId])

  useEffect(() => {
    if (!tapId) {
      setError('Missing tapId.')
      setMeta(null)
      setSamples([])
      setActiveSampleId(null)
      return
    }
    isClosedRef.current = false
    lastMetaSigRef.current = ''
    samplesByIdRef.current.clear()
    setMeta(null)
    setSamples([])
    setActiveSampleId(null)
    void refresh(0)
  }, [refresh, tapId])

  useEffect(() => {
    if (activeSampleId) return
    if (!samples.length) return
    setActiveSampleId(samples[samples.length - 1]?.id ?? null)
  }, [activeSampleId, samples])

  useEffect(() => {
    if (!autoRefresh) return
    if (!tapId) return
    if (isClosedRef.current) return
    const timer = window.setInterval(() => {
      if (inFlightRef.current) return
      // quiet refresh: no `busy` state toggling here, only applyTap if meaningful changes arrive
      void fetchTap(1)
        .then(({ tap, httpStatus }) => {
          const added = applyTap(tap)
          if (!added) return
          setStatus({
            kind: 'ok',
            text: `OK ${httpStatus} · drain 1 · +${added} · samples ${tap.samples?.length ?? 0}/${tap.maxItems} · lastRead ${tap.lastReadAt}`,
          })
        })
        .catch(() => {
          // ignore (no flicker)
        })
    }, 1200)
    return () => window.clearInterval(timer)
  }, [applyTap, autoRefresh, fetchTap, tapId])

  const activeSample = useMemo(() => {
    if (!activeSampleId) return null
    return samples.find((s) => s.id === activeSampleId) ?? null
  }, [activeSampleId, samples])

  const activeEnvelope = useMemo(() => {
    if (!activeSample) return null
    return parseWorkItemEnvelope(activeSample.payload)
  }, [activeSample])

  const rawPayload = useMemo(() => {
    if (!activeSample) return null
    return tryPrettyJson(activeSample.payload) ?? activeSample.payload
  }, [activeSample])

  if (!tapId) {
    return (
      <div className="page">
        <div className="card">Missing tapId.</div>
      </div>
    )
  }

  return (
    <div className="page">
      <div className="pageHeader">
        <div>
          <div className="h2">Debug tap</div>
          <div className="muted">
            tapId <code>{tapId}</code>
            {meta ? (
              <>
                {' '}
                · swarm <code>{meta.swarmId}</code> · role <code>{meta.role}</code> · {meta.direction} <code>{meta.ioName}</code>
              </>
            ) : null}
          </div>
        </div>
        <div className="row" style={{ gap: 10, alignItems: 'center' }}>
          <Link className="actionButton actionButtonGhost" to={meta?.swarmId ? `/hive/${encodeURIComponent(meta.swarmId)}/view` : '/hive'}>
            Back
          </Link>
          <span className="spinnerSlot" aria-hidden="true" title={busy ? 'Working…' : undefined}>
            <span className={busy ? 'spinner' : 'spinner spinnerHidden'} />
          </span>
          <button
            type="button"
            className="actionButton actionButtonGhost"
            disabled={busy || isClosedRef.current}
            title="Best-effort: deletes the tap queue and stops capturing."
            onClick={() => void closeTap()}
          >
            <span className="actionButtonContent">
              <span>Close</span>
            </span>
          </button>
        </div>
      </div>

      {meta ? (
        <div className="card tapBanner" style={{ marginTop: 12 }}>
          exchange <code>{meta.exchange}</code> · routingKey <code>{meta.routingKey}</code> · queue <code>{meta.queue}</code>
        </div>
      ) : null}

      <div
        className="card swarmMessage"
        style={{
          marginTop: 12,
          minHeight: 44,
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          gap: 12,
        }}
        title={status?.title}
        aria-live="polite"
      >
        {error ? (
          <span style={{ flex: '1 1 auto', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
            {compactOneLine(error, 240)}
          </span>
        ) : (
          <span className="muted" style={{ flex: '1 1 auto', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
            {status?.text ?? 'OK · ready'}
          </span>
        )}
        <span className="muted" style={{ flex: '0 0 auto' }}>
          {isClosedRef.current ? 'closed' : ''}
        </span>
      </div>

      <div className="row between" style={{ marginTop: 12, gap: 12, flexWrap: 'wrap' }}>
        <div className="row" style={{ gap: 10, alignItems: 'center' }}>
          <button
            type="button"
            className="actionButton"
            disabled={busy || isClosedRef.current}
            title="Reads tap metadata (no consume)."
            onClick={() => void refresh(0)}
          >
            <span className="actionButtonContent">
              <span>Refresh</span>
            </span>
          </button>
          <label className="row" style={{ gap: 8, alignItems: 'center' }} title="How many messages to consume from the tap queue.">
            <span className="muted">Pull</span>
            <input
              className="textInput"
              style={{ width: 84 }}
              type="number"
              min={1}
              value={fetchCount}
              onChange={(e) => {
                if (e.target.value === '') return
                const value = e.target.valueAsNumber
                if (!Number.isFinite(value)) return
                const next = Math.max(1, Math.floor(value))
                const cap = meta?.maxItems ?? null
                setFetchCount(cap ? Math.min(next, cap) : next)
              }}
            />
          </label>
          <button
            type="button"
            className="actionButton"
            disabled={busy || isClosedRef.current}
            title="Consumes up to N messages from the tap queue and appends them to the sample list."
            onClick={() => {
              const cap = meta?.maxItems ?? null
              const drain = cap ? Math.min(Math.max(1, fetchCount), cap) : Math.max(1, fetchCount)
              void refresh(drain)
            }}
          >
            <span className="actionButtonContent">
              <span>Pull now</span>
            </span>
          </button>
          <label className="row" style={{ gap: 8, alignItems: 'center' }} title="Polls metadata quietly. Only updates UI when new samples arrive.">
            <input type="checkbox" checked={autoRefresh} onChange={(e) => setAutoRefresh(e.target.checked)} />
            <span className="muted">Auto refresh</span>
          </label>
        </div>
        <div className="muted">
          samples <code>{samples.length}</code>
          {isClosedRef.current ? ' · closed' : ''}
        </div>
      </div>

      <div className="debugTapViewerGrid" style={{ marginTop: 12 }}>
        <div className="card debugTapViewerPanel">
          <div className="row between" style={{ marginBottom: 8 }}>
            <div className="h3">Samples</div>
            <div className="muted">{samples.length ? `${samples.length}` : '—'}</div>
          </div>
          <div className="debugTapSampleList">
            {samples.length ? (
              samples.map((s) => {
                const isSelected = s.id === activeSampleId
                return (
                  <button
                    key={s.id}
                    type="button"
                    className={isSelected ? 'debugTapSampleRow debugTapSampleRowSelected' : 'debugTapSampleRow'}
                    onClick={() => setActiveSampleId(s.id)}
                    title="Click to inspect."
                  >
                    <div className="row between" style={{ gap: 10 }}>
                      <div className="muted">{s.receivedAt}</div>
                      <div className="muted">{s.sizeBytes}B</div>
                    </div>
                  </button>
                )
              })
            ) : (
              <div className="muted">No samples yet. Use “Pull now”.</div>
            )}
          </div>
        </div>

        <div className="card debugTapViewerPanel">
          <div className="row between" style={{ marginBottom: 8 }}>
            <div className="h3">Details</div>
            <div className="muted">{activeEnvelope ? 'work-item' : activeSample ? 'message' : '—'}</div>
          </div>
          {activeSample ? (
            <div className="debugTapParsed">
              {activeEnvelope ? (
                <>
                  <div className="tapMeta" style={{ marginBottom: 2 }}>
                    <div className="muted">
                      messageId <code>{activeEnvelope.messageId ?? '—'}</code> · contentType <code>{activeEnvelope.contentType ?? '—'}</code>
                    </div>
                  </div>

                  <details
                    className="tapDisclosure"
                    open={envelopeOpen}
                    onToggle={(e) => setEnvelopeOpen((e.currentTarget as HTMLDetailsElement).open)}
                  >
                    <summary>
                      <span className="tapDisclosureSummary">
                        <span className="tapDisclosureChevron">▸</span>
                        <span className="tapDisclosureTitle">Envelope</span>
                      </span>
                      <span className="tapDisclosureMeta">headers + observability</span>
                    </summary>
                    <div className="tapDisclosureBody">
                      <div>
                        <div className="muted" style={{ marginBottom: 6 }}>
                          envelope.headers
                        </div>
                        <pre className="codePre tapPayload">{safeStringify(activeEnvelope.headers ?? {})}</pre>
                      </div>
                      <div>
                        <div className="muted" style={{ marginBottom: 6 }}>
                          envelope.observability
                        </div>
                        <pre className="codePre tapPayload">{safeStringify(activeEnvelope.observability ?? {})}</pre>
                      </div>
                    </div>
                  </details>

                  <div className="muted" style={{ marginTop: 2 }}>
                    steps
                  </div>
                  <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
                    {(activeEnvelope.steps ?? []).length ? (
                      (activeEnvelope.steps ?? []).map((step, idx) => {
                        const pretty = typeof step.payload === 'string' ? tryPrettyJson(step.payload) : null
                        const stepIndex = step.index ?? idx
                        const service = (step.headers?.['ph.step.service'] as string) ?? '—'
                        const instance = (step.headers?.['ph.step.instance'] as string) ?? '—'
                        const encoding = step.payloadEncoding ?? 'utf-8'
                        const payload = pretty ?? (step.payload ?? '')
                        const payloadBytes = typeof payload === 'string' ? payload.length : 0
                        return (
                          <details key={String(stepIndex)} className="tapDisclosure">
                            <summary>
                              <span className="tapDisclosureSummary">
                                <span className="tapDisclosureChevron">▸</span>
                                <span className="tapDisclosureTitle">
                                  step <code>{stepIndex}</code>
                                </span>
                              </span>
                              <span className="tapDisclosureMeta">
                                {service} · {instance} · {encoding} · {payloadBytes} chars
                              </span>
                            </summary>
                            <div className="tapDisclosureBody">
                              <div>
                                <div className="muted" style={{ marginBottom: 6 }}>
                                  step.headers
                                </div>
                                <pre className="codePre tapPayload">{safeStringify(step.headers ?? {})}</pre>
                              </div>
                              <div>
                                <div className="muted" style={{ marginBottom: 6 }}>
                                  step.payload
                                </div>
                                <pre className="codePre tapPayload">{payload}</pre>
                              </div>
                            </div>
                          </details>
                        )
                      })
                    ) : (
                      <div className="muted">No steps.</div>
                    )}
                  </div>
                </>
              ) : (
                <div className="muted">Selected payload is not a JSON WorkItem envelope.</div>
              )}

              <details className="tapDisclosure">
                <summary>
                  <span className="tapDisclosureSummary">
                    <span className="tapDisclosureChevron">▸</span>
                    <span className="tapDisclosureTitle">Raw</span>
                  </span>
                  <span className="tapDisclosureMeta">{activeSample.sizeBytes}B</span>
                </summary>
                <div className="tapDisclosureBody">
                  {rawPayload ? <pre className="codePre tapPayload">{rawPayload}</pre> : <div className="muted">No payload.</div>}
                </div>
              </details>
            </div>
          ) : (
            <div className="muted">Select a sample to inspect.</div>
          )}
        </div>
      </div>
    </div>
  )
}
