import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  fetchJson,
  formatInstant,
  normalizeManualOverride,
  normalizeBindings,
  normalizeProfiles,
  readErrorMessage,
  summarizeFaultConfig,
  summarizeFaults,
  type ManualNetworkOverride,
  type NetworkBinding,
  type NetworkProfile,
} from '../lib/networkProxy'

type HealthStatus = 'UP' | 'DOWN' | 'UNKNOWN'

type ActuatorHealth = {
  status: HealthStatus
}

type EditorMode = 'manual' | 'raw'

type ManualOverrideDraft = {
  enabled: boolean
  latencyEnabled: boolean
  latencyMs: number
  jitterMs: number
  bandwidthEnabled: boolean
  rateKbps: number
  slowCloseEnabled: boolean
  slowCloseDelayMs: number
  limitDataEnabled: boolean
  limitDataKb: number
  timeoutEnabled: boolean
  timeoutMs: number
}

type ToxicDocLinkProps = {
  href: string
  label: string
}

const TOXIPROXY_DOCS = {
  latency: 'https://github.com/Shopify/toxiproxy#latency',
  bandwidth: 'https://github.com/Shopify/toxiproxy#bandwidth',
  slowClose: 'https://github.com/Shopify/toxiproxy#slow_close',
  limitData: 'https://github.com/Shopify/toxiproxy#limit_data',
  timeout: 'https://github.com/Shopify/toxiproxy#timeout',
  resetPeer: 'https://github.com/Shopify/toxiproxy#reset_peer',
} as const

const DEFAULT_MANUAL_OVERRIDE: ManualOverrideDraft = {
  enabled: false,
  latencyEnabled: true,
  latencyMs: 250,
  jitterMs: 25,
  bandwidthEnabled: false,
  rateKbps: 1024,
  slowCloseEnabled: false,
  slowCloseDelayMs: 1000,
  limitDataEnabled: false,
  limitDataKb: 64,
  timeoutEnabled: false,
  timeoutMs: 2000,
}

function normalizeHealth(data: unknown): HealthStatus {
  if (!data || typeof data !== 'object') return 'UNKNOWN'
  const value = data as Record<string, unknown>
  return value.status === 'UP' || value.status === 'DOWN' ? value.status : 'UNKNOWN'
}

async function fetchHealth(url: string): Promise<ActuatorHealth> {
  const response = await fetch(url, { headers: { Accept: 'application/json' } })
  if (!response.ok) {
    throw new Error(await readErrorMessage(response))
  }
  return { status: normalizeHealth(await response.json()) }
}

function healthPill(status: HealthStatus): string {
  if (status === 'UP') return 'pill pillOk'
  if (status === 'DOWN') return 'pill pillBad'
  return 'pill pillWarn'
}

function renderHealthLabel(status: HealthStatus): string {
  if (status === 'UP') return 'UP'
  if (status === 'DOWN') return 'DOWN'
  return 'UNKNOWN'
}

function clamp(value: number, min: number, max: number): number {
  if (!Number.isFinite(value)) return min
  return Math.min(max, Math.max(min, Math.round(value)))
}

function draftFromManualOverride(override: ManualNetworkOverride): ManualOverrideDraft {
  return {
    enabled: override.enabled,
    latencyEnabled: override.latencyMs !== null,
    latencyMs: override.latencyMs ?? DEFAULT_MANUAL_OVERRIDE.latencyMs,
    jitterMs: override.jitterMs ?? DEFAULT_MANUAL_OVERRIDE.jitterMs,
    bandwidthEnabled: override.bandwidthKbps !== null,
    rateKbps: override.bandwidthKbps ?? DEFAULT_MANUAL_OVERRIDE.rateKbps,
    slowCloseEnabled: override.slowCloseDelayMs !== null,
    slowCloseDelayMs: override.slowCloseDelayMs ?? DEFAULT_MANUAL_OVERRIDE.slowCloseDelayMs,
    limitDataEnabled: override.limitDataBytes !== null,
    limitDataKb:
      override.limitDataBytes !== null ? Math.max(1, Math.round(override.limitDataBytes / 1024)) : DEFAULT_MANUAL_OVERRIDE.limitDataKb,
    timeoutEnabled: override.timeoutMs !== null,
    timeoutMs: override.timeoutMs ?? DEFAULT_MANUAL_OVERRIDE.timeoutMs,
  }
}

