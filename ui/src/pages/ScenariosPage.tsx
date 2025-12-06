import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import Editor from '@monaco-editor/react'
import { Link } from 'react-router-dom'
import {
  listScenarios,
  downloadScenarioBundle,
  uploadScenarioBundle,
  replaceScenarioBundle,
  fetchScenarioRaw,
  saveScenarioRaw,
  getScenario,
  buildPlanView,
  saveScenarioPlan,
  mergePlan,
  type ScenarioPayload,
  type ScenarioPlanView,
} from '../lib/scenarioManagerApi'
import type { ScenarioSummary } from '../types/scenarios'
import { useUIStore } from '../store'

export default function ScenariosPage() {
  const [items, setItems] = useState<ScenarioSummary[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [selectedScenario, setSelectedScenario] = useState<ScenarioPayload | null>(null)
  const [planExpanded, setPlanExpanded] = useState(false)
  const [planDraft, setPlanDraft] = useState<ScenarioPlanView | null>(null)

  const [editing, setEditing] = useState(false)
  const [rawYaml, setRawYaml] = useState('')
  const [savedYaml, setSavedYaml] = useState('')
  const [rawError, setRawError] = useState<string | null>(null)
  const [rawLoading, setRawLoading] = useState(false)
  const [saving, setSaving] = useState(false)

  const uploadInputRef = useRef<HTMLInputElement | null>(null)
  const replaceInputRef = useRef<HTMLInputElement | null>(null)

  const setToast = useUIStore((s) => s.setToast)

  const loadScenarios = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const list = await listScenarios()
      setItems(list)
      if (list.length > 0) {
        setSelectedId((current) => current ?? list[0].id)
      } else {
        setSelectedId(null)
        setSelectedScenario(null)
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to load scenarios')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    void loadScenarios()
  }, [loadScenarios])

  useEffect(() => {
    const id = selectedId
    if (!id) {
      setSelectedScenario(null)
      setRawYaml('')
      setSavedYaml('')
      setRawError(null)
      setPlanDraft(null)
      return
    }
    let cancelled = false
    const load = async () => {
      try {
        const scenario = await getScenario(id)
        if (!cancelled) {
          setSelectedScenario(scenario)
        }
        setRawLoading(true)
        setRawError(null)
        try {
          const text = await fetchScenarioRaw(id)
          if (!cancelled) {
            setRawYaml(text)
            setSavedYaml(text)
          }
        } catch (e) {
          if (!cancelled) {
            setRawError(e instanceof Error ? e.message : 'Failed to load scenario YAML')
            setRawYaml('')
            setSavedYaml('')
          }
        } finally {
          if (!cancelled) {
            setRawLoading(false)
          }
        }
        if (!cancelled) {
          const plan =
            scenario && typeof scenario.plan !== 'undefined'
              ? buildPlanView(scenario.plan)
              : null
          setPlanDraft(plan)
        }
      } catch {
        if (!cancelled) {
          setSelectedScenario(null)
          setPlanDraft(null)
        }
      }
    }
    void load()
    return () => {
      cancelled = true
    }
  }, [selectedId])

  const handleDownload = async (id: string) => {
    try {
      const blob = await downloadScenarioBundle(id)
      const url = URL.createObjectURL(blob)
      const link = document.createElement('a')
      link.href = url
      link.download = `${id}-bundle.zip`
      document.body.appendChild(link)
      link.click()
      document.body.removeChild(link)
      URL.revokeObjectURL(url)
      setToast(`Downloaded bundle for ${id}`)
    } catch (e) {
      setToast(
        e instanceof Error ? `Download failed: ${e.message}` : 'Download failed (scenario bundle)',
      )
    }
  }

  const handleUpload = async (file: File | null) => {
    if (!file) return
    try {
      const summary = await uploadScenarioBundle(file)
      setToast(`Uploaded scenario bundle ${summary.id}`)
      await loadScenarios()
      setSelectedId(summary.id)
    } catch (e) {
      setToast(
        e instanceof Error ? `Upload failed: ${e.message}` : 'Upload failed (scenario bundle)',
      )
    }
  }

  const handleReplace = async (file: File | null) => {
    if (!file || !selectedId) return
    try {
      const summary = await replaceScenarioBundle(selectedId, file)
      setToast(`Updated scenario bundle ${summary.id}`)
      await loadScenarios()
      setSelectedId(summary.id)
    } catch (e) {
      setToast(
        e instanceof Error ? `Update failed: ${e.message}` : 'Update failed (scenario bundle)',
      )
    }
  }

  const handleEditToggle = async () => {
    if (!selectedId) return
    if (editing) {
      setEditing(false)
      setRawError(null)
      return
    }
    setEditing(true)
  }

  const handleSave = async () => {
    if (!selectedId) return
    if (rawYaml === savedYaml) {
      setEditing(false)
      return
    }
    setSaving(true)
    setRawError(null)
    try {
      await saveScenarioRaw(selectedId, rawYaml)
      await loadScenarios()
      setSavedYaml(rawYaml)
      setEditing(false)
      setToast(`Saved scenario ${selectedId}`)
    } catch (e) {
      setRawError(e instanceof Error ? e.message : 'Failed to save scenario')
    } finally {
      setSaving(false)
    }
  }

  const selectedSummary = useMemo(
    () => items.find((s) => s.id === selectedId) ?? null,
    [items, selectedId],
  )

  const hasUnsavedChanges = useMemo(
    () => rawYaml !== savedYaml,
    [rawYaml, savedYaml],
  )

  const updateSwarmStep = useCallback(
    (index: number, patch: Partial<ScenarioPlanView['swarm'][number]>) => {
      setPlanDraft((current) => {
        if (!current) return current
        const swarm = current.swarm.map((step, i) =>
          i === index ? { ...step, ...patch } : step,
        )
        return { ...current, swarm }
      })
    },
    [],
  )

  const removeSwarmStep = useCallback((index: number) => {
    setPlanDraft((current) => {
      if (!current) return current
      return { ...current, swarm: current.swarm.filter((_, i) => i !== index) }
    })
  }, [])

  const updateBee = useCallback(
    (index: number, patch: { instanceId?: string | null; role?: string | null }) => {
      setPlanDraft((current) => {
        if (!current) return current
        const bees = current.bees.map((bee, i) =>
          i === index ? { ...bee, ...patch } : bee,
        )
        return { ...current, bees }
      })
    },
    [],
  )

  const addBeeStep = useCallback((beeIndex: number) => {
    setPlanDraft((current) => {
      if (!current) return current
      const bees = current.bees.map((bee, i) =>
        i === beeIndex
          ? {
              ...bee,
              steps: [
                ...bee.steps,
                {
                  stepId: null,
                  name: null,
                  time: 'PT1S',
                  type: 'config-update',
                },
              ],
            }
          : bee,
      )
      return { ...current, bees }
    })
  }, [])

  const updateBeeStep = useCallback(
    (
      beeIndex: number,
      stepIndex: number,
      patch: Partial<ScenarioPlanView['swarm'][number]>,
    ) => {
      setPlanDraft((current) => {
        if (!current) return current
        const bees = current.bees.map((bee, i) => {
          if (i !== beeIndex) return bee
          const steps = bee.steps.map((step, j) =>
            j === stepIndex ? { ...step, ...patch } : step,
          )
          return { ...bee, steps }
        })
        return { ...current, bees }
      })
    },
    [],
  )

  const removeBeeStep = useCallback((beeIndex: number, stepIndex: number) => {
    setPlanDraft((current) => {
      if (!current) return current
      const bees = current.bees.map((bee, i) => {
        if (i !== beeIndex) return bee
        return { ...bee, steps: bee.steps.filter((_, j) => j !== stepIndex) }
      })
      return { ...current, bees }
    })
  }, [])

  const removeBee = useCallback((index: number) => {
    setPlanDraft((current) => {
      if (!current) return current
      return { ...current, bees: current.bees.filter((_, i) => i !== index) }
    })
  }, [])

  return (
    <div className="flex h-full min-h-0 bg-[#05070b] text-white">
      <div className="w-72 border-r border-white/10 px-4 py-4 space-y-3">
        <div className="flex items-center justify-between">
          <h1 className="text-sm font-semibold text-white/90">Scenarios</h1>
          <Link
            to="/hive"
            className="text-xs text-sky-300 hover:text-sky-200 transition"
          >
            Back to Hive
          </Link>
        </div>
        <div className="flex items-center gap-2">
          <button
            type="button"
            className="rounded border border-white/15 bg-white/10 px-2 py-1 text-[11px] text-white/80 hover:bg-white/20"
            onClick={() => uploadInputRef.current?.click()}
          >
            Upload bundle
          </button>
          <input
            ref={uploadInputRef}
            type="file"
            accept=".zip"
            className="hidden"
            onChange={(e) => {
              const [file] = Array.from(e.target.files ?? [])
              void handleUpload(file ?? null)
              e.target.value = ''
            }}
          />
        </div>
        {loading && (
          <div className="text-xs text-white/60">Loading scenarios…</div>
        )}
        {error && (
          <div className="text-xs text-red-400">Failed to load: {error}</div>
        )}
        <div className="space-y-1 overflow-y-auto max-h-[calc(100vh-7rem)] pr-1">
          {items.map((scenario) => {
            const isSelected = scenario.id === selectedId
            return (
              <button
                key={scenario.id}
                type="button"
                onClick={() => setSelectedId(scenario.id)}
                className={`w-full text-left rounded-md px-3 py-2 text-xs border ${
                  isSelected
                    ? 'border-sky-400 bg-sky-500/10'
                    : 'border-white/10 bg-white/5 hover:bg-white/10'
                }`}
              >
                <div className="flex items-center justify-between gap-2">
                  <span className="font-medium text-white/90">
                    {scenario.name || scenario.id}
                  </span>
                </div>
                <div className="mt-0.5 text-[10px] text-white/60">
                  {scenario.id}
                </div>
              </button>
            )
          })}
          {!loading && !error && items.length === 0 && (
            <div className="text-xs text-white/60">No scenarios defined.</div>
          )}
        </div>
      </div>
      <div className="flex-1 px-6 py-4 overflow-y-auto">
        {!selectedSummary && !loading && (
          <div className="text-sm text-white/70">
            Select a scenario on the left to view details.
          </div>
        )}
        {selectedSummary && (
          <div className="space-y-4">
            <div className="flex items-center justify-between gap-4">
              <div>
                <h2 className="text-base font-semibold text-white/90">
                  {selectedSummary.name || selectedSummary.id}
                </h2>
                <div className="mt-1 text-xs text-white/60 space-x-2">
                  <span>ID: {selectedSummary.id}</span>
                  {selectedScenario?.description && (
                    <span>{selectedScenario.description}</span>
                  )}
                </div>
              </div>
              <div className="flex items-center gap-2">
                <button
                  type="button"
                  className="rounded bg-white/10 px-2 py-1 text-[11px] text-white/80 hover:bg-white/20"
                  onClick={() => void handleDownload(selectedSummary.id)}
                >
                  Download bundle
                </button>
                <button
                  type="button"
                  className="rounded bg-white/10 px-2 py-1 text-[11px] text-white/80 hover:bg-white/20"
                  onClick={() => replaceInputRef.current?.click()}
                >
                  Replace bundle
                </button>
                <input
                  ref={replaceInputRef}
                  type="file"
                  accept=".zip"
                  className="hidden"
                  onChange={(e) => {
                    const [file] = Array.from(e.target.files ?? [])
                    void handleReplace(file ?? null)
                    e.target.value = ''
                  }}
                />
                <button
                  type="button"
                  className="rounded bg-white/10 px-2 py-1 text-[11px] text-white/80 hover:bg-white/20 disabled:opacity-50"
                  disabled={!selectedId || rawLoading}
                  onClick={() => void handleEditToggle()}
                >
                  {editing ? 'Close editor' : 'Edit YAML'}
                </button>
              </div>
            </div>
            {planDraft && (
              <div className="border border-white/15 rounded-md p-3 bg-white/5">
                <div className="flex items-center justify-between">
                  <h3 className="text-xs font-semibold text-white/80">
                    Plan overview
                  </h3>
                  <div className="flex items-center gap-2">
                    <button
                      type="button"
                      className="rounded bg-white/10 px-2 py-1 text-[11px] text-white/80 hover:bg-white/20"
                      onClick={() =>
                        setPlanDraft((current) => ({
                          swarm: current?.swarm ?? [],
                          bees: [
                            ...(current?.bees ?? []),
                            { instanceId: 'bee-1', role: null, steps: [] },
                          ],
                        }))
                      }
                    >
                      Add bee timeline
                    </button>
                    <button
                      type="button"
                      className="rounded bg-white/10 px-2 py-1 text-[11px] text-white/80 hover:bg-white/20"
                      onClick={() =>
                        setPlanDraft((current) => ({
                          swarm: [
                            ...(current?.swarm ?? []),
                            {
                              stepId: 'swarm-step',
                              name: null,
                              time: 'PT1S',
                              type: 'config-update',
                            },
                          ],
                          bees: current?.bees ?? [],
                        }))
                      }
                    >
                      Add swarm step
                    </button>
                    <button
                      type="button"
                      className="rounded bg-sky-500/80 px-2 py-1 text-[11px] text-white hover:bg-sky-500 disabled:opacity-50"
                      disabled={!selectedId}
                      onClick={async () => {
                        if (!selectedId) return
                        try {
                          const merged = mergePlan(
                            selectedScenario?.plan,
                            planDraft,
                          )
                          await saveScenarioPlan(selectedId, merged)
                          setToast(`Saved plan for ${selectedId}`)
                          await loadScenarios()
                          setPlanExpanded(false)
                        } catch (e) {
                          setToast(
                            e instanceof Error
                              ? `Failed to save plan: ${e.message}`
                              : 'Failed to save plan',
                          )
                        }
                      }}
                    >
                      Save plan
                    </button>
                    <button
                      type="button"
                      className="text-[11px] text-sky-300 hover:text-sky-200"
                      onClick={() => setPlanExpanded((prev) => !prev)}
                    >
                      {planExpanded ? 'Collapse' : 'Expand'}
                    </button>
                  </div>
                </div>
                <div className="mt-2 space-y-2 text-xs text-white/80">
                  {planDraft.swarm.length > 0 && (
                    <div>
                      <div className="font-semibold text-white/90 mb-1">
                        Swarm steps
                      </div>
                      <ul className="space-y-1">
                        {planDraft.swarm.map((step, idx) => (
                          <li key={`${step.stepId ?? idx}`} className="flex gap-2 items-center">
                            <input
                              className="w-24 rounded border border-white/15 bg-black/40 px-1 py-0.5 text-[11px] text-white/90"
                              value={step.time ?? ''}
                              placeholder="PT1S"
                              onChange={(e) =>
                                updateSwarmStep(idx, {
                                  time: e.target.value.trim() || null,
                                })
                              }
                            />
                            <input
                              className="w-32 rounded border border-white/15 bg-black/40 px-1 py-0.5 text-[11px] text-white/90"
                              value={step.stepId ?? ''}
                              placeholder="step-id"
                              onChange={(e) =>
                                updateSwarmStep(idx, {
                                  stepId: e.target.value.trim() || null,
                                })
                              }
                            />
                            <input
                              className="flex-1 rounded border border-white/15 bg-black/40 px-1 py-0.5 text-[11px] text-white/90"
                              value={step.name ?? ''}
                              placeholder="Step name"
                              onChange={(e) =>
                                updateSwarmStep(idx, {
                                  name: e.target.value.trim() || null,
                                })
                              }
                            />
                            <input
                              className="w-32 rounded border border-white/15 bg-black/40 px-1 py-0.5 text-[11px] text-white/90"
                              value={step.type ?? ''}
                              placeholder="config-update"
                              onChange={(e) =>
                                updateSwarmStep(idx, {
                                  type: e.target.value.trim() || null,
                                })
                              }
                            />
                            <button
                              type="button"
                              className="text-[11px] text-red-300 hover:text-red-200"
                              onClick={() => removeSwarmStep(idx)}
                            >
                              ✕
                            </button>
                          </li>
                        ))}
                      </ul>
                    </div>
                  )}
                  {planDraft.bees.length > 0 && (
                    <div>
                      <div className="font-semibold text-white/90 mb-1">
                        Bee steps
                      </div>
                      <div className="space-y-1">
                        {planDraft.bees.map((bee, index) => (
                          <div key={`${bee.instanceId ?? bee.role ?? index}`}>
                            <div className="flex items-center gap-2 mb-0.5">
                              <input
                                className="w-32 rounded border border-white/15 bg-black/40 px-1 py-0.5 text-[11px] text-white/90"
                                value={bee.instanceId ?? ''}
                                placeholder="instanceId"
                                onChange={(e) =>
                                  updateBee(index, {
                                    instanceId: e.target.value.trim() || null,
                                  })
                                }
                              />
                              <input
                                className="w-28 rounded border border-white/15 bg-black/40 px-1 py-0.5 text-[11px] text-white/90"
                                value={bee.role ?? ''}
                                placeholder="role"
                                onChange={(e) =>
                                  updateBee(index, {
                                    role: e.target.value.trim() || null,
                                  })
                                }
                              />
                              <button
                                type="button"
                                className="text-[11px] text-red-300 hover:text-red-200 ml-auto"
                                onClick={() => removeBee(index)}
                              >
                                Remove bee
                              </button>
                              <button
                                type="button"
                                className="text-[11px] text-sky-300 hover:text-sky-200"
                                onClick={() => addBeeStep(index)}
                              >
                                Add step
                              </button>
                            </div>
                            <ul className="space-y-1">
                              {bee.steps.map((step, idx) => (
                                <li
                                  key={`${step.stepId ?? idx}`}
                                  className="flex gap-2 items-center"
                                >
                                  <input
                                    className="w-24 rounded border border-white/15 bg-black/40 px-1 py-0.5 text-[11px] text-white/90"
                                    value={step.time ?? ''}
                                    placeholder="PT1S"
                                    onChange={(e) =>
                                      updateBeeStep(index, idx, {
                                        time: e.target.value.trim() || null,
                                      })
                                    }
                                  />
                                  <input
                                    className="w-32 rounded border border-white/15 bg-black/40 px-1 py-0.5 text-[11px] text-white/90"
                                    value={step.stepId ?? ''}
                                    placeholder="step-id"
                                    onChange={(e) =>
                                      updateBeeStep(index, idx, {
                                        stepId: e.target.value.trim() || null,
                                      })
                                    }
                                  />
                                  <input
                                    className="flex-1 rounded border border-white/15 bg-black/40 px-1 py-0.5 text-[11px] text-white/90"
                                    value={step.name ?? ''}
                                    placeholder="Step name"
                                    onChange={(e) =>
                                      updateBeeStep(index, idx, {
                                        name: e.target.value.trim() || null,
                                      })
                                    }
                                  />
                                  <input
                                    className="w-32 rounded border border-white/15 bg-black/40 px-1 py-0.5 text-[11px] text-white/90"
                                    value={step.type ?? ''}
                                    placeholder="config-update"
                                    onChange={(e) =>
                                      updateBeeStep(index, idx, {
                                        type: e.target.value.trim() || null,
                                      })
                                    }
                                  />
                                  <button
                                    type="button"
                                    className="text-[11px] text-red-300 hover:text-red-200"
                                    onClick={() => removeBeeStep(index, idx)}
                                  >
                                    ✕
                                  </button>
                                </li>
                              ))}
                            </ul>
                          </div>
                        ))}
                      </div>
                    </div>
                  )}
                  {planExpanded && selectedScenario && selectedScenario.plan != null ? (
                    <pre className="mt-2 max-h-60 overflow-auto rounded bg-black/60 p-2 text-[11px] text-sky-100 whitespace-pre-wrap">
                      {JSON.stringify(selectedScenario.plan, null, 2)}
                    </pre>
                  ) : null}
                </div>
              </div>
            )}
            {editing && (
              <div className="flex flex-col gap-3 h-[60vh]">
                <div className="flex items-center justify-between">
                  <h3 className="text-xs font-semibold text-white/80">
                    Edit scenario (YAML){' '}
                    {hasUnsavedChanges ? (
                      <span className="text-[10px] text-amber-300">(unsaved changes)</span>
                    ) : (
                      <span className="text-[10px] text-white/40">(saved)</span>
                    )}
                  </h3>
                  <div className="flex items-center gap-2">
                    <button
                      type="button"
                      className="rounded bg-white/5 px-2 py-1 text-[11px] text-white/70 hover:bg-white/15"
                      onClick={() => {
                        const template = [
                          '# Scenario template snippet',
                          'id: example-scenario',
                          'name: Example scenario',
                          'description: Example scenario for PocketHive',
                          'template:',
                          '  image: pockethive-swarm-controller:latest',
                          '  instanceId: marshal-bee',
                          '  bees:',
                          '    - role: generator',
                          '      instanceId: gen-1',
                          '      image: pockethive-generator:latest',
                          '      work: { out: gen }',
                          '    - role: processor',
                          '      instanceId: proc-1',
                          '      image: pockethive-processor:latest',
                          '      work: { in: gen, out: final }',
                          'plan:',
                          '  swarm:',
                          '    - stepId: swarm-start',
                          '      name: Start swarm work',
                          '      time: PT1S',
                          '      type: start',
                          '  bees:',
                          '    - instanceId: gen-1',
                          '      steps:',
                          '        - stepId: gen-rate',
                          '          name: Change generator rate',
                          '          time: PT5S',
                          '          type: config-update',
                          '          config:',
                          '            ratePerSec: "10"',
                          '',
                        ].join('\n')
                        setRawYaml((current) => {
                          const trimmed = current.trim()
                          if (!trimmed) return template
                          return `${current.replace(/\s*$/, '')}\n\n${template}`
                        })
                      }}
                    >
                      Insert template
                    </button>
                    <button
                      type="button"
                      className="rounded bg-white/10 px-2 py-1 text-[11px] text-white/80 hover:bg-white/20 disabled:opacity-50"
                      disabled={saving || rawLoading || !hasUnsavedChanges}
                      onClick={() => void handleSave()}
                    >
                      {saving ? 'Saving…' : 'Save'}
                    </button>
                    <button
                      type="button"
                      className="rounded bg-white/10 px-2 py-1 text-[11px] text-white/80 hover:bg-white/20"
                      onClick={() => {
                        setEditing(false)
                        setRawError(null)
                      }}
                    >
                      Close
                    </button>
                  </div>
                </div>
                {rawLoading && (
                  <div className="text-xs text-white/60">Loading YAML…</div>
                )}
                {rawError && (
                  <div className="text-xs text-red-400">
                    Failed to load/save: {rawError}
                  </div>
                )}
                <div className="flex-1 min-h-0 border border-white/15 rounded overflow-hidden">
                  <Editor
                    height="100%"
                    defaultLanguage="yaml"
                    theme="vs-dark"
                    value={rawYaml}
                    onChange={(value) => setRawYaml(value ?? '')}
                    options={{
                      fontSize: 11,
                      minimap: { enabled: false },
                      scrollBeyondLastLine: false,
                      wordWrap: 'on',
                    }}
                  />
                </div>
              </div>
            )}
            {!editing && selectedScenario && (
              <div>
                <h3 className="text-xs font-semibold text-white/80 mb-1.5">
                  Raw scenario (YAML)
                </h3>
                {rawLoading && (
                  <div className="text-xs text-white/60">Loading YAML…</div>
                )}
                {rawError && (
                  <div className="text-xs text-red-400 mb-1">
                    Failed to load: {rawError}
                  </div>
                )}
                {!rawLoading && !rawError && (
                  <pre className="bg-black/60 border border-white/10 rounded-md p-3 text-[11px] text-sky-100 overflow-x-auto whitespace-pre-wrap">
                    {rawYaml}
                  </pre>
                )}
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  )
}
