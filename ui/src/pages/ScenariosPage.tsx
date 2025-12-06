import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type * as React from 'react'
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
  createScenario,
  type ScenarioPayload,
  type ScenarioPlanView,
  type ScenarioPlanStep,
} from '../lib/scenarioManagerApi'
import type { ScenarioSummary } from '../types/scenarios'
import type { CapabilityConfigEntry, CapabilityManifest } from '../types/capabilities'
import { useUIStore } from '../store'
import { useCapabilities } from '../contexts/CapabilitiesContext'

type TimelineRow = {
  key: string
  kind: 'swarm' | 'bee'
  beeIndex: number | null
  stepIndex: number
  seconds: number | null
  original: string
  target: string
  stepId: string
  name: string | null
  type: string | null
}

type TimelineRowRef = Pick<TimelineRow, 'kind' | 'beeIndex' | 'stepIndex'>

interface PlanTimelineLanesProps {
  rows: TimelineRow[]
  onTimeChange: (row: TimelineRowRef, seconds: number) => void
}

type ConfigFormValue = string | boolean

const TIMELINE_DIVISION_PX = 80

const ZOOM_LEVELS = [
  { id: '1s', label: '1s / division', secondsPerDivision: 1 },
  { id: '5s', label: '5s / division', secondsPerDivision: 5 },
  { id: '10s', label: '10s / division', secondsPerDivision: 10 },
  { id: '30s', label: '30s / division', secondsPerDivision: 30 },
  { id: '1m', label: '1m / division', secondsPerDivision: 60 },
  { id: '5m', label: '5m / division', secondsPerDivision: 300 },
  { id: '10m', label: '10m / division', secondsPerDivision: 600 },
  { id: '30m', label: '30m / division', secondsPerDivision: 1800 },
  { id: '1h', label: '1h / division', secondsPerDivision: 3600 },
  { id: '1d', label: '1d / division', secondsPerDivision: 86400 },
] as const

function formatDurationLabel(seconds: number | null): string {
  if (seconds == null || !Number.isFinite(seconds) || seconds < 0) {
    return '—'
  }
  const total = Math.round(seconds)
  if (total === 0) return '0s'
  if (total < 60) return `${total}s`
  if (total < 3600 && total % 60 === 0) return `${total / 60}m`
  if (total < 86400 && total % 3600 === 0) return `${total / 3600}h`
  if (total % 86400 === 0) return `${total / 86400}d`

  const parts: string[] = []
  let remaining = total
  const days = Math.floor(remaining / 86400)
  if (days > 0) {
    parts.push(`${days}d`)
    remaining -= days * 86400
  }
  const hours = Math.floor(remaining / 3600)
  if (hours > 0) {
    parts.push(`${hours}h`)
    remaining -= hours * 3600
  }
  const minutes = Math.floor(remaining / 60)
  if (minutes > 0) {
    parts.push(`${minutes}m`)
    remaining -= minutes * 60
  }
  if (remaining > 0) {
    parts.push(`${remaining}s`)
  }
  return parts.join(' ')
}

function parseSeconds(value: string | null): number | null {
  if (!value) return null
  const text = value.trim()
  if (!text) return null

  if (text.startsWith('PT')) {
    const match = /^PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?$/.exec(text)
    if (!match) return null
    const hours = match[1] ? Number(match[1]) : 0
    const minutes = match[2] ? Number(match[2]) : 0
    const seconds = match[3] ? Number(match[3]) : 0
    const total = hours * 3600 + minutes * 60 + seconds
    return Number.isFinite(total) ? total : null
  }

  const simple = /^(\d+(?:\.\d+)?)([smh])$/i.exec(text)
  if (simple) {
    const amount = Number(simple[1])
    if (!Number.isFinite(amount)) return null
    const unit = simple[2].toLowerCase()
    if (unit === 's') return amount
    if (unit === 'm') return amount * 60
    if (unit === 'h') return amount * 3600
  }

  return null
}

function formatSeconds(seconds: number): string {
  if (!Number.isFinite(seconds) || seconds <= 0) {
    return '0s'
  }
  const rounded = Math.round(seconds)
  return `${rounded}s`
}

function normalisePlanTimes(view: ScenarioPlanView | null): ScenarioPlanView | null {
  if (!view) return null
  const swarm = view.swarm.map((step) => {
    const sec = parseSeconds(step.time)
    if (sec !== null && sec >= 0) {
      return { ...step, time: formatSeconds(sec) }
    }
    return step
  })
  const bees = view.bees.map((bee) => ({
    ...bee,
    steps: bee.steps.map((step) => {
      const sec = parseSeconds(step.time)
      if (sec !== null && sec >= 0) {
        return { ...step, time: formatSeconds(sec) }
      }
      return step
    }),
  }))
  return { swarm, bees }
}

