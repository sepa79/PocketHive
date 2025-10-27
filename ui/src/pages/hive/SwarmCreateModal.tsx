import { useEffect, useMemo, useState } from 'react'
import { createSwarm } from '../../lib/orchestratorApi'
import { apiFetch } from '../../lib/api'
import type {
  CapabilityAction,
  CapabilityConfigEntry,
  CapabilityManifest,
} from '../../types/capabilities'

interface Props {
  onClose: () => void
}

interface ScenarioTemplate {
  id: string
  name: string
  description: string | null
  controllerImage: string | null
  bees: ScenarioBee[]
}

interface ScenarioBee {
  role: string
  image: string | null
}

type ApiError = Error & { status?: number }

export default function SwarmCreateModal({ onClose }: Props) {
  const [swarmId, setSwarmId] = useState('')
  const [templates, setTemplates] = useState<ScenarioTemplate[]>([])
  const [manifests, setManifests] = useState<CapabilityManifest[]>([])
  const [scenarioId, setScenarioId] = useState('')
  const [message, setMessage] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false

    const load = async () => {
      try {
        const [templatesResponse, capabilitiesResponse] = await Promise.all([
          apiFetch('/scenario-manager/api/templates', {
            headers: { Accept: 'application/json' },
          }),
          apiFetch('/scenario-manager/api/capabilities?all=true', {
            headers: { Accept: 'application/json' },
          }),
        ])

        const [templatesData, manifestData] = await Promise.all([
          templatesResponse.ok ? templatesResponse.json() : Promise.resolve(null),
          capabilitiesResponse.ok ? capabilitiesResponse.json() : Promise.resolve(null),
        ])

        if (cancelled) return

        setTemplates(normalizeTemplates(templatesData))
        setManifests(normalizeManifests(manifestData))
      } catch {
        if (!cancelled) {
          setTemplates([])
          setManifests([])
        }
      }
    }

    void load()

    return () => {
      cancelled = true
    }
  }, [])

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!swarmId.trim() || !scenarioId) {
      setMessage('Swarm ID and scenario required')
      return
    }
    if (!/^[a-zA-Z0-9-]+$/.test(swarmId)) {
      setMessage('Invalid swarm ID')
      return
    }
    try {
      await createSwarm(swarmId.trim(), scenarioId)
      setMessage('Swarm created')
      setSwarmId('')
      setScenarioId('')
    } catch (error) {
      const apiError = error as ApiError
      if (apiError?.status === 409) {
        setMessage(apiError.message || 'Swarm already exists')
      } else {
        setMessage('Failed to create swarm')
      }
    }
  }

  const manifestIndex = useMemo(() => buildManifestIndex(manifests), [manifests])

  const selectedTemplate = useMemo(
    () => templates.find((template) => template.id === scenarioId) ?? null,
    [templates, scenarioId],
  )

  const selectedComponents = useMemo(() => {
    if (!selectedTemplate) return []

    const components: ComponentManifestView[] = []
    if (selectedTemplate.controllerImage) {
      components.push({
        key: 'controller',
        label: 'Controller',
        image: selectedTemplate.controllerImage,
        manifest: findManifestForImage(selectedTemplate.controllerImage, manifestIndex),
      })
    }

    selectedTemplate.bees.forEach((bee, index) => {
      const label = bee.role ? `${capitalize(bee.role)} Bee` : `Bee ${index + 1}`
      components.push({
        key: `bee-${index}-${bee.role ?? 'unknown'}`,
        label,
        image: bee.image,
        manifest: bee.image ? findManifestForImage(bee.image, manifestIndex) : null,
      })
    })

    return components
  }, [manifestIndex, selectedTemplate])

  return (
    <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50">
      <div className="bg-[#1a1d24] p-4 rounded w-80">
        <h3 className="text-lg mb-2">Create Swarm</h3>
        <form onSubmit={handleSubmit} className="space-y-2">
          <div>
            <label htmlFor="swarmId" className="block text-sm mb-1">
              Swarm ID
            </label>
            <input
              id="swarmId"
              value={swarmId}
              onChange={(e) => setSwarmId(e.target.value)}
              className="w-full rounded border border-white/20 bg-white/10 px-2 py-1 text-sm"
            />
          </div>
          <div>
            <label htmlFor="scenario" className="block text-sm mb-1">
              Scenario
            </label>
            <select
              id="scenario"
              value={scenarioId}
              onChange={(e) => setScenarioId(e.target.value)}
              className="themed-select w-full text-sm"
            >
              <option value="">Select scenario</option>
              {templates.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.name}
                </option>
              ))}
            </select>
          </div>
          {selectedTemplate && (
            <div className="border border-white/10 rounded p-2 max-h-60 overflow-y-auto space-y-3 text-xs text-white/80">
              {selectedTemplate.description && (
                <p className="text-white/60">{selectedTemplate.description}</p>
              )}
              {selectedComponents.map((component) => (
                <div key={component.key} className="space-y-2">
                  <div className="font-semibold text-white">{component.label}</div>
                  <div className="text-white/60 break-words">
                    Image: {component.image ?? '—'}
                  </div>
                  {component.manifest ? (
                    <div className="space-y-2">
                      <div className="space-y-1">
                        <div className="uppercase text-[10px] tracking-wide text-white/50">Config</div>
                        {component.manifest.config.length === 0 && (
                          <div className="text-white/50">No configurable options</div>
                        )}
                        {component.manifest.config.map((entry) => (
                          <label key={entry.name} className="block space-y-1">
                            <span className="block text-white/70">
                              {entry.name}
                              <span className="text-white/40"> ({entry.type})</span>
                            </span>
                            {renderConfigControl(entry)}
                          </label>
                        ))}
                      </div>
                      <div className="space-y-1">
                        <div className="uppercase text-[10px] tracking-wide text-white/50">Actions</div>
                        {component.manifest.actions.length === 0 && (
                          <div className="text-white/50">No actions available</div>
                        )}
                        <div className="flex flex-wrap gap-2">
                          {component.manifest.actions.map((action) => (
                            <button
                              key={action.id}
                              type="button"
                              disabled
                              className="rounded bg-white/10 px-2 py-1 text-white/70 text-[11px]"
                              title={action.params.length ? formatActionTooltip(action) : undefined}
                            >
                              {action.label}
                            </button>
                          ))}
                        </div>
                      </div>
                      <div className="space-y-1">
                        <div className="uppercase text-[10px] tracking-wide text-white/50">Panels</div>
                        {component.manifest.panels.length === 0 && (
                          <div className="text-white/50">No panels registered</div>
                        )}
                        <div className="flex flex-wrap gap-1">
                          {component.manifest.panels.map((panel) => (
                            <span
                              key={panel.id}
                              className="rounded-full border border-white/20 px-2 py-[1px] text-[11px] text-white/70"
                            >
                              {panel.id}
                            </span>
                          ))}
                        </div>
                      </div>
                    </div>
                  ) : (
                    <div className="text-amber-300 text-[11px]">
                      Capability manifest not found for this image.
                    </div>
                  )}
                </div>
              ))}
            </div>
          )}
          <div className="flex justify-end gap-2 pt-2">
            <button
              type="button"
              onClick={onClose}
              className="rounded bg-white/10 hover:bg-white/20 px-2 py-1 text-sm"
            >
              Close
            </button>
            <button
              type="submit"
              className="rounded bg-white/20 hover:bg-white/30 px-2 py-1 text-sm"
            >
              Create
            </button>
          </div>
        </form>
        {message && <div className="text-xs mt-2 text-white/70">{message}</div>}
      </div>
    </div>
  )
}

