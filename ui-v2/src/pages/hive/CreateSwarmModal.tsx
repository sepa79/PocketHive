import React, { useCallback, useEffect, useMemo, useState } from 'react'
import YAML from 'yaml'
import { ScenarioTree } from '../../components/scenarios/ScenarioTree'

type BeeSummary = {
  role: string
  image: string | null
}

type ScenarioTemplate = {
  bundleKey: string
  bundlePath: string
  id: string | null
  name: string
  folderPath: string | null
  description: string | null
  controllerImage: string | null
  bees: BeeSummary[]
  defunct: boolean
  defunctReason: string | null
}

type VariablesProfile = {
  id: string
  name: string | null
}

type NetworkProfileOption = {
  id: string
  name: string | null
}

type VariablesMeta = {
  exists: boolean
  hasGlobalVars: boolean
  hasSutVars: boolean
  profiles: VariablesProfile[]
}

type NetworkMode = 'DIRECT' | 'PROXIED'

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
      const bundleKey = typeof value.bundleKey === 'string' ? value.bundleKey.trim() : ''
      const bundlePath = typeof value.bundlePath === 'string' ? value.bundlePath.trim() : ''
      const name = typeof value.name === 'string' ? value.name.trim() : ''
      if (!bundleKey || !bundlePath || !name) return null
      const id = typeof value.id === 'string' && value.id.trim().length > 0 ? value.id.trim() : null
      const folderPath =
        typeof value.folderPath === 'string' && value.folderPath.trim().length > 0 ? value.folderPath.trim() : null
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
      const defunct = value.defunct === true
      const defunctReason =
        typeof value.defunctReason === 'string' && value.defunctReason.trim().length > 0 ? value.defunctReason.trim() : null
      return { bundleKey, bundlePath, id, name, folderPath, description, controllerImage, bees, defunct, defunctReason }
    })
    .filter((template): template is ScenarioTemplate => template !== null)
}

