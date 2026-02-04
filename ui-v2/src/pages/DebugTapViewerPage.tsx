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

const ORCHESTRATOR_BASE = '/orchestrator/api'

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
  steps?: Array<{
    index?: number
    payload?: string
    payloadEncoding?: string
    headers?: Record<string, unknown>
  }>
  observability?: Record<string, unknown>
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

export function DebugTapViewerPage() {
  const { tapId: tapIdParam } = useParams<{ tapId: string }>()
  const tapId = (tapIdParam ?? '').trim()

  const [tap, setTap] = useState<DebugTap | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const [captureRunning, setCaptureRunning] = useState(false)
  const [captureCount, setCaptureCount] = useState<number>(5)
  const captureAbortRef = useRef(false)
  const activeSampleId = useRef<string | null>(null)
  const [, forceRerender] = useState(0)

  const activeSample = useMemo(() => {
    if (!tap) return null
    const id = activeSampleId.current
    if (!id) return null
    return tap.samples.find((s) => s.id === id) ?? null
  }, [tap])

  const activeEnvelope = useMemo(() => {
    if (!activeSample) return null
    return parseWorkItemEnvelope(activeSample.payload)
  }, [activeSample])

  const fetchTap = useCallback(
    async (drain: number): Promise<DebugTap> => {
      if (!tapId) throw new Error('Missing tapId.')
      const response = await fetch(
        `${ORCHESTRATOR_BASE}/debug/taps/${encodeURIComponent(tapId)}?drain=${encodeURIComponent(String(drain))}`,
        { headers: { Accept: 'application/json' } },
      )
      if (!response.ok) throw new Error(await readErrorMessage(response))
      return (await response.json()) as DebugTap
    },
    [tapId],
  )

  const refreshTap = useCallback(
    async (drain: number) => {
      if (!tapId) return
      if (busy) return
      setBusy(true)
      setError(null)
      try {
        const updated = await fetchTap(drain)
        setTap(updated)
        if (!activeSampleId.current && updated.samples.length) {
          activeSampleId.current = updated.samples[updated.samples.length - 1]?.id ?? null
          forceRerender((v) => v + 1)
        }
      } catch (err) {
        setError(err instanceof Error ? err.message : 'Failed to read tap.')
      } finally {
        setBusy(false)
      }
    },
    [busy, fetchTap, tapId],
  )

  useEffect(() => {
    if (!tapId) {
      setTap(null)
      setError('Missing tapId.')
      return
    }
    void refreshTap(0)
  }, [refreshTap, tapId])

  const stopCapture = useCallback(() => {
    captureAbortRef.current = true
    setCaptureRunning(false)
  }, [])

  const closeTap = useCallback(async () => {
    if (!tapId) return
    setBusy(true)
    setError(null)
    try {
      const response = await fetch(`${ORCHESTRATOR_BASE}/debug/taps/${encodeURIComponent(tapId)}`, { method: 'DELETE' })
      if (!response.ok) throw new Error(await readErrorMessage(response))
      const closed = (await response.json()) as DebugTap
      setTap(closed)
      setCaptureRunning(false)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to close tap.')
    } finally {
      setBusy(false)
    }
  }, [tapId])

  const capture = useCallback(async () => {
    if (!tapId) return
    if (captureRunning) return
    captureAbortRef.current = false
    setCaptureRunning(true)
    setError(null)

    const start = Date.now()
    const ttlMs = Math.max(1000, (tap?.ttlSeconds ?? 20) * 1000)
    const target = Math.max(1, Math.floor(captureCount))

    try {
      // Ensure we have the latest tap metadata first (maxItems/ttlSeconds).
      const initial = await fetchTap(0)
      setTap(initial)

      while (!captureAbortRef.current) {
        const current = await fetchTap(0)
        setTap(current)
        const currentCount = current.samples.length
        const remaining = Math.max(0, target - currentCount)
        if (remaining <= 0) break
        if (Date.now() - start > ttlMs) break
        const updated = await fetchTap(remaining)
        setTap(updated)
        if (!activeSampleId.current && updated.samples.length) {
          activeSampleId.current = updated.samples[updated.samples.length - 1]?.id ?? null
          forceRerender((v) => v + 1)
        }
        await new Promise((r) => setTimeout(r, 350))
      }
    } finally {
      setCaptureRunning(false)
      // Best-effort close: if we captured enough (or timed out), we don't want to keep an idle tap queue around.
      void closeTap()
    }
  }, [captureCount, captureRunning, closeTap, fetchTap, tap?.ttlSeconds, tapId])

  useEffect(() => {
    // Auto-capture by default (debug use-case: get a few messages, stop).
    if (!tapId) return
    if (!tap) return
    if (tap.samples.length > 0) return
    void capture()
  }, [capture, tap, tapId])

  useEffect(() => {
    if (!tap) return
    const next = Math.max(1, tap.maxItems || 5)
    setCaptureCount((prev) => (Number.isFinite(prev) && prev > 0 ? prev : next))
  }, [tap?.maxItems])

  return (
    <div className="page">
      <div className="pageHeader">
        <div>
          <div className="h2">Debug tap viewer</div>
          <div className="muted">
            tapId <code>{tapId || '—'}</code>
            {tap ? (
              <>
                {' '}
                · swarm <code>{tap.swarmId}</code> · role <code>{tap.role}</code> · {tap.direction} <code>{tap.ioName}</code>
              </>
            ) : null}
          </div>
        </div>
        <div className="row" style={{ gap: 10, alignItems: 'center' }}>
          <Link className="actionButton actionButtonGhost" to={tap?.swarmId ? `/hive/${encodeURIComponent(tap.swarmId)}/view` : '/hive'}>
            Back
          </Link>
          <button
            type="button"
            className="actionButton actionButtonGhost"
            disabled={busy || !tapId}
            title="Deletes the tap queue and stops capturing. This is best-effort: queues are exclusive + auto-delete."
            onClick={() => void closeTap()}
          >
            Close tap
          </button>
        </div>
      </div>

      <div className="card tapBanner" style={{ marginTop: 12 }}>
        Mirrors data-plane traffic to an ephemeral queue. Treat payloads as sensitive (PII/secrets).{' '}
        <span className="muted">Drain/auto-refresh consume messages from the tap queue.</span>
      </div>

      {error ? (
        <div className="card swarmMessage" style={{ marginTop: 12 }}>
          {error}
        </div>
      ) : null}

      <div className="row between" style={{ marginTop: 12, gap: 12, flexWrap: 'wrap' }}>
        <div className="row" style={{ gap: 10, alignItems: 'center' }}>
          <button
            type="button"
            className="actionButton"
            disabled={busy || !tapId}
            title="Reads up to maxItems messages from the tap queue and appends them to the sample list. Messages are consumed (removed) from the tap queue."
            onClick={() => void refreshTap(Math.max(1, tap?.maxItems ?? 10))}
          >
            Drain
          </button>

          <label className="row" style={{ gap: 8, alignItems: 'center' }} title="How many messages to capture before auto-closing the tap.">
            <span className="muted">Capture</span>
            <input
              className="textInput"
              style={{ width: 84 }}
              type="number"
              min={1}
              value={captureCount}
              onChange={(e) => {
                if (e.target.value === '') return
                const value = e.target.valueAsNumber
                if (!Number.isFinite(value)) return
                setCaptureCount(Math.max(1, Math.floor(value)))
              }}
            />
          </label>
          <button
            type="button"
            className="actionButton"
            disabled={busy || !tapId || captureRunning}
            title="Connects briefly, drains until N messages are captured, then closes the tap (best-effort). This avoids streaming thousands of messages into a debug queue."
            onClick={() => void capture()}
          >
            Capture now
          </button>
          <button
            type="button"
            className="actionButton actionButtonGhost"
            disabled={!captureRunning}
            title="Stops the capture loop."
            onClick={stopCapture}
          >
            Stop
          </button>
        </div>

        {tap ? (
          <div className="muted">
            exchange <code>{tap.exchange}</code> · routingKey <code>{tap.routingKey}</code> · queue <code>{tap.queue}</code>
          </div>
        ) : null}
      </div>

      <div className="debugTapViewerGrid" style={{ marginTop: 12 }}>
        <div className="card debugTapViewerPanel">
          <div className="row between" style={{ marginBottom: 8 }}>
            <div className="h3">Samples</div>
            <div className="muted">
              {tap ? `${tap.samples.length}/${tap.maxItems}` : '—'}
              {captureRunning ? ' · capturing…' : ''}
            </div>
          </div>
          <div className="debugTapSampleList">
            {tap?.samples?.length ? (
              tap.samples.map((s) => {
                const isSelected = s.id === activeSampleId.current
                return (
                  <button
                    key={s.id}
                    type="button"
                    className={isSelected ? 'debugTapSampleRow debugTapSampleRowSelected' : 'debugTapSampleRow'}
                    onClick={() => {
                      activeSampleId.current = s.id
                      forceRerender((v) => v + 1)
                    }}
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
              <div className="muted">No samples yet. Use “Capture” or “Drain”.</div>
            )}
          </div>
        </div>

        <div className="card debugTapViewerPanel">
          <div className="row between" style={{ marginBottom: 8 }}>
            <div className="h3">Parsed</div>
            <div className="muted">{captureRunning ? 'capturing' : 'idle'}</div>
          </div>
          {activeSample ? (
            activeEnvelope ? (
              <div className="debugTapParsed">
                <div className="tapMeta" style={{ marginBottom: 10 }}>
                  <div className="muted">
                    messageId <code>{activeEnvelope.messageId ?? '—'}</code> · contentType <code>{activeEnvelope.contentType ?? '—'}</code>
                  </div>
                </div>
                <details open>
                  <summary className="muted">headers</summary>
                  <pre className="codePre tapPayload">{safeStringify(activeEnvelope.headers ?? {})}</pre>
                </details>
                <details open>
                  <summary className="muted">observability</summary>
                  <pre className="codePre tapPayload">{safeStringify(activeEnvelope.observability ?? {})}</pre>
                </details>
                <details open>
                  <summary className="muted">steps</summary>
                  <div style={{ display: 'flex', flexDirection: 'column', gap: 10, marginTop: 10 }}>
                    {(activeEnvelope.steps ?? []).map((step, idx) => {
                      const pretty = typeof step.payload === 'string' ? tryPrettyJson(step.payload) : null
                      return (
                        <div key={String(step.index ?? idx)} className="tapSample">
                          <div className="muted" style={{ marginBottom: 6 }}>
                            step <code>{step.index ?? idx}</code> · encoding <code>{step.payloadEncoding ?? 'utf-8'}</code> · service{' '}
                            <code>{(step.headers?.['ph.step.service'] as string) ?? '—'}</code> · instance{' '}
                            <code>{(step.headers?.['ph.step.instance'] as string) ?? '—'}</code>
                          </div>
                          <details>
                            <summary className="muted">step.headers</summary>
                            <pre className="codePre tapPayload">{safeStringify(step.headers ?? {})}</pre>
                          </details>
                          <details open>
                            <summary className="muted">payload</summary>
                            <pre className="codePre tapPayload">{pretty ?? (step.payload ?? '')}</pre>
                          </details>
                        </div>
                      )
                    })}
                  </div>
                </details>
              </div>
            ) : (
              <div className="muted">
                Payload is not valid JSON WorkItem envelope. Showing raw in the right panel.
              </div>
            )
          ) : (
            <div className="muted">Select a sample to inspect.</div>
          )}
        </div>

        <div className="card debugTapViewerPanel">
          <div className="row between" style={{ marginBottom: 8 }}>
            <div className="h3">Raw</div>
            <div className="muted">{activeSample ? `${activeSample.sizeBytes}B` : '—'}</div>
          </div>
          {activeSample ? (
            <pre className="codePre tapPayload">{tryPrettyJson(activeSample.payload) ?? activeSample.payload}</pre>
          ) : (
            <div className="muted">Select a sample.</div>
          )}
        </div>
      </div>
    </div>
  )
}