interface ComponentManifestView {
  key: string
  label: string
  image: string | null
  manifest: CapabilityManifest | null
}

interface ManifestIndex {
  byDigest: Map<string, CapabilityManifest>
  byNameAndTag: Map<string, CapabilityManifest>
}

function normalizeTemplates(data: unknown): ScenarioTemplate[] {
  if (!Array.isArray(data)) return []
  return data
    .map((entry) => normalizeTemplate(entry))
    .filter((entry): entry is ScenarioTemplate => entry !== null)
}

function normalizeTemplate(entry: unknown): ScenarioTemplate | null {
  if (!entry || typeof entry !== 'object') return null
  const value = entry as Record<string, unknown>
  const id = typeof value.id === 'string' ? value.id : null
  const name = typeof value.name === 'string' ? value.name : null
  if (!id || !name) return null

  const description = typeof value.description === 'string' ? value.description : null
  const controllerImage =
    typeof value.controllerImage === 'string' && value.controllerImage.trim().length > 0
      ? value.controllerImage.trim()
      : null
  const bees: ScenarioBee[] = Array.isArray(value.bees)
    ? value.bees
        .map((bee) => normalizeBee(bee))
        .filter((bee): bee is ScenarioBee => bee !== null)
    : []

  return { id, name, description, controllerImage, bees }
}