function templateMatchesNeedle(template: ScenarioTemplate, needle: string): boolean {
  if (!needle) return true
  const haystack = `${template.folderPath ?? ''} ${template.bundlePath} ${template.id ?? ''} ${template.name} ${template.description ?? ''}`.toLowerCase()
  return haystack.includes(needle)
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
  const [selectedBundleKey, setSelectedBundleKey] = useState('')
  const [swarmId, setSwarmId] = useState('')
  const [autoPullImages, setAutoPullImages] = useState(false)

  const [sutIds, setSutIds] = useState<string[]>([])
  const [sutId, setSutId] = useState('')
  const [networkProfiles, setNetworkProfiles] = useState<NetworkProfileOption[]>([])
  const [networkMode, setNetworkMode] = useState<NetworkMode>('DIRECT')
  const [networkProfileId, setNetworkProfileId] = useState('')

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
      const [templatesResponse, profilesResponse] = await Promise.all([
        fetch(TEMPLATES_ENDPOINT, { headers: { Accept: 'application/json' } }),
        fetch('/scenario-manager/network-profiles', { headers: { Accept: 'application/json' } }),
      ])
      if (!templatesResponse.ok) {
        setTemplates([])
        setTemplatesLoaded(true)
        return
      }
      const payload = await templatesResponse.json()
      setTemplates(normalizeTemplates(payload))
      if (profilesResponse.ok) {
        const profilesPayload = (await profilesResponse.json()) as unknown
        const profiles = Array.isArray(profilesPayload)
          ? profilesPayload
              .map((entry) => {
                if (!entry || typeof entry !== 'object') return null
                const value = entry as Record<string, unknown>
                const id = typeof value.id === 'string' ? value.id.trim() : ''
                if (!id) return null
                const name = typeof value.name === 'string' && value.name.trim().length > 0 ? value.name.trim() : null
                return { id, name }
              })
              .filter((entry): entry is NetworkProfileOption => entry !== null)
          : []
        setNetworkProfiles(profiles)
        setNetworkProfileId((current) => {
          if (profiles.length === 1) {
            return profiles[0].id
          }
          return profiles.some((profile) => profile.id === current) ? current : ''
        })
      } else {
        setNetworkProfiles([])
        setNetworkProfileId('')
      }
      setTemplatesLoaded(true)
    } catch {
      setTemplates([])
      setNetworkProfiles([])
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
    if (!selectedBundleKey) {
      setSutIds([])
      setSutId('')
      setVariablesMeta({ exists: false, hasGlobalVars: false, hasSutVars: false, profiles: [] })
      setVariablesProfileId('')
      return
    }
    const selectedTemplate = templates.find((template) => template.bundleKey === selectedBundleKey) ?? null
    if (!selectedTemplate?.id || selectedTemplate.defunct) {
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
    const scenarioId = selectedTemplate.id

    const loadSuts = async () => {
      try {
        const response = await fetch(`/scenario-manager/scenarios/${encodeURIComponent(scenarioId)}/suts`, {
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
        const response = await fetch(`/scenario-manager/scenarios/${encodeURIComponent(scenarioId)}/variables`, {
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
  }, [open, selectedBundleKey, templates])

  const filteredTemplates = useMemo(() => {
    const needle = templateFilter.trim().toLowerCase()
    if (!needle) return templates
    return templates.filter((template) => templateMatchesNeedle(template, needle))
  }, [templateFilter, templates])

  const selectedTemplate = useMemo(
    () => templates.find((template) => template.bundleKey === selectedBundleKey) ?? null,
    [selectedBundleKey, templates],
  )

  const openFolderPaths = useMemo(() => {
    const needle = templateFilter.trim()
    if (needle.length > 0) return null
    if (!selectedTemplate?.folderPath) return new Set<string>()
    const segments = selectedTemplate.folderPath.split('/').map((seg) => seg.trim()).filter((seg) => seg.length > 0)
    const open = new Set<string>()
    let current = ''
    for (const segment of segments) {
      current = current ? `${current}/${segment}` : segment
      open.add(current)
    }
    return open
  }, [selectedTemplate?.folderPath, templateFilter])

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
      const selectedTemplate = templates.find((template) => template.bundleKey === selectedBundleKey) ?? null
      const trimmedTemplateId = selectedTemplate?.id?.trim() ?? ''
      const trimmedSutId = sutId.trim()
      const trimmedProfileId = variablesProfileId.trim()
      const trimmedNetworkProfileId = networkProfileId.trim()

      if (!selectedTemplate) {
        setError('Swarm ID and scenario are required.')
        return
      }
      if (selectedTemplate.defunct) {
        setError(selectedTemplate.defunctReason ?? 'Selected bundle is defunct.')
        return
      }
      if (!trimmedSwarmId || !trimmedTemplateId) {
        setError('Swarm ID and scenario are required.')
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
      if (networkMode === 'PROXIED' && networkProfiles.length === 0) {
        setError('No network profiles are available.')
        return
      }
      if (networkMode === 'PROXIED' && !trimmedNetworkProfileId) {
        setError('Network profile is required when proxy mode is enabled.')
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
            autoPullImages,
            sutId: trimmedSutId ? trimmedSutId : null,
            variablesProfileId: trimmedProfileId ? trimmedProfileId : null,
            networkMode,
            networkProfileId: networkMode === 'PROXIED' ? trimmedNetworkProfileId : null,
          }),
        })
        if (!response.ok) {
          throw new Error(await readErrorMessage(response))
        }
        setMessage(`Create request accepted for ${trimmedSwarmId}.`)
        setSwarmId('')
        setSelectedBundleKey('')
        setTemplateFilter('')
        setAutoPullImages(false)
        setSutIds([])
        setSutId('')
        setNetworkMode('DIRECT')
        setNetworkProfileId('')
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
    [autoPullImages, busy, networkMode, networkProfileId, networkProfiles.length, onClose, onCreated, requiresProfile, requiresSut, selectedBundleKey, swarmId, sutId, templates, variablesProfileId],
  )

  if (!open) return null

  return (
    <div className="modalBackdrop" role="presentation" onClick={onClose}>
      <div className="modal" role="dialog" aria-modal="true" onClick={(event) => event.stopPropagation()}>
        <div className="modalHeader">
          <div>
            <div className="h2">Create swarm</div>
            <div className="muted">Provision a controller from a scenario bundle.</div>
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

            <label className="field">
              <span className="fieldLabel">Network mode</span>
              <select
                className="textInput"
                value={networkMode}
                onChange={(event) => {
                  const nextMode: NetworkMode = event.target.value === 'PROXIED' ? 'PROXIED' : 'DIRECT'
                  setNetworkMode(nextMode)
                  if (nextMode === 'DIRECT') {
                    setNetworkProfileId('')
                  } else if (networkProfiles.length === 1) {
                    setNetworkProfileId(networkProfiles[0].id)
                  }
                }}
              >
                <option value="DIRECT">Direct</option>
                <option value="PROXIED">Proxied</option>
              </select>
            </label>

            <label className="field">
              <span className="fieldLabel">Bundle SUT{requiresSut ? ' (required)' : ''}</span>
              <select
                className="textInput"
                value={sutId}
                onChange={(event) => setSutId(event.target.value)}
                disabled={!hasBundleSuts}
              >
                <option value="">{hasBundleSuts ? '(none)' : '(none available)'}</option>
                {sutIds.map((id) => (
                  <option key={id} value={id}>
                    {id}
                  </option>
                ))}
              </select>
            </label>

            {networkMode === 'PROXIED' ? (
              <label className="field">
                <span className="fieldLabel">Network profile (required)</span>
                <select
                  className="textInput"
                  value={networkProfileId}
                  onChange={(event) => setNetworkProfileId(event.target.value)}
                  disabled={networkProfiles.length === 0}
                >
                  <option value="">{networkProfiles.length === 0 ? '(no profiles available)' : '(select)'}</option>
                  {networkProfiles.map((profile) => (
                    <option key={profile.id} value={profile.id}>
                      {profile.name ? `${profile.name} (${profile.id})` : profile.id}
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
                <span>Scenarios</span>
                <input
                  className="textInput textInputCompact"
                  value={templateFilter}
                  onChange={(event) => setTemplateFilter(event.target.value)}
                  placeholder="Filter scenarios"
                  aria-label="Scenario filter"
                />
              </div>
              <div className="swarmTemplateListBody">
                {!templatesLoaded ? (
                  <div className="muted">Loading scenarios…</div>
                ) : filteredTemplates.length === 0 ? (
                  <div className="muted">No scenarios found.</div>
                ) : (
                  <>
                    {templates.some((template) => template.defunct) ? (
                      <div className="muted" style={{ fontSize: 11, padding: '4px 0' }}>
                        {templates.filter((template) => template.defunct).length} bundle(s) are defunct and shown as non-runnable
                      </div>
                    ) : null}
                    <ScenarioTree
                      items={filteredTemplates}
                      selectedBundleKey={selectedBundleKey}
                      onSelectBundle={setSelectedBundleKey}
                      searchTerm={templateFilter}
                      openPaths={openFolderPaths}
                      rowHeight={30}
                      emptyMessage="No scenarios found."
                    />
                  </>
                )}
              </div>
            </div>
            <div className="swarmTemplateDetail">
              {selectedTemplate ? (
                <>
                  <div className={selectedTemplate.defunct ? 'swarmTemplateTitle swarmTemplateTitleDefunct' : 'swarmTemplateTitle'}>
                    {selectedTemplate.name}
                  </div>
                  <div className="swarmTemplateId">
                    {selectedTemplate.bundlePath}
                  </div>
                  <div className="muted">
                    {selectedTemplate.defunct
                      ? (selectedTemplate.defunctReason ?? 'This bundle is unavailable.')
                      : (selectedTemplate.description ?? 'No description provided.')}
                  </div>
                  {selectedTemplate.defunct ? (
                    <div className="card swarmMessage" style={{ marginTop: 12 }}>
                      <div className="pill pillBad">DEFUNCT</div>
                      <div className="muted" style={{ marginTop: 8 }}>{selectedTemplate.defunctReason ?? 'Reason unavailable.'}</div>
                    </div>
                  ) : null}
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
                <div className="muted">Select a scenario to see details.</div>
              )}
            </div>
          </div>

          <div className="swarmCreateActions" style={{ marginTop: 12 }}>
            <label className={autoPullImages ? 'swarmCreateToggle swarmCreateToggleChecked' : 'swarmCreateToggle'}>
              <input
                type="checkbox"
                checked={autoPullImages}
                onChange={(event) => setAutoPullImages(event.target.checked)}
                disabled={busy}
              />
              <span>Pull images</span>
            </label>
            <button type="submit" className="actionButton" disabled={busy}>
              {busy ? 'Creating…' : 'Create'}
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}