function PlanTimelineLanes({ rows, onTimeChange }: PlanTimelineLanesProps) {
  const [zoomIndex, setZoomIndex] = useState(0)
  const [draggingKey, setDraggingKey] = useState<string | null>(null)
  const dragStateRef = useRef<{
    row: TimelineRow
    startClientX: number
    startSeconds: number
    deltaPx: number
  } | null>(null)
  const [, setDragVersion] = useState(0)

  const maxSeconds = useMemo(() => {
    let max = 0
    for (const row of rows) {
      if (row.seconds != null && row.seconds > max) {
        max = row.seconds
      }
    }
    return max
  }, [rows])

  useEffect(() => {
    if (rows.length === 0) {
      setZoomIndex(0)
      return
    }
    const max = maxSeconds
    if (!Number.isFinite(max) || max <= 0) {
      setZoomIndex(0)
      return
    }
    const targetDivisions = 12
    const idx = ZOOM_LEVELS.findIndex(
      (level) => max / level.secondsPerDivision <= targetDivisions,
    )
    if (idx >= 0) {
      setZoomIndex(idx)
    } else {
      setZoomIndex(ZOOM_LEVELS.length - 1)
    }
  }, [maxSeconds, rows.length])

  const zoom = ZOOM_LEVELS[zoomIndex] ?? ZOOM_LEVELS[0]
  const pxPerSecond = TIMELINE_DIVISION_PX / zoom.secondsPerDivision

  const lanes = useMemo(
    () => {
      const map = new Map<
        string,
        { label: string; rows: TimelineRow[] }
      >()
      for (const row of rows) {
        const laneKey = row.kind === 'swarm' ? 'swarm' : `bee-${row.target}`
        const label = row.kind === 'swarm' ? 'Swarm' : row.target
        const existing = map.get(laneKey)
        if (existing) {
          existing.rows.push(row)
        } else {
          map.set(laneKey, { label, rows: [row] })
        }
      }
      const entries = Array.from(map.entries())
      entries.sort((a, b) => {
        if (a[0] === 'swarm') return -1
        if (b[0] === 'swarm') return 1
        return a[1].label.localeCompare(b[1].label)
      })
      return entries.map(([key, lane]) => ({
        key,
        label: lane.label,
        rows: [...lane.rows].sort((a, b) => {
          const aSec = a.seconds ?? Number.POSITIVE_INFINITY
          const bSec = b.seconds ?? Number.POSITIVE_INFINITY
          if (aSec !== bSec) return aSec - bSec
          return a.stepId.localeCompare(b.stepId)
        }),
      }))
    },
    [rows],
  )

  const totalWidth = useMemo(() => {
    if (!Number.isFinite(maxSeconds) || maxSeconds <= 0) {
      return TIMELINE_DIVISION_PX * 6
    }
    const divisions = maxSeconds / zoom.secondsPerDivision
    const base = (divisions + 2) * TIMELINE_DIVISION_PX
    return Math.max(base, TIMELINE_DIVISION_PX * 6)
  }, [maxSeconds, zoom.secondsPerDivision])

  const handleCardMouseDown = (
    row: TimelineRow,
    event: React.MouseEvent<HTMLDivElement>,
  ) => {
    if (row.seconds == null) return
    event.preventDefault()
    dragStateRef.current = {
      row,
      startClientX: event.clientX,
      startSeconds: row.seconds,
      deltaPx: 0,
    }
    setDraggingKey(row.key)
  }

  useEffect(() => {
    if (!draggingKey) return
    const handleMove = (event: MouseEvent) => {
      const state = dragStateRef.current
      if (!state) return
      const deltaX = event.clientX - state.startClientX
      dragStateRef.current = { ...state, deltaPx: deltaX }
      setDragVersion((v) => v + 1)
    }
    const handleUp = () => {
      const state = dragStateRef.current
      if (state) {
        const deltaSeconds = state.deltaPx / pxPerSecond
        const nextSeconds = Math.max(0, state.startSeconds + deltaSeconds)
        onTimeChange(
          {
            kind: state.row.kind,
            beeIndex: state.row.beeIndex,
            stepIndex: state.row.stepIndex,
          },
          nextSeconds,
        )
      }
      dragStateRef.current = null
      setDraggingKey(null)
    }
    window.addEventListener('mousemove', handleMove)
    window.addEventListener('mouseup', handleUp)
    return () => {
      window.removeEventListener('mousemove', handleMove)
      window.removeEventListener('mouseup', handleUp)
    }
  }, [draggingKey, onTimeChange, pxPerSecond])

  if (rows.length === 0) {
    return null
  }

  const activeDrag = dragStateRef.current

  return (
    <div className="border border-white/10 rounded bg-black/30 p-2">
      <div className="flex items-center justify-between mb-1">
        <div className="text-[11px] font-semibold text-white/80">
          Timeline
        </div>
        <div className="flex items-center gap-2 text-[11px] text-white/70">
          <span className="text-white/60">Scale:</span>
          <select
            className="rounded border border-white/20 bg-black/60 px-1 py-0.5 text-[11px] text-white/80"
            value={zoomIndex}
            onChange={(e) => setZoomIndex(Number(e.target.value) || 0)}
          >
            {ZOOM_LEVELS.map((level, index) => (
              <option key={level.id} value={index}>
                {level.label}
              </option>
            ))}
          </select>
        </div>
      </div>
      <div className="mt-1 overflow-x-auto">
        <div
          className="min-w-full"
          style={{ width: `${totalWidth}px` }}
        >
          <div className="flex items-center gap-2 pl-32 mb-1">
            <div className="relative flex-1 h-6">
              {Array.from(
                { length: Math.ceil(totalWidth / TIMELINE_DIVISION_PX) + 1 },
                (_, index) => {
                  const left = index * TIMELINE_DIVISION_PX
                  const seconds = index * zoom.secondsPerDivision
                  if (left > totalWidth) return null
                  return (
                    <div
                      key={index}
                      className="absolute top-0 h-full border-l border-white/15"
                      style={{ left: `${left}px` }}
                    >
                      <div className="absolute -top-4 -translate-x-1/2 text-[10px] text-white/60">
                        {formatDurationLabel(seconds)}
                      </div>
                    </div>
                  )
                },
              )}
            </div>
          </div>
          <div className="flex flex-col gap-1 py-1">
            {lanes.map((lane) => (
              <div
                key={lane.key}
                className="flex items-center gap-2"
              >
                <div className="w-32 pr-2 text-right text-[11px] text-white/70">
                  {lane.label}
                </div>
                <div className="relative flex-1 h-9 border-l border-white/10">
                  {lane.rows.map((row) => {
                    const secs = row.seconds ?? 0
                    const baseLeft =
                      (secs / zoom.secondsPerDivision) *
                      TIMELINE_DIVISION_PX
                    const offsetPx =
                      activeDrag && activeDrag.row.key === row.key
                        ? activeDrag.deltaPx
                        : 0
                    const left = Math.max(0, baseLeft + offsetPx)
                    return (
                      <div
                        key={row.key}
                        className={`absolute top-0.5 inline-flex cursor-grab items-center gap-1 rounded border border-sky-400/70 bg-sky-500/60 px-2 py-0.5 text-[10px] text-white shadow-sm ${
                          draggingKey === row.key
                            ? 'ring-2 ring-sky-300'
                            : 'hover:bg-sky-500/80'
                        }`}
                        style={{ left: `${left}px` }}
                        onMouseDown={(event) =>
                          handleCardMouseDown(row, event)
                        }
                      >
                        <span className="font-mono">
                          {formatDurationLabel(row.seconds)}
                        </span>
                        <span className="max-w-[140px] truncate">
                          {row.stepId ||
                            row.name ||
                            row.type ||
                            '(step)'}
                        </span>
                      </div>
                    )
                  })}
                </div>
              </div>
            ))}
          </div>
        </div>
      </div>
    </div>
  )
}

