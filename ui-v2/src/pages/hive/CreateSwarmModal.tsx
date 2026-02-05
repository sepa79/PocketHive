import React, { useCallback, useEffect, useMemo, useState } from 'react'
import YAML from 'yaml'

type BeeSummary = {
  role: string
  image: string | null
}

type ScenarioTemplate = {
  id: string
  name: string
  description: string | null
  controllerImage: string | null
  bees: BeeSummary[]
}

type VariablesProfile = {
  id: string
  name: string | null
}

type VariablesMeta = {
  exists: boolean
  hasGlobalVars: boolean
  hasSutVars: boolean
  profiles: VariablesProfile[]
}

const ORCHESTRATOR_BASE = '/orchestrator/api'
const TEMPLATES_ENDPOINT = '/scenario-manager/api/templates'

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
        typeof value.description === 'string' && value.description.trim().length > 0 ? value.description.trim() : null
      const controllerImage =
        typeof value.controllerImage === 'string' && value.controllerImage.trim().length > 0 ? value.controllerImage.trim() : null
      const bees: BeeSummary[] = Array.isArray(value.bees)
        ? value.bees
            .map((bee) => {
              if (!bee || typeof bee !== 'object') return null
              const beeValue = bee as Record<string, unknown>
              const role = typeof beeValue.role === 'string' ? beeValue.role.trim() : ''
              if (!role) return null
              const image =
                typeof beeValue.image === 'string' && beeValue.image.trim().length > 0 ? beeValue.image.trim() : null
              return { role, image }
            })
            .filter((bee): bee is BeeSummary => bee !== null)
        : []
      return { id, name, description, controllerImage, bees }
    })
    .filter((template): template is ScenarioTemplate => template !== null)
}

function extractVariablesMeta(yamlText: string): VariablesMeta {
  const parsed = YAML.parse(yamlText) as unknown
  if (!parsed || typeof parsed !== 'object') {
    return { exists: true, hasGlobalVars: false, hasSutVars: false, profiles: [] }
  }
  const obj = parsed as Record<string, unknown>

  const profiles: VariablesProfile[] = Array.isArray(obj.profiles)
    ? obj.profiles
        .map((raw) => {
          if (!raw || typeof raw !== 'object') return null
          const rec = raw as Record<string, unknown>
          const id = typeof rec.id === 'string' ? rec.id.trim() : ''
          if (!id) return null
          const name = typeof rec.name === 'string' && rec.name.trim().length > 0 ? rec.name.trim() : null
          return { id, name }
        })
        .filter((p): p is VariablesProfile => p !== null)
    : []

  const defs: unknown[] = Array.isArray(obj.definitions) ? obj.definitions : []
  let hasGlobalVars = false
  let hasSutVars = false
  for (const raw of defs) {
    if (!raw || typeof raw !== 'object') continue
    const rec = raw as Record<string, unknown>
    const scope = typeof rec.scope === 'string' ? rec.scope.trim().toLowerCase() : ''
    if (scope === 'global') hasGlobalVars = true
    if (scope === 'sut') hasSutVars = true
  }

  return { exists: true, hasGlobalVars, hasSutVars, profiles }
}

