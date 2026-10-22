import { useScenarioCatalogue } from '../../lib/useScenarioCatalogue'
import type { BundleTemplateEntry as ScenarioTemplate } from '../../lib/scenariosApi'
import type { NetworkMode } from '../../lib/NetworkMode'
import React, { useCallback, useEffect, useMemo, useState } from 'react'
import YAML from 'yaml'
import { useAuth } from '../../lib/authContext'
import { readBundleFile, readBundleTree, type BundleFilePayload, type BundleTreeNode } from '../../lib/scenariosApi'
import { newUuid } from '../../lib/uuid'
import { parseControlResponse } from '@pockethive/swarm-lifecycle-contract'
import { usePlannerCreation } from '../../components/planner/usePlannerCreation'
import { CreateSwarmPlannerControls } from './CreateSwarmPlannerControls'

import { CreateSwarmDialog } from './CreateSwarmDialog'
import type { SwarmVariablesMeta as VariablesMeta } from './SwarmVariablesMeta'
import type { SwarmNetworkProfileOption as NetworkProfileOption } from './SwarmNetworkProfileOption'

const ORCHESTRATOR_BASE = '/orchestrator/api'

function createIdempotencyKey() {
  return `ph-${newUuid()}`
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

  const profiles: VariablesMeta['profiles'] = Array.isArray(obj.profiles)
    ? obj.profiles
        .map((raw) => {
          if (!raw || typeof raw !== 'object') return null
          const rec = raw as Record<string, unknown>
          const id = typeof rec.id === 'string' ? rec.id.trim() : ''
          if (!id) return null
          const name = typeof rec.name === 'string' && rec.name.trim().length > 0 ? rec.name.trim() : null
          return { id, name }
        })
        .filter((p): p is VariablesMeta['profiles'][number] => p !== null)
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

/**
 * Responsibility: present swarm creation using the backend-filtered runnable catalogue.
 * Must not: interpret per-bundle grant scopes or authorize backend mutations.
 * Contract: RESP-SCENARIO-CATALOGUE-ACCESS — docs/architecture/runtime-responsibilities.md#resp-scenario-catalogue-access (catalogue slice).
 */
export function CreateSwarmModal({
  open,
  onClose,
  onCreated,
}: {
  open: boolean
  onClose: () => void
  onCreated: () => void
}) {
  const auth = useAuth()
  const { entries: templates, loading: templatesLoading, error: catalogueError } = useScenarioCatalogue('run', auth.user, open)
  const templatesLoaded = !templatesLoading
  const [templateFilter, setTemplateFilter] = useState('')
  const [selectedBundleKey, setSelectedBundleKey] = useState('')
  const [bundleTreeNodes, setBundleTreeNodes] = useState<BundleTreeNode[]>([])
  const [bundleTreeLoading, setBundleTreeLoading] = useState(false)
  const [bundleTreeError, setBundleTreeError] = useState<string | null>(null)
  const [selectedFilePath, setSelectedFilePath] = useState<string | null>(null)
  const [selectedFile, setSelectedFile] = useState<BundleFilePayload | null>(null)
  const [selectedFileLoading, setSelectedFileLoading] = useState(false)
  const [selectedFileError, setSelectedFileError] = useState<string | null>(null)
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
  const plannerCreation = usePlannerCreation(open, auth.canManageDeployment)
  const { pendingCreation } = plannerCreation

  const loadNetworkProfiles = useCallback(async () => {
    try {
      const profilesResponse = await fetch('/scenario-manager/network-profiles', { headers: { Accept: 'application/json' } })
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
    } catch {
      setNetworkProfiles([])
    }
  }, [])

  useEffect(() => {
    if (!open) return
    void loadNetworkProfiles()
  }, [loadNetworkProfiles, open])

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

  const runnableTemplates = filteredTemplates

  const selectedTemplate = useMemo(
    () => runnableTemplates.find((template) => template.bundleKey === selectedBundleKey) ?? null,
    [runnableTemplates, selectedBundleKey],
  )

  useEffect(() => {
    if (!open || !selectedTemplate) {
      setBundleTreeNodes([])
      setBundleTreeError(null)
      setBundleTreeLoading(false)
      setSelectedFilePath(null)
      setSelectedFile(null)
      setSelectedFileError(null)
      setSelectedFileLoading(false)
      return
    }

    let cancelled = false
    setBundleTreeLoading(true)
    setBundleTreeError(null)
    setBundleTreeNodes([])
    setSelectedFilePath(null)
    setSelectedFile(null)
    setSelectedFileError(null)

    readBundleTree(selectedTemplate.bundleKey)
      .then((tree) => {
        if (cancelled) return
        setBundleTreeNodes(tree.nodes)
        const preferred = tree.nodes.find((node) => node.nodeType === 'file' && node.path === 'scenario.yaml')
          ?? tree.nodes.find((node) => node.nodeType === 'file' && node.editorKind !== 'unsupported')
          ?? tree.nodes.find((node) => node.nodeType === 'file')
          ?? null
        setSelectedFilePath(preferred?.path ?? null)
      })
      .catch((err: unknown) => {
        if (cancelled) return
        setBundleTreeNodes([])
        setBundleTreeError(err instanceof Error ? err.message : 'Failed to load bundle files')
      })
      .finally(() => {
        if (!cancelled) setBundleTreeLoading(false)
      })

    return () => {
      cancelled = true
    }
  }, [open, selectedTemplate])

  useEffect(() => {
    if (!open || !selectedTemplate || !selectedFilePath) {
      setSelectedFile(null)
      setSelectedFileError(null)
      setSelectedFileLoading(false)
      return
    }

    let cancelled = false
    setSelectedFileLoading(true)
    setSelectedFileError(null)
    setSelectedFile(null)

    readBundleFile(selectedTemplate.bundleKey, selectedFilePath)
      .then((file) => {
        if (!cancelled) setSelectedFile(file)
      })
      .catch((err: unknown) => {
        if (!cancelled) setSelectedFileError(err instanceof Error ? err.message : 'Failed to load bundle file')
      })
      .finally(() => {
        if (!cancelled) setSelectedFileLoading(false)
      })

    return () => {
      cancelled = true
    }
  }, [open, selectedTemplate, selectedFilePath])

  useEffect(() => {
    if (!selectedBundleKey) return
    if (runnableTemplates.some((template) => template.bundleKey === selectedBundleKey)) return
    setSelectedBundleKey('')
  }, [runnableTemplates, selectedBundleKey])

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

  const requiresProfile = variablesMeta.exists && (variablesMeta.hasGlobalVars || variablesMeta.hasSutVars)
  const requiresSut = variablesMeta.exists && variablesMeta.hasSutVars

  const handleCreate = useCallback(
    async (event: React.FormEvent) => {
      event.preventDefault()
      if (busy) return
      setError(null)
      setMessage(null)

      if (pendingCreation) {
        setBusy(true)
        try {
          await plannerCreation.retry()
          setSwarmId('')
          onCreated()
          onClose()
        } catch (reason) {
          setError(reason instanceof Error ? reason.message : 'Cannot link the accepted creation')
          setMessage(`CREATE was already accepted for ${pendingCreation.swarmId}. Only the association is retried.`)
        } finally { setBusy(false) }
        return
      }

      const trimmedSwarmId = swarmId.trim()
      const selectedTemplate = runnableTemplates.find((template) => template.bundleKey === selectedBundleKey) ?? null
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
        const preparedPlanner = await plannerCreation.prepare()
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
        if (!response.ok) throw new Error(await readErrorMessage(response))
        if (preparedPlanner) {
          const creation = { swarmId: trimmedSwarmId, accepted: parseControlResponse(await response.json()) }
          plannerCreation.remember(creation)
          setMessage(`CREATE accepted for ${creation.swarmId}. Confirming creation and saving the Planner association. If linking fails, retry only the association.`)
          await plannerCreation.complete(creation, preparedPlanner)
        }
        plannerCreation.reset()
        setMessage(`Create request accepted for ${trimmedSwarmId}.`)
        setSwarmId('')
        setSelectedBundleKey('')
        setTemplateFilter('')
        setBundleTreeNodes([])
        setSelectedFilePath(null)
        setSelectedFile(null)
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
    [autoPullImages, busy, networkMode, networkProfileId, networkProfiles.length, onClose, onCreated, requiresProfile, requiresSut, runnableTemplates, selectedBundleKey, swarmId, sutId, variablesProfileId, plannerCreation, pendingCreation],
  )

  if (!open) return null

  return (
    <CreateSwarmDialog
      creationPending={pendingCreation !== null}
      associationBlocked={plannerCreation.retryBlocked}
      plannerControls={<CreateSwarmPlannerControls planner={plannerCreation} busy={busy} />}
      swarmId={swarmId}
      networkMode={networkMode}
      sutIds={sutIds}
      sutId={sutId}
      networkProfiles={networkProfiles}
      networkProfileId={networkProfileId}
      variablesMeta={variablesMeta}
      variablesProfileId={variablesProfileId}
      requiresSut={requiresSut}
      requiresProfile={requiresProfile}
      templates={runnableTemplates}
      templatesLoaded={templatesLoaded}
      templateFilter={templateFilter}
      selectedBundleKey={selectedBundleKey}
      selectedTemplate={selectedTemplate}
      openFolderPaths={openFolderPaths}
      bundleTreeNodes={bundleTreeNodes}
      bundleTreeLoading={bundleTreeLoading}
      bundleTreeError={bundleTreeError}
      selectedFilePath={selectedFilePath}
      selectedFile={selectedFile}
      selectedFileLoading={selectedFileLoading}
      selectedFileError={selectedFileError}
      autoPullImages={autoPullImages}
      busy={busy}
      catalogueError={catalogueError}
      error={error}
      message={message}
      onClose={() => {
        if (busy) return
        if (pendingCreation) { plannerCreation.reset(); onCreated() }
        onClose()
      }}
      onSubmit={handleCreate}
      onSwarmIdChange={setSwarmId}
      onNetworkModeChange={(nextMode) => {
        setNetworkMode(nextMode)
        if (nextMode === 'DIRECT') setNetworkProfileId('')
        else if (networkProfiles.length === 1) setNetworkProfileId(networkProfiles[0].id)
      }}
      onSutIdChange={setSutId}
      onNetworkProfileIdChange={setNetworkProfileId}
      onVariablesProfileIdChange={setVariablesProfileId}
      onTemplateFilterChange={setTemplateFilter}
      onSelectBundle={setSelectedBundleKey}
      onSelectFile={setSelectedFilePath}
      onAutoPullImagesChange={setAutoPullImages}
    />
  )
}
