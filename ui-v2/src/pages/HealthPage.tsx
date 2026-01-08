import { useEffect, useState } from 'react'
import { subscribeControlPlaneHealth, type ControlPlaneHealth } from '../lib/controlPlane/healthStore'
import {
  getControlPlaneSettings,
  subscribeControlPlaneSettings,
  updateControlPlaneSettings,
  type ControlPlaneSettings,
} from '../lib/controlPlane/settingsStore'

type HealthState = 'checking' | 'ok' | 'down'

type HealthDetails = {
  state: HealthState
  orchestrator: string
  scenarioManager: string
  lastCheckedAt?: number
}

async function fetchText(url: string): Promise<string> {
  const response = await fetch(url)
  const text = await response.text()
  if (!response.ok) throw new Error(`${url}: ${response.status} ${text}`.trim())
  return text
}

export function HealthPage() {
  const [details, setDetails] = useState<HealthDetails>({
    state: 'checking',
    orchestrator: 'checking…',
    scenarioManager: 'checking…',
  })
  const [controlPlane, setControlPlane] = useState<ControlPlaneHealth>({
    schemaStatus: 'idle',
    stompState: 'idle',
    invalidCount: 0,
  })
  const [settings, setSettings] = useState<ControlPlaneSettings>(() => getControlPlaneSettings())

  useEffect(() => {
    let cancelled = false
    const run = async () => {
      try {
        const [orch, sm] = await Promise.all([
          fetchText('/orchestrator/actuator/health'),
          fetchText('/scenario-manager/actuator/health'),
        ])
        if (cancelled) return
        const ok = orch.includes('"status":"UP"') && sm.includes('"status":"UP"')
        setDetails({
          state: ok ? 'ok' : 'down',
          orchestrator: orch,
          scenarioManager: sm,
          lastCheckedAt: Date.now(),
        })
      } catch (e) {
        if (cancelled) return
        setDetails({
          state: 'down',
          orchestrator: e instanceof Error ? e.message : 'failed to fetch orchestrator health',
          scenarioManager: 'n/a',
          lastCheckedAt: Date.now(),
        })
      }
    }
    void run()
    return () => {
      cancelled = true
    }
  }, [])

  useEffect(() => {
    const unsubscribe = subscribeControlPlaneHealth(setControlPlane)
    return () => {
      unsubscribe()
    }
  }, [])

  useEffect(() => {
    const unsubscribe = subscribeControlPlaneSettings(setSettings)
    return () => {
      unsubscribe()
    }
  }, [])

  return (
    <div className="page">
      <h1 className="h1">Connectivity / Health</h1>
      <div className="muted">
        Placeholder. Next step: show STOMP state + retry/backoff + credentials controls, and keep this page updated live.
      </div>

      <div className="card" style={{ marginTop: 12 }}>
        <div className="row between">
          <div className="h2">Backends</div>
          <div className={details.state === 'ok' ? 'pill pillOk' : details.state === 'checking' ? 'pill pillInfo' : 'pill pillBad'}>
            {details.state.toUpperCase()}
          </div>
        </div>

        <div className="kvGrid" style={{ marginTop: 10 }}>
          <div className="kv">
            <div className="k">Orchestrator</div>
            <pre className="codePre">{details.orchestrator}</pre>
          </div>
          <div className="kv">
            <div className="k">Scenario Manager</div>
            <pre className="codePre">{details.scenarioManager}</pre>
          </div>
        </div>
      </div>

      <div className="card" style={{ marginTop: 12 }}>
        <div className="row between">
          <div className="h2">Control-plane</div>
          <div className={controlPlane.schemaStatus === 'ready' ? 'pill pillOk' : 'pill pillWarn'}>
            {controlPlane.schemaStatus.toUpperCase()}
          </div>
        </div>

        <div className="kvGrid" style={{ marginTop: 10 }}>
          <div className="kv">
            <div className="k">Schema status</div>
            <div className="v">{controlPlane.schemaStatus}</div>
            {controlPlane.schemaError ? <div className="muted">{controlPlane.schemaError}</div> : null}
          </div>
          <div className="kv">
            <div className="k">STOMP state</div>
            <div className="v">{controlPlane.stompState}</div>
          </div>
          <div className="kv">
            <div className="k">Invalid control frames</div>
            <div className="v">{controlPlane.invalidCount}</div>
          </div>
        </div>
        <div className="formGrid" style={{ marginTop: 14 }}>
          <label className="field">
            <span className="fieldLabel">STOMP URL</span>
            <input
              className="textInput"
              value={settings.url}
              onChange={(event) => updateControlPlaneSettings({ url: event.target.value })}
            />
          </label>
          <label className="field">
            <span className="fieldLabel">STOMP user</span>
            <input
              className="textInput"
              value={settings.user}
              onChange={(event) => updateControlPlaneSettings({ user: event.target.value })}
            />
          </label>
          <label className="field">
            <span className="fieldLabel">STOMP passcode</span>
            <input
              className="textInput"
              type="password"
              value={settings.passcode}
              onChange={(event) => updateControlPlaneSettings({ passcode: event.target.value })}
            />
          </label>
          <div className="field">
            <span className="fieldLabel">Connection</span>
            <div className="row">
              <button
                type="button"
                className="actionButton"
                onClick={() => updateControlPlaneSettings({ enabled: true })}
              >
                Connect
              </button>
              <button
                type="button"
                className="actionButton actionButtonDanger"
                onClick={() => updateControlPlaneSettings({ enabled: false })}
              >
                Disconnect
              </button>
            </div>
            <div className="muted" style={{ marginTop: 6 }}>
              {settings.enabled ? 'Auto-connect enabled' : 'Disconnected by user'}
            </div>
          </div>
        </div>
      </div>
    </div>
  )
}