function normalizeBee(entry: unknown): ScenarioBee | null {
  if (!entry || typeof entry !== 'object') return null
  const value = entry as Record<string, unknown>
  const role = typeof value.role === 'string' ? value.role.trim() : ''
  const image =
    typeof value.image === 'string' && value.image.trim().length > 0 ? value.image.trim() : null
  return { role, image }
}

function normalizeManifests(data: unknown): CapabilityManifest[] {
  if (!Array.isArray(data)) return []
  return data
    .map((entry) => normalizeManifest(entry))
    .filter((entry): entry is CapabilityManifest => entry !== null)
}

function normalizeManifest(entry: unknown): CapabilityManifest | null {
  if (!entry || typeof entry !== 'object') return null
  const value = entry as Record<string, unknown>

  if (typeof value.schemaVersion !== 'string' || typeof value.capabilitiesVersion !== 'string') {
    return null
  }
  if (!value.image || typeof value.image !== 'object') return null

  const imageValue = value.image as Record<string, unknown>
  const image = {
    name: typeof imageValue.name === 'string' ? imageValue.name : null,
    tag: typeof imageValue.tag === 'string' ? imageValue.tag : null,
    digest: typeof imageValue.digest === 'string' ? imageValue.digest : null,
  }

  const config = Array.isArray(value.config)
    ? value.config
        .map((item) => normalizeConfigEntry(item))
        .filter((item): item is NonNullable<ReturnType<typeof normalizeConfigEntry>> => item !== null)
    : []

  const actions = Array.isArray(value.actions)
    ? value.actions
        .map((item) => normalizeAction(item))
        .filter((item): item is NonNullable<ReturnType<typeof normalizeAction>> => item !== null)
    : []

  const panels = Array.isArray(value.panels)
    ? value.panels
        .map((item) => normalizePanel(item))
        .filter((item): item is NonNullable<ReturnType<typeof normalizePanel>> => item !== null)
    : []

  const role = typeof value.role === 'string' ? value.role : ''

  return {
    schemaVersion: value.schemaVersion,
    capabilitiesVersion: value.capabilitiesVersion,
    image,
    role,
    config,
    actions,
    panels,
  }
}

function normalizeConfigEntry(entry: unknown) {
  if (!entry || typeof entry !== 'object') return null
  const value = entry as Record<string, unknown>
  if (typeof value.name !== 'string' || typeof value.type !== 'string') return null
  return {
    name: value.name,
    type: value.type,
    default: value.default,
    min: typeof value.min === 'number' ? value.min : undefined,
    max: typeof value.max === 'number' ? value.max : undefined,
    multiline: typeof value.multiline === 'boolean' ? value.multiline : undefined,
    ui: value.ui,
  }
}

function normalizeAction(entry: unknown) {
  if (!entry || typeof entry !== 'object') return null
  const value = entry as Record<string, unknown>
  if (typeof value.id !== 'string' || typeof value.label !== 'string') return null
  const params = Array.isArray(value.params)
    ? value.params
        .map((item) => normalizeActionParam(item))
        .filter((item): item is NonNullable<ReturnType<typeof normalizeActionParam>> => item !== null)
    : []
  return { id: value.id, label: value.label, params }
}

function normalizeActionParam(entry: unknown) {
  if (!entry || typeof entry !== 'object') return null
  const value = entry as Record<string, unknown>
  if (typeof value.name !== 'string' || typeof value.type !== 'string') return null
  return {
    name: value.name,
    type: value.type,
    default: value.default,
    required: typeof value.required === 'boolean' ? value.required : undefined,
    ui: value.ui,
  }
}

