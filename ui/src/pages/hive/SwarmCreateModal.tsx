import { useEffect, useMemo, useState } from 'react'
import { createSwarm } from '../../lib/orchestratorApi'
import { apiFetch } from '../../lib/api'
import type {
  CapabilityAction,
  CapabilityConfigEntry,
  CapabilityManifest,
} from '../../types/capabilities'
import {
  findManifestForImage,
  formatCapabilityValue,
  inferCapabilityInputType,
} from '../../lib/capabilities'
import { useCapabilities } from '../../contexts/CapabilitiesContext'

interface Props {
  onClose: () => void
  autoPullOnStart: boolean
  onChangeAutoPull: (next: boolean) => void
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

export default function SwarmCreateModal({ onClose, autoPullOnStart, onChangeAutoPull }: Props) {
  const [swarmId, setSwarmId] = useState('')
  const [templates, setTemplates] = useState<ScenarioTemplate[]>([])
  const [scenarioId, setScenarioId] = useState('')
  const [message, setMessage] = useState<string | null>(null)
  const { manifestIndex, refreshCapabilities } = useCapabilities()

  useEffect(() => {
    let cancelled = false

    const load = async () => {
      try {
        const [templatesResponse] = await Promise.all([
          apiFetch('/scenario-manager/api/templates', {
            headers: { Accept: 'application/json' },
          }),
          refreshCapabilities(),
        ])

        if (cancelled) return

        if (!templatesResponse.ok) {
          setTemplates([])
          return
        }

        const templatesData = await templatesResponse.json()
        setTemplates(normalizeTemplates(templatesData))
      } catch {
        if (!cancelled) {
          setTemplates([])
        }
      }
    }

    void load()

    return () => {
      cancelled = true
    }
  }, [refreshCapabilities])

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
      await createSwarm(swarmId.trim(), scenarioId, { autoPullImages: autoPullOnStart })
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
      <div className="bg-[#1a1d24] p-5 rounded-lg w-[900px] max-w-[95vw] max-h-[85vh] flex flex-col shadow-xl border border-white/10">
        <div className="flex items-center justify-between mb-3">
          <h3 className="text-xl font-semibold">Create Swarm</h3>
          <button
            type="button"
            onClick={onClose}
            className="text-white/60 hover:text-white text-sm px-2 py-1"
          >
            ×
          </button>
        </div>
        <form onSubmit={handleSubmit} className="flex flex-col gap-4 flex-1 min-h-0">
          <div className="flex gap-4">
            <div className="flex-1">
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
            <div className="flex items-end">
              <label className="flex items-center gap-1 text-xs text-white/70">
                <input
                  type="checkbox"
                  className="h-3 w-3 rounded border-white/40 bg-transparent"
                  checked={autoPullOnStart}
                  onChange={(e) => onChangeAutoPull(e.target.checked)}
                />
                <span>Pull images on start</span>
              </label>
            </div>
          </div>
          <div className="flex gap-4 flex-1 min-h-0">
            <div className="w-64 flex flex-col border border-white/10 rounded-md bg-white/5">
              <div className="px-3 py-2 border-b border-white/10 text-xs uppercase tracking-wide text-white/60">
                Scenarios
              </div>
              <div className="flex-1 overflow-y-auto">
                {templates.length === 0 ? (
                  <div className="px-3 py-3 text-xs text-white/50">No scenarios available.</div>
                ) : (
                  <ul className="text-sm">
                    {templates.map((template) => {
                      const selected = template.id === scenarioId
                      return (
                        <li key={template.id}>
                          <button
                            type="button"
                            onClick={() => setScenarioId(template.id)}
                            className={`w-full text-left px-3 py-2 hover:bg-white/10 ${
                              selected ? 'bg-white/15 text-white' : 'text-white/80'
                            }`}
                          >
                            <div className="font-medium leading-snug break-words">
                              {template.name}
                            </div>
                            {template.description && (
                              <div className="text-[11px] text-white/50 line-clamp-2">
                                {template.description}
                              </div>
                            )}
                          </button>
                        </li>
                      )
                    })}
                  </ul>
                )}
              </div>
            </div>
            <div className="flex-1 min-h-0 border border-white/10 rounded-md bg-black/20 p-3 overflow-y-auto text-xs text-white/80">
              {!selectedTemplate && (
                <div className="text-white/50">
                  Select a scenario on the left to preview its controller and bees.
                </div>
              )}
              {selectedTemplate && (
                <div className="space-y-4">
                  <div>
                    <div className="text-xs uppercase tracking-wide text-white/50 mb-1">
                      Scenario
                    </div>
                    <div className="text-base font-semibold text-white leading-snug">
                      {selectedTemplate.name}
                    </div>
                  </div>
                  {selectedTemplate.description && (
                    <p className="text-white/70 text-sm">{selectedTemplate.description}</p>
                  )}
                  {selectedComponents.map((component) => (
                    <div key={component.key} className="space-y-2 border border-white/10 rounded-md p-3">
                      <div className="flex items-baseline justify-between gap-3">
                        <div className="font-semibold text-white">{component.label}</div>
                        <div className="text-[11px] text-white/50 truncate">
                          Image: {component.image ?? '—'}
                        </div>
                      </div>
                      {component.manifest ? (
                        <div className="space-y-3">
                          <div className="space-y-1">
                            <div className="uppercase text-[10px] tracking-wide text-white/50">
                              Config
                            </div>
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
                            <div className="uppercase text-[10px] tracking-wide text-white/50">
                              Actions
                            </div>
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
                                  title={
                                    action.params.length ? formatActionTooltip(action) : undefined
                                  }
                                >
                                  {action.label}
                                </button>
                              ))}
                            </div>
                          </div>
                          <div className="space-y-1">
                            <div className="uppercase text-[10px] tracking-wide text-white/50">
                              Panels
                            </div>
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
            </div>
          </div>
          <div className="flex justify-end gap-2 pt-2 border-t border-white/10 mt-2">
            <button
              type="button"
              onClick={onClose}
              className="rounded bg-white/10 hover:bg-white/20 px-3 py-1.5 text-sm"
            >
              Close
            </button>
            <button
              type="submit"
              className="rounded bg-blue-500 hover:bg-blue-600 px-3 py-1.5 text-sm text-white"
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

function renderConfigControl(entry: CapabilityConfigEntry) {
  const value = formatCapabilityValue(entry.default)
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

  const inputType = inferCapabilityInputType(entry.type)
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
