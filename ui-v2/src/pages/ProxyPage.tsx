import { useCallback, useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'

type NetworkMode = 'DIRECT' | 'PROXIED'

type SwarmSummary = {
  id: string
  status: string | null
  templateId: string | null
  sutId: string | null
  networkMode: NetworkMode
  networkProfileId: string | null
}

type ResolvedSutEndpoint = {
  endpointId: string
  kind: string | null
  clientBaseUrl: string | null
  clientAuthority: string | null
  upstreamAuthority: string | null
}

type NetworkBinding = {
  swarmId: string
  sutId: string
  networkMode: NetworkMode
  networkProfileId: string | null
  effectiveMode: NetworkMode
  requestedBy: string
  appliedAt: string | null
  affectedEndpoints: ResolvedSutEndpoint[]
}

type NetworkProfile = {
  id: string
  name: string | null
  faults: { type: string; config: Record<string, unknown> }[]
  targets: string[]
}

type ProxyRow = {
  swarmId: string
  status: string | null
  templateId: string | null
  sutId: string | null
  networkMode: NetworkMode
  networkProfileId: string | null
  appliedAt: string | null
  affectedEndpoints: ResolvedSutEndpoint[]
  bindingRequestedBy: string | null
  hasBinding: boolean
}

function createIdempotencyKey() {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) {
    return crypto.randomUUID()
  }
  return `ph-${Date.now()}-${Math.random().toString(16).slice(2)}`
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

async function fetchJson<T>(url: string): Promise<T> {
  const response = await fetch(url, { headers: { Accept: 'application/json' } })
  if (!response.ok) {
    throw new Error(await readErrorMessage(response))
  }
  return (await response.json()) as T
}

function normalizeMode(value: unknown): NetworkMode {
  return value === 'PROXIED' ? 'PROXIED' : 'DIRECT'
}

function normalizeSwarmSummaries(data: unknown): SwarmSummary[] {
  if (!Array.isArray(data)) return []
  return data
    .map((entry) => {
      if (!entry || typeof entry !== 'object') return null
      const value = entry as Record<string, unknown>
      const id = typeof value.id === 'string' ? value.id.trim() : ''
      if (!id) return null
      return {
        id,
        status: typeof value.status === 'string' ? value.status.trim() : null,
        templateId: typeof value.templateId === 'string' && value.templateId.trim().length > 0 ? value.templateId.trim() : null,
        sutId: typeof value.sutId === 'string' && value.sutId.trim().length > 0 ? value.sutId.trim() : null,
        networkMode: normalizeMode(value.networkMode),
        networkProfileId:
          typeof value.networkProfileId === 'string' && value.networkProfileId.trim().length > 0
            ? value.networkProfileId.trim()
            : null,
      } satisfies SwarmSummary
    })
    .filter((entry): entry is SwarmSummary => entry !== null)
}

function normalizeEndpoint(entry: unknown): ResolvedSutEndpoint | null {
  if (!entry || typeof entry !== 'object') return null
  const value = entry as Record<string, unknown>
  const endpointId = typeof value.endpointId === 'string' ? value.endpointId.trim() : ''
  if (!endpointId) return null
  return {
    endpointId,
    kind: typeof value.kind === 'string' && value.kind.trim().length > 0 ? value.kind.trim() : null,
    clientBaseUrl: typeof value.clientBaseUrl === 'string' && value.clientBaseUrl.trim().length > 0 ? value.clientBaseUrl.trim() : null,
    clientAuthority:
      typeof value.clientAuthority === 'string' && value.clientAuthority.trim().length > 0 ? value.clientAuthority.trim() : null,
    upstreamAuthority:
      typeof value.upstreamAuthority === 'string' && value.upstreamAuthority.trim().length > 0
        ? value.upstreamAuthority.trim()
        : null,
  }
}

function normalizeBindings(data: unknown): NetworkBinding[] {
  if (!Array.isArray(data)) return []
  return data
    .map((entry) => {
      if (!entry || typeof entry !== 'object') return null
      const value = entry as Record<string, unknown>
      const swarmId = typeof value.swarmId === 'string' ? value.swarmId.trim() : ''
      const sutId = typeof value.sutId === 'string' ? value.sutId.trim() : ''
      if (!swarmId || !sutId) return null
      return {
        swarmId,
        sutId,
        networkMode: normalizeMode(value.networkMode),
        networkProfileId:
          typeof value.networkProfileId === 'string' && value.networkProfileId.trim().length > 0
            ? value.networkProfileId.trim()
            : null,
        effectiveMode: normalizeMode(value.effectiveMode),
        requestedBy: typeof value.requestedBy === 'string' ? value.requestedBy.trim() : 'unknown',
        appliedAt: typeof value.appliedAt === 'string' && value.appliedAt.trim().length > 0 ? value.appliedAt.trim() : null,
        affectedEndpoints: Array.isArray(value.affectedEndpoints)
          ? value.affectedEndpoints.map(normalizeEndpoint).filter((item): item is ResolvedSutEndpoint => item !== null)
          : [],
      } satisfies NetworkBinding
    })
    .filter((entry): entry is NetworkBinding => entry !== null)
}

function normalizeProfiles(data: unknown): NetworkProfile[] {
  if (!Array.isArray(data)) return []
  return data
    .map((entry) => {
      if (!entry || typeof entry !== 'object') return null
      const value = entry as Record<string, unknown>
      const id = typeof value.id === 'string' ? value.id.trim() : ''
      if (!id) return null
      const faults = Array.isArray(value.faults)
        ? value.faults
            .map((fault) => {
              if (!fault || typeof fault !== 'object') return null
              const faultValue = fault as Record<string, unknown>
              const type = typeof faultValue.type === 'string' ? faultValue.type.trim() : ''
              if (!type) return null
              const config =
                faultValue.config && typeof faultValue.config === 'object' && !Array.isArray(faultValue.config)
                  ? (faultValue.config as Record<string, unknown>)
                  : {}
              return { type, config }
            })
            .filter((fault): fault is { type: string; config: Record<string, unknown> } => fault !== null)
        : []
      const targets = Array.isArray(value.targets)
        ? value.targets
            .map((target) => (typeof target === 'string' ? target.trim() : ''))
            .filter((target) => target.length > 0)
        : []
      return {
        id,
        name: typeof value.name === 'string' && value.name.trim().length > 0 ? value.name.trim() : null,
        faults,
        targets,
      } satisfies NetworkProfile
    })
    .filter((entry): entry is NetworkProfile => entry !== null)
}

function formatInstant(value: string | null): string {
  if (!value) return '—'
  const timestamp = Date.parse(value)
  if (Number.isNaN(timestamp)) return value
  return new Date(timestamp).toLocaleString()
}

function summarizeFaults(profile: NetworkProfile): string {
  if (profile.faults.length === 0) return 'No faults'
  return profile.faults.map((fault) => fault.type).join(', ')
}

function summarizeFaultConfig(config: Record<string, unknown>): string {
  const entries = Object.entries(config)
  if (entries.length === 0) return 'default'
  return entries
    .map(([key, value]) => `${key}=${String(value)}`)
    .join(', ')
}

export function ProxyPage() {
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)
  const [busySwarmId, setBusySwarmId] = useState<string | null>(null)
  const [swarms, setSwarms] = useState<SwarmSummary[]>([])
  const [bindings, setBindings] = useState<NetworkBinding[]>([])
  const [proxies, setProxies] = useState<NetworkBinding[]>([])
  const [profiles, setProfiles] = useState<NetworkProfile[]>([])
  const [profileDrafts, setProfileDrafts] = useState<Record<string, string>>({})

  const reload = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const [swarmData, bindingData, profileData, proxyData] = await Promise.all([
        fetchJson<unknown>('/orchestrator/api/swarms'),
        fetchJson<unknown>('/network-proxy-manager/api/network/bindings'),
        fetchJson<unknown>('/scenario-manager/network-profiles'),
        fetchJson<unknown>('/network-proxy-manager/api/network/proxies'),
      ])
      const nextSwarms = normalizeSwarmSummaries(swarmData)
      const nextBindings = normalizeBindings(bindingData)
      const nextProfiles = normalizeProfiles(profileData)
      const nextProxies = normalizeBindings(proxyData)
      setSwarms(nextSwarms)
      setBindings(nextBindings)
      setProfiles(nextProfiles)
      setProxies(nextProxies)
      setProfileDrafts((current) => {
        const next = { ...current }
        const fallbackProfileId = nextProfiles[0]?.id ?? ''
        for (const swarm of nextSwarms) {
          if (!next[swarm.id] || next[swarm.id].trim().length === 0) {
            next[swarm.id] = swarm.networkProfileId ?? fallbackProfileId
          }
        }
        for (const binding of nextBindings) {
          if (!next[binding.swarmId] || next[binding.swarmId].trim().length === 0) {
            next[binding.swarmId] = binding.networkProfileId ?? fallbackProfileId
          }
        }
        return next
      })
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to load proxy state')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    void reload()
  }, [reload])

  const rows = useMemo<ProxyRow[]>(() => {
    const swarmsById = new Map(swarms.map((entry) => [entry.id, entry]))
    const bindingsById = new Map(bindings.map((entry) => [entry.swarmId, entry]))
    const ids = new Set<string>([...swarmsById.keys(), ...bindingsById.keys()])
    return Array.from(ids)
      .sort((left, right) => left.localeCompare(right))
      .map((swarmId) => {
        const swarm = swarmsById.get(swarmId)
        const binding = bindingsById.get(swarmId)
        return {
          swarmId,
          status: swarm?.status ?? null,
          templateId: swarm?.templateId ?? null,
          sutId: binding?.sutId ?? swarm?.sutId ?? null,
          networkMode: binding?.effectiveMode ?? swarm?.networkMode ?? 'DIRECT',
          networkProfileId: binding?.networkProfileId ?? swarm?.networkProfileId ?? null,
          appliedAt: binding?.appliedAt ?? null,
          affectedEndpoints: binding?.affectedEndpoints ?? [],
          bindingRequestedBy: binding?.requestedBy ?? null,
          hasBinding: binding != null,
        }
      })
  }, [bindings, swarms])

  const totals = useMemo(() => {
    const proxied = rows.filter((row) => row.networkMode === 'PROXIED').length
    const direct = rows.length - proxied
    const endpointCount = rows.reduce((sum, row) => sum + row.affectedEndpoints.length, 0)
    return { proxied, direct, endpointCount }
  }, [rows])

  const applyNetworkMode = useCallback(
    async (swarmId: string, networkMode: NetworkMode) => {
      setBusySwarmId(swarmId)
      setError(null)
      setMessage(null)
      try {
        const profileId = (profileDrafts[swarmId] ?? '').trim()
        if (networkMode === 'PROXIED' && profileId.length === 0) {
          throw new Error(`Select a network profile for swarm '${swarmId}'`)
        }
        const body =
          networkMode === 'PROXIED'
            ? { networkMode, networkProfileId: profileId, idempotencyKey: createIdempotencyKey() }
            : { networkMode, idempotencyKey: createIdempotencyKey() }
        const response = await fetch(`/orchestrator/api/swarms/${encodeURIComponent(swarmId)}/network`, {
          method: 'POST',
          headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
          body: JSON.stringify(body),
        })
        if (!response.ok) {
          throw new Error(await readErrorMessage(response))
        }
        setMessage(
          networkMode === 'PROXIED'
            ? `Applied profile '${profileId}' to swarm '${swarmId}'.`
            : `Cleared proxy routing for swarm '${swarmId}'.`,
        )
        await reload()
      } catch (e) {
        setError(e instanceof Error ? e.message : 'Network update failed')
      } finally {
        setBusySwarmId(null)
      }
    },
    [profileDrafts, reload],
  )

  return (
    <div className="page">
      <div className="row between">
        <div>
          <h1 className="h1">Proxy Console</h1>
          <div className="muted">Runtime control for `DIRECT` and `PROXIED` swarms, backed by orchestrator + proxy manager.</div>
        </div>
        <button type="button" className="actionButton" onClick={() => void reload()} disabled={loading || busySwarmId !== null}>
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

      <div className="tileGrid" style={{ marginTop: 12 }}>
        <div className="card">
          <div className="muted">Tracked swarms</div>
          <div className="h1" style={{ marginTop: 8 }}>
            {rows.length}
          </div>
        </div>
        <div className="card">
          <div className="muted">Proxied</div>
          <div className="h1" style={{ marginTop: 8 }}>
            {totals.proxied}
          </div>
        </div>
        <div className="card">
          <div className="muted">Direct</div>
          <div className="h1" style={{ marginTop: 8 }}>
            {totals.direct}
          </div>
        </div>
        <div className="card">
          <div className="muted">Active endpoints</div>
          <div className="h1" style={{ marginTop: 8 }}>
            {totals.endpointCount}
          </div>
        </div>
      </div>

      <div className="swarmViewGrid" style={{ marginTop: 12 }}>
        <div className="swarmViewCards">
          <div className="card">
            <div className="row between">
              <div className="h2">Bindings</div>
              <div className={loading ? 'pill pillInfo' : 'pill pillOk'}>{loading ? 'LOADING' : `${rows.length}`}</div>
            </div>

            <div className="swarmCardList" style={{ maxHeight: 'min(74vh, 920px)', marginTop: 12 }}>
              {rows.map((row) => {
                const selectedProfile = profileDrafts[row.swarmId] ?? row.networkProfileId ?? profiles[0]?.id ?? ''
                const busy = busySwarmId === row.swarmId
                const canControl = row.sutId != null
                return (
                  <div key={row.swarmId} className="swarmWorkerCard">
                    <div className="row between">
                      <div>
                        <div className="h2">{row.swarmId}</div>
                        <div className="muted" style={{ marginTop: 4 }}>
                          template `{row.templateId ?? '—'}` • sut `{row.sutId ?? '—'}`
                        </div>
                      </div>
                      <div className="row">
                        <div className={row.networkMode === 'PROXIED' ? 'pill pillInfo' : 'pill pillWarn'}>{row.networkMode}</div>
                        {row.status ? <div className="pill pillOk">{row.status}</div> : null}
                      </div>
                    </div>

                    <div className="kvGrid" style={{ marginTop: 12 }}>
                      <div className="kv">
                        <div className="k">Profile</div>
                        <div className="v">{row.networkProfileId ?? '—'}</div>
                      </div>
                      <div className="kv">
                        <div className="k">Applied at</div>
                        <div className="v">{formatInstant(row.appliedAt)}</div>
                      </div>
                      <div className="kv">
                        <div className="k">Endpoints</div>
                        <div className="v">{row.affectedEndpoints.length}</div>
                      </div>
                      <div className="kv">
                        <div className="k">Requested by</div>
                        <div className="v">{row.bindingRequestedBy ?? '—'}</div>
                      </div>
                    </div>

                    {row.affectedEndpoints.length > 0 ? (
                      <div style={{ marginTop: 12, display: 'grid', gap: 8 }}>
                        {row.affectedEndpoints.map((endpoint) => (
                          <div key={`${row.swarmId}:${endpoint.endpointId}`} className="card" style={{ padding: 10 }}>
                            <div className="row between">
                              <div className="h2">{endpoint.endpointId}</div>
                              <div className="pill pillInfo">{endpoint.kind ?? 'UNKNOWN'}</div>
                            </div>
                            <div className="muted" style={{ marginTop: 6 }}>
                              client `{endpoint.clientAuthority ?? endpoint.clientBaseUrl ?? '—'}`
                            </div>
                            <div className="muted" style={{ marginTop: 4 }}>
                              upstream `{endpoint.upstreamAuthority ?? '—'}`
                            </div>
                          </div>
                        ))}
                      </div>
                    ) : (
                      <div className="muted" style={{ marginTop: 12 }}>
                        {row.hasBinding ? 'Binding exists but has no affected endpoints.' : 'No active binding in proxy manager.'}
                      </div>
                    )}

                    <div className="formGrid" style={{ marginTop: 14 }}>
                      <label className="field">
                        <span className="fieldLabel">Profile for PROXIED</span>
                        <select
                          className="textInput textInputCompact"
                          value={selectedProfile}
                          onChange={(event) =>
                            setProfileDrafts((current) => ({
                              ...current,
                              [row.swarmId]: event.target.value,
                            }))
                          }
                          disabled={busy || !canControl || profiles.length === 0}
                        >
                          {profiles.length === 0 ? <option value="">No profiles</option> : null}
                          {profiles.map((profile) => (
                            <option key={profile.id} value={profile.id}>
                              {profile.name ? `${profile.id} · ${profile.name}` : profile.id}
                            </option>
                          ))}
                        </select>
                      </label>
                      <div className="field">
                        <span className="fieldLabel">Actions</span>
                        <div className="row" style={{ flexWrap: 'wrap' }}>
                          <button
                            type="button"
                            className="actionButton"
                            onClick={() => void applyNetworkMode(row.swarmId, 'PROXIED')}
                            disabled={busy || !canControl || selectedProfile.trim().length === 0}
                          >
                            Proxy enabled
                          </button>
                          <button
                            type="button"
                            className="actionButton actionButtonGhost"
                            onClick={() => void applyNetworkMode(row.swarmId, 'DIRECT')}
                            disabled={busy || !canControl}
                          >
                            Direct
                          </button>
                          <Link className="actionButton actionButtonGhost" to={`/hive/${encodeURIComponent(row.swarmId)}`}>
                            Open swarm
                          </Link>
                        </div>
                        {!canControl ? (
                          <div className="muted" style={{ marginTop: 6 }}>
                            Runtime control requires a swarm with bound SUT metadata.
                          </div>
                        ) : null}
                      </div>
                    </div>
                  </div>
                )
              })}
              {!loading && rows.length === 0 ? <div className="muted">No swarms or bindings available.</div> : null}
            </div>
          </div>
        </div>

        <div className="swarmViewCards">
          <div className="card">
            <div className="row between">
              <div className="h2">Profiles</div>
              <div className="pill pillInfo">{profiles.length}</div>
            </div>

            <div style={{ marginTop: 12, display: 'grid', gap: 8 }}>
              {profiles.map((profile) => (
                <div key={profile.id} className="swarmWorkerCard">
                  <div className="row between">
                    <div className="h2">{profile.id}</div>
                    <div className={profile.faults.length === 0 ? 'pill pillWarn' : 'pill pillInfo'}>
                      {profile.faults.length === 0 ? 'PASSTHROUGH' : `${profile.faults.length} FAULTS`}
                    </div>
                  </div>
                  <div className="muted" style={{ marginTop: 6 }}>
                    {profile.name ?? 'Unnamed profile'}
                  </div>
                  <div className="muted" style={{ marginTop: 6 }}>
                    Targets: {profile.targets.length === 0 ? 'all resolved endpoints' : profile.targets.join(', ')}
                  </div>
                  <div className="muted" style={{ marginTop: 6 }}>
                    {summarizeFaults(profile)}
                  </div>
                  {profile.faults.length > 0 ? (
                    <div style={{ marginTop: 8, display: 'grid', gap: 6 }}>
                      {profile.faults.map((fault, index) => (
                        <div key={`${profile.id}:${fault.type}:${index}`} className="card" style={{ padding: 10 }}>
                          <div className="h2">{fault.type}</div>
                          <div className="muted" style={{ marginTop: 4 }}>
                            {summarizeFaultConfig(fault.config)}
                          </div>
                        </div>
                      ))}
                    </div>
                  ) : null}
                </div>
              ))}
              {!loading && profiles.length === 0 ? <div className="muted">No network profiles available.</div> : null}
            </div>
          </div>

          <div className="card" style={{ marginTop: 12 }}>
            <div className="row between">
              <div className="h2">Proxy runtime</div>
              <div className="pill pillInfo">{proxies.length}</div>
            </div>
            <div style={{ marginTop: 12, display: 'grid', gap: 8 }}>
              {proxies.map((proxy) => (
                <div key={`${proxy.swarmId}:${proxy.sutId}`} className="swarmWorkerCard">
                  <div className="row between">
                    <div className="h2">{proxy.swarmId}</div>
                    <div className={proxy.effectiveMode === 'PROXIED' ? 'pill pillInfo' : 'pill pillWarn'}>
                      {proxy.effectiveMode}
                    </div>
                  </div>
                  <div className="muted" style={{ marginTop: 6 }}>
                    sut `{proxy.sutId}` • profile `{proxy.networkProfileId ?? '—'}`
                  </div>
                  <div className="muted" style={{ marginTop: 6 }}>
                    requestedBy `{proxy.requestedBy}` • {formatInstant(proxy.appliedAt)}
                  </div>
                </div>
              ))}
              {!loading && proxies.length === 0 ? <div className="muted">No active proxy runtime entries.</div> : null}
            </div>
          </div>
        </div>
      </div>
    </div>
  )
}