function normalizePanel(entry: unknown) {
  if (!entry || typeof entry !== 'object') return null
  const value = entry as Record<string, unknown>
  if (typeof value.id !== 'string') return null
  return { id: value.id, options: value.options }
}

function buildManifestIndex(list: CapabilityManifest[]): ManifestIndex {
  const byDigest = new Map<string, CapabilityManifest>()
  const byNameAndTag = new Map<string, CapabilityManifest>()

  list.forEach((manifest) => {
    const digest = manifest.image.digest?.trim().toLowerCase()
    if (digest) {
      byDigest.set(digest, manifest)
    }

    const name = manifest.image.name?.trim().toLowerCase()
    const tag = manifest.image.tag?.trim()
    if (name && tag) {
      byNameAndTag.set(`${name}:::${tag}`, manifest)
    }
  })

  return { byDigest, byNameAndTag }
}

function findManifestForImage(image: string, index: ManifestIndex): CapabilityManifest | null {
  const reference = parseImageReference(image)
  if (!reference) return null

  if (reference.digest) {
    const manifest = index.byDigest.get(reference.digest)
    if (manifest) return manifest
  }

  if (reference.name && reference.tag) {
    const manifest = index.byNameAndTag.get(`${reference.name}:::${reference.tag}`)
    if (manifest) return manifest
  }

  return null
}

interface ImageReference {
  name: string | null
  tag: string | null
  digest: string | null
}

function parseImageReference(image: string): ImageReference | null {
  if (!image || typeof image !== 'string') return null
  const trimmed = image.trim()
  if (!trimmed) return null

  let digest: string | null = null
  let remainder = trimmed

  const digestIndex = trimmed.indexOf('@')
  if (digestIndex >= 0) {
    digest = trimmed
      .slice(digestIndex + 1)
      .trim()
      .toLowerCase()
    remainder = trimmed.slice(0, digestIndex)
  }

  remainder = remainder.trim()
  let namePart = remainder
  let tag: string | null = null

  if (remainder) {
    const lastColon = remainder.lastIndexOf(':')
    const lastSlash = remainder.lastIndexOf('/')
    if (lastColon > lastSlash) {
      tag = remainder.slice(lastColon + 1).trim() || null
      namePart = remainder.slice(0, lastColon)
    }
  }

  const name = namePart ? namePart.trim().toLowerCase() : null

  return { name, tag, digest }
}

function renderConfigControl(entry: CapabilityConfigEntry) {
  const value = formatDefaultValue(entry.default)
  const normalizedType = entry.type ? entry.type.toLowerCase() : ''
  if (entry.multiline || normalizedType === 'text' || normalizedType === 'json') {
    return (
      <textarea
        className="w-full rounded bg-white/10 px-2 py-1 text-white"
        value={value}
        readOnly
        rows={3}
      />
    )
  }

  const inputType = inferInputType(entry.type)
  return (
    <input
      className="w-full rounded bg-white/10 px-2 py-1 text-white"
      type={inputType}
      value={value}
      readOnly
      min={entry.min}
      max={entry.max}
    />
  )
}

function formatDefaultValue(value: unknown): string {
  if (value === null || value === undefined) return ''
  if (typeof value === 'string') return value
  if (typeof value === 'number' || typeof value === 'boolean') return String(value)
  try {
    return JSON.stringify(value, null, 2)
  } catch {
    return ''
  }
}

function inferInputType(type: string | undefined) {
  const normalized = type?.toLowerCase() ?? ''
  if (normalized === 'int' || normalized === 'integer' || normalized === 'number') return 'number'
  if (normalized === 'boolean' || normalized === 'bool') return 'text'
  return 'text'
}

function formatActionTooltip(action: CapabilityAction) {
  if (!action.params.length) return undefined
  const parts = action.params.map((param) => {
    const required = param.required ? 'required' : 'optional'
    return `${param.name}: ${param.type} (${required})`
  })
  return parts.join('\n')
}

function capitalize(value: string) {
  if (!value) return ''
  return value.charAt(0).toUpperCase() + value.slice(1)
}