export function CreateSwarmModal({
  open,
  onClose,
  onCreated,
}: {
  open: boolean
  onClose: () => void
  onCreated: () => void
}) {
  const [templates, setTemplates] = useState<ScenarioTemplate[]>([])
  const [templatesLoaded, setTemplatesLoaded] = useState(false)
  const [templateFilter, setTemplateFilter] = useState('')
  const [templateId, setTemplateId] = useState('')
  const [swarmId, setSwarmId] = useState('')

  const [sutIds, setSutIds] = useState<string[]>([])
  const [sutId, setSutId] = useState('')

  const [variablesMeta, setVariablesMeta] = useState<VariablesMeta>({
    exists: false,
    hasGlobalVars: false,
    hasSutVars: false,
    profiles: [],
  })
  const [variablesProfileId, setVariablesProfileId] = useState('')

  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)

  const loadTemplates = useCallback(async () => {
    if (templatesLoaded) return
    try {
      const response = await fetch(TEMPLATES_ENDPOINT, { headers: { Accept: 'application/json' } })
      if (!response.ok) {
        setTemplates([])
        setTemplatesLoaded(true)
        return
      }
      const payload = await response.json()
      setTemplates(normalizeTemplates(payload))
      setTemplatesLoaded(true)
    } catch {
      setTemplates([])
      setTemplatesLoaded(true)
    }
  }, [templatesLoaded])

  useEffect(() => {
    if (!open) return
    void loadTemplates()
  }, [loadTemplates, open])

  useEffect(() => {
    if (!open) return
    setError(null)
    setMessage(null)
  }, [open])

  useEffect(() => {
    if (!open) return
    if (!templateId) {
      setSutIds([])
      setSutId('')
      setVariablesMeta({ exists: false, hasGlobalVars: false, hasSutVars: false, profiles: [] })
      setVariablesProfileId('')
      return
    }

    setError(null)
    setMessage(null)
    setSutIds([])
    setSutId('')
    setVariablesMeta({ exists: false, hasGlobalVars: false, hasSutVars: false, profiles: [] })
    setVariablesProfileId('')

    const controller = new AbortController()

    const loadSuts = async () => {
      try {
        const response = await fetch(`/scenario-manager/scenarios/${encodeURIComponent(templateId)}/suts`, {
          headers: { Accept: 'application/json' },
          signal: controller.signal,
        })
        if (!response.ok) {
          setSutIds([])
          return
        }
        const payload = (await response.json()) as unknown
        if (!Array.isArray(payload)) {
          setSutIds([])
          return
        }
        const normalized = payload
          .map((v) => (typeof v === 'string' ? v.trim() : ''))
          .filter((v) => v.length > 0)
        setSutIds(normalized)
        if (normalized.length === 1) {
          setSutId(normalized[0])
        }
      } catch {
        setSutIds([])
      }
    }

    const loadVariables = async () => {
      try {
        const response = await fetch(`/scenario-manager/scenarios/${encodeURIComponent(templateId)}/variables`, {
          headers: { Accept: 'text/plain' },
          signal: controller.signal,
        })
        if (response.status === 404) {
          setVariablesMeta({ exists: false, hasGlobalVars: false, hasSutVars: false, profiles: [] })
          return
        }
        if (!response.ok) {
          setVariablesMeta({ exists: false, hasGlobalVars: false, hasSutVars: false, profiles: [] })
          return
        }
        const yamlText = await response.text()
        const meta = extractVariablesMeta(yamlText)
        setVariablesMeta(meta)
        if (meta.profiles.length === 1) {
          setVariablesProfileId(meta.profiles[0].id)
        }
      } catch {
        setVariablesMeta({ exists: false, hasGlobalVars: false, hasSutVars: false, profiles: [] })
      }
    }

    void loadSuts()
    void loadVariables()

    return () => controller.abort()
  }, [open, templateId])

  const filteredTemplates = useMemo(() => {
    const needle = templateFilter.trim().toLowerCase()
    if (!needle) return templates
    return templates.filter((template) => {
      const haystack = `${template.id} ${template.name} ${template.description ?? ''}`.toLowerCase()
      return haystack.includes(needle)
    })
  }, [templateFilter, templates])

  const selectedTemplate = useMemo(() => templates.find((template) => template.id === templateId) ?? null, [templateId, templates])

  const hasBundleSuts = sutIds.length > 0
  const requiresProfile = variablesMeta.exists && (variablesMeta.hasGlobalVars || variablesMeta.hasSutVars)
  const requiresSut = variablesMeta.exists && variablesMeta.hasSutVars

  const handleCreate = useCallback(
    async (event: React.FormEvent) => {
      event.preventDefault()
      if (busy) return
      setError(null)
      setMessage(null)

      const trimmedSwarmId = swarmId.trim()
      const trimmedTemplateId = templateId.trim()
      const trimmedSutId = sutId.trim()
      const trimmedProfileId = variablesProfileId.trim()

      if (!trimmedSwarmId || !trimmedTemplateId) {
        setError('Swarm ID and template are required.')
        return
      }

      if (requiresProfile && !trimmedProfileId) {
        setError('Variables profile is required for this scenario.')
        return
      }

      if (requiresSut && !trimmedSutId) {
        setError('SUT is required for this scenario (SUT-scoped variables).')
        return
      }

      setBusy(true)
      try {
        const response = await fetch(`${ORCHESTRATOR_BASE}/swarms/${encodeURIComponent(trimmedSwarmId)}/create`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            templateId: trimmedTemplateId,
            idempotencyKey: createIdempotencyKey(),
            sutId: trimmedSutId ? trimmedSutId : null,
            variablesProfileId: trimmedProfileId ? trimmedProfileId : null,
          }),
        })
        if (!response.ok) {
          throw new Error(await readErrorMessage(response))
        }
        setMessage(`Create request accepted for ${trimmedSwarmId}.`)
        setSwarmId('')
        setTemplateId('')
        setTemplateFilter('')
        setSutIds([])
        setSutId('')
        setVariablesMeta({ exists: false, hasGlobalVars: false, hasSutVars: false, profiles: [] })
        setVariablesProfileId('')
        onCreated()
        onClose()
      } catch (err) {
        setError(err instanceof Error ? err.message : 'Failed to create swarm.')
      } finally {
        setBusy(false)
      }
    },
    [busy, onClose, onCreated, requiresProfile, requiresSut, swarmId, sutId, templateId, variablesProfileId],
  )

  if (!open) return null

  return (
    <div className="modalBackdrop" role="presentation" onClick={onClose}>
      <div className="modal" role="dialog" aria-modal="true" onClick={(event) => event.stopPropagation()}>
        <div className="modalHeader">
          <div>
            <div className="h2">Create swarm</div>
            <div className="muted">Provision a controller from a scenario template.</div>
          </div>
          <button type="button" className="actionButton actionButtonGhost" onClick={onClose}>
            Close
          </button>
        </div>

        {error ? <div className="card swarmMessage">{error}</div> : null}
        {message ? <div className="card swarmMessage">{message}</div> : null}

        <form className="modalSection modalSectionGrow" onSubmit={handleCreate}>
          <div className="formGrid">
            <label className="field">
              <span className="fieldLabel">Swarm ID</span>
              <input className="textInput" value={swarmId} onChange={(event) => setSwarmId(event.target.value)} placeholder="demo" />
            </label>

            {hasBundleSuts || requiresSut ? (
              <label className="field">
                <span className="fieldLabel">Bundle SUT{requiresSut ? ' (required)' : ''}</span>
                <select className="textInput" value={sutId} onChange={(event) => setSutId(event.target.value)}>
                  <option value="">(none)</option>
                  {sutIds.map((id) => (
                    <option key={id} value={id}>
                      {id}
                    </option>
                  ))}
                </select>
              </label>
            ) : (
              <div />
            )}

            {variablesMeta.exists ? (
              <label className="field">
                <span className="fieldLabel">Variables profile{requiresProfile ? ' (required)' : ''}</span>
                <select
                  className="textInput"
                  value={variablesProfileId}
                  onChange={(event) => setVariablesProfileId(event.target.value)}
                >
                  <option value="">(select)</option>
                  {variablesMeta.profiles.map((profile) => (
                    <option key={profile.id} value={profile.id}>
                      {profile.name ? `${profile.name} (${profile.id})` : profile.id}
                    </option>
                  ))}
                </select>
                <div className="muted" style={{ marginTop: 6 }}>
                  vars: {variablesMeta.hasGlobalVars ? 'global' : '—'} · {variablesMeta.hasSutVars ? 'sut' : '—'}
                </div>
              </label>
            ) : (
              <div />
            )}
          </div>

          <div className="swarmTemplatePicker swarmTemplatePickerGrow" style={{ marginTop: 12 }}>
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
                {!templatesLoaded ? (
                  <div className="muted">Loading templates…</div>
                ) : filteredTemplates.length === 0 ? (
                  <div className="muted">No templates found.</div>
                ) : (
                  filteredTemplates.map((template) => (
                    <button
                      key={template.id}
                      type="button"
                      className={template.id === templateId ? 'swarmTemplateItem swarmTemplateItemSelected' : 'swarmTemplateItem'}
                      onClick={() => setTemplateId(template.id)}
                    >
                      <div className="swarmTemplateTitle">{template.name}</div>
                      <div className="swarmTemplateId">{template.id}</div>
                      <div className="swarmTemplateDesc">{template.description ?? 'No description'}</div>
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
                  <div className="muted">{selectedTemplate.description ?? 'No description provided.'}</div>
                  <div className="swarmTemplateMeta">
                    <div>
                      <span className="fieldLabel">Controller image</span>
                      <div className="swarmTemplateValue">{selectedTemplate.controllerImage ?? '—'}</div>
                    </div>
                    <div>
                      <span className="fieldLabel">Bees</span>
                      <div className="swarmTemplateValue">
                        {selectedTemplate.bees.length === 0 ? '—' : selectedTemplate.bees.map((bee) => bee.role).join(', ')}
                      </div>
                    </div>
                  </div>
                </>
              ) : (
                <div className="muted">Select a template to see details.</div>
              )}
            </div>
          </div>

          <div className="row between" style={{ marginTop: 12 }}>
            <div className="muted">Create sends a request; start is a separate action.</div>
            <button type="submit" className="actionButton" disabled={busy}>
              {busy ? 'Creating…' : 'Create'}
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}