export default function ScenariosPage() {
  const [items, setItems] = useState<ScenarioSummary[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [selectedScenario, setSelectedScenario] = useState<ScenarioPayload | null>(null)
  const [planExpanded, setPlanExpanded] = useState(false)
  const [planDraft, setPlanDraft] = useState<ScenarioPlanView | null>(null)
  const [planHistoryState, setPlanHistoryState] = useState<{
    stack: ScenarioPlanView[]
    index: number
  }>({
    stack: [],
    index: -1,
  })
  const [viewMode, setViewMode] = useState<'plan' | 'yaml'>('plan')

  const [rawYaml, setRawYaml] = useState('')
  const [savedYaml, setSavedYaml] = useState('')
  const [rawError, setRawError] = useState<string | null>(null)
  const [rawLoading, setRawLoading] = useState(false)
  const [saving, setSaving] = useState(false)

  const [creatingScenario, setCreatingScenario] = useState(false)
  const [newScenarioId, setNewScenarioId] = useState('')
  const [newScenarioName, setNewScenarioName] = useState('')

  const uploadInputRef = useRef<HTMLInputElement | null>(null)
  const replaceInputRef = useRef<HTMLInputElement | null>(null)

  const setToast = useUIStore((s) => s.setToast)
  const { ensureCapabilities, getManifestForImage } = useCapabilities()

  const [configModalTarget, setConfigModalTarget] = useState<{
    kind: 'swarm' | 'bee'
    beeIndex: number | null
    stepIndex: number
  } | null>(null)
  const [configModalManifest, setConfigModalManifest] = useState<CapabilityManifest | null>(null)
  const [configModalEntries, setConfigModalEntries] = useState<CapabilityConfigEntry[]>([])
  const [configModalForm, setConfigModalForm] = useState<Record<string, ConfigFormValue>>({})
  const [configModalError, setConfigModalError] = useState<string | null>(null)

  const resetPlanHistory = useCallback((initial: ScenarioPlanView | null) => {
    if (!initial) {
      setPlanHistoryState({ stack: [], index: -1 })
      setPlanDraft(null)
    } else {
      setPlanHistoryState({ stack: [initial], index: 0 })
      setPlanDraft(initial)
    }
  }, [])

  const applyPlanUpdate = useCallback(
    (updater: (current: ScenarioPlanView | null) => ScenarioPlanView | null) => {
      setPlanHistoryState((state) => {
        const baseCurrent =
          state.index >= 0 && state.index < state.stack.length
            ? state.stack[state.index]
            : null
        const next = updater(baseCurrent)
        if (!next) {
          setPlanDraft(null)
          return { stack: [], index: -1 }
        }
        const baseStack =
          state.index >= 0 && state.index < state.stack.length
            ? state.stack.slice(0, state.index + 1)
            : state.stack
        const last = baseStack[baseStack.length - 1]
        const lastSerialized = last ? JSON.stringify(last) : null
        const nextSerialized = JSON.stringify(next)
        if (lastSerialized === nextSerialized) {
          setPlanDraft(next)
          return {
            stack: baseStack,
            index: baseStack.length - 1,
          }
        }
        const appended = [...baseStack, next]
        const trimmed =
          appended.length > 50 ? appended.slice(appended.length - 50) : appended
        const index = trimmed.length - 1
        setPlanDraft(next)
        return { stack: trimmed, index }
      })
    },
    [],
  )

  const canUndoPlan = planHistoryState.index > 0
  const canRedoPlan =
    planHistoryState.index >= 0 &&
    planHistoryState.index < planHistoryState.stack.length - 1

  const handleUndoPlan = useCallback(() => {
    setPlanHistoryState((state) => {
      if (state.index <= 0) {
        return state
      }
      const index = state.index - 1
      const plan = state.stack[index]
      setPlanDraft(plan)
      return { ...state, index }
    })
  }, [])

  const handleRedoPlan = useCallback(() => {
    setPlanHistoryState((state) => {
      if (state.index < 0 || state.index >= state.stack.length - 1) {
        return state
      }
      const index = state.index + 1
      const plan = state.stack[index]
      setPlanDraft(plan)
      return { ...state, index }
    })
  }, [])

  useEffect(() => {
    void ensureCapabilities()
  }, [ensureCapabilities])

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
      resetPlanHistory(null)
      setViewMode('plan')
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
          const builtPlan =
            scenario && typeof scenario.plan !== 'undefined'
              ? buildPlanView(scenario.plan)
              : null
          const normalised = normalisePlanTimes(builtPlan)
          const initialPlan =
            normalised ?? {
              swarm: [],
              bees: [],
            }
          resetPlanHistory(initialPlan)
          setViewMode(builtPlan ? 'plan' : 'yaml')
        }
      } catch {
        if (!cancelled) {
          setSelectedScenario(null)
          resetPlanHistory(null)
        }
      }
    }
    void load()
    return () => {
      cancelled = true
    }
  }, [selectedId, normalisePlanTimes, resetPlanHistory])

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

  const handleCreateScenario = async () => {
    const id = newScenarioId.trim()
    const name = newScenarioName.trim() || id
    if (!id) {
      setToast('Scenario ID is required')
      return
    }
    try {
      const created = await createScenario({ id, name })
      setToast(`Created scenario ${created.id}`)
      setCreatingScenario(false)
      setNewScenarioId('')
      setNewScenarioName('')
      await loadScenarios()
      setSelectedId(created.id)
    } catch (e) {
      setToast(
        e instanceof Error ? `Create failed: ${e.message}` : 'Failed to create scenario',
      )
    }
  }

  const handleSave = async () => {
    if (!selectedId) return
    if (rawYaml === savedYaml) {
      return
    }
    setSaving(true)
    setRawError(null)
    try {
      await saveScenarioRaw(selectedId, rawYaml)
      await loadScenarios()
      setSavedYaml(rawYaml)
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

  const roleOptions = useMemo(() => {
    if (!selectedScenario?.templateRoles || selectedScenario.templateRoles.length === 0) {
      return [] as string[]
    }
    return Array.from(new Set(selectedScenario.templateRoles)).sort()
  }, [selectedScenario])

  const getStepAtTarget = useCallback(
    (
      target: { kind: 'swarm' | 'bee'; beeIndex: number | null; stepIndex: number },
    ): ScenarioPlanStep | null => {
      if (!planDraft) return null
      if (target.kind === 'swarm') {
        return planDraft.swarm[target.stepIndex] ?? null
      }
      if (target.kind === 'bee' && target.beeIndex !== null) {
        const bee = planDraft.bees[target.beeIndex]
        if (!bee) return null
        return bee.steps[target.stepIndex] ?? null
      }
      return null
    },
    [planDraft],
  )

  const resolveBeeImage = useCallback(
    (beeIndex: number): string | null => {
      if (!selectedScenario?.template || !planDraft) return null
      const tpl = selectedScenario.template
      const beePlan = planDraft.bees[beeIndex]
      if (!beePlan) return null
      const tplBees = tpl.bees ?? []
      if (beePlan.instanceId) {
        const matches = tplBees.filter(
          (b) => b.instanceId && b.instanceId === beePlan.instanceId,
        )
        if (matches.length === 1 && matches[0]?.image) {
          return matches[0].image ?? null
        }
        return null
      }
      if (beePlan.role) {
        const matches = tplBees.filter(
          (b) => b.role && b.role === beePlan.role,
        )
        if (matches.length === 1 && matches[0]?.image) {
          return matches[0].image ?? null
        }
      }
      return null
    },
    [planDraft, selectedScenario],
  )

  const getValueForPath = useCallback(
    (config: Record<string, unknown> | undefined, path: string): unknown => {
      if (!config || !path) return undefined
      const segments = path.split('.').filter((segment) => segment.length > 0)
      let current: unknown = config
      for (const segment of segments) {
        if (typeof current !== 'object' || current === null || Array.isArray(current)) {
          return undefined
        }
        current = (current as Record<string, unknown>)[segment]
      }
      return current
    },
    [],
  )

  const formatValueForInput = useCallback(
    (entry: CapabilityConfigEntry, value: unknown): ConfigFormValue => {
      const normalizedType = (entry.type || '').toLowerCase()
      if (normalizedType === 'boolean' || normalizedType === 'bool') {
        if (typeof value === 'boolean') return value
        if (typeof value === 'number') return value !== 0
        if (typeof value === 'string') {
          const normalized = value.trim().toLowerCase()
          if (normalized === 'true') return true
          if (normalized === 'false') return false
        }
        return false
      }
      if (normalizedType === 'json') {
        if (value === undefined || value === null) return ''
        if (typeof value === 'string') {
          const trimmed = value.trim()
          if (!trimmed) return ''
          try {
            return JSON.stringify(JSON.parse(trimmed), null, 2)
          } catch {
            return value
          }
        }
        try {
          return JSON.stringify(value, null, 2)
        } catch {
          return ''
        }
      }
      if (normalizedType === 'number' || normalizedType === 'int' || normalizedType === 'integer') {
        if (typeof value === 'number') return Number.isFinite(value) ? String(value) : ''
        if (typeof value === 'string') return value
        return ''
      }
      if (value === null || value === undefined) return ''
      if (typeof value === 'string') return value
      if (typeof value === 'number' || typeof value === 'boolean') return String(value)
      try {
        return JSON.stringify(value, null, 2)
      } catch {
        return ''
      }
    },
    [],
  )

  const computeInitialConfigValue = useCallback(
    (entry: CapabilityConfigEntry, config: Record<string, unknown> | undefined): ConfigFormValue => {
      const existing = getValueForPath(config, entry.name)
      const source = existing !== undefined ? existing : entry.default
      return formatValueForInput(entry, source)
    },
    [formatValueForInput, getValueForPath],
  )

  const convertConfigFormValue = useCallback(
    (entry: CapabilityConfigEntry, rawValue: ConfigFormValue): { ok: true; apply: boolean; value: unknown } | { ok: false; message: string } => {
      const normalizedType = (entry.type || '').toLowerCase()
      if (normalizedType === 'boolean' || normalizedType === 'bool') {
        return { ok: true, apply: true, value: rawValue === true }
      }
      if (normalizedType === 'json') {
        const str = typeof rawValue === 'string' ? rawValue.trim() : ''
        if (!str) {
          return { ok: true, apply: false, value: undefined }
        }
        try {
          return { ok: true, apply: true, value: JSON.parse(str) }
        } catch {
          return { ok: false, message: `Invalid JSON for ${entry.name}` }
        }
      }
      if (normalizedType === 'number' || normalizedType === 'int' || normalizedType === 'integer') {
        const str = typeof rawValue === 'string' ? rawValue.trim() : ''
        if (!str) {
          return { ok: true, apply: false, value: undefined }
        }
        const num = Number(str)
        if (Number.isNaN(num)) {
          return { ok: false, message: `${entry.name} must be a number` }
        }
        return { ok: true, apply: true, value: num }
      }
      if (typeof rawValue === 'string') {
        const trimmed = rawValue.trim()
        if (!trimmed) {
          return { ok: true, apply: false, value: undefined }
        }
        return { ok: true, apply: true, value: rawValue }
      }
      return { ok: true, apply: false, value: undefined }
    },
    [],
  )

  const assignNestedValue = useCallback(
    (target: Record<string, unknown>, path: string, value: unknown) => {
      const segments = path.split('.').filter((segment) => segment.length > 0)
      if (segments.length === 0) return
      let cursor: Record<string, unknown> = target
      for (let i = 0; i < segments.length - 1; i += 1) {
        const key = segments[i]!
        const next = cursor[key]
        if (typeof next !== 'object' || next === null || Array.isArray(next)) {
          const created: Record<string, unknown> = {}
          cursor[key] = created
          cursor = created
        } else {
          cursor = next as Record<string, unknown>
        }
      }
      cursor[segments[segments.length - 1]!] = value
    },
    [],
  )

  const openConfigModal = useCallback(
    async (target: { kind: 'swarm' | 'bee'; beeIndex: number | null; stepIndex: number }) => {
      if (!planDraft || !selectedScenario) {
        return
      }
      let image: string | null = null
      if (target.kind === 'swarm') {
        image = selectedScenario.template?.image ?? null
      } else if (target.kind === 'bee' && target.beeIndex !== null) {
        image = resolveBeeImage(target.beeIndex)
      }
      if (!image) {
        setToast('No image mapping for this step; capabilities unavailable')
        return
      }
      try {
        await ensureCapabilities()
      } catch {
        // ignore; manifest lookup will handle missing data
      }
      const manifest = getManifestForImage(image)
      if (!manifest) {
        setToast('Capability manifest not available for this component')
        return
      }
      const entries = Array.isArray(manifest.config) ? manifest.config : []
      if (entries.length === 0) {
        setToast('No configurable options defined for this component')
        return
      }
      const step = getStepAtTarget(target)
      const existingConfig =
        step && step.config && typeof step.config === 'object'
          ? (step.config as Record<string, unknown>)
          : undefined
      const form: Record<string, ConfigFormValue> = {}
      entries.forEach((entry) => {
        form[entry.name] = computeInitialConfigValue(entry, existingConfig)
      })
      setConfigModalManifest(manifest)
      setConfigModalEntries(entries)
      setConfigModalForm(form)
      setConfigModalError(null)
      setConfigModalTarget(target)
    },
    [
      planDraft,
      selectedScenario,
      resolveBeeImage,
      ensureCapabilities,
      getManifestForImage,
      getStepAtTarget,
      computeInitialConfigValue,
      setToast,
    ],
  )

  const applyConfigModal = useCallback(() => {
    if (!configModalTarget || !configModalEntries.length) {
      setConfigModalTarget(null)
      setConfigModalManifest(null)
      setConfigModalEntries([])
      setConfigModalForm({})
      setConfigModalError(null)
      return
    }
    const patch: Record<string, unknown> = {}
    for (const entry of configModalEntries) {
      const raw = configModalForm[entry.name]
      const result = convertConfigFormValue(entry, raw)
      if (!result.ok) {
        setConfigModalError(result.message)
        return
      }
      if (result.apply) {
        assignNestedValue(patch, entry.name, result.value)
      }
    }
    const target = configModalTarget
    const hasConfig = Object.keys(patch).length > 0
    applyPlanUpdate((current) => {
      if (!current) return current
      if (target.kind === 'swarm') {
        const swarm = current.swarm.map((step, idx) =>
          idx === target.stepIndex ? { ...step, config: hasConfig ? patch : undefined } : step,
        )
        return { ...current, swarm }
      }
      if (target.kind === 'bee' && target.beeIndex !== null) {
        const bees = current.bees.map((bee, idx) => {
          if (idx !== target.beeIndex) return bee
          const steps = bee.steps.map((step, sIdx) =>
            sIdx === target.stepIndex ? { ...step, config: hasConfig ? patch : undefined } : step,
          )
          return { ...bee, steps }
        })
        return { ...current, bees }
      }
      return current
    })
    setConfigModalTarget(null)
    setConfigModalManifest(null)
    setConfigModalEntries([])
    setConfigModalForm({})
    setConfigModalError(null)
  }, [
    applyPlanUpdate,
    assignNestedValue,
    configModalEntries,
    configModalForm,
    configModalTarget,
    convertConfigFormValue,
  ])

  const timelineRows = useMemo(() => {
    if (!planDraft) return []
    const rows: TimelineRow[] = []

    planDraft.swarm.forEach((step, idx) => {
      const seconds = parseSeconds(step.time)
      rows.push({
        key: `swarm-${idx}`,
        kind: 'swarm',
        beeIndex: null,
        stepIndex: idx,
        seconds,
        original: step.time ?? '',
        target: 'swarm',
        stepId: step.stepId ?? String(idx + 1),
        name: step.name ?? null,
        type: step.type ?? null,
      })
    })

    planDraft.bees.forEach((bee, beeIndex) => {
      const label =
        bee.instanceId || bee.role
          ? `${bee.instanceId ?? ''}${bee.role ? ` (${bee.role})` : ''}`
          : `bee-${beeIndex + 1}`
      bee.steps.forEach((step, idx) => {
        const seconds = parseSeconds(step.time)
        rows.push({
          key: `bee-${beeIndex}-${idx}`,
          kind: 'bee',
          beeIndex,
          stepIndex: idx,
          seconds,
          original: step.time ?? '',
          target: label,
          stepId: step.stepId ?? String(idx + 1),
          name: step.name ?? null,
          type: step.type ?? null,
        })
      })
    })

    return rows.sort((a, b) => {
      const aSec = a.seconds ?? Number.POSITIVE_INFINITY
      const bSec = b.seconds ?? Number.POSITIVE_INFINITY
      if (aSec !== bSec) return aSec - bSec
      return a.target.localeCompare(b.target) || a.stepId.localeCompare(b.stepId)
    })
  }, [planDraft])

  const updateTimelineTime = useCallback(
    (row: TimelineRowRef, seconds: number) => {
      const clamped = Math.max(0, Math.round(seconds))
      applyPlanUpdate((current) => {
        if (!current) return current
        const formatted = formatSeconds(clamped)
        if (row.kind === 'swarm') {
          if (!current.swarm[row.stepIndex]) return current
          const updated = current.swarm.map((step, idx) =>
            idx === row.stepIndex ? { ...step, time: formatted } : step,
          )
          const sorted = [...updated].sort((a, b) => {
            const aSec = parseSeconds(a.time) ?? Number.POSITIVE_INFINITY
            const bSec = parseSeconds(b.time) ?? Number.POSITIVE_INFINITY
            if (aSec !== bSec) return aSec - bSec
            const aId = a.stepId ?? ''
            const bId = b.stepId ?? ''
            return aId.localeCompare(bId)
          })
          return { ...current, swarm: sorted }
        }
        if (
          row.kind === 'bee' &&
          row.beeIndex !== null &&
          current.bees[row.beeIndex]
        ) {
          const bees = current.bees.map((bee, idx) => {
            if (idx !== row.beeIndex) return bee
            const updatedSteps = bee.steps.map((step, sIdx) =>
              sIdx === row.stepIndex ? { ...step, time: formatted } : step,
            )
            const sortedSteps = [...updatedSteps].sort((a, b) => {
              const aSec = parseSeconds(a.time) ?? Number.POSITIVE_INFINITY
              const bSec = parseSeconds(b.time) ?? Number.POSITIVE_INFINITY
              if (aSec !== bSec) return aSec - bSec
              const aId = a.stepId ?? ''
              const bId = b.stepId ?? ''
              return aId.localeCompare(bId)
            })
            return { ...bee, steps: sortedSteps }
          })
            return { ...current, bees }
          }
          return current
      })
    },
    [applyPlanUpdate],
  )

  const updateSwarmStep = useCallback(
    (index: number, patch: Partial<ScenarioPlanView['swarm'][number]>) => {
      applyPlanUpdate((current) => {
        if (!current) return current
        const swarm = current.swarm.map((step, i) =>
          i === index ? { ...step, ...patch } : step,
        )
        return { ...current, swarm }
      })
    },
    [applyPlanUpdate],
  )

  const removeSwarmStep = useCallback((index: number) => {
    applyPlanUpdate((current) => {
      if (!current) return current
      return { ...current, swarm: current.swarm.filter((_, i) => i !== index) }
    })
  }, [applyPlanUpdate])

  const updateBee = useCallback(
    (index: number, patch: { instanceId?: string | null; role?: string | null }) => {
      applyPlanUpdate((current) => {
        if (!current) return current
        const bees = current.bees.map((bee, i) =>
          i === index ? { ...bee, ...patch } : bee,
        )
        return { ...current, bees }
      })
    },
    [applyPlanUpdate],
  )

  const addBeeStep = useCallback((beeIndex: number) => {
    applyPlanUpdate((current) => {
      if (!current) {
        return {
          swarm: [],
          bees: [
            {
              instanceId: 'bee-1',
              role: null,
              steps: [
                {
                  stepId: null,
                  name: null,
                  time: '1s',
                  type: 'config-update',
                },
              ],
            },
          ],
        }
      }
      const bees = current.bees.map((bee, i) =>
        i === beeIndex
          ? {
              ...bee,
              steps: [
                ...bee.steps,
                {
                  stepId: null,
                  name: null,
                  time: '1s',
                  type: 'config-update',
                },
              ],
            }
          : bee,
      )
      return { ...current, bees }
    })
  }, [applyPlanUpdate])

  const updateBeeStep = useCallback(
    (
      beeIndex: number,
      stepIndex: number,
      patch: Partial<ScenarioPlanView['swarm'][number]>,
    ) => {
      applyPlanUpdate((current) => {
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
    [applyPlanUpdate],
  )

  const removeBeeStep = useCallback((beeIndex: number, stepIndex: number) => {
    applyPlanUpdate((current) => {
      if (!current) return current
      const bees = current.bees.map((bee, i) => {
        if (i !== beeIndex) return bee
        return { ...bee, steps: bee.steps.filter((_, j) => j !== stepIndex) }
      })
      return { ...current, bees }
    })
  }, [applyPlanUpdate])

  const removeBee = useCallback((index: number) => {
    applyPlanUpdate((current) => {
      if (!current) return current
      return { ...current, bees: current.bees.filter((_, i) => i !== index) }
    })
  }, [applyPlanUpdate])

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
          <button
            type="button"
            className="rounded border border-white/15 bg-white/10 px-2 py-1 text-[11px] text-white/80 hover:bg-white/20"
            onClick={() => {
              setCreatingScenario(true)
              setNewScenarioId('')
              setNewScenarioName('')
            }}
          >
            New scenario
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
        {creatingScenario && (
          <div className="space-y-1 rounded border border-white/20 bg-white/5 px-2 py-2 text-[11px] text-white/80">
            <div className="font-semibold text-white/90 mb-1">New scenario</div>
            <input
              className="w-full rounded border border-white/20 bg-black/40 px-2 py-1 text-[11px] text-white/90"
              placeholder="scenario-id"
              value={newScenarioId}
              onChange={(event) => setNewScenarioId(event.target.value)}
            />
            <input
              className="w-full rounded border border-white/20 bg-black/40 px-2 py-1 text-[11px] text-white/90"
              placeholder="Name (optional)"
              value={newScenarioName}
              onChange={(event) => setNewScenarioName(event.target.value)}
            />
            <div className="flex items-center justify-end gap-2 pt-1">
              <button
                type="button"
                className="rounded px-2 py-1 text-[11px] text-white/70 hover:bg-white/10"
                onClick={() => {
                  setCreatingScenario(false)
                  setNewScenarioId('')
                  setNewScenarioName('')
                }}
              >
                Cancel
              </button>
              <button
                type="button"
                className="rounded bg-sky-500/80 px-2 py-1 text-[11px] text-white hover:bg-sky-500"
                onClick={() => void handleCreateScenario()}
              >
                Create
              </button>
            </div>
          </div>
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
              </div>
            </div>
            <div className="flex items-center gap-2 border-b border-white/10 pb-2 mb-3">
              <button
                type="button"
                className={`rounded px-2 py-1 text-[11px] ${
                  viewMode === 'plan'
                    ? 'bg-white/20 text-white'
                    : 'bg-white/5 text-white/70 hover:bg-white/10'
                }`}
                onClick={() => setViewMode('plan')}
              >
                Plan editor
              </button>
              <button
                type="button"
                className={`rounded px-2 py-1 text-[11px] ${
                  viewMode === 'yaml'
                    ? 'bg-white/20 text-white'
                    : 'bg-white/5 text-white/70 hover:bg-white/10'
                }`}
                onClick={() => setViewMode('yaml')}
              >
                Scenario YAML
              </button>
            </div>
            {viewMode === 'plan' && planDraft && (
              <div className="border border-white/15 rounded-md p-3 bg-white/5 space-y-3">
                <div className="flex items-center justify-between">
                  <h3 className="text-xs font-semibold text-white/80">
                    Plan overview
                  </h3>
                  <div className="flex items-center gap-2">
                    <button
                    type="button"
                    className="rounded bg-white/10 px-2 py-1 text-[11px] text-white/80 hover:bg-white/20"
                    onClick={() =>
                      applyPlanUpdate((current) => {
                        const base =
                          current ??
                          ({
                            swarm: [],
                            bees: [],
                          } as ScenarioPlanView)
                        return {
                          swarm: base.swarm,
                          bees: [
                            ...base.bees,
                            { instanceId: 'bee-1', role: null, steps: [] },
                          ],
                        }
                      })
                    }
                  >
                    Add bee timeline
                    </button>
                    <button
                    type="button"
                    className="rounded bg-white/10 px-2 py-1 text-[11px] text-white/80 hover:bg-white/20"
                    onClick={() =>
                      applyPlanUpdate((current) => {
                        const base =
                          current ??
                          ({
                            swarm: [],
                            bees: [],
                          } as ScenarioPlanView)
                        return {
                          swarm: [
                            ...base.swarm,
                            {
                              stepId: 'swarm-step',
                              name: null,
                              time: '1s',
                              type: 'config-update',
                            },
                          ],
                          bees: base.bees,
                        }
                      })
                    }
                  >
                    Add swarm step
                    </button>
                    <button
                    type="button"
                    className="rounded bg-white/10 px-2 py-1 text-[11px] text-white/80 hover:bg-white/20 disabled:opacity-40"
                    disabled={!canUndoPlan}
                    onClick={handleUndoPlan}
                  >
                    Undo
                    </button>
                    <button
                    type="button"
                    className="rounded bg-white/10 px-2 py-1 text-[11px] text-white/80 hover:bg-white/20 disabled:opacity-40"
                    disabled={!canRedoPlan}
                    onClick={handleRedoPlan}
                  >
                    Redo
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
                {timelineRows.length > 0 && (
                  <PlanTimelineLanes
                    rows={timelineRows}
                    onTimeChange={updateTimelineTime}
                  />
                )}
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
                              className="w-24 rounded border border-white/15 bg-black/40 px-1 py-0.5 text-[11px] text-white/90 font-mono"
                              value={step.time ?? ''}
                              placeholder="time"
                              onChange={(e) =>
                                updateSwarmStep(idx, {
                                  time: e.target.value.trim() || null,
                                })
                              }
                              onBlur={(e) => {
                                const text = e.target.value.trim()
                                if (!text) {
                                  updateSwarmStep(idx, { time: null })
                                  return
                                }
                                const seconds = parseSeconds(text)
                                if (seconds === null || seconds < 0) {
                                  setToast(
                                    'Invalid time; use 5s, 2m, 1h or PT5S',
                                  )
                                  return
                                }
                                updateTimelineTime(
                                  {
                                    kind: 'swarm',
                                    beeIndex: null,
                                    stepIndex: idx,
                                  },
                                  seconds,
                                )
                              }}
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
                            <select
                              className="w-32 rounded border border-white/15 bg-black/40 px-1 py-1 text-[11px] text-white/90"
                              value={step.type ?? ''}
                              onChange={(e) =>
                                updateSwarmStep(idx, {
                                  type: e.target.value.trim() || null,
                                })
                              }
                            >
                              <option value="">type…</option>
                              <option value="config-update">config-update</option>
                              <option value="start">start</option>
                              <option value="stop">stop</option>
                            </select>
                            {step.type === 'config-update' && (
                              <button
                                type="button"
                                className="text-[11px] text-sky-300 hover:text-sky-200"
                                onClick={() =>
                                  void openConfigModal({
                                    kind: 'swarm',
                                    beeIndex: null,
                                    stepIndex: idx,
                                  })
                                }
                              >
                                Edit config
                              </button>
                            )}
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
                              <select
                                className="w-32 rounded border border-white/15 bg-black/40 px-1 py-0.5 text-[11px] text-white/90"
                                value={bee.role ?? ''}
                                onChange={(e) =>
                                  updateBee(index, {
                                    role: e.target.value.trim() || null,
                                  })
                                }
                              >
                                <option value="">role…</option>
                                {roleOptions.map((role) => (
                                  <option key={role} value={role}>
                                    {role}
                                  </option>
                                ))}
                              </select>
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
                                    className="w-24 rounded border border-white/15 bg-black/40 px-1 py-0.5 text-[11px] text-white/90 font-mono"
                                    value={step.time ?? ''}
                                    placeholder="time"
                                    onChange={(e) =>
                                      updateBeeStep(index, idx, {
                                        time: e.target.value.trim() || null,
                                      })
                                    }
                                    onBlur={(e) => {
                                      const text = e.target.value.trim()
                                      if (!text) {
                                        updateBeeStep(index, idx, { time: null })
                                        return
                                      }
                                      const seconds = parseSeconds(text)
                                      if (seconds === null || seconds < 0) {
                                        setToast(
                                          'Invalid time; use 5s, 2m, 1h or PT5S',
                                        )
                                        return
                                      }
                                      updateTimelineTime(
                                        {
                                          kind: 'bee',
                                          beeIndex: index,
                                          stepIndex: idx,
                                        },
                                        seconds,
                                      )
                                    }}
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
                                  <select
                                    className="w-32 rounded border border-white/15 bg-black/40 px-1 py-1 text-[11px] text-white/90"
                                    value={step.type ?? ''}
                                    onChange={(e) =>
                                      updateBeeStep(index, idx, {
                                        type: e.target.value.trim() || null,
                                      })
                                    }
                                  >
                                    <option value="">type…</option>
                                    <option value="config-update">config-update</option>
                                    <option value="start">start</option>
                                    <option value="stop">stop</option>
                                  </select>
                                  {step.type === 'config-update' && (
                                    <button
                                      type="button"
                                      className="text-[11px] text-sky-300 hover:text-sky-200"
                                      onClick={() =>
                                        void openConfigModal({
                                          kind: 'bee',
                                          beeIndex: index,
                                          stepIndex: idx,
                                        })
                                      }
                                    >
                                      Edit config
                                    </button>
                                  )}
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
            {viewMode === 'yaml' && (
              <div className="flex flex-col gap-3 h-[60vh]">
                <div className="flex items-center justify-between">
                  <h3 className="text-xs font-semibold text-white/80">
                    Scenario YAML{' '}
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
          </div>
        )}
      </div>
      {configModalTarget && configModalManifest && (
        <div className="fixed inset-0 z-40 flex items-center justify-center bg-black/70">
          <div
            role="dialog"
            aria-modal="true"
            className="w-full max-w-lg rounded-lg bg-[#05070b] border border-white/20 p-4 text-sm text-white"
          >
            <div className="flex items-center justify-between mb-2">
              <h3 className="text-xs font-semibold text-white/80">
                Edit config-update patch
              </h3>
              <button
                type="button"
                className="text-white/60 hover:text-white"
                onClick={() => {
                  setConfigModalTarget(null)
                  setConfigModalManifest(null)
                  setConfigModalEntries([])
                  setConfigModalForm({})
                  setConfigModalError(null)
                }}
              >
                ×
              </button>
            </div>
            <div className="mb-2 text-[11px] text-white/60">
              Image:{' '}
              <span className="font-mono text-white/80">
                {configModalManifest.image?.name ?? '(unknown)'}
                {configModalManifest.image?.tag ? `:${configModalManifest.image.tag}` : ''}
              </span>
            </div>
            <div className="max-h-64 overflow-y-auto space-y-2 mb-3">
              {configModalEntries.map((entry) => {
                const unit =
                  entry.ui && typeof entry.ui === 'object'
                    ? (() => {
                        const value = (entry.ui as Record<string, unknown>).unit
                        return typeof value === 'string' && value.trim().length > 0
                          ? value.trim()
                          : null
                      })()
                    : null
                const labelSuffix = `${entry.type || 'string'}${unit ? ` • ${unit}` : ''}`
                const rawValue = configModalForm[entry.name]
                const normalizedType = (entry.type || '').toLowerCase()
                const options = Array.isArray(entry.options) ? entry.options : undefined
                let field: React.ReactElement
                if (options && options.length > 0) {
                  const value =
                    typeof rawValue === 'string'
                      ? rawValue
                      : (entry.default as string | undefined) ?? ''
                  field = (
                    <select
                      className="w-full rounded bg-white/10 px-2 py-1 text-white text-xs"
                      value={value}
                      onChange={(event) =>
                        setConfigModalForm((prev) => ({
                          ...prev,
                          [entry.name]: event.target.value,
                        }))
                      }
                    >
                      {options.map((option, index) => {
                        const label =
                          option === null || option === undefined
                            ? ''
                            : typeof option === 'string'
                              ? option
                              : JSON.stringify(option)
                        return (
                          <option key={index} value={label}>
                            {label}
                          </option>
                        )
                      })}
                    </select>
                  )
                } else if (normalizedType === 'boolean' || normalizedType === 'bool') {
                  const checked = rawValue === true
                  field = (
                    <label className="inline-flex items-center gap-2 text-xs text-white/70">
                      <input
                        type="checkbox"
                        className="h-4 w-4 accent-blue-500"
                        checked={checked}
                        onChange={(event) =>
                          setConfigModalForm((prev) => ({
                            ...prev,
                            [entry.name]: event.target.checked,
                          }))
                        }
                      />
                      <span>Enabled</span>
                    </label>
                  )
                } else if (
                  entry.multiline ||
                  normalizedType === 'text' ||
                  normalizedType === 'json'
                ) {
                  const value = typeof rawValue === 'string' ? rawValue : ''
                  field = (
                    <textarea
                      className="w-full rounded bg-white/10 px-2 py-1 text-white text-xs"
                      rows={normalizedType === 'json' ? 4 : 3}
                      value={value}
                      onChange={(event) =>
                        setConfigModalForm((prev) => ({
                          ...prev,
                          [entry.name]: event.target.value,
                        }))
                      }
                    />
                  )
                } else {
                  const value = typeof rawValue === 'string' ? rawValue : ''
                  field = (
                    <input
                      className="w-full rounded bg-white/10 px-2 py-1 text-white text-xs"
                      type={
                        normalizedType === 'number' ||
                        normalizedType === 'int' ||
                        normalizedType === 'integer'
                          ? 'number'
                          : 'text'
                      }
                      value={value}
                      onChange={(event) =>
                        setConfigModalForm((prev) => ({
                          ...prev,
                          [entry.name]: event.target.value,
                        }))
                      }
                    />
                  )
                }
                return (
                  <label key={entry.name} className="block space-y-1 text-xs">
                    <span className="block text-white/70">
                      {entry.name}
                      <span className="text-white/40"> ({labelSuffix})</span>
                    </span>
                    {field}
                  </label>
                )
              })}
            </div>
            {configModalError && (
              <div className="mb-2 text-[11px] text-red-400">{configModalError}</div>
            )}
            <div className="flex items-center justify-end gap-2">
              <button
                type="button"
                className="rounded px-2 py-1 text-[11px] text-white/70 hover:bg-white/10"
                onClick={() => {
                  setConfigModalTarget(null)
                  setConfigModalManifest(null)
                  setConfigModalEntries([])
                  setConfigModalForm({})
                  setConfigModalError(null)
                }}
              >
                Cancel
              </button>
              <button
                type="button"
                className="rounded bg-sky-500/80 px-3 py-1 text-[11px] text-white hover:bg-sky-500"
                onClick={() => applyConfigModal()}
              >
                Apply
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
