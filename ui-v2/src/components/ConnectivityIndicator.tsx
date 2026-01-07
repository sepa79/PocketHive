import { useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { subscribeControlPlaneHealth, type ControlPlaneHealth } from '../lib/controlPlane/healthStore'

type HealthState = 'unknown' | 'checking' | 'ok' | 'down'

type HealthResult = {
  state: HealthState
  lastOkAt?: number
  lastError?: string
}

async function fetchHealth(url: string, signal: AbortSignal): Promise<boolean> {
  const response = await fetch(url, { signal })
  if (!response.ok) return false
  const json = (await response.json()) as unknown
  if (typeof json !== 'object' || json === null) return true
  const status = (json as any).status
  return typeof status === 'string' ? status.toUpperCase() === 'UP' : true
}

export function ConnectivityIndicator() {
  const navigate = useNavigate()
  const [result, setResult] = useState<HealthResult>({ state: 'unknown' })
  const timerRef = useRef<number | null>(null)
  const [controlPlane, setControlPlane] = useState<ControlPlaneHealth>({
    schemaStatus: 'idle',
    stompState: 'idle',
    invalidCount: 0,
  })

  const title = useMemo(() => {
    const controlPlaneOk =
      controlPlane.schemaStatus === 'ready' && controlPlane.stompState === 'connected'
    const controlPlaneSummary = controlPlaneOk
      ? 'control-plane ok'
      : `control-plane ${controlPlane.schemaStatus}/${controlPlane.stompState}`
    if (result.state === 'down') return `Connectivity: problems (${result.lastError ?? 'unknown'})`
    if (result.state === 'ok' && !controlPlaneOk) return `Connectivity: degraded (${controlPlaneSummary})`
    if (result.state === 'ok') return 'Connectivity: OK (click for details)'
    if (result.state === 'checking') return 'Connectivity: checking…'
    return 'Connectivity: unknown'
  }, [controlPlane, result])

  useEffect(() => {
    let stopped = false
    const controller = new AbortController()

    const schedule = (ms: number) => {
      if (timerRef.current) window.clearTimeout(timerRef.current)
      timerRef.current = window.setTimeout(run, ms)
    }

    const run = async () => {
      if (stopped) return
      setResult((prev) => ({ ...prev, state: prev.state === 'ok' ? 'ok' : 'checking' }))
      try {
        const [orch, sm] = await Promise.all([
          fetchHealth('/orchestrator/actuator/health', controller.signal),
          fetchHealth('/scenario-manager/actuator/health', controller.signal),
        ])
        if (stopped) return
        if (orch && sm) {
          setResult({ state: 'ok', lastOkAt: Date.now() })
          schedule(10_000)
        } else {
          setResult((prev) => ({
            state: 'down',
            lastError: 'backend health is not UP',
            lastOkAt: prev.lastOkAt,
          }))
          schedule(5_000)
        }
      } catch (e) {
        if (stopped) return
        const message = e instanceof Error ? e.message : 'health check failed'
        setResult((prev) => ({
          state: 'down',
          lastError: message,
          lastOkAt: prev.lastOkAt,
        }))
        schedule(5_000)
      }
    }

    schedule(300)
    return () => {
      stopped = true
      controller.abort()
      if (timerRef.current) window.clearTimeout(timerRef.current)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => {
    const unsubscribe = subscribeControlPlaneHealth(setControlPlane)
    return () => {
      unsubscribe()
    }
  }, [])

  const cls = useMemo(() => {
    if (result.state === 'down') return 'alert'
    if (result.state === 'checking') return 'warn'
    if (result.state === 'ok') {
      return controlPlane.schemaStatus === 'ready' && controlPlane.stompState === 'connected' ? 'ok' : 'warn'
    }
    return 'missing'
  }, [controlPlane, result])

  return (
    <button type="button" className="iconButton iconButtonBare" title={title} onClick={() => navigate('/health')}>
      <span className="hal-eye" data-state={cls} aria-hidden="true" />
    </button>
  )
}
