import { useEffect, useMemo, useState } from 'react'
import { createSwarm } from '../../lib/orchestratorApi'
import { apiFetch } from '../../lib/api'
import YAML from 'yaml'

interface Props {
  onClose: () => void
  autoPullOnStart: boolean
  onChangeAutoPull: (next: boolean) => void
}

interface ScenarioTemplate {
  id: string
  name: string
  folderPath: string | null
  description: string | null
  controllerImage: string | null
  bees: ScenarioBee[]
}

interface ScenarioBee {
  role: string
  image: string | null
}

type ApiError = Error & { status?: number }

type VariablesProfile = { id: string; name: string | null }

type TemplateFolderNode = {
  name: string
  path: string
  children: TemplateFolderNode[]
  templates: ScenarioTemplate[]
}

function templateMatchesNeedle(template: ScenarioTemplate, needle: string): boolean {
  if (!needle) return true
  const haystack = `${template.folderPath ?? ''} ${template.id} ${template.name} ${template.description ?? ''}`.toLowerCase()
  return haystack.includes(needle)
}

function buildTemplateFolderTree(templates: ScenarioTemplate[]): { folders: TemplateFolderNode[]; rootTemplates: ScenarioTemplate[] } {
  const rootTemplates: ScenarioTemplate[] = []
  type MutableNode = { name: string; path: string; children: Map<string, MutableNode>; templates: ScenarioTemplate[] }
  type RootNode = { children: Map<string, MutableNode>; templates: ScenarioTemplate[] }
  const root: RootNode = { children: new Map(), templates: [] }

  const ensureNode = (parent: RootNode | MutableNode, name: string, path: string): MutableNode => {
    const existing = parent.children.get(name)
    if (existing) return existing
    const created: MutableNode = { name, path, children: new Map<string, MutableNode>(), templates: [] }
    parent.children.set(name, created)
    return created
  }

  for (const template of templates) {
    const folderPath = template.folderPath
    if (!folderPath) {
      rootTemplates.push(template)
      continue
    }
    const segments = folderPath.split('/').map((seg) => seg.trim()).filter((seg) => seg.length > 0)
    if (segments.length === 0) {
      rootTemplates.push(template)
      continue
    }
    let current: RootNode | MutableNode = root
    let currentPath = ''
    for (const segment of segments) {
      currentPath = currentPath ? `${currentPath}/${segment}` : segment
      current = ensureNode(current, segment, currentPath)
    }
    current.templates.push(template)
  }

  const finalize = (node: any): TemplateFolderNode => {
    const children = Array.from(node.children.values()).map(finalize).sort((a, b) => a.name.localeCompare(b.name))
    const templatesSorted = [...node.templates].sort((a, b) => a.name.localeCompare(b.name))
    return { name: node.name, path: node.path, children, templates: templatesSorted }
  }

  const folders = Array.from(root.children.values()).map(finalize).sort((a, b) => a.name.localeCompare(b.name))
  rootTemplates.sort((a, b) => a.name.localeCompare(b.name))
  return { folders, rootTemplates }
}

function countTemplates(node: TemplateFolderNode): number {
  let count = node.templates.length
  for (const child of node.children) {
    count += countTemplates(child)
  }
  return count
}

