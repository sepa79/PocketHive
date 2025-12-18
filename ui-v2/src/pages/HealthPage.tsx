import { useEffect, useState } from 'react'

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
    </div>
  )
}

