import { useCallback, useEffect, useMemo, useState } from 'react'
import { useToolsBar } from '../components/ToolsBarContext'

type BeeSummary = {
  role: string
  image: string | null
}

type SwarmSummary = {
  id: string
  status?: string | null
  health?: string | null
  heartbeat?: string | null
  workEnabled?: boolean
  controllerEnabled?: boolean
  templateId?: string | null
  controllerImage?: string | null
  bees?: BeeSummary[]
}

type ScenarioTemplate = {
  id: string
  name: string
  description: string | null
  controllerImage: string | null
  bees: BeeSummary[]
}

type SutEnvironment = {
  id: string
  name: string
  type: string | null
}

type ScenarioBee = {
  id: string | null
  role: string | null
  image: string | null
  work: {
    in: Record<string, string> | undefined
    out: Record<string, string> | undefined
  } | null
  ports: { id: string; direction: 'in' | 'out' }[] | null
}

type ScenarioTopologyEdge = {
  id: string | null
  from: { beeId: string | null; port: string | null } | null
  to: { beeId: string | null; port: string | null } | null
}

type ScenarioDefinition = {
  id: string | null
  name: string | null
  description: string | null
  template?: {
    image: string | null
    bees?: ScenarioBee[]
  } | null
  topology?: {
    version: number | null
    edges?: ScenarioTopologyEdge[]
  } | null
}

type SwarmAction = 'start' | 'stop' | 'remove'

const ORCHESTRATOR_BASE = '/orchestrator/api'
const TEMPLATES_ENDPOINT = '/scenario-manager/api/templates'
const SUT_ENDPOINT = '/scenario-manager/sut-environments'

function createIdempotencyKey() {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) {
    return crypto.randomUUID()
  }
  return `ph-${Date.now()}-${Math.random().toString(16).slice(2)}`
}

function normalizeTemplates(data: unknown): ScenarioTemplate[] {
  if (!Array.isArray(data)) return []
  return data
    .map((entry) => {
      if (!entry || typeof entry !== 'object') return null
      const value = entry as Record<string, unknown>
      const id = typeof value.id === 'string' ? value.id.trim() : ''
      const name = typeof value.name === 'string' ? value.name.trim() : ''
      if (!id || !name) return null
      const description =
        typeof value.description === 'string' && value.description.trim().length > 0
          ? value.description.trim()
          : null
      const controllerImage =
        typeof value.controllerImage === 'string' && value.controllerImage.trim().length > 0
          ? value.controllerImage.trim()
          : null
      const bees: BeeSummary[] = Array.isArray(value.bees)
        ? value.bees
            .map((bee) => {
              if (!bee || typeof bee !== 'object') return null
              const beeValue = bee as Record<string, unknown>
              const role = typeof beeValue.role === 'string' ? beeValue.role.trim() : ''
              const image =
                typeof beeValue.image === 'string' && beeValue.image.trim().length > 0
                  ? beeValue.image.trim()
                  : null
              if (!role) return null
              return { role, image }
            })
            .filter((bee): bee is BeeSummary => bee !== null)
        : []
      return { id, name, description, controllerImage, bees }
    })
    .filter((entry): entry is ScenarioTemplate => entry !== null)
}