export default function SwarmCreateModal({ onClose, autoPullOnStart, onChangeAutoPull }: Props) {
  const [swarmId, setSwarmId] = useState('')
  const [templates, setTemplates] = useState<ScenarioTemplate[]>([])
  const [templateFilter, setTemplateFilter] = useState('')
  const [scenarioId, setScenarioId] = useState('')
  const [bundleSuts, setBundleSuts] = useState<string[]>([])
  const [sutId, setSutId] = useState<string>('')
  const [variablesProfiles, setVariablesProfiles] = useState<VariablesProfile[]>([])
  const [variablesProfileId, setVariablesProfileId] = useState('')
  const [variablesRequireSut, setVariablesRequireSut] = useState(false)
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

        if (cancelled) return

        if (!templatesResponse.ok) {
          setTemplates([])
          return
        }

        const templatesData = await templatesResponse.json()
        setTemplates(normalizeTemplates(templatesData))
        setBundleSuts([])
        setVariablesProfiles([])
        setVariablesProfileId('')
        setVariablesRequireSut(false)
      } catch {
        if (!cancelled) {
          setTemplates([])
          setBundleSuts([])
          setVariablesProfiles([])
          setVariablesProfileId('')
          setVariablesRequireSut(false)
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
    if (variablesProfiles.length > 0 && !variablesProfileId) {
      setMessage('Variables profile is required for this scenario')
      return
    }
    if (variablesRequireSut && !sutId) {
      setMessage('SUT is required for this scenario (sut-scoped variables exist)')
      return
    }
    try {
      await createSwarm(swarmId.trim(), scenarioId, {
        autoPullImages: autoPullOnStart,
        sutId: sutId || null,
        variablesProfileId: variablesProfileId || null,
      })
      setMessage('Swarm created')
      setSwarmId('')
      setScenarioId('')
      setSutId('')
      setVariablesProfileId('')
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

  const filteredTemplates = useMemo(() => {
    const needle = templateFilter.trim().toLowerCase()
    if (!needle) return templates
    return templates.filter((template) => templateMatchesNeedle(template, needle))
  }, [templateFilter, templates])

  const hasAnyFolder = useMemo(() => templates.some((template) => Boolean(template.folderPath)), [templates])

  const tree = useMemo(() => buildTemplateFolderTree(filteredTemplates), [filteredTemplates])

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

  useEffect(() => {
    let cancelled = false
    setScenarioPreview(null)
    setScenarioPreviewError(null)
    setBundleSuts([])
    setSutId('')
    setVariablesProfiles([])
    setVariablesProfileId('')
    setVariablesRequireSut(false)

    const loadScenario = async () => {
      if (!selectedTemplate) {
        return
      }
      setShowRawScenario(false)
      try {
        const scenarioReq = apiFetch(
          `/scenario-manager/scenarios/${encodeURIComponent(selectedTemplate.id)}`,
          { headers: { Accept: 'application/json' } },
        )
        const sutsReq = apiFetch(
          `/scenario-manager/scenarios/${encodeURIComponent(selectedTemplate.id)}/suts`,
          { headers: { Accept: 'application/json' } },
        )
        const varsReq = apiFetch(
          `/scenario-manager/scenarios/${encodeURIComponent(selectedTemplate.id)}/variables`,
          { headers: { Accept: 'text/plain' } },
        )
        const response = await scenarioReq
        const sutsResponse = await sutsReq
        const varsResponse = await varsReq
        if (cancelled) return
        if (!response.ok) {
          setScenarioPreviewError('Failed to load scenario definition')
          return
        }
        const body = await response.json()
        setScenarioPreview(JSON.stringify(body, null, 2))

        if (sutsResponse.ok) {
          const data = (await sutsResponse.json()) as unknown
          const ids = Array.isArray(data)
            ? data
                .map((entry) => (typeof entry === 'string' ? entry.trim() : ''))
                .filter((entry) => entry.length > 0)
            : []
          setBundleSuts(ids)
        } else {
          setBundleSuts([])
        }

        if (varsResponse.ok) {
          const text = await varsResponse.text()
          const parsed = parseVariables(text)
          setVariablesProfiles(parsed.profiles)
          setVariablesRequireSut(parsed.requiresSut)
          if (parsed.profiles.length === 1) {
            setVariablesProfileId(parsed.profiles[0].id)
          }
        } else {
          setVariablesProfiles([])
          setVariablesRequireSut(false)
        }
      } catch {
        if (!cancelled) {
          setScenarioPreviewError('Failed to load scenario definition')
          setBundleSuts([])
          setVariablesProfiles([])
          setVariablesRequireSut(false)
        }
      }
    }

    void loadScenario()

    return () => {
      cancelled = true
    }
  }, [selectedTemplate])

  const renderTemplateButton = (template: ScenarioTemplate) => {
    const selected = template.id === scenarioId
    return (
      <button
        key={template.id}
        type="button"
        onClick={() => setScenarioId(template.id)}
        aria-label={template.name}
        className={`w-full text-left px-3 py-2 hover:bg-white/10 ${selected ? 'bg-white/15 text-white' : 'text-white/80'}`}
      >
        <div className="font-medium leading-snug break-words">{template.name}</div>
        <div className="text-[10px] text-white/50 break-words">
          {template.folderPath ? `${template.folderPath}/${template.id}` : template.id}
        </div>
        {template.description && <div className="text-[11px] text-white/50 line-clamp-2">{template.description}</div>}
      </button>
    )
  }

  const renderFolderNode = (node: TemplateFolderNode): React.ReactNode => {
    const open = openFolderPaths === null ? true : openFolderPaths.has(node.path)
    return (
      <details key={node.path} open={open} className="border-t border-white/10">
        <summary aria-label={`folder ${node.path}`} className="px-3 py-2 text-xs text-white/60 cursor-pointer select-none">
          <span className="font-medium text-white/80">{node.name}</span>{' '}
          <span className="text-white/40">({countTemplates(node)})</span>
        </summary>
        <div className="pl-3">
          {node.children.map((child) => renderFolderNode(child))}
          {node.templates.map((template) => (
            <div key={template.id}>{renderTemplateButton(template)}</div>
          ))}
        </div>
      </details>
    )
  }

  return (
    <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50">
      <div className="bg-[#1a1d24] p-5 rounded-lg w-[900px] max-w-[95vw] h-[calc(92vh-8px)] max-h-[calc(92vh-8px)] min-h-[min(700px,80vh)] flex flex-col shadow-xl border border-white/10 overflow-hidden">
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
                {bundleSuts.length > 0 && (
                  <>
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
                      {bundleSuts.map((id) => (
                        <option key={id} value={id}>
                          {id}
                        </option>
                      ))}
                    </select>
                  </>
                )}
              </div>
              {variablesProfiles.length > 0 && (
                <div className="flex flex-col gap-1 mr-4">
                  <label htmlFor="varsProfile" className="block text-xs mb-0.5 text-white/70">
                    Variables profile
                  </label>
                  <select
                    id="varsProfile"
                    className="rounded border border-white/20 bg-[#020617] px-2 py-1 text-xs text-white"
                    value={variablesProfileId}
                    onChange={(e) => setVariablesProfileId(e.target.value)}
                  >
                    <option value="">(select)</option>
                    {variablesProfiles.map((profile) => (
                      <option key={profile.id} value={profile.id}>
                        {profile.name ? `${profile.name} (${profile.id})` : profile.id}
                      </option>
                    ))}
                  </select>
                </div>
              )}
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
                <div className="flex items-center justify-between gap-2">
                  <span>Scenarios</span>
                </div>
                <div className="mt-2">
                  <input
                    value={templateFilter}
                    onChange={(e) => setTemplateFilter(e.target.value)}
                    placeholder="Filter"
                    className="w-full rounded border border-white/20 bg-white/10 px-2 py-1 text-[11px] normal-case tracking-normal text-white/80"
                    aria-label="Scenario filter"
                  />
                </div>
              </div>
              <div className="flex-1 overflow-y-auto">
                {filteredTemplates.length === 0 ? (
                  <div className="px-3 py-3 text-xs text-white/50">No scenarios available.</div>
                ) : hasAnyFolder ? (
                  <div className="text-sm">
                    {tree.folders.map((folder) => renderFolderNode(folder))}
                    {tree.rootTemplates.length > 0 ? (
                      <details
                        open={openFolderPaths === null || Boolean(selectedTemplate && !selectedTemplate.folderPath)}
                        className="border-t border-white/10"
                      >
                        <summary className="px-3 py-2 text-xs text-white/60 cursor-pointer select-none">
                          <span className="font-medium text-white/80">(root)</span>{' '}
                          <span className="text-white/40">({tree.rootTemplates.length})</span>
                        </summary>
                        <div className="pl-3">
                          {tree.rootTemplates.map((template) => (
                            <div key={template.id}>{renderTemplateButton(template)}</div>
                          ))}
                        </div>
                      </details>
                    ) : null}
                  </div>
                ) : (
                  <ul className="text-sm">
                    {filteredTemplates.map((template) => {
                      return (
                        <li key={template.id}>
                          {renderTemplateButton(template)}
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

  const folderPath =
    typeof value.folderPath === 'string' && value.folderPath.trim().length > 0 ? value.folderPath.trim() : null
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

  return { id, name, folderPath, description, controllerImage, bees }
}

function normalizeBee(entry: unknown): ScenarioBee | null {
  if (!entry || typeof entry !== 'object') return null
  const value = entry as Record<string, unknown>
  const role = typeof value.role === 'string' ? value.role.trim() : ''
  const image =
    typeof value.image === 'string' && value.image.trim().length > 0 ? value.image.trim() : null
  return { role, image }
}

function parseVariables(text: string): { profiles: VariablesProfile[]; requiresSut: boolean } {
  try {
    const doc = (YAML.parse(text) ?? {}) as Record<string, unknown>
    const definitions = Array.isArray(doc.definitions) ? doc.definitions : []
    const requiresSut = definitions.some((def) => {
      if (!def || typeof def !== 'object') return false
      const scope = (def as Record<string, unknown>).scope
      return typeof scope === 'string' && scope.trim().toLowerCase() === 'sut'
    })
    const profiles = Array.isArray(doc.profiles) ? doc.profiles : []
    const normalized: VariablesProfile[] = profiles
      .map((profile) => {
        if (!profile || typeof profile !== 'object') return null
        const record = profile as Record<string, unknown>
        const id = typeof record.id === 'string' ? record.id.trim() : ''
        if (!id) return null
        const name = typeof record.name === 'string' ? record.name.trim() : null
        return { id, name: name && name.length > 0 ? name : null }
      })
      .filter((p): p is VariablesProfile => Boolean(p))
    return { profiles: normalized, requiresSut }
  } catch {
    return { profiles: [], requiresSut: false }
  }
}
