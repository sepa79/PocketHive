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

type SwarmAction = 'start' | 'stop' | 'remove'

const ORCHESTRATOR_BASE = '/orchestrator/api'
const TEMPLATES_ENDPOINT = '/scenario-manager/api/templates'

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
  const [busySwarm, setBusySwarm] = useState<string | null>(null)
  const [busyAction, setBusyAction] = useState<SwarmAction | null>(null)

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

  useEffect(() => {
    void loadSwarms()
  }, [loadSwarms])

  useEffect(() => {
    if (showCreate && templates.length === 0) {
      void loadTemplates()
    }
  }, [loadTemplates, showCreate, templates.length])

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
            }),
          },
        )
        if (!response.ok) {
          throw new Error(await readErrorMessage(response))
        }
        setMessage(`Create request accepted for ${trimmedSwarmId}.`)
        setSwarmId('')
        setTemplateId('')
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
          <label className="field">
            <span className="fieldLabel">Swarm ID</span>
            <input
              className="textInput"
              value={swarmId}
              onChange={(event) => setSwarmId(event.target.value)}
              placeholder="demo"
            />
          </label>
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
              <div key={swarm.id} className="swarmRow">
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