function normalizeSutEnvironments(data: unknown): SutEnvironment[] {
  if (!Array.isArray(data)) return []
  const result: SutEnvironment[] = []
  for (const entry of data) {
    if (!entry || typeof entry !== 'object') continue
    const value = entry as Record<string, unknown>
    const id = typeof value.id === 'string' ? value.id.trim() : ''
    const name = typeof value.name === 'string' ? value.name.trim() : ''
    if (!id || !name) continue
    const type =
      typeof value.type === 'string' && value.type.trim().length > 0
        ? value.type.trim()
        : null
    result.push({ id, name, type })
  }
  return result
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

function toStringOrNull(value: unknown): string | null {
  return typeof value === 'string' && value.trim().length > 0 ? value.trim() : null
}

function formatWorkMap(value?: Record<string, string>) {
  if (!value) return '—'
  const entries = Object.entries(value)
  if (entries.length === 0) return '—'
  return entries.map(([key, item]) => `${key}: ${item}`).join(', ')
}

function asScenarioDefinition(data: unknown): ScenarioDefinition | null {
  if (!isRecord(data)) return null
  const template = isRecord(data.template) ? data.template : null
  const bees = Array.isArray(template?.bees)
    ? (template?.bees as unknown[])
        .map((bee) => {
          if (!isRecord(bee)) return null
          const work = isRecord(bee.work) ? bee.work : null
          const workIn = isRecord(work?.in) ? (work?.in as Record<string, string>) : undefined
          const workOut = isRecord(work?.out) ? (work?.out as Record<string, string>) : undefined
          const ports = Array.isArray(bee.ports)
            ? (bee.ports as unknown[])
                .map((port) => {
                  if (!isRecord(port)) return null
                  const id = toStringOrNull(port.id)
                  const direction = toStringOrNull(port.direction)
                  if (!id || (direction !== 'in' && direction !== 'out')) return null
                  return { id, direction }
                })
                .filter((port): port is { id: string; direction: 'in' | 'out' } => port !== null)
            : null
          return {
            id: toStringOrNull(bee.id),
            role: toStringOrNull(bee.role),
            image: toStringOrNull(bee.image),
            work: workIn || workOut ? { in: workIn, out: workOut } : null,
            ports,
          }
        })
        .filter((bee): bee is ScenarioBee => bee !== null)
    : undefined
  const topology = isRecord(data.topology) ? data.topology : null
  const edges = Array.isArray(topology?.edges)
    ? (topology?.edges as unknown[])
        .map((edge) => {
          if (!isRecord(edge)) return null
          const from = isRecord(edge.from) ? edge.from : null
          const to = isRecord(edge.to) ? edge.to : null
          return {
            id: toStringOrNull(edge.id),
            from: from
              ? { beeId: toStringOrNull(from.beeId), port: toStringOrNull(from.port) }
              : null,
            to: to ? { beeId: toStringOrNull(to.beeId), port: toStringOrNull(to.port) } : null,
          }
        })
        .filter((edge): edge is ScenarioTopologyEdge => edge !== null)
    : undefined

  return {
    id: toStringOrNull(data.id),
    name: toStringOrNull(data.name),
    description: toStringOrNull(data.description),
    template: template
      ? {
          image: toStringOrNull(template.image),
          bees,
        }
      : null,
    topology: topology
      ? {
          version: typeof topology.version === 'number' ? topology.version : null,
          edges,
        }
      : null,
  }
}

async function readErrorMessage(response: Response): Promise<string> {
  try {
    const text = await response.text()
    if (!text) return `HTTP ${response.status}`
    try {
      const data = JSON.parse(text) as { message?: unknown }
      if (data && typeof data.message === 'string') {
        return data.message
      }
    } catch {
      return text
    }
    return text
  } catch {
    return `HTTP ${response.status}`
  }
}

function healthClass(health: string | null | undefined) {
  if (!health) return 'pill pillInfo'
  const normalized = health.toUpperCase()
  if (normalized === 'HEALTHY') return 'pill pillOk'
  if (normalized === 'DEGRADED') return 'pill pillWarn'
  if (normalized === 'UNHEALTHY') return 'pill pillBad'
  return 'pill pillInfo'
}

export function HivePage() {
  const [swarms, setSwarms] = useState<SwarmSummary[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)
  const [showCreate, setShowCreate] = useState(false)
  const [templates, setTemplates] = useState<ScenarioTemplate[]>([])
  const [templateFilter, setTemplateFilter] = useState('')
  const [swarmId, setSwarmId] = useState('')
  const [templateId, setTemplateId] = useState('')
  const [sutEnvironments, setSutEnvironments] = useState<SutEnvironment[]>([])
  const [sutId, setSutId] = useState('')
  const [busySwarm, setBusySwarm] = useState<string | null>(null)
  const [busyAction, setBusyAction] = useState<SwarmAction | null>(null)
  const [selectedSwarmId, setSelectedSwarmId] = useState<string | null>(null)
  const [selectedScenario, setSelectedScenario] = useState<ScenarioDefinition | null>(null)
  const [scenarioError, setScenarioError] = useState<string | null>(null)
  const [scenarioLoading, setScenarioLoading] = useState(false)
  const [selectedBeeKey, setSelectedBeeKey] = useState<string | null>(null)

  const loadSwarms = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const response = await fetch(`${ORCHESTRATOR_BASE}/swarms`, {
        headers: { Accept: 'application/json' },
      })
      if (!response.ok) {
        throw new Error(await readErrorMessage(response))
      }
      const payload = (await response.json()) as SwarmSummary[]
      setSwarms(Array.isArray(payload) ? payload : [])
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load swarms')
      setSwarms([])
    } finally {
      setLoading(false)
    }
  }, [])

  const loadTemplates = useCallback(async () => {
    try {
      const response = await fetch(TEMPLATES_ENDPOINT, { headers: { Accept: 'application/json' } })
      if (!response.ok) {
        setTemplates([])
        return
      }
      const payload = await response.json()
      setTemplates(normalizeTemplates(payload))
    } catch {
      setTemplates([])
    }
  }, [])

  const loadSutEnvironments = useCallback(async () => {
    try {
      const response = await fetch(SUT_ENDPOINT, { headers: { Accept: 'application/json' } })
      if (!response.ok) {
        setSutEnvironments([])
        return
      }
      const payload = await response.json()
      setSutEnvironments(normalizeSutEnvironments(payload))
    } catch {
      setSutEnvironments([])
    }
  }, [])

  useEffect(() => {
    void loadSwarms()
  }, [loadSwarms])

  useEffect(() => {
    if (showCreate && templates.length === 0) {
      void loadTemplates()
    }
    if (showCreate && sutEnvironments.length === 0) {
      void loadSutEnvironments()
    }
  }, [loadSutEnvironments, loadTemplates, showCreate, sutEnvironments.length, templates.length])

  const handleCreate = useCallback(
    async (event: React.FormEvent) => {
      event.preventDefault()
      setMessage(null)
      const trimmedSwarmId = swarmId.trim()
      const trimmedTemplateId = templateId.trim()
      if (!trimmedSwarmId || !trimmedTemplateId) {
        setMessage('Swarm ID and template are required.')
        return
      }

      try {
        const response = await fetch(
          `${ORCHESTRATOR_BASE}/swarms/${encodeURIComponent(trimmedSwarmId)}/create`,
          {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
              templateId: trimmedTemplateId,
              idempotencyKey: createIdempotencyKey(),
              sutId: sutId.trim() ? sutId.trim() : null,
            }),
          },
        )
        if (!response.ok) {
          throw new Error(await readErrorMessage(response))
        }
        setMessage(`Create request accepted for ${trimmedSwarmId}.`)
        setSwarmId('')
        setTemplateId('')
        setSutId('')
        setTemplateFilter('')
        void loadSwarms()
      } catch (err) {
        setMessage(err instanceof Error ? err.message : 'Failed to create swarm.')
      }
    },
    [loadSwarms, swarmId, templateId],
  )

  const filteredTemplates = useMemo(() => {
    const needle = templateFilter.trim().toLowerCase()
    if (!needle) return templates
    return templates.filter((template) => {
      const haystack = `${template.id} ${template.name} ${template.description ?? ''}`.toLowerCase()
      return haystack.includes(needle)
    })
  }, [templateFilter, templates])

  const selectedTemplate = useMemo(
    () => templates.find((template) => template.id === templateId) ?? null,
    [templateId, templates],
  )

  const runSwarmAction = useCallback(
    async (swarm: SwarmSummary, action: SwarmAction) => {
      if (!swarm.id) return
      setMessage(null)
      setBusySwarm(swarm.id)
      setBusyAction(action)
      try {
        const response = await fetch(
          `${ORCHESTRATOR_BASE}/swarms/${encodeURIComponent(swarm.id)}/${action}`,
          {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ idempotencyKey: createIdempotencyKey() }),
          },
        )
        if (!response.ok) {
          throw new Error(await readErrorMessage(response))
        }
        setMessage(`${action} request accepted for ${swarm.id}.`)
        void loadSwarms()
      } catch (err) {
        setMessage(err instanceof Error ? err.message : `Failed to ${action} swarm.`)
      } finally {
        setBusySwarm(null)
        setBusyAction(null)
      }
    },
    [loadSwarms],
  )

  useEffect(() => {
    const handle = window.setInterval(() => {
      void loadSwarms()
    }, 5000)
    return () => window.clearInterval(handle)
  }, [loadSwarms])

  const loadScenarioDetail = useCallback(
    async (scenarioId: string | null) => {
      if (!scenarioId) {
        setSelectedScenario(null)
        setScenarioError(null)
        setScenarioLoading(false)
        return
      }
      setScenarioLoading(true)
      setScenarioError(null)
      try {
        const response = await fetch(
          `/scenario-manager/scenarios/${encodeURIComponent(scenarioId)}`,
          {
            headers: { Accept: 'application/json' },
          },
        )
        if (!response.ok) {
          throw new Error(await readErrorMessage(response))
        }
        const payload = await response.json()
        setSelectedScenario(asScenarioDefinition(payload))
      } catch (err) {
        setSelectedScenario(null)
        setScenarioError(err instanceof Error ? err.message : 'Failed to load scenario')
      } finally {
        setScenarioLoading(false)
      }
    },
    [],
  )

  const toolsBar = useMemo(
    () => (
      <div className="pageToolsBarContent">
        <button type="button" className="actionButton" onClick={loadSwarms}>
          Refresh
        </button>
        <button
          type="button"
          className={showCreate ? 'actionButton' : 'actionButton actionButtonGhost'}
          onClick={() => setShowCreate((prev) => !prev)}
        >
          New swarm
        </button>
      </div>
    ),
    [loadSwarms, showCreate],
  )

  useToolsBar(toolsBar)

  const swarmCountLabel = loading
    ? 'Loading...'
    : swarms.length === 1
      ? '1 swarm'
      : `${swarms.length} swarms`

  return (
    <div className="page hivePage">
      <div>
        <h1 className="h1">Hive</h1>
        <div className="muted">Swarms and runtime controls.</div>
      </div>

      {error && <div className="card swarmMessage">{error}</div>}
      {message && <div className="card swarmMessage">{message}</div>}

      {showCreate && (
        <form className="card swarmCreateCard" onSubmit={handleCreate}>
          <div className="row between">
            <div>
              <div className="h2">Create swarm</div>
              <div className="muted">Provision a controller from a scenario template.</div>
            </div>
            <button
              type="button"
              className="actionButton actionButtonGhost"
              onClick={() => setShowCreate(false)}
            >
              Close
            </button>
          </div>
          <div className="formGrid">
            <label className="field">
              <span className="fieldLabel">Swarm ID</span>
              <input
                className="textInput"
                value={swarmId}
                onChange={(event) => setSwarmId(event.target.value)}
                placeholder="demo"
              />
            </label>
            <label className="field">
              <span className="fieldLabel">System under test</span>
              <select
                className="textInput"
                value={sutId}
                onChange={(event) => setSutId(event.target.value)}
              >
                <option value="">(none)</option>
                {sutEnvironments.map((env) => (
                  <option key={env.id} value={env.id}>
                    {env.name} {env.type ? `(${env.type})` : ''}
                  </option>
                ))}
              </select>
            </label>
          </div>
          <div className="swarmTemplatePicker">
            <div className="swarmTemplateList">
              <div className="swarmTemplateListHeader">
                <span>Templates</span>
                <input
                  className="textInput textInputCompact"
                  value={templateFilter}
                  onChange={(event) => setTemplateFilter(event.target.value)}
                  placeholder="Filter"
                />
              </div>
              <div className="swarmTemplateListBody">
                {filteredTemplates.length === 0 ? (
                  <div className="muted">No templates found.</div>
                ) : (
                  filteredTemplates.map((template) => (
                    <button
                      key={template.id}
                      type="button"
                      className={
                        template.id === templateId
                          ? 'swarmTemplateItem swarmTemplateItemSelected'
                          : 'swarmTemplateItem'
                      }
                      onClick={() => setTemplateId(template.id)}
                    >
                      <div className="swarmTemplateTitle">{template.name}</div>
                      <div className="swarmTemplateId">{template.id}</div>
                      <div className="swarmTemplateDesc">
                        {template.description ?? 'No description'}
                      </div>
                    </button>
                  ))
                )}
              </div>
            </div>
            <div className="swarmTemplateDetail">
              {selectedTemplate ? (
                <>
                  <div className="swarmTemplateTitle">{selectedTemplate.name}</div>
                  <div className="swarmTemplateId">{selectedTemplate.id}</div>
                  <div className="muted">
                    {selectedTemplate.description ?? 'No description provided.'}
                  </div>
                  <div className="swarmTemplateMeta">
                    <div>
                      <span className="fieldLabel">Controller image</span>
                      <div className="swarmTemplateValue">
                        {selectedTemplate.controllerImage ?? '—'}
                      </div>
                    </div>
                    <div>
                      <span className="fieldLabel">Bees</span>
                      <div className="swarmTemplateValue">
                        {selectedTemplate.bees.length === 0
                          ? '—'
                          : selectedTemplate.bees.map((bee) => bee.role).join(', ')}
                      </div>
                    </div>
                  </div>
                </>
              ) : (
                <div className="muted">Select a template to see details.</div>
              )}
            </div>
          </div>
          <div className="row between">
            <div className="muted">Create sends a request; start is a separate action.</div>
            <button type="submit" className="actionButton">
              Create
            </button>
          </div>
        </form>
      )}

      <div className="card swarmTableCard">
        <div className="row between">
          <div>
            <div className="h2">Swarms</div>
            <div className="muted">Current orchestrator view.</div>
          </div>
          <div className="muted">{swarmCountLabel}</div>
        </div>
        <div className="swarmTable">
          <div className="swarmRow swarmHeader">
            <div className="swarmCell">Swarm</div>
            <div className="swarmCell">State</div>
            <div className="swarmCell">Template</div>
            <div className="swarmCell">Bees</div>
            <div className="swarmCell swarmActions">Actions</div>
          </div>
          {swarms.map((swarm) => {
            const beeRoles =
              swarm.bees && swarm.bees.length > 0
                ? swarm.bees.map((bee) => bee.role).filter(Boolean).join(', ')
                : '—'
            const isBusy = busySwarm === swarm.id
            return (
              <div key={swarm.id} className="swarmCard">
                <div className="swarmRow">
                  <div className="swarmCell">
                    <div className="swarmName">{swarm.id}</div>
                    <div className="muted">{swarm.controllerImage ?? 'controller: unknown'}</div>
                  </div>
                  <div className="swarmCell swarmMeta">
                    <span className="pill pillInfo">{swarm.status ?? 'UNKNOWN'}</span>
                    <span className={healthClass(swarm.health)}>{swarm.health ?? 'UNKNOWN'}</span>
                  </div>
                  <div className="swarmCell">
                    <div className="swarmTemplate">{swarm.templateId ?? '—'}</div>
                  </div>
                  <div className="swarmCell swarmBees">{beeRoles}</div>
                  <div className="swarmCell swarmActions">
                    <button
                      type="button"
                      className="actionButton actionButtonGhost"
                      onClick={() => {
                        const next = selectedSwarmId === swarm.id ? null : swarm.id
                        setSelectedSwarmId(next)
                        setSelectedBeeKey(null)
                        void loadScenarioDetail(next ? swarm.templateId ?? null : null)
                      }}
                    >
                      {selectedSwarmId === swarm.id ? 'Hide' : 'Details'}
                    </button>
                    <button
                      type="button"
                      className="actionButton"
                      disabled={isBusy}
                      onClick={() => runSwarmAction(swarm, 'start')}
                    >
                      {isBusy && busyAction === 'start' ? 'Starting...' : 'Start'}
                    </button>
                    <button
                      type="button"
                      className="actionButton actionButtonGhost"
                      disabled={isBusy}
                      onClick={() => runSwarmAction(swarm, 'stop')}
                    >
                      {isBusy && busyAction === 'stop' ? 'Stopping...' : 'Stop'}
                    </button>
                    <button
                      type="button"
                      className="actionButton actionButtonDanger"
                      disabled={isBusy}
                      onClick={() => runSwarmAction(swarm, 'remove')}
                    >
                      {isBusy && busyAction === 'remove' ? 'Removing...' : 'Remove'}
                    </button>
                  </div>
                </div>
                {selectedSwarmId === swarm.id && (
                  <div className="swarmDetail">
                    {scenarioLoading && <div className="muted">Loading scenario details...</div>}
                    {scenarioError && <div className="muted">{scenarioError}</div>}
                    {!scenarioLoading && !scenarioError && selectedScenario && (
                      <>
                        <div className="swarmDetailHeader">
                          <div>
                            <div className="swarmTemplateTitle">
                              {selectedScenario.name ?? swarm.templateId ?? 'Scenario'}
                            </div>
                            <div className="swarmTemplateId">{swarm.templateId ?? '—'}</div>
                          </div>
                          <div className="swarmDetailMeta">
                            <span className="pill pillInfo">
                              Bees {selectedScenario.template?.bees?.length ?? 0}
                            </span>
                            <span className="pill pillInfo">
                              Edges {selectedScenario.topology?.edges?.length ?? 0}
                            </span>
                          </div>
                        </div>
                        <div className="swarmDetailGrid">
                          <div className="swarmDetailSection">
                            <div className="fieldLabel">Bees</div>
                            {selectedScenario.template?.bees?.length ? (
                              <div className="swarmBeeList">
                                {selectedScenario.template.bees.map((bee, idx) => {
                                  const label = bee.role ?? bee.id ?? `bee-${idx + 1}`
                                  const key = bee.id ?? bee.role ?? `bee-${idx + 1}`
                                  const isActive = (selectedBeeKey ?? (selectedScenario.template?.bees?.[0]
                                    ? selectedScenario.template.bees[0].id ??
                                      selectedScenario.template.bees[0].role ??
                                      'bee-1'
                                    : null)) === key
                                  return (
                                    <button
                                      key={`${label}-${idx}`}
                                      type="button"
                                      className={
                                        isActive
                                          ? 'swarmBeeItem swarmBeeItemSelected'
                                          : 'swarmBeeItem'
                                      }
                                      onClick={() => setSelectedBeeKey(key)}
                                    >
                                      <div className="swarmBeeHeader">
                                        <span className="swarmBeeRole">{label}</span>
                                        <span className="swarmBeeImage">{bee.image ?? '—'}</span>
                                      </div>
                                      <div className="swarmBeeMeta">
                                        <span>in: {formatWorkMap(bee.work?.in)}</span>
                                        <span>out: {formatWorkMap(bee.work?.out)}</span>
                                      </div>
                                    </button>
                                  )
                                })}
                              </div>
                            ) : (
                              <div className="muted">No bees listed.</div>
                            )}
                          </div>
                          <div className="swarmDetailSection">
                            <div className="fieldLabel">Selected worker</div>
                            {selectedScenario.template?.bees?.length ? (() => {
                              const bees = selectedScenario.template?.bees ?? []
                              const fallback = bees[0]
                              const activeKey =
                                selectedBeeKey ??
                                (fallback ? fallback.id ?? fallback.role ?? 'bee-1' : null)
                              const activeBee =
                                bees.find(
                                  (bee, idx) =>
                                    (bee.id ?? bee.role ?? `bee-${idx + 1}`) === activeKey,
                                ) ?? fallback
                              if (!activeBee) return <div className="muted">No bee selected.</div>
                              const ports = activeBee.ports
                                ? activeBee.ports
                                    .map((port) => `${port.id}:${port.direction}`)
                                    .join(', ')
                                : '—'
                              return (
                                <div className="swarmWorkerDetail">
                                  <div className="swarmBeeHeader">
                                    <span className="swarmBeeRole">
                                      {activeBee.role ?? activeBee.id ?? 'worker'}
                                    </span>
                                    <span className="swarmBeeImage">{activeBee.image ?? '—'}</span>
                                  </div>
                                  <div className="swarmBeeMeta">
                                    <span>id: {activeBee.id ?? '—'}</span>
                                    <span>ports: {ports}</span>
                                    <span>in: {formatWorkMap(activeBee.work?.in)}</span>
                                    <span>out: {formatWorkMap(activeBee.work?.out)}</span>
                                  </div>
                                </div>
                              )
                            })() : (
                              <div className="muted">No bees listed.</div>
                            )}
                          </div>
                          <div className="swarmDetailSection swarmDetailSectionWide">
                            <div className="fieldLabel">Topology</div>
                            {selectedScenario.topology?.edges?.length ? (
                              <div className="swarmEdgeList">
                                {selectedScenario.topology.edges.map((edge, idx) => {
                                  const from = edge.from
                                    ? `${edge.from.beeId ?? 'bee'}:${edge.from.port ?? 'port'}`
                                    : '—'
                                  const to = edge.to
                                    ? `${edge.to.beeId ?? 'bee'}:${edge.to.port ?? 'port'}`
                                    : '—'
                                  return (
                                    <div key={`${edge.id ?? idx}`} className="swarmEdgeItem">
                                      <span>{edge.id ?? `edge-${idx + 1}`}</span>
                                      <span className="muted">
                                        {from} → {to}
                                      </span>
                                    </div>
                                  )
                                })}
                              </div>
                            ) : (
                              <div className="muted">No topology edges defined.</div>
                            )}
                          </div>
                        </div>
                      </>
                    )}
                    {!scenarioLoading && !scenarioError && !selectedScenario && (
                      <div className="muted">Scenario details unavailable.</div>
                    )}
                  </div>
                )}
              </div>
            )
          })}
          {loading && swarms.length === 0 && <div className="muted">Loading swarms...</div>}
          {!loading && swarms.length === 0 && (
            <div className="muted">No swarms found. Create one to get started.</div>
          )}
        </div>
      </div>
    </div>
  )
}