function toManualOverridePayload(draft: ManualOverrideDraft) {
  return {
    enabled: draft.enabled,
    latencyMs: draft.latencyEnabled ? clamp(draft.latencyMs, 0, 5000) : null,
    jitterMs: draft.latencyEnabled ? clamp(draft.jitterMs, 0, 1000) : null,
    bandwidthKbps: draft.bandwidthEnabled ? clamp(draft.rateKbps, 64, 100000) : null,
    slowCloseDelayMs: draft.slowCloseEnabled ? clamp(draft.slowCloseDelayMs, 1, 60000) : null,
    limitDataBytes: draft.limitDataEnabled ? clamp(draft.limitDataKb, 1, 10240) * 1024 : null,
    timeoutMs: draft.timeoutEnabled ? clamp(draft.timeoutMs, 100, 60000) : null,
    requestedBy: 'hive',
    reason: 'manual direct control',
  }
}

function ToxicDocLink({ href, label }: ToxicDocLinkProps) {
  return (
    <a
      href={href}
      target="_blank"
      rel="noreferrer"
      className="proxyDocLink"
      aria-label={`Open Toxiproxy documentation for ${label}`}
      title={`Open Toxiproxy documentation for ${label}`}
    >
      ?
    </a>
  )
}

export function ProxyPage() {
  const [loading, setLoading] = useState(false)
  const [savingRaw, setSavingRaw] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)
  const [bindings, setBindings] = useState<NetworkBinding[]>([])
  const [proxies, setProxies] = useState<NetworkBinding[]>([])
  const [profiles, setProfiles] = useState<NetworkProfile[]>([])
  const [rawProfiles, setRawProfiles] = useState('')
  const [rawLoaded, setRawLoaded] = useState(false)
  const [proxyManagerHealth, setProxyManagerHealth] = useState<HealthStatus>('UNKNOWN')
  const [profileRegistryHealth, setProfileRegistryHealth] = useState<HealthStatus>('UNKNOWN')
  const [editorMode, setEditorMode] = useState<EditorMode>('manual')
  const [manualOverride, setManualOverride] = useState<ManualOverrideDraft>(DEFAULT_MANUAL_OVERRIDE)
  const [manualOverrideLoaded, setManualOverrideLoaded] = useState(false)
  const [manualOverrideApplying, setManualOverrideApplying] = useState(false)
  const [manualOverrideStatus, setManualOverrideStatus] = useState<ManualNetworkOverride | null>(null)
  const [dropConnectionsBusy, setDropConnectionsBusy] = useState(false)
  const manualOverridePayloadRef = useRef<string>('')

  const reload = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const [bindingData, profileData, proxyData, rawData, managerHealth, scenarioHealth, manualOverrideData] = await Promise.all([
        fetchJson<unknown>('/network-proxy-manager/api/network/bindings'),
        fetchJson<unknown>('/scenario-manager/network-profiles'),
        fetchJson<unknown>('/network-proxy-manager/api/network/proxies'),
        fetch('/scenario-manager/network-profiles/raw', { headers: { Accept: 'text/plain' } }).then(async (response) => {
          if (!response.ok) {
            throw new Error(await readErrorMessage(response))
          }
          return response.text()
        }),
        fetchHealth('/network-proxy-manager/actuator/health'),
        fetchHealth('/scenario-manager/actuator/health'),
        fetchJson<unknown>('/network-proxy-manager/api/network/manual-override'),
      ])
      setBindings(normalizeBindings(bindingData))
      setProfiles(normalizeProfiles(profileData))
      setProxies(normalizeBindings(proxyData))
      setRawProfiles(rawData)
      setRawLoaded(true)
      setProxyManagerHealth(managerHealth.status)
      setProfileRegistryHealth(scenarioHealth.status)
      const nextManualOverride = normalizeManualOverride(manualOverrideData)
      setManualOverrideStatus(nextManualOverride)
      setManualOverride(draftFromManualOverride(nextManualOverride))
      manualOverridePayloadRef.current = JSON.stringify(toManualOverridePayload(draftFromManualOverride(nextManualOverride)))
      setManualOverrideLoaded(true)
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to load proxy stack state')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    void reload()
  }, [reload])

  const saveRawProfiles = useCallback(async () => {
    setSavingRaw(true)
    setError(null)
    setMessage(null)
    try {
      const response = await fetch('/scenario-manager/network-profiles/raw', {
        method: 'PUT',
        headers: { 'Content-Type': 'text/plain' },
        body: rawProfiles,
      })
      if (!response.ok) {
        throw new Error(await readErrorMessage(response))
      }
      setMessage('Saved network profiles YAML to Scenario Manager.')
      await reload()
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to save network profiles YAML')
    } finally {
      setSavingRaw(false)
    }
  }, [rawProfiles, reload])

  const totals = useMemo(() => {
    const activeEndpoints = bindings.reduce((sum, binding) => sum + binding.affectedEndpoints.length, 0)
    const proxiedBindings = bindings.filter((binding) => binding.effectiveMode === 'PROXIED').length
    const uniqueSuts = new Set(bindings.map((binding) => binding.sutId)).size
    return {
      activeBindings: bindings.length,
      proxiedBindings,
      activeEndpoints,
      uniqueSuts,
    }
  }, [bindings])

  useEffect(() => {
    if (!manualOverrideLoaded) return
    const payload = toManualOverridePayload(manualOverride)
    const serialized = JSON.stringify(payload)
    if (serialized === manualOverridePayloadRef.current) {
      return
    }
    const timeoutId = window.setTimeout(async () => {
      setManualOverrideApplying(true)
      setError(null)
      try {
        const response = await fetch('/network-proxy-manager/api/network/manual-override', {
          method: 'PUT',
          headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
          body: serialized,
        })
        if (!response.ok) {
          throw new Error(await readErrorMessage(response))
        }
        const data = normalizeManualOverride(await response.json())
        manualOverridePayloadRef.current = serialized
        setManualOverrideStatus(data)
        setMessage(data.enabled ? 'Manual override applied to the shared proxy stack.' : 'Manual override cleared from the shared proxy stack.')
        await reload()
      } catch (e) {
        setError(e instanceof Error ? e.message : 'Failed to apply manual override')
      } finally {
        setManualOverrideApplying(false)
      }
    }, 250)
    return () => window.clearTimeout(timeoutId)
  }, [manualOverride, manualOverrideLoaded, reload])

  const dropConnectionsNow = useCallback(async () => {
    setDropConnectionsBusy(true)
    setError(null)
    setMessage(null)
    try {
      const response = await fetch('/network-proxy-manager/api/network/manual-override/drop-connections', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
        body: JSON.stringify({ requestedBy: 'hive', reason: 'drop connections now' }),
      })
      if (!response.ok) {
        throw new Error(await readErrorMessage(response))
      }
      setMessage('Issued one-shot connection drop across the shared proxy stack.')
      await reload()
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to drop connections')
    } finally {
      setDropConnectionsBusy(false)
    }
  }, [reload])

  return (
    <div className="page proxyPage">
      <div className="row between">
        <div>
          <h1 className="h1">Proxy</h1>
          <div className="muted">Shared proxy stack, runtime routes and profile administration.</div>
        </div>
        <button type="button" className="actionButton" onClick={() => void reload()} disabled={loading || savingRaw}>
          Refresh
        </button>
      </div>

      {error ? (
        <div className="card" style={{ marginTop: 12 }}>
          <div className="pill pillBad">ERROR</div>
          <div className="muted" style={{ marginTop: 8 }}>
            {error}
          </div>
        </div>
      ) : null}

      {message ? (
        <div className="card" style={{ marginTop: 12 }}>
          <div className="pill pillOk">UPDATED</div>
          <div className="muted" style={{ marginTop: 8 }}>
            {message}
          </div>
        </div>
      ) : null}

      <div className="card proxyHero" style={{ marginTop: 12 }}>
        <div className="proxyHeroHeader">
          <div>
            <div className="h2">Shared stack</div>
            <div className="muted">One shared HAProxy + Toxiproxy stack for v1. Per-swarm mode selection lives on Hive.</div>
          </div>
          <div className="proxyHeroBadges">
            <span className="pill pillInfo">shared-default</span>
            <span className={healthPill(proxyManagerHealth)}>manager {renderHealthLabel(proxyManagerHealth)}</span>
            <span className={healthPill(profileRegistryHealth)}>profiles {renderHealthLabel(profileRegistryHealth)}</span>
          </div>
        </div>

        <div className="proxyOverviewGrid">
          <div className="proxyMetric">
            <div className="proxyMetricLabel">Active bindings</div>
            <div className="proxyMetricValue">{totals.activeBindings}</div>
            <div className="muted">proxied {totals.proxiedBindings}</div>
          </div>
          <div className="proxyMetric">
            <div className="proxyMetricLabel">Tracked SUTs</div>
            <div className="proxyMetricValue">{totals.uniqueSuts}</div>
            <div className="muted">shared blast radius</div>
          </div>
          <div className="proxyMetric">
            <div className="proxyMetricLabel">Runtime endpoints</div>
            <div className="proxyMetricValue">{totals.activeEndpoints}</div>
            <div className="muted">resolved routes</div>
          </div>
          <div className="proxyMetric">
            <div className="proxyMetricLabel">Profiles</div>
            <div className="proxyMetricValue">{profiles.length}</div>
            <div className="muted">catalog entries</div>
          </div>
        </div>
      </div>

      <div className="proxySectionGrid" style={{ marginTop: 12 }}>
        <div className="card">
          <div className="row between">
            <div>
              <div className="h2">Runtime bindings</div>
              <div className="muted">What is currently materialized on the shared stack.</div>
            </div>
            <div className="pill pillInfo">{bindings.length}</div>
          </div>

          <div className="proxyList" style={{ marginTop: 12 }}>
            {bindings.map((binding) => (
              <div key={`${binding.swarmId}:${binding.sutId}`} className="proxyRuntimeRow">
                <div className="proxyRuntimeHead">
                  <div>
                    <div className="proxyRuntimeTitle">{binding.sutId}</div>
                    <div className="muted">swarm `{binding.swarmId}`</div>
                  </div>
                  <div className="row">
                    <span className={binding.effectiveMode === 'PROXIED' ? 'pill pillInfo' : 'pill pillWarn'}>
                      {binding.effectiveMode}
                    </span>
                    <span className="pill pillInfo">{binding.networkProfileId ?? '—'}</span>
                  </div>
                </div>
                <div className="proxyRuntimeMeta">
                  <span>requestedBy `{binding.requestedBy}`</span>
                  <span>{formatInstant(binding.appliedAt)}</span>
                  <span>{binding.affectedEndpoints.length} endpoint(s)</span>
                </div>
                {binding.affectedEndpoints.length > 0 ? (
                  <div className="proxyEndpointList">
                    {binding.affectedEndpoints.map((endpoint) => (
                      <div key={`${binding.swarmId}:${endpoint.endpointId}`} className="proxyEndpointItem">
                        <div className="proxyEndpointTitle">
                          <strong>{endpoint.endpointId}</strong>
                          <span className="pill pillInfo">{endpoint.kind ?? 'UNKNOWN'}</span>
                        </div>
                        <div className="muted">client `{endpoint.clientAuthority ?? endpoint.clientBaseUrl ?? '—'}`</div>
                        <div className="muted">upstream `{endpoint.upstreamAuthority ?? '—'}`</div>
                      </div>
                    ))}
                  </div>
                ) : null}
              </div>
            ))}
            {!loading && bindings.length === 0 ? <div className="muted">No active bindings on the shared proxy stack.</div> : null}
          </div>
        </div>

        <div className="card">
          <div className="row between">
            <div>
              <div className="h2">Proxy listeners</div>
              <div className="muted">Listener/runtime entries currently reconciled in Toxiproxy.</div>
            </div>
            <div className="pill pillInfo">{proxies.length}</div>
          </div>

          <div className="proxyList" style={{ marginTop: 12 }}>
            {proxies.map((proxy) => (
              <div key={`${proxy.swarmId}:${proxy.sutId}:runtime`} className="proxyRuntimeRow">
                <div className="proxyRuntimeHead">
                  <div className="proxyRuntimeTitle">{proxy.sutId}</div>
                  <span className={proxy.effectiveMode === 'PROXIED' ? 'pill pillInfo' : 'pill pillWarn'}>
                    {proxy.effectiveMode}
                  </span>
                </div>
                <div className="proxyRuntimeMeta">
                  <span>swarm `{proxy.swarmId}`</span>
                  <span>profile `{proxy.networkProfileId ?? '—'}`</span>
                  <span>{formatInstant(proxy.appliedAt)}</span>
                </div>
              </div>
            ))}
            {!loading && proxies.length === 0 ? <div className="muted">No active proxy listener entries.</div> : null}
          </div>
        </div>
      </div>

      <div className="proxySectionGrid" style={{ marginTop: 12 }}>
        <div className="card">
          <div className="row between">
            <div>
              <div className="h2">Profiles</div>
              <div className="muted">Catalog for proxied swarms. Assignment happens on the swarm page.</div>
            </div>
            <div className="pill pillInfo">{profiles.length}</div>
          </div>

          <div className="proxyList" style={{ marginTop: 12 }}>
            {profiles.map((profile) => (
              <div key={profile.id} className="proxyProfileRow">
                <div className="proxyProfileHead">
                  <div>
                    <div className="proxyRuntimeTitle">{profile.id}</div>
                    <div className="muted">{profile.name ?? 'Unnamed profile'}</div>
                  </div>
                  <div className={profile.faults.length === 0 ? 'pill pillWarn' : 'pill pillInfo'}>
                    {profile.faults.length === 0 ? 'PASSTHROUGH' : `${profile.faults.length} FAULTS`}
                  </div>
                </div>
                <div className="proxyRuntimeMeta">
                  <span>targets {profile.targets.length === 0 ? 'all resolved endpoints' : profile.targets.join(', ')}</span>
                  <span>{summarizeFaults(profile)}</span>
                </div>
                {profile.faults.length > 0 ? (
                  <div className="proxyFaultList">
                    {profile.faults.map((fault, index) => (
                      <div key={`${profile.id}:${fault.type}:${index}`} className="proxyFaultChip">
                        <strong>{fault.type}</strong>
                        <span>{summarizeFaultConfig(fault.config)}</span>
                      </div>
                    ))}
                  </div>
                ) : null}
              </div>
            ))}
            {!loading && profiles.length === 0 ? <div className="muted">No network profiles available.</div> : null}
          </div>
        </div>

        <div className="card">
          <div className="row between">
            <div>
              <div className="h2">{editorMode === 'manual' ? 'Manual override' : 'Raw profile editor'}</div>
              <div className="muted">
                {editorMode === 'manual'
                  ? 'Build a transient override profile with guarded controls instead of editing YAML by hand.'
                  : 'Add profiles or tune fault parameters in `network-profiles.yaml`.'}
              </div>
            </div>
            <div className="proxyEditorToolbar">
              <div className="proxyTabStrip" role="tablist" aria-label="Proxy editor mode">
                <button
                  type="button"
                  role="tab"
                  aria-selected={editorMode === 'manual'}
                  className={editorMode === 'manual' ? 'proxyTabButton proxyTabButtonActive' : 'proxyTabButton'}
                  onClick={() => setEditorMode('manual')}
                  disabled={savingRaw}
                >
                  Manual override
                </button>
                <button
                  type="button"
                  role="tab"
                  aria-selected={editorMode === 'raw'}
                  className={editorMode === 'raw' ? 'proxyTabButton proxyTabButtonActive' : 'proxyTabButton'}
                  onClick={() => setEditorMode('raw')}
                  disabled={savingRaw}
                >
                  Raw editor
                </button>
              </div>
              {editorMode === 'raw' ? (
                <div className="row">
                  <button
                    type="button"
                    className="actionButton actionButtonGhost"
                    onClick={() => void reload()}
                    disabled={loading || savingRaw}
                  >
                    Reload
                  </button>
                  <button type="button" className="actionButton" onClick={() => void saveRawProfiles()} disabled={savingRaw || !rawLoaded}>
                    Save YAML
                  </button>
                </div>
              ) : null}
            </div>
          </div>

          {editorMode === 'manual' ? (
            <div className="proxyManualPanel" style={{ marginTop: 12 }}>
              <div className="proxyManualHeader">
                <div>
                  <div className="fieldLabel">Direct control</div>
                  <div className="muted">
                    Live controls for the shared proxy stack. Changes apply immediately to active proxied routes.
                  </div>
                </div>
                <button
                  type="button"
                  role="switch"
                  aria-checked={manualOverride.enabled}
                  className={manualOverride.enabled ? 'proxyOverrideSwitch proxyOverrideSwitchEnabled' : 'proxyOverrideSwitch'}
                  onClick={() =>
                    setManualOverride((current) => ({
                      ...current,
                      enabled: !current.enabled,
                    }))
                  }
                  disabled={manualOverrideApplying || dropConnectionsBusy}
                >
                  <span className="proxyOverrideSwitchKnob" />
                  <span className="proxyOverrideSwitchText">
                    <strong>{manualOverride.enabled ? 'Manual override enabled' : 'Manual override disabled'}</strong>
                    <span>{manualOverride.enabled ? 'Runtime override is active on the shared stack.' : 'Enable to unlock live controls below.'}</span>
                  </span>
                </button>
              </div>

              <div className={`proxyOverrideControls ${manualOverride.enabled ? '' : 'proxyOverrideControlsDisabled'}`}>
                <div className="proxyOverrideGroup">
                  <div className="proxyOverrideGroupHead">
                    <div className="proxyToggleRow">
                      <label className="proxyToggle">
                        <input
                          type="checkbox"
                          checked={manualOverride.latencyEnabled}
                          onChange={(event) =>
                            setManualOverride((current) => ({
                              ...current,
                              latencyEnabled: event.target.checked,
                            }))
                          }
                          disabled={!manualOverride.enabled || manualOverrideApplying || dropConnectionsBusy}
                        />
                        <span>Latency</span>
                      </label>
                      <ToxicDocLink href={TOXIPROXY_DOCS.latency} label="latency toxic" />
                    </div>
                    <div className="muted">adds delay and jitter</div>
                  </div>
                  <div className="proxySliderGrid">
                    <label className="field">
                      <span className="fieldLabel">Latency ms</span>
                      <input
                        type="range"
                        min="0"
                        max="5000"
                        step="25"
                        value={manualOverride.latencyMs}
                        onChange={(event) =>
                          setManualOverride((current) => ({
                            ...current,
                            latencyMs: Number(event.target.value),
                          }))
                        }
                        disabled={!manualOverride.enabled || !manualOverride.latencyEnabled || manualOverrideApplying || dropConnectionsBusy}
                      />
                      <div className="muted">{manualOverride.latencyMs} ms</div>
                    </label>
                    <label className="field">
                      <span className="fieldLabel">Jitter ms</span>
                      <input
                        type="range"
                        min="0"
                        max="1000"
                        step="5"
                        value={manualOverride.jitterMs}
                        onChange={(event) =>
                          setManualOverride((current) => ({
                            ...current,
                            jitterMs: Number(event.target.value),
                          }))
                        }
                        disabled={!manualOverride.enabled || !manualOverride.latencyEnabled || manualOverrideApplying || dropConnectionsBusy}
                      />
                      <div className="muted">{manualOverride.jitterMs} ms</div>
                    </label>
                  </div>
                </div>

                <div className="proxyOverrideGroup">
                  <div className="proxyOverrideGroupHead">
                    <div className="proxyToggleRow">
                      <label className="proxyToggle">
                        <input
                          type="checkbox"
                          checked={manualOverride.bandwidthEnabled}
                          onChange={(event) =>
                            setManualOverride((current) => ({
                              ...current,
                              bandwidthEnabled: event.target.checked,
                            }))
                          }
                          disabled={!manualOverride.enabled || manualOverrideApplying || dropConnectionsBusy}
                        />
                        <span>Bandwidth cap</span>
                      </label>
                      <ToxicDocLink href={TOXIPROXY_DOCS.bandwidth} label="bandwidth toxic" />
                    </div>
                    <div className="muted">caps throughput</div>
                  </div>
                  <label className="field">
                    <span className="fieldLabel">Rate Kbps</span>
                    <input
                      type="range"
                      min="64"
                      max="10000"
                      step="64"
                      value={manualOverride.rateKbps}
                      onChange={(event) =>
                          setManualOverride((current) => ({
                            ...current,
                            rateKbps: Number(event.target.value),
                          }))
                        }
                        disabled={!manualOverride.enabled || !manualOverride.bandwidthEnabled || manualOverrideApplying || dropConnectionsBusy}
                      />
                      <div className="muted">{manualOverride.rateKbps} Kbps</div>
                  </label>
                </div>

                <div className="proxyOverrideGroup">
                  <div className="proxyOverrideGroupHead">
                    <div className="proxyToggleRow">
                      <label className="proxyToggle">
                        <input
                          type="checkbox"
                          checked={manualOverride.slowCloseEnabled}
                          onChange={(event) =>
                            setManualOverride((current) => ({
                              ...current,
                              slowCloseEnabled: event.target.checked,
                            }))
                          }
                          disabled={!manualOverride.enabled || manualOverrideApplying || dropConnectionsBusy}
                        />
                        <span>Slow close</span>
                      </label>
                      <ToxicDocLink href={TOXIPROXY_DOCS.slowClose} label="slow close toxic" />
                    </div>
                    <div className="muted">delays socket close by the selected amount</div>
                  </div>
                  <label className="field">
                    <span className="fieldLabel">Delay close by ms</span>
                    <input
                      type="range"
                      min="1"
                      max="10000"
                      step="50"
                      value={manualOverride.slowCloseDelayMs}
                      onChange={(event) =>
                        setManualOverride((current) => ({
                          ...current,
                          slowCloseDelayMs: Number(event.target.value),
                        }))
                      }
                      disabled={!manualOverride.enabled || !manualOverride.slowCloseEnabled || manualOverrideApplying || dropConnectionsBusy}
                    />
                    <div className="muted">{manualOverride.slowCloseDelayMs} ms</div>
                  </label>
                </div>

                <div className="proxyOverrideGroup">
                  <div className="proxyOverrideGroupHead">
                    <div className="proxyToggleRow">
                      <label className="proxyToggle">
                        <input
                          type="checkbox"
                          checked={manualOverride.limitDataEnabled}
                          onChange={(event) =>
                            setManualOverride((current) => ({
                              ...current,
                              limitDataEnabled: event.target.checked,
                            }))
                          }
                          disabled={!manualOverride.enabled || manualOverrideApplying || dropConnectionsBusy}
                        />
                        <span>Limit data</span>
                      </label>
                      <ToxicDocLink href={TOXIPROXY_DOCS.limitData} label="limit data toxic" />
                    </div>
                    <div className="muted">allows only the first chunk of data through, then closes the connection</div>
                  </div>
                  <label className="field">
                    <span className="fieldLabel">Allow KiB before close</span>
                    <input
                      type="range"
                      min="1"
                      max="1024"
                      step="1"
                      value={manualOverride.limitDataKb}
                      onChange={(event) =>
                        setManualOverride((current) => ({
                          ...current,
                          limitDataKb: Number(event.target.value),
                        }))
                      }
                      disabled={!manualOverride.enabled || !manualOverride.limitDataEnabled || manualOverrideApplying || dropConnectionsBusy}
                    />
                    <div className="muted">{manualOverride.limitDataKb} KiB</div>
                  </label>
                </div>

                <div className="proxyOverrideGroup">
                  <div className="proxyOverrideGroupHead">
                    <div className="proxyToggleRow">
                      <label className="proxyToggle">
                        <input
                          type="checkbox"
                          checked={manualOverride.timeoutEnabled}
                          onChange={(event) =>
                            setManualOverride((current) => ({
                              ...current,
                              timeoutEnabled: event.target.checked,
                            }))
                          }
                          disabled={!manualOverride.enabled || manualOverrideApplying || dropConnectionsBusy}
                        />
                        <span>Stall then close</span>
                      </label>
                      <ToxicDocLink href={TOXIPROXY_DOCS.timeout} label="timeout toxic" />
                    </div>
                    <div className="muted">hangs each affected connection, then closes it after the selected delay</div>
                  </div>
                  <label className="field">
                    <span className="fieldLabel">Close after ms</span>
                    <input
                      type="range"
                      min="100"
                      max="10000"
                      step="100"
                      value={manualOverride.timeoutMs}
                      onChange={(event) =>
                          setManualOverride((current) => ({
                            ...current,
                            timeoutMs: Number(event.target.value),
                          }))
                        }
                        disabled={!manualOverride.enabled || !manualOverride.timeoutEnabled || manualOverrideApplying || dropConnectionsBusy}
                      />
                      <div className="muted">{manualOverride.timeoutMs} ms</div>
                    </label>
                  </div>

                <div className="proxyOverrideGroup">
                  <div className="proxyOverrideGroupHead">
                    <div>
                      <div className="proxyInlineHead">
                        <div className="fieldLabel">Drop connections now</div>
                        <ToxicDocLink href={TOXIPROXY_DOCS.resetPeer} label="reset peer toxic" />
                      </div>
                      <div className="muted">One-shot operator action that tears down active proxied connections on the shared stack.</div>
                    </div>
                    <button
                      type="button"
                      className="actionButton"
                      onClick={() => void dropConnectionsNow()}
                      disabled={dropConnectionsBusy || manualOverrideApplying || totals.proxiedBindings === 0}
                    >
                      {dropConnectionsBusy ? 'Dropping...' : 'Drop now'}
                    </button>
                  </div>
                </div>
              </div>

              <div className="proxyManualSummary">
                <div className="fieldLabel">Preview</div>
                <div className="muted">
                  {manualOverride.enabled
                    ? `${[
                        manualOverride.latencyEnabled ? `latency ${manualOverride.latencyMs} ms / jitter ${manualOverride.jitterMs} ms` : null,
                        manualOverride.bandwidthEnabled ? `bandwidth ${manualOverride.rateKbps} Kbps` : null,
                        manualOverride.slowCloseEnabled ? `slow close ${manualOverride.slowCloseDelayMs} ms` : null,
                        manualOverride.limitDataEnabled ? `limit data ${manualOverride.limitDataKb} KiB` : null,
                        manualOverride.timeoutEnabled ? `stall then close after ${manualOverride.timeoutMs} ms` : null,
                      ]
                        .filter(Boolean)
                        .join(', ') || 'No active faults'} on ${totals.proxiedBindings} proxied binding(s).`
                    : `Manual direct control is off. Base profile behavior remains unchanged.`}
                </div>
                <div className="muted" style={{ marginTop: 6 }}>
                  {manualOverrideApplying
                    ? 'Applying changes...'
                    : manualOverrideStatus
                      ? `Last applied ${formatInstant(manualOverrideStatus.appliedAt)} by ${manualOverrideStatus.requestedBy}.`
                      : 'No runtime override state loaded yet.'}
                </div>
              </div>
            </div>
          ) : (
            <label className="field" style={{ marginTop: 12 }}>
              <span className="fieldLabel">network-profiles.yaml</span>
              <textarea
                className="textInput proxyYamlEditor"
                value={rawProfiles}
                onChange={(event) => setRawProfiles(event.target.value)}
                spellCheck={false}
                rows={20}
                disabled={!rawLoaded || savingRaw}
              />
            </label>
          )}
        </div>
      </div>
    </div>
  )
}
