import { useEffect, useMemo, useState } from 'react'
import { createSwarm } from '../../lib/orchestratorApi'
import { apiFetch } from '../../lib/api'

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

interface SutEnvironment {
  id: string
  name: string
  type: string | null
}

export default function SwarmCreateModal({ onClose, autoPullOnStart, onChangeAutoPull }: Props) {
  const [swarmId, setSwarmId] = useState('')
  const [templates, setTemplates] = useState<ScenarioTemplate[]>([])
  const [scenarioId, setScenarioId] = useState('')
  const [sutEnvironments, setSutEnvironments] = useState<SutEnvironment[]>([])
  const [sutId, setSutId] = useState<string>('')
  const [message, setMessage] = useState<string | null>(null)
  const [showRawScenario, setShowRawScenario] = useState(false)
  const [scenarioPreview, setScenarioPreview] = useState<string | null>(null)
  const [scenarioPreviewError, setScenarioPreviewError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false

    const load = async () => {
      try {
        const templatesResponse = await apiFetch('/scenario-manager/api/templates', {
          headers: { Accept: 'application/json' },
        })
        const sutResponse = await apiFetch('/scenario-manager/sut-environments', {
          headers: { Accept: 'application/json' },
        })

        if (cancelled) return

        if (!templatesResponse.ok) {
          setTemplates([])
          return
        }

        const templatesData = await templatesResponse.json()
        setTemplates(normalizeTemplates(templatesData))
        if (sutResponse.ok) {
          const sutData = (await sutResponse.json()) as unknown
          setSutEnvironments(normalizeSutEnvironments(sutData))
        } else {
          setSutEnvironments([])
        }
      } catch {
        if (!cancelled) {
          setTemplates([])
          setSutEnvironments([])
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
      await createSwarm(swarmId.trim(), scenarioId, {
        autoPullImages: autoPullOnStart,
        sutId: sutId || null,
      })
      setMessage('Swarm created')
      setSwarmId('')
      setScenarioId('')
      setSutId('')
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

  useEffect(() => {
    let cancelled = false
    setScenarioPreview(null)
    setScenarioPreviewError(null)

    const loadScenario = async () => {
      if (!selectedTemplate) {
        return
      }
      setShowRawScenario(false)
      try {
        const response = await apiFetch(`/scenario-manager/scenarios/${encodeURIComponent(selectedTemplate.id)}`, {
          headers: { Accept: 'application/json' },
        })
        if (cancelled) return
        if (!response.ok) {
          setScenarioPreviewError('Failed to load scenario definition')
          return
        }
        const body = await response.json()
        setScenarioPreview(JSON.stringify(body, null, 2))
      } catch {
        if (!cancelled) {
          setScenarioPreviewError('Failed to load scenario definition')
        }
      }
    }

    void loadScenario()

    return () => {
      cancelled = true
    }
  }, [selectedTemplate])

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
              <div className="flex flex-col gap-1 mr-4">
                <label htmlFor="sutEnv" className="block text-xs mb-0.5 text-white/70">
                  System under test
                </label>
                <select
                  id="sutEnv"
                  className="rounded border border-white/20 bg-[#020617] px-2 py-1 text-xs text-white"
                  value={sutId}
                  onChange={(e) => setSutId(e.target.value)}
                >
                  <option value="">(none)</option>
                  {sutEnvironments.map((env) => (
                    <option key={env.id} value={env.id}>
                      {env.name} {env.type ? `(${env.type})` : ''}
                    </option>
                  ))}
                </select>
              </div>
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
                  Select a scenario on the left to preview its definition.
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
                  <div className="space-y-3">
                    <div>
                      <div className="uppercase text-[10px] tracking-wide text-white/50 mb-1">
                        Components
                      </div>
                      <ul className="text-[12px] text-white/80 space-y-1">
                        {selectedTemplate.controllerImage && (
                          <li>
                            <span className="text-white/60">controller:</span>{' '}
                            <span className="text-white/90">{selectedTemplate.controllerImage}</span>
                          </li>
                        )}
                        {selectedTemplate.bees.map((bee, index) => (
                          <li key={`${bee.role}-${index}`}>
                            <span className="text-white/60">
                              {bee.role || `bee-${index + 1}`}:
                            </span>{' '}
                            <span className="text-white/90">{bee.image ?? '—'}</span>
                          </li>
                        ))}
                      </ul>
                    </div>
                    <div className="space-y-1">
                      <button
                        type="button"
                        className="text-[11px] text-blue-300 hover:text-blue-200 underline-offset-2 underline"
                        onClick={() => setShowRawScenario((prev) => !prev)}
                        disabled={!scenarioPreview && !scenarioPreviewError}
                      >
                        {showRawScenario ? 'Hide raw scenario definition' : 'Show raw scenario definition'}
                      </button>
                      {showRawScenario && (
                        <div className="mt-1">
                          <div className="uppercase text-[10px] tracking-wide text-white/50 mb-1">
                            Scenario definition (from scenario-manager)
                          </div>
                          {scenarioPreviewError && (
                            <div className="text-amber-300 text-[11px]">{scenarioPreviewError}</div>
                          )}
                          {scenarioPreview && (
                            <pre className="max-h-[260px] overflow-auto rounded bg-black/60 p-2 text-[11px] whitespace-pre text-white/90">
                              {scenarioPreview}
                            </pre>
                          )}
                          {!scenarioPreview && !scenarioPreviewError && (
                            <div className="text-white/50 text-[11px]">Loading scenario…</div>
                          )}
                        </div>
                      )}
                    </div>
                  </div>
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
