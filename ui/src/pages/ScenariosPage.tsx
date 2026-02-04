import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type * as React from 'react'
import type { editor as MonacoEditor } from 'monaco-editor'
import Editor from '@monaco-editor/react'
import { useNavigate } from 'react-router-dom'
import YAML from 'yaml'
import ScenarioVariablesModal from './scenarios/ScenarioVariablesModal'
import ScenarioSutsModal from './scenarios/ScenarioSutsModal'
import {
  listScenarios,
  downloadScenarioBundle,
  uploadScenarioBundle,
  replaceScenarioBundle,
  fetchScenarioRaw,
  saveScenarioRaw,
  getScenario,
  buildPlanView,
  mergePlan,
  createScenario,
  saveScenarioSchema,
  listScenarioSchemas,
  fetchScenarioSchema,
  listHttpTemplates,
  fetchHttpTemplate,
  saveHttpTemplate,
  renameHttpTemplate,
  deleteHttpTemplate,
  type ScenarioPayload,
  type ScenarioPlanView,
  type ScenarioPlanStep,
} from '../lib/scenarioManagerApi'
import type { ScenarioSummary } from '../types/scenarios'
import type { CapabilityConfigEntry, CapabilityManifest } from '../types/capabilities'
import { useUIStore } from '../store'
import { useCapabilities } from '../contexts/CapabilitiesContext'
import { ConfigUpdatePatchModal } from '../components/ConfigUpdatePatchModal'

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

type TemplateNode = Record<string, unknown> | null
type TopologyNode = Record<string, unknown> | null

const TIMELINE_DIVISION_PX = 80

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function readPortMapEntry(value: unknown, portId: string): string | null {
  if (!isPlainObject(value)) return null
  const raw = value[portId]
  if (typeof raw !== 'string') return null
  const trimmed = raw.trim()
  return trimmed.length > 0 ? trimmed : null
}

function updatePortMapEntry(
  work: Record<string, unknown>,
  direction: 'in' | 'out',
  portId: string,
  rawValue: string,
) {
  const section = isPlainObject(work[direction])
    ? { ...(work[direction] as Record<string, unknown>) }
    : {}
  const trimmed = rawValue.trim()
  if (trimmed) {
    section[portId] = trimmed
  } else {
    delete section[portId]
  }
  if (Object.keys(section).length > 0) {
    work[direction] = section
  } else {
    delete work[direction]
  }
}

function mergeConfig(
  base: Record<string, unknown> | undefined,
  patch: Record<string, unknown> | undefined,
): Record<string, unknown> | undefined {
  if (!patch || !isPlainObject(patch)) {
    return base && Object.keys(base).length > 0 ? { ...base } : undefined
  }
  const target: Record<string, unknown> = base && isPlainObject(base) ? { ...base } : {}
  for (const [key, value] of Object.entries(patch)) {
    const existing = target[key]
    if (isPlainObject(value) && isPlainObject(existing)) {
      target[key] = mergeConfig(existing, value) as Record<string, unknown>
    } else {
      target[key] = value
    }
  }
  return Object.keys(target).length > 0 ? target : undefined
}

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
                    const baseSeconds = row.seconds ?? 0
                    const virtualSeconds =
                      activeDrag && activeDrag.row.key === row.key
                        ? Math.max(0, baseSeconds + activeDrag.deltaPx / pxPerSecond)
                        : row.seconds
                    const secondsForPosition = virtualSeconds ?? baseSeconds
                    const left = Math.max(
                      0,
                      (secondsForPosition / zoom.secondsPerDivision) *
                        TIMELINE_DIVISION_PX,
                    )
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
                          {formatDurationLabel(virtualSeconds ?? row.seconds)}
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

interface SwarmTemplateEditorProps {
  template: TemplateNode
  onChange: (updater: (current: TemplateNode) => TemplateNode) => void
  onOpenConfig: (beeIndex: number) => void
  onOpenSchemaEditor?: (beeIndex: number) => void
}

function SwarmTemplateEditor({
  template,
  onChange,
  onOpenConfig,
  onOpenSchemaEditor,
}: SwarmTemplateEditorProps) {
  const [selectedBeeIndex, setSelectedBeeIndex] = useState(0)
  const [interceptorsEditorBeeIndex, setInterceptorsEditorBeeIndex] = useState<number | null>(null)
  const [interceptorsEditorText, setInterceptorsEditorText] = useState('')
  const [interceptorsEditorError, setInterceptorsEditorError] = useState<string | null>(null)
  const { getManifestForImage } = useCapabilities()

  const controllerImage =
    template && typeof template.image === 'string' ? (template.image as string) : ''

  const bees = useMemo(() => {
    if (!template || typeof template !== 'object' || Array.isArray(template)) {
      return []
    }
    const record = template as Record<string, unknown>
    const beesRaw = Array.isArray(record['bees']) ? (record['bees'] as unknown[]) : []
    return beesRaw
      .map((entry, index) => {
        if (!entry || typeof entry !== 'object' || Array.isArray(entry)) {
          return null
        }
        const bee = entry as Record<string, unknown>
        const beeId = typeof bee['id'] === 'string' ? (bee['id'] as string) : ''
        const role = typeof bee['role'] === 'string' ? (bee['role'] as string) : ''
        const instanceId =
          typeof bee['instanceId'] === 'string' ? (bee['instanceId'] as string) : ''
        const image = typeof bee['image'] === 'string' ? (bee['image'] as string) : ''
        let workIn: string | null = null
        let workOut: string | null = null
        const work = bee['work']
        if (isPlainObject(work)) {
          const workRec = work as Record<string, unknown>
          workIn = readPortMapEntry(workRec['in'], 'in')
          workOut = readPortMapEntry(workRec['out'], 'out')
        }
        const portsRaw = Array.isArray(bee['ports']) ? (bee['ports'] as unknown[]) : []
        const ports: { id: string; direction: string }[] = []
        for (const port of portsRaw) {
          if (!isPlainObject(port)) continue
          const portRec = port as Record<string, unknown>
          const id = typeof portRec['id'] === 'string' ? (portRec['id'] as string) : ''
          const direction =
            typeof portRec['direction'] === 'string' ? (portRec['direction'] as string) : ''
          if (id || direction) {
            ports.push({ id, direction })
          }
        }
        let inputType: string | null = null
        let hasSchedulerConfig = false
        let hasRedisConfig = false
        let schemaRef: string | null = null
        const config = bee['config']
        if (config && typeof config === 'object' && !Array.isArray(config)) {
          const cfg = config as Record<string, unknown>
          const inputsRaw = cfg['inputs']
          if (inputsRaw && typeof inputsRaw === 'object' && !Array.isArray(inputsRaw)) {
            const inputs = inputsRaw as Record<string, unknown>
            const typeVal = inputs['type']
            if (typeof typeVal === 'string') {
              inputType = typeVal
            }
            const schedulerRaw = inputs['scheduler']
            if (
              schedulerRaw &&
              typeof schedulerRaw === 'object' &&
              !Array.isArray(schedulerRaw) &&
              Object.keys(schedulerRaw as Record<string, unknown>).length > 0
            ) {
              hasSchedulerConfig = true
            }
            const redisRaw = inputs['redis']
            if (
              redisRaw &&
              typeof redisRaw === 'object' &&
              !Array.isArray(redisRaw) &&
              Object.keys(redisRaw as Record<string, unknown>).length > 0
            ) {
              hasRedisConfig = true
            }
          }
          const workerRaw = cfg['worker']
          if (workerRaw && typeof workerRaw === 'object' && !Array.isArray(workerRaw)) {
            const workerCfg = workerRaw as Record<string, unknown>
            const messageRaw = workerCfg['message']
            if (messageRaw && typeof messageRaw === 'object' && !Array.isArray(messageRaw)) {
              const msgCfg = messageRaw as Record<string, unknown>
              const refValue = msgCfg['schemaRef']
              if (typeof refValue === 'string' && refValue.trim().length > 0) {
                schemaRef = refValue.trim()
              }
            }
          }
        }
        return {
          index,
          beeId,
          role,
          instanceId,
          image,
          workIn,
          workOut,
          ports,
          inputType,
          hasSchedulerConfig,
          hasRedisConfig,
          schemaRef,
        }
      })
      .filter((entry) => entry !== null) as {
      index: number
      beeId: string
      role: string
      instanceId: string
      image: string
      workIn: string | null
      workOut: string | null
      ports: { id: string; direction: string }[]
      inputType: string | null
      hasSchedulerConfig: boolean
      hasRedisConfig: boolean
      schemaRef: string | null
    }[]
  }, [template])

  const queueOptions = useMemo(() => {
    const result = new Set<string>()
    for (const bee of bees) {
      if (bee.workIn) {
        result.add(bee.workIn)
      }
      if (bee.workOut) {
        result.add(bee.workOut)
      }
    }
    return Array.from(result).sort((a, b) => a.localeCompare(b))
  }, [bees])

  useEffect(() => {
    if (!bees.length) {
      setSelectedBeeIndex(0)
      return
    }
    if (selectedBeeIndex < 0 || selectedBeeIndex >= bees.length) {
      setSelectedBeeIndex(0)
    }
  }, [bees, selectedBeeIndex])

  const selectedBee =
    bees.length > 0 && selectedBeeIndex >= 0 && selectedBeeIndex < bees.length
      ? bees[selectedBeeIndex]
      : null

  const readBeeInterceptors = useCallback(
    (beeIndex: number): unknown => {
      if (!template || typeof template !== 'object' || Array.isArray(template)) return undefined
      const record = template as Record<string, unknown>
      const beesRaw = Array.isArray(record['bees']) ? (record['bees'] as unknown[]) : []
      const entry = beesRaw[beeIndex]
      if (!entry || typeof entry !== 'object' || Array.isArray(entry)) return undefined
      const bee = entry as Record<string, unknown>
      const config = bee['config']
      if (!isPlainObject(config)) return undefined
      return (config as Record<string, unknown>)['interceptors']
    },
    [template],
  )

  const setBeeInterceptors = useCallback(
    (beeIndex: number, interceptors: Record<string, unknown> | undefined) => {
      onChange((current) => {
        const base = isPlainObject(current) ? { ...(current as Record<string, unknown>) } : {}
        const beesRaw = Array.isArray(base.bees) ? [...(base.bees as unknown[])] : []
        if (beeIndex < 0 || beeIndex >= beesRaw.length) return base
        const existingBee = isPlainObject(beesRaw[beeIndex])
          ? { ...(beesRaw[beeIndex] as Record<string, unknown>) }
          : {}
        const configRaw = existingBee.config
        const config = isPlainObject(configRaw) ? { ...(configRaw as Record<string, unknown>) } : {}
        if (interceptors && Object.keys(interceptors).length > 0) {
          config.interceptors = interceptors
        } else {
          delete config.interceptors
        }
        existingBee.config = Object.keys(config).length > 0 ? config : undefined
        beesRaw[beeIndex] = existingBee
        base.bees = beesRaw
        return base
      })
    },
    [onChange],
  )

  const interceptorsMeta = useMemo(() => {
    if (!selectedBee) {
      return { keys: [] as string[], count: 0 }
    }
    const raw = readBeeInterceptors(selectedBee.index)
    if (!isPlainObject(raw)) {
      return { keys: [] as string[], count: 0 }
    }
    const keys = Object.keys(raw).sort((a, b) => a.localeCompare(b))
    return { keys, count: keys.length }
  }, [readBeeInterceptors, selectedBee])

  const openInterceptorsEditor = useCallback(
    (beeIndex: number) => {
      const raw = readBeeInterceptors(beeIndex)
      const text = isPlainObject(raw) ? YAML.stringify(raw) : ''
      setInterceptorsEditorBeeIndex(beeIndex)
      setInterceptorsEditorText(text)
      setInterceptorsEditorError(null)
    },
    [readBeeInterceptors],
  )

  const closeInterceptorsEditor = useCallback(() => {
    setInterceptorsEditorBeeIndex(null)
    setInterceptorsEditorText('')
    setInterceptorsEditorError(null)
  }, [])

  const applyInterceptorsEditor = useCallback(() => {
    if (interceptorsEditorBeeIndex === null) return
    const text = interceptorsEditorText.trim()
    if (!text) {
      setBeeInterceptors(interceptorsEditorBeeIndex, undefined)
      closeInterceptorsEditor()
      return
    }
    try {
      const parsed = YAML.parse(text)
      if (parsed === null || parsed === undefined || parsed === '') {
        setBeeInterceptors(interceptorsEditorBeeIndex, undefined)
        closeInterceptorsEditor()
        return
      }
      if (!isPlainObject(parsed)) {
        setInterceptorsEditorError('Interceptors must be a YAML mapping (object).')
        return
      }
      setBeeInterceptors(interceptorsEditorBeeIndex, parsed)
      closeInterceptorsEditor()
    } catch (err) {
      setInterceptorsEditorError(err instanceof Error ? err.message : 'Invalid YAML.')
    }
  }, [
    closeInterceptorsEditor,
    interceptorsEditorBeeIndex,
    interceptorsEditorText,
    setBeeInterceptors,
  ])

  const inputWarnings: string[] = []
  if (selectedBee) {
    if (selectedBee.inputType === 'SCHEDULER' && !selectedBee.hasSchedulerConfig) {
      inputWarnings.push(
        'Scheduler input selected, but inputs.scheduler.* is not configured for this bee.',
      )
    }
    if (selectedBee.inputType === 'REDIS_DATASET' && !selectedBee.hasRedisConfig) {
      inputWarnings.push(
        'Redis dataset input selected, but inputs.redis.* is not configured for this bee.',
      )
    }
    if (!selectedBee.inputType && (selectedBee.hasSchedulerConfig || selectedBee.hasRedisConfig)) {
      inputWarnings.push(
        'Input configuration is present, but inputs.type is not set; choose an explicit IO type.',
      )
    }
  }

  const selectedManifest = useMemo(() => {
    if (!selectedBee || !selectedBee.image) return null
    const manifest = getManifestForImage(selectedBee.image)
    return manifest ?? null
  }, [getManifestForImage, selectedBee])

  const inputTypeOptions = useMemo(() => {
    if (!selectedManifest || !Array.isArray(selectedManifest.config)) {
      return [] as string[]
    }
    const entry = selectedManifest.config.find((cfg) => cfg.name === 'inputs.type')
    if (!entry || !Array.isArray(entry.options)) {
      return [] as string[]
    }
    const raw = entry.options as unknown[]
    const values: string[] = []
    raw.forEach((opt) => {
      if (typeof opt === 'string' && opt.trim().length > 0) {
        values.push(opt.trim())
      }
    })
    return values
  }, [selectedManifest])

  const updateControllerImage = (next: string) => {
    onChange((current) => {
      const base =
        current && typeof current === 'object' && !Array.isArray(current)
          ? { ...(current as Record<string, unknown>) }
          : {}
      base.image = next.trim() || undefined
      return base
    })
  }

  const updateBeeField = (
    beeIndex: number,
    field: 'id' | 'role' | 'instanceId' | 'image' | 'workIn' | 'workOut',
    value: string,
  ) => {
    onChange((current) => {
      const base =
        current && typeof current === 'object' && !Array.isArray(current)
          ? { ...(current as Record<string, unknown>) }
          : {}
      const beesRaw = Array.isArray(base.bees) ? [...(base.bees as unknown[])] : []
      if (beeIndex < 0 || beeIndex >= beesRaw.length) {
        return base
      }
      const existing =
        beesRaw[beeIndex] && typeof beesRaw[beeIndex] === 'object' && !Array.isArray(beesRaw[beeIndex])
          ? { ...(beesRaw[beeIndex] as Record<string, unknown>) }
          : {}
      if (field === 'id' || field === 'role' || field === 'instanceId' || field === 'image') {
        existing[field] = value.trim() || undefined
      } else {
        const work = isPlainObject(existing.work)
          ? { ...(existing.work as Record<string, unknown>) }
          : {}
        if (field === 'workIn') {
          updatePortMapEntry(work, 'in', 'in', value)
        }
        if (field === 'workOut') {
          updatePortMapEntry(work, 'out', 'out', value)
        }
        if (Object.keys(work).length > 0) {
          existing.work = work
        } else {
          delete existing.work
        }
      }
      beesRaw[beeIndex] = existing
      ;(base as Record<string, unknown>).bees = beesRaw
      return base
    })
  }

  const updateBeePort = (
    beeIndex: number,
    portIndex: number,
    field: 'id' | 'direction',
    rawValue: string,
  ) => {
    onChange((current) => {
      const base =
        current && typeof current === 'object' && !Array.isArray(current)
          ? { ...(current as Record<string, unknown>) }
          : {}
      const beesRaw = Array.isArray(base.bees) ? [...(base.bees as unknown[])] : []
      if (beeIndex < 0 || beeIndex >= beesRaw.length) {
        return base
      }
      const existing =
        beesRaw[beeIndex] && typeof beesRaw[beeIndex] === 'object' && !Array.isArray(beesRaw[beeIndex])
          ? { ...(beesRaw[beeIndex] as Record<string, unknown>) }
          : {}
      const portsRaw = Array.isArray(existing.ports)
        ? ([...(existing.ports as unknown[])] as unknown[])
        : ([] as unknown[])
      const port =
        portsRaw[portIndex] && isPlainObject(portsRaw[portIndex])
          ? { ...(portsRaw[portIndex] as Record<string, unknown>) }
          : {}
      const value = rawValue.trim()
      if (field === 'id') {
        if (value) {
          port.id = value
        } else {
          delete port.id
        }
      }
      if (field === 'direction') {
        if (value) {
          port.direction = value
        } else {
          delete port.direction
        }
      }
      portsRaw[portIndex] = port
      existing.ports = portsRaw
      beesRaw[beeIndex] = existing
      ;(base as Record<string, unknown>).bees = beesRaw
      return base
    })
  }

  const addBeePort = (beeIndex: number) => {
    onChange((current) => {
      const base =
        current && typeof current === 'object' && !Array.isArray(current)
          ? { ...(current as Record<string, unknown>) }
          : {}
      const beesRaw = Array.isArray(base.bees) ? [...(base.bees as unknown[])] : []
      if (beeIndex < 0 || beeIndex >= beesRaw.length) {
        return base
      }
      const existing =
        beesRaw[beeIndex] && typeof beesRaw[beeIndex] === 'object' && !Array.isArray(beesRaw[beeIndex])
          ? { ...(beesRaw[beeIndex] as Record<string, unknown>) }
          : {}
      const portsRaw = Array.isArray(existing.ports)
        ? ([...(existing.ports as unknown[])] as unknown[])
        : ([] as unknown[])
      portsRaw.push({ id: '', direction: 'in' })
      existing.ports = portsRaw
      beesRaw[beeIndex] = existing
      ;(base as Record<string, unknown>).bees = beesRaw
      return base
    })
  }

  const removeBeePort = (beeIndex: number, portIndex: number) => {
    onChange((current) => {
      const base =
        current && typeof current === 'object' && !Array.isArray(current)
          ? { ...(current as Record<string, unknown>) }
          : {}
      const beesRaw = Array.isArray(base.bees) ? [...(base.bees as unknown[])] : []
      if (beeIndex < 0 || beeIndex >= beesRaw.length) {
        return base
      }
      const existing =
        beesRaw[beeIndex] && typeof beesRaw[beeIndex] === 'object' && !Array.isArray(beesRaw[beeIndex])
          ? { ...(beesRaw[beeIndex] as Record<string, unknown>) }
          : {}
      const portsRaw = Array.isArray(existing.ports)
        ? ([...(existing.ports as unknown[])] as unknown[])
        : ([] as unknown[])
      if (portIndex < 0 || portIndex >= portsRaw.length) {
        return base
      }
      portsRaw.splice(portIndex, 1)
      if (portsRaw.length > 0) {
        existing.ports = portsRaw
      } else {
        delete existing.ports
      }
      beesRaw[beeIndex] = existing
      ;(base as Record<string, unknown>).bees = beesRaw
      return base
    })
  }

  const updateBeeIoType = (beeIndex: number, field: 'inputs.type' | 'outputs.type', rawValue: string) => {
    const value = rawValue.trim()
    if (!value) {
      return
    }
    onChange((current) => {
      const base =
        current && typeof current === 'object' && !Array.isArray(current)
          ? { ...(current as Record<string, unknown>) }
          : {}
      const beesRaw = Array.isArray((base as Record<string, unknown>).bees)
        ? ([...(base as Record<string, unknown>).bees as unknown[]] as unknown[])
        : ([] as unknown[])
      if (beeIndex < 0 || beeIndex >= beesRaw.length) {
        return base
      }
      const existing =
        beesRaw[beeIndex] &&
        typeof beesRaw[beeIndex] === 'object' &&
        !Array.isArray(beesRaw[beeIndex])
          ? { ...(beesRaw[beeIndex] as Record<string, unknown>) }
          : {}
      const configRaw = existing.config
      const config =
        configRaw && typeof configRaw === 'object' && !Array.isArray(configRaw)
          ? { ...(configRaw as Record<string, unknown>) }
          : {}
      const [rootKey, leafKey] = field.split('.')
      if (!rootKey || !leafKey) {
        return base
      }
      const ioRaw = config[rootKey]
      const io =
        ioRaw && typeof ioRaw === 'object' && !Array.isArray(ioRaw)
          ? { ...(ioRaw as Record<string, unknown>) }
          : {}
      io['type'] = value
      config[rootKey] = io
      existing.config = config
      beesRaw[beeIndex] = existing
      ;(base as Record<string, unknown>).bees = beesRaw
      return base
    })
  }

  const handleAddBee = () => {
    const newIndex = bees.length
    onChange((current) => {
      const base =
        current && typeof current === 'object' && !Array.isArray(current)
          ? { ...(current as Record<string, unknown>) }
          : {}
      const beesRaw = Array.isArray((base as Record<string, unknown>).bees)
        ? ([...(base as Record<string, unknown>).bees as unknown[]] as unknown[])
        : ([] as unknown[])
      const newBee: Record<string, unknown> = {
        role: '',
        image: '',
        work: {},
      }
      beesRaw.push(newBee)
      ;(base as Record<string, unknown>).bees = beesRaw
      return base
    })
    setSelectedBeeIndex(newIndex)
  }

  const handleRemoveBee = (beeIndex: number) => {
    if (beeIndex < 0) return
    onChange((current) => {
      const base =
        current && typeof current === 'object' && !Array.isArray(current)
          ? { ...(current as Record<string, unknown>) }
          : {}
      const beesRaw = Array.isArray((base as Record<string, unknown>).bees)
        ? ([...(base as Record<string, unknown>).bees as unknown[]] as unknown[])
        : ([] as unknown[])
      if (beeIndex >= beesRaw.length) {
        return base
      }
      beesRaw.splice(beeIndex, 1)
      ;(base as Record<string, unknown>).bees = beesRaw
      return base
    })
    setSelectedBeeIndex((currentIndex) => {
      if (currentIndex > beeIndex) return currentIndex - 1
      if (currentIndex === beeIndex) return Math.max(0, currentIndex - 1)
      return currentIndex
    })
  }

  const handleMoveBee = (beeIndex: number, direction: -1 | 1) => {
    if (beeIndex < 0) return
    const targetIndex = beeIndex + direction
    if (targetIndex < 0 || targetIndex >= bees.length) return
    onChange((current) => {
      const base =
        current && typeof current === 'object' && !Array.isArray(current)
          ? { ...(current as Record<string, unknown>) }
          : {}
      const beesRaw = Array.isArray((base as Record<string, unknown>).bees)
        ? ([...(base as Record<string, unknown>).bees as unknown[]] as unknown[])
        : ([] as unknown[])
      if (beeIndex < 0 || beeIndex >= beesRaw.length) {
        return base
      }
      const [moved] = beesRaw.splice(beeIndex, 1)
      beesRaw.splice(targetIndex, 0, moved)
      ;(base as Record<string, unknown>).bees = beesRaw
      return base
    })
    setSelectedBeeIndex(targetIndex)
  }

  if (!template || (typeof template !== 'object' && !Array.isArray(template))) {
    return (
      <div className="text-xs text-white/70">
        This scenario YAML does not define a <code>template</code> section yet.
      </div>
    )
  }

  return (
    <div className="space-y-3">
      <div className="flex items-center justify-between">
        <h3 className="text-xs font-semibold text-white/80">Swarm template</h3>
      </div>
      <div className="flex items-center gap-2 text-[11px] text-white/80">
        <span className="text-white/70 w-28">Controller image</span>
        <input
          className="flex-1 rounded border border-white/20 bg-black/40 px-2 py-1 text-[11px] text-white/90"
          value={controllerImage}
          placeholder="swarm-controller:latest"
          onChange={(e) => updateControllerImage(e.target.value)}
        />
      </div>
      <div className="mt-3 grid grid-cols-[minmax(0,220px)_minmax(0,1fr)] gap-4 items-start">
        <div className="border border-white/15 rounded bg-black/40">
          <div className="px-3 py-2 border-b border-white/10 text-[11px] uppercase tracking-wide text-white/60 flex items-center justify-between">
            <span>Bees</span>
            <button
              type="button"
              className="rounded bg-white/10 px-1.5 py-0.5 text-[10px] text-white/80 hover:bg-white/20"
              onClick={handleAddBee}
            >
              + Add
            </button>
          </div>
          <div className="max-h-[420px] overflow-y-auto">
            {bees.length === 0 ? (
              <div className="px-3 py-3 text-[11px] text-white/60">
                No bees defined in template.
              </div>
            ) : (
              <ul className="text-[11px]">
                {bees.map((bee) => {
                  const isSelected = bee.index === selectedBeeIndex
                  const label = bee.beeId
                    ? `${bee.beeId}${bee.role ? ` (${bee.role})` : ''}`
                    : bee.instanceId || bee.role
                      ? `${bee.instanceId || ''}${bee.role ? ` (${bee.role})` : ''}`
                      : `bee-${bee.index + 1}`
                  return (
                    <li key={bee.index}>
                      <button
                        type="button"
                        onClick={() => setSelectedBeeIndex(bee.index)}
                        className={`w-full text-left px-3 py-1.5 border-b border-white/5 last:border-b-0 ${
                          isSelected
                            ? 'bg-white/20 text-white border-white/40'
                            : 'bg-transparent text-white/80 hover:bg-white/10'
                        }`}
                      >
                        <div className="font-medium truncate">{label}</div>
                        <div className="text-[10px] text-white/60 truncate">
                          in: {bee.workIn || '—'} • out: {bee.workOut || '—'}
                        </div>
                        <div className="mt-1 flex justify-end gap-1 text-[10px]">
                          <button
                            type="button"
                            className="px-1 py-0.5 rounded bg-white/5 text-white/70 hover:bg-white/15 disabled:opacity-30"
                            disabled={bee.index === 0}
                            onClick={() => handleMoveBee(bee.index, -1)}
                          >
                            ↑
                          </button>
                          <button
                            type="button"
                            className="px-1 py-0.5 rounded bg-white/5 text-white/70 hover:bg-white/15 disabled:opacity-30"
                            disabled={bee.index === bees.length - 1}
                            onClick={() => handleMoveBee(bee.index, 1)}
                          >
                            ↓
                          </button>
                          <button
                            type="button"
                            className="px-1 py-0.5 rounded bg-red-500/40 text-red-50 hover:bg-red-500/60"
                            onClick={() => handleRemoveBee(bee.index)}
                          >
                            ✕
                          </button>
                        </div>
                      </button>
                    </li>
                  )
                })}
              </ul>
            )}
          </div>
        </div>
        <div className="space-y-3">
          <div className="border border-white/15 rounded bg-black/30 p-3 text-[11px] text-white/80">
          {!selectedBee && (
            <div className="text-white/60">Select a bee to view details.</div>
          )}
          {selectedBee && (
            <div className="space-y-2">
              <div className="font-semibold text-white/90 mb-1">Bee details</div>
              <div className="flex items-center gap-2">
                <span className="w-28 text-white/70">Instance ID</span>
                <input
                  className="flex-1 rounded border border-white/20 bg-black/40 px-2 py-1 text-[11px] text-white/90"
                  value={selectedBee.instanceId}
                  placeholder="gen-1"
                  onChange={(e) =>
                    updateBeeField(selectedBee.index, 'instanceId', e.target.value)
                  }
                />
              </div>
              <div className="flex items-center gap-2">
                <span className="w-28 text-white/70">Bee ID</span>
                <input
                  className="flex-1 rounded border border-white/20 bg-black/40 px-2 py-1 text-[11px] text-white/90"
                  value={selectedBee.beeId}
                  placeholder="genA"
                  onChange={(e) => updateBeeField(selectedBee.index, 'id', e.target.value)}
                />
              </div>
              <div className="flex items-center gap-2">
                <span className="w-28 text-white/70">Role</span>
                <input
                  className="flex-1 rounded border border-white/20 bg-black/40 px-2 py-1 text-[11px] text-white/90"
                  value={selectedBee.role}
                  placeholder="generator"
                  onChange={(e) =>
                    updateBeeField(selectedBee.index, 'role', e.target.value)
                  }
                />
              </div>
              <div className="flex items-center gap-2">
                <span className="w-28 text-white/70">Image</span>
                <input
                  className="flex-1 rounded border border-white/20 bg-black/40 px-2 py-1 text-[11px] text-white/90"
                  value={selectedBee.image}
                  placeholder="generator:latest"
                  onChange={(e) =>
                    updateBeeField(selectedBee.index, 'image', e.target.value)
                  }
                />
              </div>
              {(inputTypeOptions.length > 0 || selectedBee.inputType) && (
                <div className="flex items-center gap-2">
                  <span className="w-28 text-white/70">Input type</span>
                  {inputTypeOptions.length > 0 ? (
                    <select
                      className="w-40 rounded border border-white/20 bg-black/60 px-2 py-1 text-[11px] text-white/90"
                      value={selectedBee.inputType ?? ''}
                      onChange={(e) =>
                        updateBeeIoType(selectedBee.index, 'inputs.type', e.target.value)
                      }
                    >
                      <option value="">(not set)</option>
                      {inputTypeOptions.map((option) => (
                        <option key={option} value={option}>
                          {option}
                        </option>
                      ))}
                    </select>
                  ) : (
                    <span className="text-white/70 text-[11px]">
                      {selectedBee.inputType || '(not set)'}
                    </span>
                  )}
                </div>
              )}
              {inputWarnings.length > 0 && (
                <div className="pl-28 text-[10px] text-amber-300 space-y-0.5">
                  {inputWarnings.map((warning) => (
                    <div key={warning}>{warning}</div>
                  ))}
                </div>
              )}
              <div className="flex items-center gap-2">
                <span className="w-28 text-white/70">Work in</span>
                <input
                  className="flex-1 rounded border border-white/20 bg-black/40 px-2 py-1 text-[11px] text-white/90"
                  value={selectedBee.workIn ?? ''}
                  placeholder="queue-in"
                  onChange={(e) =>
                    updateBeeField(selectedBee.index, 'workIn', e.target.value)
                  }
                />
                {queueOptions.length > 0 && (
                  <select
                    className="w-28 rounded border border-white/20 bg-black/60 px-1 py-0.5 text-[10px] text-white/80"
                    value=""
                    onChange={(e) => {
                      const value = e.target.value
                      if (value) {
                        updateBeeField(selectedBee.index, 'workIn', value)
                      }
                    }}
                  >
                    <option value="">pick…</option>
                    {queueOptions.map((name) => (
                      <option key={name} value={name}>
                        {name}
                      </option>
                    ))}
                  </select>
                )}
              </div>
              <div className="flex items-center gap-2">
                <span className="w-28 text-white/70">Work out</span>
                <input
                  className="flex-1 rounded border border-white/20 bg-black/40 px-2 py-1 text-[11px] text-white/90"
                  value={selectedBee.workOut ?? ''}
                  placeholder="queue-out"
                  onChange={(e) =>
                    updateBeeField(selectedBee.index, 'workOut', e.target.value)
                  }
                />
                {queueOptions.length > 0 && (
                  <select
                    className="w-28 rounded border border-white/20 bg-black/60 px-1 py-0.5 text-[10px] text-white/80"
                    value=""
                    onChange={(e) => {
                      const value = e.target.value
                      if (value) {
                        updateBeeField(selectedBee.index, 'workOut', value)
                      }
                    }}
                  >
                    <option value="">pick…</option>
                    {queueOptions.map((name) => (
                      <option key={name} value={name}>
                        {name}
                      </option>
                    ))}
                  </select>
                )}
              </div>
              <div className="flex items-start gap-2">
                <span className="w-28 text-white/70 pt-1">Ports</span>
                <div className="flex-1 min-w-0 space-y-1">
                  {selectedBee.ports.length === 0 && (
                    <div className="text-[10px] text-white/50">(none)</div>
                  )}
                  {selectedBee.ports.map((port, portIndex) => (
                    <div key={portIndex} className="flex items-center gap-2">
                      <input
                        className="flex-1 rounded border border-white/20 bg-black/40 px-2 py-1 text-[11px] text-white/90"
                        value={port.id}
                        placeholder="port-id"
                        onChange={(e) =>
                          updateBeePort(selectedBee.index, portIndex, 'id', e.target.value)
                        }
                      />
                      <select
                        className="w-24 rounded border border-white/20 bg-black/60 px-2 py-1 text-[11px] text-white/90"
                        value={port.direction || 'in'}
                        onChange={(e) =>
                          updateBeePort(selectedBee.index, portIndex, 'direction', e.target.value)
                        }
                      >
                        <option value="in">in</option>
                        <option value="out">out</option>
                      </select>
                      <button
                        type="button"
                        className="px-1.5 py-0.5 rounded bg-red-500/40 text-red-50 hover:bg-red-500/60"
                        onClick={() => removeBeePort(selectedBee.index, portIndex)}
                      >
                        ✕
                      </button>
                    </div>
                  ))}
                  <div className="flex items-center gap-2">
                    <button
                      type="button"
                      className="rounded bg-white/10 px-2 py-1 text-[11px] text-white/80 hover:bg-white/20"
                      onClick={() => addBeePort(selectedBee.index)}
                    >
                      + Add port
                    </button>
                    <span className="text-[10px] text-white/50">
                      Used only for topology edges.
                    </span>
                  </div>
                </div>
              </div>
              <div className="flex items-start gap-2">
                <span className="w-28 text-white/70 pt-1">Interceptors</span>
                <div className="flex-1 min-w-0">
	                  {interceptorsMeta.count === 0 ? (
	                    <div className="text-[11px] text-white/50">(none)</div>
	                  ) : (
	                    <div className="flex flex-wrap gap-1">
	                      {interceptorsMeta.keys.slice(0, 3).map((key) => (
	                        <span
	                          key={key}
	                          className="inline-flex items-center rounded-full border border-white/20 bg-black/60 px-1.5 py-0.5 text-[10px] text-white/80"
	                        >
	                          {key}
	                        </span>
	                      ))}
	                      {interceptorsMeta.count > 3 && (
	                        <span className="text-[10px] text-white/50 pt-0.5">
	                          +{interceptorsMeta.count - 3} more
	                        </span>
	                      )}
	                    </div>
	                  )}
	                </div>
	                <div className="flex items-center gap-1">
	                  <button
	                    type="button"
	                    className="px-2 py-1 rounded bg-white/10 text-[11px] text-white/80 hover:bg-white/20"
	                    onClick={() => openInterceptorsEditor(selectedBee.index)}
	                  >
	                    Edit
	                  </button>
	                  {interceptorsMeta.count > 0 && (
	                    <button
	                      type="button"
	                      className="px-2 py-1 rounded bg-white/5 text-[11px] text-white/70 hover:bg-white/15"
	                      onClick={() => setBeeInterceptors(selectedBee.index, undefined)}
	                      title="Remove interceptors"
	                    >
	                      Clear
	                    </button>
	                  )}
	                </div>
	              </div>
	              <div className="pt-2">
	                <div className="flex flex-wrap items-center gap-2">
	                  <button
	                    type="button"
	                    className="rounded bg-white/10 px-2 py-1 text-[11px] text-white/80 hover:bg-white/20"
                    onClick={() => onOpenConfig(selectedBee.index)}
                  >
                    Edit config via capabilities
                  </button>
                  {onOpenSchemaEditor && selectedBee.role === 'generator' && (
                    <button
                      type="button"
                      className="rounded bg-sky-500/20 px-2 py-1 text-[11px] text-sky-100 hover:bg-sky-500/30"
                      onClick={() => onOpenSchemaEditor(selectedBee.index)}
                    >
                      {selectedBee.schemaRef ? 'Edit HTTP body via schema' : 'Attach schema…'}
                    </button>
                  )}
                </div>
              </div>
            </div>
          )}
          </div>
          <div className="border border-dashed border-white/20 rounded bg-black/40 p-3 text-[11px] text-white/80">
          <div className="font-semibold text-white/80 mb-1">Flow preview</div>
	          {bees.length === 0 ? (
	            <div className="text-white/60">No bees defined yet.</div>
	          ) : (
	            <>
              {queueOptions.length > 0 && (
                <div className="mb-2 text-[10px] text-white/60">
                  Queues:{' '}
                  {queueOptions.map((q) => (
                    <span
                      key={q}
                      className="inline-flex items-center rounded-full border border-white/20 bg-black/60 px-1.5 py-0.5 mr-1 mb-1"
                    >
                      <span className="font-mono text-[10px] text-white/80">
                        {q}
                      </span>
                    </span>
                  ))}
                </div>
              )}
              <div className="flex items-start gap-2 overflow-x-auto pt-1">
                {bees.map((bee) => {
                  const consumers =
                    bee.workOut != null && bee.workOut !== ''
                      ? bees.filter((other) => other.workIn === bee.workOut)
                      : []
                  const producers =
                    bee.workIn != null && bee.workIn !== ''
                      ? bees.filter((other) => other.workOut === bee.workIn)
                      : []
                  return (
                    <div key={bee.index} className="flex flex-col items-start gap-1">
                      <div
                        className={`min-w-[140px] max-w-[180px] rounded border px-2 py-1 ${
                          bee.index === selectedBeeIndex
                            ? 'border-sky-400 bg-sky-500/20'
                            : 'border-white/25 bg-black/70'
                        }`}
                      >
                        <div className="text-[11px] font-semibold truncate">
                          {bee.role || 'bee'}
                        </div>
                        <div className="text-[10px] text-white/60 truncate">
                          in: {bee.workIn || '—'}
                        </div>
                        <div className="text-[10px] text-white/60 truncate">
                          out: {bee.workOut || '—'}
                        </div>
                      </div>
                      <div className="pl-1 text-[9px] text-white/60 space-y-0.5">
                        {bee.workIn && (
                          <div>
                            ←{' '}
                            {producers.length === 0
                              ? '(no producer)'
                              : producers
                                  .map((p) => p.role || p.instanceId || `bee-${p.index + 1}`)
                                  .join(', ')}
                          </div>
                        )}
                        {bee.workOut && (
                          <div>
                            →{' '}
                            {consumers.length === 0
                              ? '(no consumer)'
                              : consumers
                                  .map((c) => c.role || c.instanceId || `bee-${c.index + 1}`)
                                  .join(', ')}
                          </div>
                        )}
                      </div>
                    </div>
                  )
                })}
              </div>
            </>
	          )}
	          </div>
	        </div>
	      </div>

	      {interceptorsEditorBeeIndex !== null && (
	        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/70">
	          <div
	            role="dialog"
	            aria-modal="true"
	            className="w-[96vw] max-w-3xl h-[80vh] rounded-lg bg-[#05070b] border border-white/20 p-4 text-sm text-white flex flex-col"
	          >
	            <div className="flex items-center justify-between gap-3 mb-2">
	              <div className="min-w-0">
	                <h3 className="text-xs font-semibold text-white/80 truncate">Edit interceptors</h3>
	                <div className="text-[11px] text-white/50 font-mono truncate">
	                  template.bees[{interceptorsEditorBeeIndex}].config.interceptors
	                </div>
	              </div>
	              <button type="button" className="text-white/60 hover:text-white" onClick={closeInterceptorsEditor}>
	                ×
	              </button>
	            </div>

	            {interceptorsEditorError && (
	              <div className="mb-2 text-[11px] text-red-400">{interceptorsEditorError}</div>
	            )}

	            <div className="flex-1 min-h-0 border border-white/15 rounded overflow-hidden">
	              <Editor
	                height="100%"
	                defaultLanguage="yaml"
	                theme="vs-dark"
	                value={interceptorsEditorText}
	                onChange={(value) => setInterceptorsEditorText(value ?? '')}
	                options={{
	                  fontSize: 11,
	                  minimap: { enabled: false },
	                  scrollBeyondLastLine: false,
	                  wordWrap: 'on',
	                }}
	              />
	            </div>

	            <div className="mt-3 pt-2 border-t border-white/10 flex items-center justify-end gap-2">
	              <button
	                type="button"
	                className="rounded px-2 py-1 text-[11px] text-white/70 hover:bg-white/10"
	                onClick={closeInterceptorsEditor}
	              >
	                Cancel
	              </button>
	              <button
	                type="button"
	                className="rounded bg-sky-500/80 px-3 py-1 text-[11px] text-white hover:bg-sky-500"
	                onClick={applyInterceptorsEditor}
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

interface TopologyEditorProps {
  topology: TopologyNode
  template: TemplateNode
  onChange: (updater: (current: TopologyNode) => TopologyNode) => void
}

function TopologyEditor({ topology, template, onChange }: TopologyEditorProps) {
  const edges = useMemo(() => {
    if (!isPlainObject(topology)) return [] as {
      index: number
      id: string
      fromBeeId: string
      fromPort: string
      toBeeId: string
      toPort: string
      selectorPolicy: string
      selectorExpr: string
    }[]
    const record = topology as Record<string, unknown>
    const edgesRaw = Array.isArray(record.edges) ? (record.edges as unknown[]) : []
    return edgesRaw.map((entry, index) => {
      const edgeRec = isPlainObject(entry) ? (entry as Record<string, unknown>) : {}
      const id = typeof edgeRec.id === 'string' ? (edgeRec.id as string) : ''
      const fromRec = isPlainObject(edgeRec.from) ? (edgeRec.from as Record<string, unknown>) : {}
      const toRec = isPlainObject(edgeRec.to) ? (edgeRec.to as Record<string, unknown>) : {}
      const selectorRec = isPlainObject(edgeRec.selector)
        ? (edgeRec.selector as Record<string, unknown>)
        : {}
      return {
        index,
        id,
        fromBeeId: typeof fromRec.beeId === 'string' ? (fromRec.beeId as string) : '',
        fromPort: typeof fromRec.port === 'string' ? (fromRec.port as string) : '',
        toBeeId: typeof toRec.beeId === 'string' ? (toRec.beeId as string) : '',
        toPort: typeof toRec.port === 'string' ? (toRec.port as string) : '',
        selectorPolicy:
          typeof selectorRec.policy === 'string' ? (selectorRec.policy as string) : '',
        selectorExpr: typeof selectorRec.expr === 'string' ? (selectorRec.expr as string) : '',
      }
    })
  }, [topology])

  const versionValue =
    isPlainObject(topology) && typeof (topology as Record<string, unknown>).version === 'number'
      ? String((topology as Record<string, unknown>).version)
      : ''

  const { beeIds, portIds } = useMemo(() => {
    const beeSet = new Set<string>()
    const portSet = new Set<string>(['in', 'out'])
    if (isPlainObject(template)) {
      const beesRaw = Array.isArray((template as Record<string, unknown>)['bees'])
        ? ((template as Record<string, unknown>)['bees'] as unknown[])
        : []
      for (const entry of beesRaw) {
        if (!isPlainObject(entry)) continue
        const bee = entry as Record<string, unknown>
        if (typeof bee.id === 'string' && bee.id.trim()) {
          beeSet.add(bee.id.trim())
        }
        const portsRaw = Array.isArray(bee.ports) ? (bee.ports as unknown[]) : []
        for (const port of portsRaw) {
          if (!isPlainObject(port)) continue
          const portRec = port as Record<string, unknown>
          if (typeof portRec.id === 'string' && portRec.id.trim()) {
            portSet.add(portRec.id.trim())
          }
        }
      }
    }
    return {
      beeIds: Array.from(beeSet).sort((a, b) => a.localeCompare(b)),
      portIds: Array.from(portSet).sort((a, b) => a.localeCompare(b)),
    }
  }, [template])

  const updateTopologyVersion = (rawValue: string) => {
    onChange((current) => {
      const base = isPlainObject(current) ? { ...(current as Record<string, unknown>) } : {}
      const value = rawValue.trim()
      const edgesRaw = Array.isArray(base.edges) ? (base.edges as unknown[]) : []
      if (value) {
        const parsed = Number(value)
        if (Number.isFinite(parsed)) {
          base.version = parsed
        }
      } else {
        delete base.version
      }
      if (edgesRaw.length > 0 && typeof base.version !== 'number') {
        base.version = 1
      }
      if (edgesRaw.length === 0 && !('version' in base)) {
        return null
      }
      return base
    })
  }

  const updateEdgeField = (
    edgeIndex: number,
    field: 'id' | 'fromBeeId' | 'fromPort' | 'toBeeId' | 'toPort' | 'selectorPolicy' | 'selectorExpr',
    rawValue: string,
  ) => {
    onChange((current) => {
      const base = isPlainObject(current) ? { ...(current as Record<string, unknown>) } : {}
      const edgesRaw = Array.isArray(base.edges) ? [...(base.edges as unknown[])] : []
      while (edgesRaw.length <= edgeIndex) {
        edgesRaw.push({})
      }
      const edge = isPlainObject(edgesRaw[edgeIndex])
        ? { ...(edgesRaw[edgeIndex] as Record<string, unknown>) }
        : {}
      const value = rawValue.trim()
      if (field === 'id') {
        if (value) {
          edge.id = value
        } else {
          delete edge.id
        }
      }
      if (field === 'fromBeeId' || field === 'fromPort') {
        const from = isPlainObject(edge.from) ? { ...(edge.from as Record<string, unknown>) } : {}
        if (field === 'fromBeeId') {
          if (value) {
            from.beeId = value
          } else {
            delete from.beeId
          }
        }
        if (field === 'fromPort') {
          if (value) {
            from.port = value
          } else {
            delete from.port
          }
        }
        if (Object.keys(from).length > 0) {
          edge.from = from
        } else {
          delete edge.from
        }
      }
      if (field === 'toBeeId' || field === 'toPort') {
        const to = isPlainObject(edge.to) ? { ...(edge.to as Record<string, unknown>) } : {}
        if (field === 'toBeeId') {
          if (value) {
            to.beeId = value
          } else {
            delete to.beeId
          }
        }
        if (field === 'toPort') {
          if (value) {
            to.port = value
          } else {
            delete to.port
          }
        }
        if (Object.keys(to).length > 0) {
          edge.to = to
        } else {
          delete edge.to
        }
      }
      if (field === 'selectorPolicy' || field === 'selectorExpr') {
        const selector = isPlainObject(edge.selector)
          ? { ...(edge.selector as Record<string, unknown>) }
          : {}
        if (field === 'selectorPolicy') {
          if (value) {
            selector.policy = value
          } else {
            delete selector.policy
          }
        }
        if (field === 'selectorExpr') {
          if (value) {
            selector.expr = value
          } else {
            delete selector.expr
          }
        }
        if (Object.keys(selector).length > 0) {
          edge.selector = selector
        } else {
          delete edge.selector
        }
      }
      edgesRaw[edgeIndex] = edge
      base.edges = edgesRaw
      if (typeof base.version !== 'number') {
        base.version = 1
      }
      return base
    })
  }

  const addEdge = () => {
    onChange((current) => {
      const base = isPlainObject(current) ? { ...(current as Record<string, unknown>) } : {}
      const edgesRaw = Array.isArray(base.edges) ? [...(base.edges as unknown[])] : []
      edgesRaw.push({ id: `edge-${edgesRaw.length + 1}`, from: {}, to: {} })
      base.edges = edgesRaw
      if (typeof base.version !== 'number') {
        base.version = 1
      }
      return base
    })
  }

  const removeEdge = (edgeIndex: number) => {
    onChange((current) => {
      const base = isPlainObject(current) ? { ...(current as Record<string, unknown>) } : {}
      const edgesRaw = Array.isArray(base.edges) ? [...(base.edges as unknown[])] : []
      if (edgeIndex < 0 || edgeIndex >= edgesRaw.length) {
        return base
      }
      edgesRaw.splice(edgeIndex, 1)
      if (edgesRaw.length > 0) {
        base.edges = edgesRaw
        if (typeof base.version !== 'number') {
          base.version = 1
        }
        return base
      }
      return null
    })
  }

  return (
    <div className="border border-white/15 rounded bg-black/30 p-3 text-[11px] text-white/80 space-y-2">
      <div className="flex items-center justify-between">
        <div className="font-semibold text-white/90">Topology</div>
        <button
          type="button"
          className="rounded bg-white/10 px-2 py-1 text-[11px] text-white/80 hover:bg-white/20"
          onClick={addEdge}
        >
          + Add edge
        </button>
      </div>
      {edges.length === 0 && (
        <div className="text-[10px] text-white/60">
          No topology edges defined. Add an edge to enable the logical graph.
        </div>
      )}
      {edges.length > 0 && (
        <div className="flex items-center gap-2">
          <span className="w-20 text-white/70">Version</span>
          <input
            className="w-20 rounded border border-white/20 bg-black/40 px-2 py-1 text-[11px] text-white/90"
            value={versionValue || '1'}
            onChange={(e) => updateTopologyVersion(e.target.value)}
          />
        </div>
      )}
      {edges.length > 0 && (
        <div className="space-y-2">
          {edges.map((edge) => (
            <div
              key={edge.index}
              className="border border-white/10 rounded bg-black/40 p-2 space-y-1"
            >
              <div className="flex items-center gap-2">
                <span className="w-16 text-white/70">Edge ID</span>
                <input
                  className="flex-1 rounded border border-white/20 bg-black/40 px-2 py-1 text-[11px] text-white/90"
                  value={edge.id}
                  placeholder="edge-1"
                  onChange={(e) => updateEdgeField(edge.index, 'id', e.target.value)}
                />
                <button
                  type="button"
                  className="px-1.5 py-0.5 rounded bg-red-500/40 text-red-50 hover:bg-red-500/60"
                  onClick={() => removeEdge(edge.index)}
                >
                  ✕
                </button>
              </div>
              <div className="grid grid-cols-[minmax(0,1fr)_minmax(0,1fr)] gap-2">
                <div className="space-y-1">
                  <div className="text-[10px] text-white/60">From</div>
                  <input
                    className="w-full rounded border border-white/20 bg-black/40 px-2 py-1 text-[11px] text-white/90"
                    value={edge.fromBeeId}
                    placeholder="beeId"
                    list="topology-bee-ids"
                    onChange={(e) => updateEdgeField(edge.index, 'fromBeeId', e.target.value)}
                  />
                  <input
                    className="w-full rounded border border-white/20 bg-black/40 px-2 py-1 text-[11px] text-white/90"
                    value={edge.fromPort}
                    placeholder="port"
                    list="topology-port-ids"
                    onChange={(e) => updateEdgeField(edge.index, 'fromPort', e.target.value)}
                  />
                </div>
                <div className="space-y-1">
                  <div className="text-[10px] text-white/60">To</div>
                  <input
                    className="w-full rounded border border-white/20 bg-black/40 px-2 py-1 text-[11px] text-white/90"
                    value={edge.toBeeId}
                    placeholder="beeId"
                    list="topology-bee-ids"
                    onChange={(e) => updateEdgeField(edge.index, 'toBeeId', e.target.value)}
                  />
                  <input
                    className="w-full rounded border border-white/20 bg-black/40 px-2 py-1 text-[11px] text-white/90"
                    value={edge.toPort}
                    placeholder="port"
                    list="topology-port-ids"
                    onChange={(e) => updateEdgeField(edge.index, 'toPort', e.target.value)}
                  />
                </div>
              </div>
              <div className="grid grid-cols-[minmax(0,120px)_minmax(0,1fr)] gap-2">
                <input
                  className="rounded border border-white/20 bg-black/40 px-2 py-1 text-[11px] text-white/90"
                  value={edge.selectorPolicy}
                  placeholder="selector policy"
                  onChange={(e) => updateEdgeField(edge.index, 'selectorPolicy', e.target.value)}
                />
                <input
                  className="rounded border border-white/20 bg-black/40 px-2 py-1 text-[11px] text-white/90"
                  value={edge.selectorExpr}
                  placeholder="selector expr"
                  onChange={(e) => updateEdgeField(edge.index, 'selectorExpr', e.target.value)}
                />
              </div>
            </div>
          ))}
        </div>
      )}
      {beeIds.length > 0 && (
        <datalist id="topology-bee-ids">
          {beeIds.map((beeId) => (
            <option key={beeId} value={beeId} />
          ))}
        </datalist>
      )}
      {portIds.length > 0 && (
        <datalist id="topology-port-ids">
          {portIds.map((portId) => (
            <option key={portId} value={portId} />
          ))}
        </datalist>
      )}
    </div>
  )
}

export default function ScenariosPage() {
  const navigate = useNavigate()
  const [items, setItems] = useState<ScenarioSummary[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [selectedScenario, setSelectedScenario] = useState<ScenarioPayload | null>(null)
  const [planExpanded, setPlanExpanded] = useState(false)
  const [planDraft, setPlanDraft] = useState<ScenarioPlanView | null>(null)
  const [templateDraft, setTemplateDraft] = useState<TemplateNode>(null)
  const [topologyDraft, setTopologyDraft] = useState<TopologyNode>(null)
  const [planHistoryState, setPlanHistoryState] = useState<{
    stack: ScenarioPlanView[]
    index: number
  }>({
    stack: [],
    index: -1,
  })
  const [viewMode, setViewMode] = useState<'plan' | 'yaml' | 'swarm' | 'httpTemplates'>('plan')

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
  const yamlEditorRef = useRef<MonacoEditor.IStandaloneCodeEditor | null>(null)

  const [httpTemplatePaths, setHttpTemplatePaths] = useState<string[]>([])
  const [httpTemplateSelectedPath, setHttpTemplateSelectedPath] = useState<string | null>(null)
  const [httpTemplateRaw, setHttpTemplateRaw] = useState('')
  const [httpTemplateLoading, setHttpTemplateLoading] = useState(false)
  const [httpTemplateError, setHttpTemplateError] = useState<string | null>(null)
  const [httpTemplateSaving, setHttpTemplateSaving] = useState(false)

  const setToast = useUIStore((s) => s.setToast)
  const { ensureCapabilities, getManifestForImage, manifests } = useCapabilities()

  type ConfigTarget =
    | { kind: 'swarm'; beeIndex: null; stepIndex: number }
    | { kind: 'bee'; beeIndex: number; stepIndex: number }
    | { kind: 'template-bee'; beeIndex: number; stepIndex: 0 }

  type ConfigPatchModalState = {
    target: ConfigTarget
    imageLabel: string
    entries: CapabilityConfigEntry[]
    baseConfig: Record<string, unknown> | undefined
    existingPatch: Record<string, unknown> | undefined
  }

  const [configPatchModalState, setConfigPatchModalState] = useState<ConfigPatchModalState | null>(null)

  type SchemaEditorState = {
    kind: 'generator' | 'http-template'
    beeIndex: number | null
    templatePath?: string
    schemaPath: string
    pointer: string | null
    fields: { name: string; description: string | null }[]
    initialValues: Record<string, string>
    schemaOptions: string[]
    rawSchema: string
    templateRaw?: string
  }

  type SchemaAttachTarget = 'generator' | 'http-template'

  type SchemaAttachState = {
    target: SchemaAttachTarget
    beeIndex: number | null
    templatePath?: string
    existingOptions: string[]
    selectedExisting: string | null
    mode: 'existing' | 'new'
    newPath: string
    newSchemaText: string
  }

  const [schemaEditorState, setSchemaEditorState] = useState<SchemaEditorState | null>(null)
  const [schemaEditorValues, setSchemaEditorValues] = useState<Record<string, string>>({})
  const [schemaEditorError, setSchemaEditorError] = useState<string | null>(null)
  const [schemaEditorShowRaw, setSchemaEditorShowRaw] = useState(false)
  const [showVariablesModal, setShowVariablesModal] = useState(false)
  const [showSutsModal, setShowSutsModal] = useState(false)

  const [schemaAttachState, setSchemaAttachState] = useState<SchemaAttachState | null>(null)
  const [schemaAttachError, setSchemaAttachError] = useState<string | null>(null)
  const [schemaAttachBusy, setSchemaAttachBusy] = useState(false)

  const resetPlanHistory = useCallback((initial: ScenarioPlanView | null) => {
    if (!initial) {
      setPlanHistoryState({ stack: [], index: -1 })
      setPlanDraft(null)
    } else {
      setPlanHistoryState({ stack: [initial], index: 0 })
      setPlanDraft(initial)
    }
  }, [])

  const syncPlanToYaml = useCallback(
    (next: ScenarioPlanView | null) => {
      if (!next) {
        return
      }
      setRawYaml((current) => {
        if (!current) return current
        try {
          const doc = YAML.parse(current) || {}
          const root =
            doc && typeof doc === 'object' && !Array.isArray(doc)
              ? (doc as Record<string, unknown>)
              : {}
          const mergedPlan = mergePlan(root['plan'], next)
          const updated: Record<string, unknown> = { ...root, plan: mergedPlan }
          return YAML.stringify(updated)
        } catch {
          // If YAML is invalid, do not attempt to rewrite it here.
          return current
        }
      })
    },
    [],
  )

  const syncTemplateToYaml = useCallback((next: TemplateNode) => {
    setRawYaml((current) => {
      if (!current) return current
      try {
        const doc = YAML.parse(current) || {}
        const root =
          doc && typeof doc === 'object' && !Array.isArray(doc)
            ? (doc as Record<string, unknown>)
            : {}
        if (next == null) {
          if ('template' in root) {
            // eslint-disable-next-line @typescript-eslint/no-dynamic-delete
            delete (root as Record<string, unknown>).template
          }
        } else {
          ;(root as Record<string, unknown>).template = next
        }
        return YAML.stringify(root)
      } catch {
        return current
      }
    })
  }, [])

  const syncTopologyToYaml = useCallback((next: TopologyNode) => {
    setRawYaml((current) => {
      if (!current) return current
      try {
        const doc = YAML.parse(current) || {}
        const root =
          doc && typeof doc === 'object' && !Array.isArray(doc)
            ? (doc as Record<string, unknown>)
            : {}
        if (next == null) {
          if ('topology' in root) {
            // eslint-disable-next-line @typescript-eslint/no-dynamic-delete
            delete (root as Record<string, unknown>).topology
          }
        } else {
          ;(root as Record<string, unknown>).topology = next
        }
        return YAML.stringify(root)
      } catch {
        return current
      }
    })
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
          syncPlanToYaml(next)
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
        syncPlanToYaml(next)
        return { stack: trimmed, index }
      })
    },
    [syncPlanToYaml],
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
      syncPlanToYaml(plan)
      return { ...state, index }
    })
  }, [syncPlanToYaml])

  const handleRedoPlan = useCallback(() => {
    setPlanHistoryState((state) => {
      if (state.index < 0 || state.index >= state.stack.length - 1) {
        return state
      }
      const index = state.index + 1
      const plan = state.stack[index]
      setPlanDraft(plan)
      syncPlanToYaml(plan)
      return { ...state, index }
    })
  }, [syncPlanToYaml])

  const applyTemplateUpdate = useCallback(
    (updater: (current: TemplateNode) => TemplateNode) => {
      setTemplateDraft((current) => {
        const next = updater(current)
        syncTemplateToYaml(next)
        return next
      })
    },
    [syncTemplateToYaml],
  )

  const applyTopologyUpdate = useCallback(
    (updater: (current: TopologyNode) => TopologyNode) => {
      setTopologyDraft((current) => {
        const next = updater(current)
        syncTopologyToYaml(next)
        return next
      })
    },
    [syncTopologyToYaml],
  )

  const handleGlobalUndo = useCallback(() => {
    if (!selectedId) return
    if (viewMode === 'yaml') {
      const editor = yamlEditorRef.current
      if (editor) {
        editor.trigger('toolbar', 'undo', null)
      }
      return
    }
    if (viewMode === 'plan') {
      handleUndoPlan()
    }
  }, [handleUndoPlan, selectedId, viewMode])

  const handleGlobalRedo = useCallback(() => {
    if (!selectedId) return
    if (viewMode === 'yaml') {
      const editor = yamlEditorRef.current
      if (editor) {
        editor.trigger('toolbar', 'redo', null)
      }
      return
    }
    if (viewMode === 'plan') {
      handleRedoPlan()
    }
  }, [handleRedoPlan, selectedId, viewMode])

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

  const rebuildFromYaml = useCallback(
    (text: string) => {
      try {
        const doc = YAML.parse(text)
        if (doc && typeof doc === 'object' && !Array.isArray(doc)) {
          const root = doc as Record<string, unknown>
          const planNode = root['plan']
          const builtPlan = buildPlanView(planNode)
          const normalised = normalisePlanTimes(builtPlan)
          const initialPlan =
            normalised ?? {
              swarm: [],
              bees: [],
            }
          resetPlanHistory(initialPlan)
          const templateNode = root['template']
          if (templateNode && typeof templateNode === 'object' && !Array.isArray(templateNode)) {
            setTemplateDraft(templateNode as Record<string, unknown>)
          } else {
            setTemplateDraft(null)
          }
          const topologyNode = root['topology']
          if (topologyNode && typeof topologyNode === 'object' && !Array.isArray(topologyNode)) {
            setTopologyDraft(topologyNode as Record<string, unknown>)
          } else {
            setTopologyDraft(null)
          }
        } else {
          resetPlanHistory({
            swarm: [],
            bees: [],
          })
          setTemplateDraft(null)
          setTopologyDraft(null)
        }
      } catch {
        // Leave existing plan/template when YAML is invalid.
      }
    },
    [buildPlanView, normalisePlanTimes, resetPlanHistory],
  )

  useEffect(() => {
    const id = selectedId
    if (!id) {
      setSelectedScenario(null)
      setRawYaml('')
      setSavedYaml('')
      setRawError(null)
      resetPlanHistory(null)
      setTemplateDraft(null)
      setTopologyDraft(null)
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
            rebuildFromYaml(text)
          }
        } catch (e) {
          if (!cancelled) {
            setRawError(e instanceof Error ? e.message : 'Failed to load scenario YAML')
            setRawYaml('')
            setSavedYaml('')
            resetPlanHistory(null)
            setTemplateDraft(null)
            setTopologyDraft(null)
          }
        } finally {
          if (!cancelled) {
            setRawLoading(false)
          }
        }
        if (!cancelled) {
          setViewMode('plan')
        }
      } catch {
        if (!cancelled) {
          setSelectedScenario(null)
          resetPlanHistory(null)
          setTemplateDraft(null)
        }
      }
    }
    void load()
    return () => {
      cancelled = true
    }
  }, [selectedId, rebuildFromYaml, resetPlanHistory])

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
      rebuildFromYaml(rawYaml)
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

  const resolveBeeTemplateConfig = useCallback(
    (beeIndex: number): Record<string, unknown> | undefined => {
      if (!selectedScenario?.template || !planDraft) return undefined
      const tpl = selectedScenario.template
      const beePlan = planDraft.bees[beeIndex]
      if (!beePlan) return undefined
      const tplBees = tpl.bees ?? []
      if (beePlan.instanceId) {
        const matches = tplBees.filter(
          (b) => b.instanceId && b.instanceId === beePlan.instanceId,
        )
        if (
          matches.length === 1 &&
          matches[0]?.config &&
          typeof matches[0].config === 'object'
        ) {
          return matches[0].config as Record<string, unknown>
        }
        return undefined
      }
      if (beePlan.role) {
        const matches = tplBees.filter(
          (b) => b.role && b.role === beePlan.role,
        )
        if (
          matches.length === 1 &&
          matches[0]?.config &&
          typeof matches[0].config === 'object'
        ) {
          return matches[0].config as Record<string, unknown>
        }
      }
      return undefined
    },
    [planDraft, selectedScenario],
  )

  const openSchemaEditor = useCallback(
    async (beeIndex: number) => {
      if (!selectedId || !templateDraft) {
        setToast('No scenario/template selected for schema editing')
        return
      }
      if (Array.isArray(templateDraft) || typeof templateDraft !== 'object') {
        setToast('Swarm template is not available for schema editing')
        return
      }
      const root = templateDraft as Record<string, unknown>
      const beesValue = root['bees']
      if (!Array.isArray(beesValue) || beeIndex < 0 || beeIndex >= beesValue.length) {
        setToast('Swarm template does not contain this bee')
        return
      }
      const beeEntry = beesValue[beeIndex]
      if (!beeEntry || typeof beeEntry !== 'object' || Array.isArray(beeEntry)) {
        setToast('Bee configuration is not editable as an object')
        return
      }
      const bee = beeEntry as Record<string, unknown>
      const configValue = bee['config']
      const config =
        configValue && typeof configValue === 'object' && !Array.isArray(configValue)
          ? (configValue as Record<string, unknown>)
          : undefined
      const workerRaw = config && config['worker']
      const worker =
        workerRaw && typeof workerRaw === 'object' && !Array.isArray(workerRaw)
          ? (workerRaw as Record<string, unknown>)
          : undefined
      const messageRaw = worker && worker['message']
      const message =
        messageRaw && typeof messageRaw === 'object' && !Array.isArray(messageRaw)
          ? (messageRaw as Record<string, unknown>)
          : undefined
      const schemaRefValue =
        message && typeof message.schemaRef === 'string' ? message.schemaRef.trim() : ''

      let pointer: string | null = null
      let schemaPathFromRef: string | null = null
      if (schemaRefValue) {
        const [schemaPathRaw, pointerRaw] = schemaRefValue.split('#', 2)
        const trimmedPath = schemaPathRaw.trim()
        if (trimmedPath.length > 0) {
          schemaPathFromRef = trimmedPath
        }
        if (pointerRaw && pointerRaw.trim().length > 0) {
          pointer = `#${pointerRaw.trim()}`
        }
      }

      let schemaOptions: string[]
      try {
        schemaOptions = await listScenarioSchemas(selectedId)
      } catch {
        setToast('Failed to list schema files for this scenario')
        return
      }

      if (!schemaRefValue) {
        setSchemaAttachState({
          target: 'generator',
          beeIndex,
          templatePath: undefined,
          existingOptions: schemaOptions,
          selectedExisting: schemaOptions.length > 0 ? schemaOptions[0] : null,
          mode: schemaOptions.length > 0 ? 'existing' : 'new',
          newPath: 'schemas/body.schema.json',
          newSchemaText: '',
        })
        setSchemaAttachError(null)
        setSchemaAttachBusy(false)
        return
      }

      if (!schemaOptions || schemaOptions.length === 0) {
        setToast('No schema files found in this scenario bundle (expected under schemas/)')
        return
      }
      const schemaPath =
        schemaPathFromRef && schemaOptions.includes(schemaPathFromRef)
          ? schemaPathFromRef
          : schemaOptions[0]

      let schemaText: string
      try {
        schemaText = await fetchScenarioSchema(selectedId, schemaPath)
      } catch (e) {
        setToast(
          e instanceof Error
            ? `Failed to load schema: ${e.message}`
            : 'Failed to load schema for this scenario',
        )
        return
      }
      let schemaRoot: unknown
      try {
        const parsed = JSON.parse(schemaText) as unknown
        if (!pointer) {
          schemaRoot = parsed
        } else {
          // Very small JSON Pointer resolver for object schemas.
          const ptr = pointer.startsWith('#') ? pointer.slice(1) : pointer
          const segments = ptr.split('/').filter((s) => s.length > 0)
          let cursor: unknown = parsed
          for (const segment of segments) {
            if (typeof cursor !== 'object' || cursor === null || Array.isArray(cursor)) {
              cursor = undefined
              break
            }
            const key = segment.replace(/~1/g, '/').replace(/~0/g, '~')
            cursor = (cursor as Record<string, unknown>)[key]
          }
          schemaRoot = cursor
        }
      } catch (e) {
        setToast(
          e instanceof Error ? `Failed to parse schema JSON: ${e.message}` : 'Schema JSON is invalid',
        )
        return
      }
      if (!schemaRoot || typeof schemaRoot !== 'object' || Array.isArray(schemaRoot)) {
        setToast('Schema root for schemaRef must be an object with properties')
        return
      }
      const schemaObj = schemaRoot as Record<string, unknown>
      const propertiesRaw = schemaObj.properties
      if (!propertiesRaw || typeof propertiesRaw !== 'object' || Array.isArray(propertiesRaw)) {
        setToast('Schema root does not declare any properties to edit')
        return
      }
      const props = propertiesRaw as Record<string, unknown>
      const fields: { name: string; description: string | null }[] = []
      Object.entries(props).forEach(([name, value]) => {
        if (!name || !value || typeof value !== 'object' || Array.isArray(value)) {
          return
        }
        const propSchema = value as Record<string, unknown>
        const description =
          typeof propSchema.description === 'string' && propSchema.description.trim().length > 0
            ? propSchema.description.trim()
            : null
        fields.push({ name, description })
      })
      if (fields.length === 0) {
        setToast('Schema has no simple properties to edit')
        return
      }

      // Derive initial values from the existing message.body when it is valid JSON.
      let initialValues: Record<string, string> = {}
      const bodyValue = message && typeof message.body === 'string' ? message.body : ''
      if (bodyValue && bodyValue.trim().length > 0) {
        try {
          const parsedBody = JSON.parse(bodyValue) as unknown
          if (parsedBody && typeof parsedBody === 'object' && !Array.isArray(parsedBody)) {
            const obj = parsedBody as Record<string, unknown>
            const map: Record<string, string> = {}
            fields.forEach((field) => {
              const raw = obj[field.name]
              if (raw === null || raw === undefined) {
                return
              }
              if (typeof raw === 'string') {
                map[field.name] = raw
              } else if (typeof raw === 'number' || typeof raw === 'boolean') {
                map[field.name] = String(raw)
              } else {
                try {
                  map[field.name] = JSON.stringify(raw)
                } catch {
                  // ignore
                }
              }
            })
            initialValues = map
          }
        } catch {
          // Ignore parse errors; start with empty values.
        }
      }

      setSchemaEditorState({
        kind: 'generator',
        beeIndex,
        templatePath: undefined,
        schemaPath,
        pointer,
        fields,
        initialValues,
        schemaOptions,
        rawSchema: schemaText,
        templateRaw: undefined,
      })
      setSchemaEditorValues(initialValues)
      setSchemaEditorError(null)
    },
    [fetchScenarioSchema, listScenarioSchemas, selectedId, setToast, templateDraft],
  )

  const openHttpTemplateSchemaEditor = useCallback(async (templatePathOverride?: string | null) => {
    if (!selectedId) {
      setToast('No scenario selected for HTTP template editing')
      return
    }
    let templatePaths: string[]
    try {
      templatePaths = await listHttpTemplates(selectedId)
    } catch (e) {
      setToast(
        e instanceof Error ? `Failed to list HTTP templates: ${e.message}` : 'Failed to list HTTP templates',
      )
      return
    }
    if (!templatePaths || templatePaths.length === 0) {
      setToast('No HTTP templates found in this scenario bundle (expected under http-templates/)')
      return
    }
    const templatePath =
      templatePathOverride && templatePaths.includes(templatePathOverride)
        ? templatePathOverride
        : templatePaths[0]
    let templateText: string
    try {
      templateText = await fetchHttpTemplate(selectedId, templatePath)
    } catch (e) {
      setToast(
        e instanceof Error ? `Failed to load HTTP template: ${e.message}` : 'Failed to load HTTP template',
      )
      return
    }
    let templateDoc: unknown
    try {
      templateDoc = YAML.parse(templateText) ?? {}
    } catch (e) {
      setToast(
        e instanceof Error ? `Failed to parse HTTP template YAML: ${e.message}` : 'Failed to parse HTTP template YAML',
      )
      return
    }
    if (!templateDoc || typeof templateDoc !== 'object' || Array.isArray(templateDoc)) {
      setToast('HTTP template must be a YAML object')
      return
    }
    const templateObj = templateDoc as Record<string, unknown>
    const schemaRefValue =
      typeof templateObj.schemaRef === 'string' ? (templateObj.schemaRef as string).trim() : ''

    let pointer: string | null = null
    let schemaPathFromRef: string | null = null
    if (schemaRefValue) {
      const [schemaPathRaw, pointerRaw] = schemaRefValue.split('#', 2)
      const trimmedPath = schemaPathRaw.trim()
      if (trimmedPath.length > 0) {
        schemaPathFromRef = trimmedPath
      }
      if (pointerRaw && pointerRaw.trim().length > 0) {
        pointer = `#${pointerRaw.trim()}`
      }
    }

    let schemaOptions: string[]
    try {
      schemaOptions = await listScenarioSchemas(selectedId)
    } catch (e) {
      setToast(
        e instanceof Error ? `Failed to list schema files: ${e.message}` : 'Failed to list schema files',
      )
      return
    }

    if (!schemaRefValue) {
      setSchemaAttachState({
        target: 'http-template',
        beeIndex: null,
        templatePath,
        existingOptions: schemaOptions,
        selectedExisting: schemaOptions.length > 0 ? schemaOptions[0] : null,
        mode: schemaOptions.length > 0 ? 'existing' : 'new',
        newPath: 'schemas/http-body.schema.json',
        newSchemaText: '',
      })
      setSchemaAttachError(null)
      setSchemaAttachBusy(false)
      return
    }

    if (!schemaOptions || schemaOptions.length === 0) {
      setToast('No schema files found for this scenario (expected under schemas/)')
      return
    }
    const schemaPath =
      schemaPathFromRef && schemaOptions.includes(schemaPathFromRef)
        ? schemaPathFromRef
        : schemaOptions[0]

    let schemaText: string
    try {
      schemaText = await fetchScenarioSchema(selectedId, schemaPath)
    } catch (e) {
      setToast(
        e instanceof Error ? `Failed to load schema: ${e.message}` : 'Failed to load schema for this scenario',
      )
      return
    }

    let schemaRoot: unknown
    try {
      const parsed = JSON.parse(schemaText) as unknown
      if (!pointer) {
        schemaRoot = parsed
      } else {
        const ptr = pointer.startsWith('#') ? pointer.slice(1) : pointer
        const segments = ptr.split('/').filter((s) => s.length > 0)
        let cursor: unknown = parsed
        for (const segment of segments) {
          if (typeof cursor !== 'object' || cursor === null || Array.isArray(cursor)) {
            cursor = undefined
            break
          }
          const key = segment.replace(/~1/g, '/').replace(/~0/g, '~')
          cursor = (cursor as Record<string, unknown>)[key]
        }
        schemaRoot = cursor
      }
    } catch (e) {
      setToast(
        e instanceof Error ? `Failed to parse schema JSON: ${e.message}` : 'Schema JSON is invalid',
      )
      return
    }
    if (!schemaRoot || typeof schemaRoot !== 'object' || Array.isArray(schemaRoot)) {
      setToast('Schema root for HTTP template must be an object with properties')
      return
    }
    const schemaObj = schemaRoot as Record<string, unknown>
    const propertiesRaw = schemaObj.properties
    if (!propertiesRaw || typeof propertiesRaw !== 'object' || Array.isArray(propertiesRaw)) {
      setToast('Schema root does not declare any properties to edit')
      return
    }
    const props = propertiesRaw as Record<string, unknown>
    const fields: { name: string; description: string | null }[] = []
    Object.entries(props).forEach(([name, value]) => {
      if (!name || !value || typeof value !== 'object' || Array.isArray(value)) {
        return
      }
      const propSchema = value as Record<string, unknown>
      const description =
        typeof propSchema.description === 'string' && propSchema.description.trim().length > 0
          ? propSchema.description.trim()
          : null
      fields.push({ name, description })
    })
    if (fields.length === 0) {
      setToast('Schema has no simple properties to edit')
      return
    }

    let initialValues: Record<string, string> = {}
    const bodyTemplateValue =
      typeof templateObj.bodyTemplate === 'string' ? (templateObj.bodyTemplate as string) : ''
    if (bodyTemplateValue && bodyTemplateValue.trim().length > 0) {
      try {
        const parsedBody = JSON.parse(bodyTemplateValue) as unknown
        if (parsedBody && typeof parsedBody === 'object' && !Array.isArray(parsedBody)) {
          const obj = parsedBody as Record<string, unknown>
          const map: Record<string, string> = {}
          fields.forEach((field) => {
            const raw = obj[field.name]
            if (raw === null || raw === undefined) {
              return
            }
            if (typeof raw === 'string') {
              map[field.name] = raw
            } else if (typeof raw === 'number' || typeof raw === 'boolean') {
              map[field.name] = String(raw)
            } else {
              try {
                map[field.name] = JSON.stringify(raw)
              } catch {
                // ignore
              }
            }
          })
          initialValues = map
        }
      } catch {
        // ignore parse errors
      }
    }

    setSchemaEditorState({
      kind: 'http-template',
      beeIndex: null,
      templatePath,
      schemaPath,
      pointer,
      fields,
      initialValues,
      schemaOptions,
      rawSchema: schemaText,
      templateRaw: templateText,
    })
    setSchemaEditorValues(initialValues)
    setSchemaEditorError(null)
    setSchemaEditorShowRaw(false)
  }, [fetchHttpTemplate, fetchScenarioSchema, listHttpTemplates, listScenarioSchemas, selectedId, setToast])

  const applySchemaAttach = useCallback(async () => {
    if (!schemaAttachState || !selectedId) {
      setSchemaAttachError('No scenario selected for schema attachment')
      return
    }
    if (schemaAttachBusy) {
      return
    }
    const { target, beeIndex, templatePath, mode, selectedExisting, newPath, newSchemaText } =
      schemaAttachState

    let schemaPath: string
    if (mode === 'existing') {
      const chosen = selectedExisting && selectedExisting.trim()
      if (!chosen) {
        setSchemaAttachError('Select a schema file to attach')
        return
      }
      schemaPath = chosen
    } else {
      let relativePath = (newPath || '').trim()
      if (!relativePath) {
        setSchemaAttachError('Schema file path is required')
        return
      }
      if (relativePath.startsWith('/') || relativePath.includes('..')) {
        setSchemaAttachError(
          'Schema file path must be relative to the bundle (for example schemas/body.schema.json)',
        )
        return
      }
      if (!relativePath.startsWith('schemas/')) {
        relativePath = `schemas/${relativePath}`
      }
      const text = newSchemaText.trim()
      if (!text) {
        setSchemaAttachError('Schema JSON is required')
        return
      }
      let normalized: string
      try {
        const parsed = JSON.parse(text) as unknown
        normalized = JSON.stringify(parsed, null, 2)
      } catch (e) {
        setSchemaAttachError(
          e instanceof Error ? `Schema JSON is invalid: ${e.message}` : 'Schema JSON is invalid',
        )
        return
      }
      setSchemaAttachBusy(true)
      try {
        await saveScenarioSchema(selectedId, relativePath, normalized)
      } catch (error) {
        setSchemaAttachBusy(false)
        setSchemaAttachError(
          error instanceof Error ? `Failed to save schema: ${error.message}` : 'Failed to save schema',
        )
        return
      }
      setSchemaAttachBusy(false)
      schemaPath = relativePath
    }

    if (target === 'generator') {
      if (beeIndex == null || beeIndex < 0) {
        setSchemaAttachError('Internal error: missing bee index for generator schema attachment')
        return
      }
      applyTemplateUpdate((current) => {
        const base =
          current && typeof current === 'object' && !Array.isArray(current)
            ? { ...(current as Record<string, unknown>) }
            : {}
        const beesRaw = Array.isArray((base as Record<string, unknown>).bees)
          ? ([...(base as Record<string, unknown>).bees as unknown[]] as unknown[])
          : ([] as unknown[])
        if (beeIndex < 0 || beeIndex >= beesRaw.length) {
          return base
        }
        const existing =
          beesRaw[beeIndex] &&
          typeof beesRaw[beeIndex] === 'object' &&
          !Array.isArray(beesRaw[beeIndex])
            ? { ...(beesRaw[beeIndex] as Record<string, unknown>) }
            : {}
        const configRaw = existing.config
        const config =
          configRaw && typeof configRaw === 'object' && !Array.isArray(configRaw)
            ? { ...(configRaw as Record<string, unknown>) }
            : {}
        const workerRaw = config.worker
        const worker =
          workerRaw && typeof workerRaw === 'object' && !Array.isArray(workerRaw)
            ? { ...(workerRaw as Record<string, unknown>) }
            : {}
        const messageRaw = worker.message
        const message =
          messageRaw && typeof messageRaw === 'object' && !Array.isArray(messageRaw)
            ? { ...(messageRaw as Record<string, unknown>) }
            : {}
        message.schemaRef = schemaPath
        worker.message = message
        config.worker = worker
        existing.config = config
        beesRaw[beeIndex] = existing
        ;(base as Record<string, unknown>).bees = beesRaw
        return base
      })
    } else if (target === 'http-template') {
      if (!templatePath) {
        setSchemaAttachError('Internal error: missing template path for HTTP template')
        return
      }
      let raw: string
      try {
        raw = await fetchHttpTemplate(selectedId, templatePath)
      } catch (error) {
        setSchemaAttachError(
          error instanceof Error
            ? `Failed to load HTTP template: ${error.message}`
            : 'Failed to load HTTP template',
        )
        return
      }
      let doc: unknown
      try {
        doc = YAML.parse(raw) ?? {}
      } catch (e) {
        setSchemaAttachError(
          e instanceof Error ? `Failed to parse HTTP template YAML: ${e.message}` : 'Failed to parse HTTP template YAML',
        )
        return
      }
      if (!doc || typeof doc !== 'object' || Array.isArray(doc)) {
        setSchemaAttachError('HTTP template must be a YAML object')
        return
      }
      const obj = doc as Record<string, unknown>
      obj.schemaRef = schemaPath
      let updatedYaml: string
      try {
        updatedYaml = YAML.stringify(obj)
      } catch (e) {
        setSchemaAttachError(
          e instanceof Error
            ? `Failed to serialise HTTP template YAML: ${e.message}`
            : 'Failed to serialise HTTP template YAML',
        )
        return
      }
      try {
        await saveHttpTemplate(selectedId, templatePath, updatedYaml)
        if (viewMode === 'httpTemplates' && templatePath === httpTemplateSelectedPath) {
          setHttpTemplateRaw(updatedYaml)
        }
      } catch (error) {
        setSchemaAttachError(
          error instanceof Error ? `Failed to save HTTP template: ${error.message}` : 'Failed to save HTTP template',
        )
        return
      }
    }

    setSchemaAttachState(null)
    setSchemaAttachError(null)
  }, [
    applyTemplateUpdate,
    fetchHttpTemplate,
    saveHttpTemplate,
    saveScenarioSchema,
    schemaAttachBusy,
    schemaAttachState,
    selectedId,
    httpTemplateSelectedPath,
    setHttpTemplateRaw,
    viewMode,
  ])

  const applySchemaEditor = useCallback(() => {
    if (!schemaEditorState) {
      setSchemaEditorError(null)
      return
    }
    const { kind, beeIndex, fields, schemaPath, pointer, templatePath, templateRaw } = schemaEditorState
    const bodyObject: Record<string, unknown> = {}
      fields.forEach((field) => {
        const raw = schemaEditorValues[field.name]
        if (raw === undefined || raw === '') {
          return
        }
      // Always store as string so templating markers like {{ var }} are preserved.
      bodyObject[field.name] = raw
    })
    let bodyJson = '{}'
    try {
      bodyJson = JSON.stringify(bodyObject, null, 2)
    } catch (e) {
      setSchemaEditorError(
        e instanceof Error ? `Failed to serialise body as JSON: ${e.message}` : 'Failed to serialise body as JSON',
      )
      return
    }
    if (kind === 'generator') {
      if (beeIndex == null || beeIndex < 0) {
        setSchemaEditorError('Internal error: missing bee index for generator schema editor')
        return
      }
      applyTemplateUpdate((current) => {
        const base =
          current && typeof current === 'object' && !Array.isArray(current)
            ? { ...(current as Record<string, unknown>) }
            : {}
        const beesRaw = Array.isArray((base as Record<string, unknown>).bees)
          ? ([...(base as Record<string, unknown>).bees as unknown[]] as unknown[])
          : ([] as unknown[])
        if (beeIndex < 0 || beeIndex >= beesRaw.length) {
          return base
        }
        const existing =
          beesRaw[beeIndex] &&
          typeof beesRaw[beeIndex] === 'object' &&
          !Array.isArray(beesRaw[beeIndex])
            ? { ...(beesRaw[beeIndex] as Record<string, unknown>) }
            : {}
        const configRaw = existing.config
        const config =
          configRaw && typeof configRaw === 'object' && !Array.isArray(configRaw)
            ? { ...(configRaw as Record<string, unknown>) }
            : {}
        const workerRaw = config.worker
        const worker =
          workerRaw && typeof workerRaw === 'object' && !Array.isArray(workerRaw)
            ? { ...(workerRaw as Record<string, unknown>) }
            : {}
        const messageRaw = worker.message
        const message =
          messageRaw && typeof messageRaw === 'object' && !Array.isArray(messageRaw)
            ? { ...(messageRaw as Record<string, unknown>) }
            : {}
        const ref =
          pointer && pointer.length > 0
            ? `${schemaPath}${pointer}`
            : schemaPath
        message.schemaRef = ref
        message.body = bodyJson
        worker.message = message
        config.worker = worker
        existing.config = config
        beesRaw[beeIndex] = existing
        ;(base as Record<string, unknown>).bees = beesRaw
        return base
      })
    } else if (kind === 'http-template') {
      if (!selectedId || !templatePath) {
        setSchemaEditorError('Internal error: missing template path for HTTP template editor')
        return
      }
      const raw = templateRaw ?? ''
      if (!raw) {
        setSchemaEditorError('Internal error: missing template content for HTTP template editor')
        return
      }
      let doc: unknown
      try {
        doc = YAML.parse(raw) ?? {}
      } catch (e) {
        setSchemaEditorError(
          e instanceof Error ? `Failed to parse HTTP template YAML: ${e.message}` : 'Failed to parse HTTP template YAML',
        )
        return
      }
      if (!doc || typeof doc !== 'object' || Array.isArray(doc)) {
        setSchemaEditorError('HTTP template must be a YAML object')
        return
      }
      const obj = doc as Record<string, unknown>
      const ref = pointer && pointer.length > 0 ? `${schemaPath}${pointer}` : schemaPath
      obj.schemaRef = ref
      obj.bodyTemplate = bodyJson
      let updatedYaml: string
      try {
        updatedYaml = YAML.stringify(obj)
      } catch (e) {
        setSchemaEditorError(
          e instanceof Error ? `Failed to serialise HTTP template YAML: ${e.message}` : 'Failed to serialise HTTP template YAML',
        )
        return
      }
      setHttpTemplateRaw((current) => {
        if (viewMode === 'httpTemplates' && templatePath === httpTemplateSelectedPath) {
          return updatedYaml
        }
        return current
      })
      void saveHttpTemplate(selectedId, templatePath, updatedYaml).catch((error) => {
        setSchemaEditorError(
          error instanceof Error ? `Failed to save HTTP template: ${error.message}` : 'Failed to save HTTP template',
        )
      })
    }
    setSchemaEditorState(null)
    setSchemaEditorValues({})
    setSchemaEditorError(null)
  }, [applyTemplateUpdate, saveHttpTemplate, schemaEditorState, schemaEditorValues, selectedId])

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

  const inferIoTypeFromConfig = useCallback(
    (componentConfig: Record<string, unknown> | undefined): string | undefined => {
      if (!componentConfig) return undefined
      const inputsRaw = componentConfig['inputs']
      if (!inputsRaw || typeof inputsRaw !== 'object' || Array.isArray(inputsRaw)) {
        return undefined
      }
      const inputs = inputsRaw as Record<string, unknown>
      const inputTypeRaw = inputs['type']
      const inputType =
        typeof inputTypeRaw === 'string' ? inputTypeRaw.trim().toUpperCase() : undefined
      if (inputType === 'SCHEDULER' || inputType === 'REDIS_DATASET') {
        return inputType
      }
      const hasScheduler =
        inputs.scheduler && typeof inputs.scheduler === 'object' && !Array.isArray(inputs.scheduler)
      const hasRedis =
        inputs.redis && typeof inputs.redis === 'object' && !Array.isArray(inputs.redis)
      if (hasScheduler) {
        return 'SCHEDULER'
      }
      if (hasRedis) {
        return 'REDIS_DATASET'
      }
      return undefined
    },
    [],
  )

  const buildConfigEntriesForComponent = useCallback(
    (
      manifest: CapabilityManifest,
      componentConfig: Record<string, unknown> | undefined,
    ): CapabilityConfigEntry[] => {
      const baseEntries = Array.isArray(manifest.config) ? manifest.config : []
      const ioType = inferIoTypeFromConfig(componentConfig)
      if (!ioType) {
        return baseEntries
      }
      const allEntries: CapabilityConfigEntry[] = [...baseEntries]
      for (const m of manifests) {
        const ui = m.ui as Record<string, unknown> | undefined
        const ioTypeRaw = ui && typeof ui.ioType === 'string' ? ui.ioType : undefined
        const manifestIoType = ioTypeRaw ? ioTypeRaw.trim().toUpperCase() : undefined
        if (manifestIoType && manifestIoType === ioType && Array.isArray(m.config)) {
          allEntries.push(...m.config)
        }
      }
      const byName = new Map<string, CapabilityConfigEntry>()
      for (const entry of allEntries) {
        if (!byName.has(entry.name)) {
          byName.set(entry.name, entry)
        }
      }
      return Array.from(byName.values())
    },
    [inferIoTypeFromConfig, manifests],
  )

  useEffect(() => {
    if (!selectedId || viewMode !== 'httpTemplates') {
      setHttpTemplatePaths([])
      setHttpTemplateSelectedPath(null)
      setHttpTemplateRaw('')
      setHttpTemplateError(null)
      setHttpTemplateLoading(false)
      setHttpTemplateSaving(false)
      return
    }
    let cancelled = false
    const load = async () => {
      setHttpTemplateLoading(true)
      setHttpTemplateError(null)
      try {
        const paths = await listHttpTemplates(selectedId)
        if (cancelled) return
        setHttpTemplatePaths(paths)
        if (paths.length === 0) {
          setHttpTemplateSelectedPath(null)
          setHttpTemplateRaw('')
          return
        }
        const first = paths[0]!
        setHttpTemplateSelectedPath(first)
        try {
          const text = await fetchHttpTemplate(selectedId, first)
          if (cancelled) return
          setHttpTemplateRaw(text)
        } catch (e) {
          if (cancelled) return
          setHttpTemplateError(
            e instanceof Error ? `Failed to load HTTP template: ${e.message}` : 'Failed to load HTTP template',
          )
        }
      } catch (e) {
        if (cancelled) return
        setHttpTemplateError(
          e instanceof Error ? `Failed to list HTTP templates: ${e.message}` : 'Failed to list HTTP templates',
        )
      } finally {
        if (!cancelled) {
          setHttpTemplateLoading(false)
        }
      }
    }
    void load()
    return () => {
      cancelled = true
    }
  }, [fetchHttpTemplate, listHttpTemplates, selectedId, viewMode])

  useEffect(() => {
    if (!selectedId || viewMode !== 'httpTemplates') {
      setHttpTemplatePaths([])
      setHttpTemplateSelectedPath(null)
      setHttpTemplateRaw('')
      setHttpTemplateError(null)
      setHttpTemplateLoading(false)
      setHttpTemplateSaving(false)
      return
    }
    let cancelled = false
    const load = async () => {
      setHttpTemplateLoading(true)
      setHttpTemplateError(null)
      try {
        const paths = await listHttpTemplates(selectedId)
        if (cancelled) return
        setHttpTemplatePaths(paths)
        if (paths.length === 0) {
          setHttpTemplateSelectedPath(null)
          setHttpTemplateRaw('')
          return
        }
        const first = paths[0]!
        setHttpTemplateSelectedPath(first)
        try {
          const text = await fetchHttpTemplate(selectedId, first)
          if (cancelled) return
          setHttpTemplateRaw(text)
        } catch (e) {
          if (cancelled) return
          setHttpTemplateError(
            e instanceof Error ? `Failed to load HTTP template: ${e.message}` : 'Failed to load HTTP template',
          )
        }
      } catch (e) {
        if (cancelled) return
        setHttpTemplateError(
          e instanceof Error ? `Failed to list HTTP templates: ${e.message}` : 'Failed to list HTTP templates',
        )
      } finally {
        if (!cancelled) {
          setHttpTemplateLoading(false)
        }
      }
    }
    void load()
    return () => {
      cancelled = true
    }
  }, [fetchHttpTemplate, listHttpTemplates, selectedId, viewMode])

  const openConfigModal = useCallback(
    async (target: ConfigTarget) => {
      if (!selectedScenario) {
        return
      }
      let image: string | null = null
      if (target.kind === 'swarm') {
        image = selectedScenario.template?.image ?? null
      } else if (target.kind === 'bee' && target.beeIndex !== null) {
        if (!planDraft) return
        image = resolveBeeImage(target.beeIndex)
      } else if (target.kind === 'template-bee') {
        const templateBees = templateDraft && !Array.isArray(templateDraft) && typeof templateDraft === 'object'
          ? (templateDraft as Record<string, unknown>).bees
          : undefined
        if (Array.isArray(templateBees) && templateBees[target.beeIndex]) {
          const bee = templateBees[target.beeIndex] as Record<string, unknown>
          const tplImage = bee.image
          image = typeof tplImage === 'string' ? tplImage : selectedScenario.template?.image ?? null
        }
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
      let templateComponentConfig: Record<string, unknown> | undefined
      let baseConfig: Record<string, unknown> | undefined
      if (target.kind === 'bee' && target.beeIndex !== null) {
        templateComponentConfig = resolveBeeTemplateConfig(target.beeIndex)
        const beeIndex = target.beeIndex
        const bee = planDraft!.bees[beeIndex]
        const templateConfig = templateComponentConfig
        let accumulated = templateConfig && isPlainObject(templateConfig) ? { ...templateConfig } : undefined
        if (bee) {
          bee.steps.forEach((step, index) => {
            if (index >= target.stepIndex) return
            if (step.type !== 'config-update') return
            if (!step.config || !isPlainObject(step.config)) return
            accumulated = mergeConfig(accumulated, step.config as Record<string, unknown>)
          })
        }
        baseConfig = accumulated
      } else if (target.kind === 'template-bee') {
        const templateBees =
          templateDraft && !Array.isArray(templateDraft) && typeof templateDraft === 'object'
            ? (templateDraft as Record<string, unknown>).bees
            : undefined
        if (Array.isArray(templateBees) && templateBees[target.beeIndex]) {
          const bee = templateBees[target.beeIndex] as Record<string, unknown>
          const configValue = bee.config
          if (configValue && typeof configValue === 'object' && !Array.isArray(configValue)) {
            templateComponentConfig = configValue as Record<string, unknown>
            baseConfig = templateComponentConfig
          }
        }
      }

      const entries = buildConfigEntriesForComponent(manifest, baseConfig ?? templateComponentConfig)
      if (entries.length === 0) {
        setToast('No configurable options defined for this component')
        return
      }
      const step =
        target.kind === 'swarm' || target.kind === 'bee' ? getStepAtTarget(target) : null
      const stepConfig =
        step && step.config && typeof step.config === 'object'
          ? (step.config as Record<string, unknown>)
          : target.kind === 'template-bee'
            ? baseConfig
            : undefined
      const baseConfigForDisplay =
        baseConfig ?? templateComponentConfig ?? undefined
      setConfigPatchModalState({
        target,
        imageLabel: image,
        entries,
        baseConfig: baseConfigForDisplay,
        existingPatch: stepConfig,
      })
    },
    [
      planDraft,
      selectedScenario,
      resolveBeeImage,
      resolveBeeTemplateConfig,
      ensureCapabilities,
      getManifestForImage,
      getStepAtTarget,
      buildConfigEntriesForComponent,
      getValueForPath,
      setToast,
    ],
  )

  const applyConfigPatchModal = useCallback(
    (patch: Record<string, unknown> | undefined) => {
      if (!configPatchModalState) {
        setConfigPatchModalState(null)
        return
      }
      const target = configPatchModalState.target
      const hasConfig = patch && Object.keys(patch).length > 0

      if (target.kind === 'template-bee') {
        applyTemplateUpdate((current) => {
          const base =
            current && typeof current === 'object' && !Array.isArray(current)
              ? { ...(current as Record<string, unknown>) }
              : {}
          const beesRaw = Array.isArray((base as Record<string, unknown>).bees)
            ? ([...(base as Record<string, unknown>).bees as unknown[]] as unknown[])
            : ([] as unknown[])
          if (target.beeIndex < 0 || target.beeIndex >= beesRaw.length) {
            return base
          }
          const existing =
            beesRaw[target.beeIndex] &&
            typeof beesRaw[target.beeIndex] === 'object' &&
            !Array.isArray(beesRaw[target.beeIndex])
              ? { ...(beesRaw[target.beeIndex] as Record<string, unknown>) }
              : {}
          existing.config = hasConfig ? patch : undefined
          beesRaw[target.beeIndex] = existing
          ;(base as Record<string, unknown>).bees = beesRaw
          return base
        })
      } else {
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
      }
      setConfigPatchModalState(null)
    },
    [applyPlanUpdate, applyTemplateUpdate, configPatchModalState],
  )

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
    <div className="flex flex-col md:flex-row h-full min-h-0 bg-[#05070b] text-white">
      <div className="w-full md:w-72 border-b md:border-b-0 md:border-r border-white/10 px-4 py-4 space-y-3">
        <div className="flex items-center justify-between">
          <h1 className="text-sm font-semibold text-white/90">Scenarios</h1>
          <button
            type="button"
            className="text-xs text-sky-300 hover:text-sky-200 transition"
            onClick={() => navigate(-1)}
          >
            Back
          </button>
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
      <div className="flex-1 px-3 sm:px-6 py-3 sm:py-4 overflow-y-auto">
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
	                <button
	                  type="button"
	                  className="rounded bg-white/10 px-2 py-1 text-[11px] text-white/80 hover:bg-white/20"
	                  onClick={() => setShowSutsModal(true)}
	                >
	                  SUTs
	                </button>
	                <button
	                  type="button"
	                  className="rounded bg-white/10 px-2 py-1 text-[11px] text-white/80 hover:bg-white/20"
	                  onClick={() => setShowVariablesModal(true)}
	                >
	                  Variables
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
                <div className="ml-4 flex items-center gap-2">
                  <button
                    type="button"
                    className="rounded bg-white/10 px-2 py-1 text-[11px] text-white/80 hover:bg-white/20 disabled:opacity-50"
                    disabled={saving || rawLoading || !hasUnsavedChanges}
                    onClick={() => void handleSave()}
                  >
                    {saving ? 'Saving…' : 'Save YAML'}
                  </button>
                  <button
                    type="button"
                    className="rounded bg-white/5 px-2 py-1 text-[11px] text-white/80 hover:bg-white/15 disabled:opacity-40"
                    disabled={
                      !selectedId ||
                      (viewMode === 'plan' && !canUndoPlan)
                    }
                    onClick={() => handleGlobalUndo()}
                  >
                    Undo
                  </button>
                  <button
                    type="button"
                    className="rounded bg-white/5 px-2 py-1 text-[11px] text-white/80 hover:bg-white/15 disabled:opacity-40"
                    disabled={
                      !selectedId ||
                      (viewMode === 'plan' && !canRedoPlan)
                    }
                    onClick={() => handleGlobalRedo()}
                  >
                    Redo
                  </button>
                </div>
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
              <button
                type="button"
                className={`rounded px-2 py-1 text-[11px] ${
                  viewMode === 'swarm'
                    ? 'bg-white/20 text-white'
                    : 'bg-white/5 text-white/70 hover:bg-white/10'
                }`}
                onClick={() => setViewMode('swarm')}
              >
                Swarm template
              </button>
              <button
                type="button"
                className={`rounded px-2 py-1 text-[11px] ${
                  viewMode === 'httpTemplates'
                    ? 'bg-white/20 text-white'
                    : 'bg-white/5 text-white/70 hover:bg-white/10'
                }`}
                onClick={() => setViewMode('httpTemplates')}
              >
                HTTP templates
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
                    Scenario YAML
                    {hasUnsavedChanges && (
                      <span className="text-[10px] text-amber-300 ml-1">(unsaved changes)</span>
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
                          '      work:',
                          '        out:',
                          '          out: gen',
                          '    - role: processor',
                          '      instanceId: proc-1',
                          '      image: pockethive-processor:latest',
                          '      work:',
                          '        in:',
                          '          in: gen',
                          '        out:',
                          '          out: final',
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
                    {/* Save is now in the shared toolbar */}
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
                    onMount={(editorInstance) => {
                      yamlEditorRef.current = editorInstance
                    }}
                    value={rawYaml}
                    onChange={(value) => {
                      const text = value ?? ''
                      setRawYaml(text)
                      rebuildFromYaml(text)
                    }}
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
            {viewMode === 'swarm' && (
              <div className="border border-white/15 rounded-md p-3 bg-white/5 space-y-3">
                <SwarmTemplateEditor
                  template={templateDraft}
                  onChange={applyTemplateUpdate}
                  onOpenConfig={(beeIndex) =>
                    openConfigModal({ kind: 'template-bee', beeIndex, stepIndex: 0 })
                  }
                  onOpenSchemaEditor={(beeIndex) => void openSchemaEditor(beeIndex)}
                />
                <TopologyEditor
                  topology={topologyDraft}
                  template={templateDraft}
                  onChange={applyTopologyUpdate}
                />
              </div>
            )}
            {viewMode === 'httpTemplates' && (
              <div className="border border-white/15 rounded-md p-3 bg-white/5 space-y-3">
                <div className="flex items-center justify-between mb-2">
                  <h3 className="text-xs font-semibold text-white/80">
                    HTTP templates
                  </h3>
                  <div className="flex items-center gap-2">
                    {httpTemplateSelectedPath && (
                      <button
                        type="button"
                        className="rounded bg-sky-500/20 px-2 py-1 text-[11px] text-sky-100 hover:bg-sky-500/30"
                        onClick={() => void openHttpTemplateSchemaEditor(httpTemplateSelectedPath)}
                      >
                        Edit body via schema
                      </button>
                    )}
                    <button
                      type="button"
                      className="rounded bg-white/10 px-2 py-1 text-[11px] text-white/80 hover:bg-white/20 disabled:opacity-40"
                      disabled={!selectedId || !httpTemplateSelectedPath || httpTemplateSaving}
                      onClick={async () => {
                        if (!selectedId || !httpTemplateSelectedPath) return
                        const nextRaw = window.prompt(
                          'Rename HTTP template (path under http-templates/)',
                          httpTemplateSelectedPath,
                        )
                        if (nextRaw == null) return
                        const trimmed = nextRaw.trim()
                        if (!trimmed) {
                          setToast('Template path cannot be empty')
                          return
                        }
                        const cleaned = trimmed.replace(/^\/+/, '')
                        const nextPath = cleaned.startsWith('http-templates/')
                          ? cleaned
                          : `http-templates/${cleaned}`
                        if (nextPath === httpTemplateSelectedPath) {
                          setToast('Template path unchanged')
                          return
                        }
                        setHttpTemplateSaving(true)
                        setHttpTemplateError(null)
                        try {
                          await renameHttpTemplate(selectedId, httpTemplateSelectedPath, nextPath)
                          setHttpTemplatePaths((current) => {
                            const updated = current.map((entry) =>
                              entry === httpTemplateSelectedPath ? nextPath : entry,
                            )
                            updated.sort((a, b) => a.localeCompare(b))
                            return updated
                          })
                          setHttpTemplateSelectedPath(nextPath)
                        } catch (e) {
                          setHttpTemplateError(
                            e instanceof Error
                              ? `Failed to rename HTTP template: ${e.message}`
                              : 'Failed to rename HTTP template',
                          )
                        } finally {
                          setHttpTemplateSaving(false)
                        }
                      }}
                    >
                      Rename template
                    </button>
                    <button
                      type="button"
                      className="rounded bg-rose-500/20 px-2 py-1 text-[11px] text-rose-100 hover:bg-rose-500/30 disabled:opacity-40"
                      disabled={!selectedId || !httpTemplateSelectedPath || httpTemplateSaving}
                      onClick={async () => {
                        if (!selectedId || !httpTemplateSelectedPath) return
                        const confirmed = window.confirm(
                          `Delete HTTP template "${httpTemplateSelectedPath}"?`,
                        )
                        if (!confirmed) return
                        setHttpTemplateSaving(true)
                        setHttpTemplateError(null)
                        try {
                          await deleteHttpTemplate(selectedId, httpTemplateSelectedPath)
                          const updated = httpTemplatePaths
                            .filter((entry) => entry !== httpTemplateSelectedPath)
                            .sort((a, b) => a.localeCompare(b))
                          setHttpTemplatePaths(updated)
                          const nextPath = updated[0] ?? null
                          setHttpTemplateSelectedPath(nextPath)
                          if (!nextPath) {
                            setHttpTemplateRaw('')
                            return
                          }
                          try {
                            const text = await fetchHttpTemplate(selectedId, nextPath)
                            setHttpTemplateRaw(text)
                          } catch (e) {
                            setHttpTemplateError(
                              e instanceof Error
                                ? `Failed to load HTTP template: ${e.message}`
                                : 'Failed to load HTTP template',
                            )
                          }
                        } catch (e) {
                          setHttpTemplateError(
                            e instanceof Error
                              ? `Failed to delete HTTP template: ${e.message}`
                              : 'Failed to delete HTTP template',
                          )
                        } finally {
                          setHttpTemplateSaving(false)
                        }
                      }}
                    >
                      Delete template
                    </button>
                    <button
                      type="button"
                      className="rounded bg-white/10 px-2 py-1 text-[11px] text-white/80 hover:bg-white/20 disabled:opacity-40"
                      disabled={!selectedId || httpTemplateSaving}
                      onClick={async () => {
                        if (!selectedId) return
                        const existing = new Set(httpTemplatePaths)
                        let index = 1
                        let path: string
                        do {
                          path = `http-templates/template-${index}.yaml`
                          index += 1
                        } while (existing.has(path))
                        const initialYaml = [
                          '# New HTTP template',
                          'schemaRef: ""',
                          'bodyTemplate: |',
                          '  {',
                          '    "example": "value"',
                          '  }',
                          '',
                        ].join('\n')
                        setHttpTemplateSaving(true)
                        setHttpTemplateError(null)
                        try {
                          await saveHttpTemplate(selectedId, path, initialYaml)
                          setHttpTemplatePaths((current) => {
                            const next = [...current, path]
                            next.sort((a, b) => a.localeCompare(b))
                            return next
                          })
                          setHttpTemplateSelectedPath(path)
                          setHttpTemplateRaw(initialYaml)
                        } catch (e) {
                          setHttpTemplateError(
                            e instanceof Error
                              ? `Failed to create HTTP template: ${e.message}`
                              : 'Failed to create HTTP template',
                          )
                        } finally {
                          setHttpTemplateSaving(false)
                        }
                      }}
                    >
                      Add template
                    </button>
                  </div>
                </div>
                {!selectedId && (
                  <div className="text-[11px] text-white/60">
                    Select a scenario to inspect HTTP templates.
                  </div>
                )}
                {selectedId && (
                  <div className="flex gap-3">
                    <div className="w-64 border border-white/15 rounded bg-black/40 p-2 text-[11px] text-white/80">
                      <div className="font-semibold mb-1 text-white/80">Template files</div>
                      {httpTemplateLoading && (
                        <div className="text-[10px] text-white/60">Loading templates…</div>
                      )}
                      {httpTemplateError && (
                        <div className="text-[10px] text-red-400">{httpTemplateError}</div>
                      )}
                      {!httpTemplateLoading && !httpTemplateError && httpTemplatePaths.length === 0 && (
                        <div className="text-[10px] text-white/60">
                          No HTTP templates found (expected under <span className="font-mono">http-templates/</span>).
                        </div>
                      )}
                      {httpTemplatePaths.length > 0 && (
                        <ul className="mt-1 max-h-56 overflow-y-auto">
                          {httpTemplatePaths.map((path) => {
                            const isSelected = path === httpTemplateSelectedPath
                            return (
                              <li key={path}>
                                <button
                                  type="button"
                                  className={`w-full text-left px-2 py-1 rounded text-[10px] ${
                                    isSelected
                                      ? 'bg-sky-500/40 text-white'
                                      : 'bg-transparent text-white/80 hover:bg-white/10'
                                  }`}
                                  onClick={async () => {
                                    if (!selectedId) return
                                    setHttpTemplateSelectedPath(path)
                                    setHttpTemplateError(null)
                                    try {
                                      const text = await fetchHttpTemplate(selectedId, path)
                                      setHttpTemplateRaw(text)
                                    } catch (e) {
                                      setHttpTemplateError(
                                        e instanceof Error
                                          ? `Failed to load HTTP template: ${e.message}`
                                          : 'Failed to load HTTP template',
                                      )
                                    }
                                  }}
                                >
                                  {path}
                                </button>
                              </li>
                            )
                          })}
                        </ul>
                      )}
                    </div>
                    <div className="flex-1 flex flex-col min-h-[260px]">
                      <div className="flex items-center justify-between mb-1">
                        <span className="text-[11px] text-white/80">
                          Template YAML
                          {httpTemplateSelectedPath && (
                            <span className="ml-1 font-mono text-white/70">
                              ({httpTemplateSelectedPath})
                            </span>
                          )}
                        </span>
                        <button
                          type="button"
                          className="rounded bg-white/10 px-2 py-0.5 text-[10px] text-white/80 hover:bg-white/20 disabled:opacity-40"
                          disabled={!httpTemplateSelectedPath || httpTemplateSaving}
                          onClick={async () => {
                            if (!selectedId || !httpTemplateSelectedPath) return
                            setHttpTemplateSaving(true)
                            setHttpTemplateError(null)
                            try {
                              await saveHttpTemplate(selectedId, httpTemplateSelectedPath, httpTemplateRaw)
                            } catch (e) {
                              setHttpTemplateError(
                                e instanceof Error
                                  ? `Failed to save HTTP template: ${e.message}`
                                  : 'Failed to save HTTP template',
                              )
                            } finally {
                              setHttpTemplateSaving(false)
                            }
                          }}
                        >
                          {httpTemplateSaving ? 'Saving…' : 'Save template'}
                        </button>
                      </div>
                      <textarea
                        className="flex-1 w-full rounded border border-white/20 bg-black/40 px-2 py-1 text-[11px] font-mono text-white/90 resize-none"
                        value={httpTemplateRaw}
                        onChange={(e) => setHttpTemplateRaw(e.target.value)}
                        placeholder={
                          httpTemplateSelectedPath
                            ? '# HTTP template YAML'
                            : '# Select a template from the list to view or edit its YAML'
                        }
                      />
                    </div>
                  </div>
                )}
              </div>
            )}
          </div>
        )}
      </div>
	      {configPatchModalState && (
	        <ConfigUpdatePatchModal
	          open
	          imageLabel={configPatchModalState.imageLabel}
	          entries={configPatchModalState.entries}
	          baseConfig={configPatchModalState.baseConfig}
	          existingPatch={configPatchModalState.existingPatch}
	          onClose={() => setConfigPatchModalState(null)}
	          onApply={(patch) => applyConfigPatchModal(patch)}
	        />
	      )}
	      {schemaAttachState && (
	        <div className="fixed inset-0 z-40 flex items-center justify-center bg-black/70">
	          <div
	            role="dialog"
	            aria-modal="true"
            className="w-full max-w-lg rounded-lg bg-[#05070b] border border-white/20 p-4 text-sm text-white"
          >
            <div className="flex items-center justify-between mb-2">
              <h3 className="text-xs font-semibold text-white/80">
                {schemaAttachState.target === 'http-template'
                  ? 'Attach schema for HTTP template'
                  : 'Attach schema for generator HTTP body'}
              </h3>
              <button
                type="button"
                className="text-white/60 hover:text-white"
                onClick={() => {
                  setSchemaAttachState(null)
                  setSchemaAttachError(null)
                  setSchemaAttachBusy(false)
                }}
              >
                ×
              </button>
            </div>
            <div className="space-y-3 mb-3 text-[11px] text-white/70">
              {schemaAttachState.existingOptions.length > 0 && (
                <div className="space-y-1">
                  <label className="flex items-center gap-2 text-[11px]">
                    <input
                      type="radio"
                      className="h-3 w-3 accent-blue-500"
                      checked={schemaAttachState.mode === 'existing'}
                      onChange={() =>
                        setSchemaAttachState((current) =>
                          current
                            ? {
                                ...current,
                                mode: 'existing',
                              }
                            : current,
                        )
                      }
                    />
                    <span>Use existing schema file</span>
                  </label>
                  {schemaAttachState.mode === 'existing' && (
                    <select
                      className="mt-1 w-full rounded bg-white/10 px-2 py-1 text-white text-[11px]"
                      value={schemaAttachState.selectedExisting ?? ''}
                      onChange={(event) =>
                        setSchemaAttachState((current) =>
                          current
                            ? {
                                ...current,
                                selectedExisting: event.target.value || null,
                              }
                            : current,
                        )
                      }
                    >
                      {schemaAttachState.existingOptions.map((option) => (
                        <option key={option} value={option}>
                          {option}
                        </option>
                      ))}
                    </select>
                  )}
                </div>
              )}
              <div className="space-y-1">
                <label className="flex items-center gap-2 text-[11px]">
                  <input
                    type="radio"
                    className="h-3 w-3 accent-blue-500"
                    checked={schemaAttachState.mode === 'new'}
                    onChange={() =>
                      setSchemaAttachState((current) =>
                        current
                          ? {
                              ...current,
                              mode: 'new',
                            }
                          : current,
                      )
                    }
                  />
                  <span>Create new schema file in bundle</span>
                </label>
                {schemaAttachState.mode === 'new' && (
                  <div className="space-y-1 pl-4">
                    <input
                      className="w-full rounded bg-white/10 px-2 py-1 text-white text-[11px]"
                      placeholder="schemas/body.schema.json"
                      value={schemaAttachState.newPath}
                      onChange={(event) =>
                        setSchemaAttachState((current) =>
                          current
                            ? {
                                ...current,
                                newPath: event.target.value,
                              }
                            : current,
                        )
                      }
                    />
                    <textarea
                      className="w-full h-32 rounded bg-white/10 px-2 py-1 text-white text-[11px] resize-none"
                      placeholder='{"type":"object","properties":{}}'
                      value={schemaAttachState.newSchemaText}
                      onChange={(event) =>
                        setSchemaAttachState((current) =>
                          current
                            ? {
                                ...current,
                                newSchemaText: event.target.value,
                              }
                            : current,
                        )
                      }
                    />
                  </div>
                )}
              </div>
            </div>
            {schemaAttachError && (
              <div className="mb-2 text-[11px] text-red-400">{schemaAttachError}</div>
            )}
            <div className="flex items-center justify-end gap-2">
              <button
                type="button"
                className="rounded px-2 py-1 text-[11px] text-white/70 hover:bg-white/10"
                onClick={() => {
                  setSchemaAttachState(null)
                  setSchemaAttachError(null)
                  setSchemaAttachBusy(false)
                }}
              >
                Cancel
              </button>
              <button
                type="button"
                className="rounded bg-sky-500/80 px-3 py-1 text-[11px] text-white hover:bg-sky-500 disabled:opacity-60"
                disabled={schemaAttachBusy}
                onClick={() => {
                  void applySchemaAttach()
                }}
              >
                {schemaAttachBusy ? 'Attaching…' : 'Attach schema'}
              </button>
            </div>
          </div>
        </div>
      )}
      {schemaEditorState && (
        <div className="fixed inset-0 z-40 flex items-center justify-center bg-black/70">
          <div
            role="dialog"
            aria-modal="true"
            className="w-full max-w-lg rounded-lg bg-[#05070b] border border-white/20 p-4 text-sm text-white"
          >
            <div className="flex items-center justify-between mb-2">
              <h3 className="text-xs font-semibold text-white/80">
                {schemaEditorState.kind === 'http-template'
                  ? 'Edit HTTP Builder body from schema'
                  : 'Edit generator HTTP body from schema'}
              </h3>
              <button
                type="button"
                className="text-white/60 hover:text-white"
                onClick={() => {
                  setSchemaEditorState(null)
                  setSchemaEditorValues({})
                  setSchemaEditorError(null)
                }}
              >
                ×
              </button>
            </div>
            {schemaEditorState.kind === 'generator' && (
              <div className="mb-2 text-[11px] text-white/60 flex items-center gap-2">
                <span>Schema:</span>
                <select
                  className="flex-1 rounded bg-white/10 px-2 py-1 text-white text-[11px]"
                  value={schemaEditorState.schemaPath}
                  onChange={async (event) => {
                  const nextPath = event.target.value
                  if (!schemaEditorState || !selectedId || !templateDraft) return
                  try {
                    const text = await fetchScenarioSchema(selectedId, nextPath)
                    let schemaRoot: unknown
                    try {
                      const parsed = JSON.parse(text) as unknown
                      const ptr = schemaEditorState.pointer
                      if (!ptr) {
                        schemaRoot = parsed
                      } else {
                        const rawPtr = ptr.startsWith('#') ? ptr.slice(1) : ptr
                        const segments = rawPtr.split('/').filter((s) => s.length > 0)
                        let cursor: unknown = parsed
                        for (const segment of segments) {
                          if (
                            typeof cursor !== 'object' ||
                            cursor === null ||
                            Array.isArray(cursor)
                          ) {
                            cursor = undefined
                            break
                          }
                          const key = segment.replace(/~1/g, '/').replace(/~0/g, '~')
                          cursor = (cursor as Record<string, unknown>)[key]
                        }
                        schemaRoot = cursor
                      }
                    } catch {
                      setSchemaEditorError('Schema JSON is invalid for the selected file')
                      return
                    }
                    if (
                      !schemaRoot ||
                      typeof schemaRoot !== 'object' ||
                      Array.isArray(schemaRoot)
                    ) {
                      setSchemaEditorError(
                        'Schema root for the selected file/pointer must be an object with properties',
                      )
                      return
                    }
                    const schemaObj = schemaRoot as Record<string, unknown>
                    const propertiesRaw = schemaObj.properties
                    if (
                      !propertiesRaw ||
                      typeof propertiesRaw !== 'object' ||
                      Array.isArray(propertiesRaw)
                    ) {
                      setSchemaEditorError(
                        'Schema root for the selected file does not declare any properties',
                      )
                      return
                    }
                    const props = propertiesRaw as Record<string, unknown>
                    const fields: { name: string; description: string | null }[] = []
                    Object.entries(props).forEach(([name, value]) => {
                      if (!name || !value || typeof value !== 'object' || Array.isArray(value)) {
                        return
                      }
                      const propSchema = value as Record<string, unknown>
                      const description =
                        typeof propSchema.description === 'string' &&
                        propSchema.description.trim().length > 0
                          ? propSchema.description.trim()
                          : null
                      fields.push({ name, description })
                    })
                    if (fields.length === 0) {
                      setSchemaEditorError(
                        'Schema for the selected file has no simple properties to edit',
                      )
                      return
                    }

                    // Recompute initial values from existing body for the new schema.
                    if (
                      schemaEditorState.kind !== 'generator' ||
                      schemaEditorState.beeIndex == null
                    ) {
                      setSchemaEditorError(
                        'Internal error: schema editor is not bound to a generator bee',
                      )
                      return
                    }
                    const config = resolveBeeTemplateConfig(schemaEditorState.beeIndex)
                    const worker =
                      config &&
                      config.worker &&
                      typeof config.worker === 'object' &&
                      !Array.isArray(config.worker)
                        ? (config.worker as Record<string, unknown>)
                        : undefined
                    const message =
                      worker &&
                      worker.message &&
                      typeof worker.message === 'object' &&
                      !Array.isArray(worker.message)
                        ? (worker.message as Record<string, unknown>)
                        : undefined
                    const bodyValue =
                      message && typeof message.body === 'string' ? message.body : ''
                    let initialValues: Record<string, string> = {}
                    if (bodyValue && bodyValue.trim().length > 0) {
                      try {
                        const parsedBody = JSON.parse(bodyValue) as unknown
                        if (
                          parsedBody &&
                          typeof parsedBody === 'object' &&
                          !Array.isArray(parsedBody)
                        ) {
                          const obj = parsedBody as Record<string, unknown>
                          const map: Record<string, string> = {}
                          fields.forEach((field) => {
                            const raw = obj[field.name]
                            if (raw === null || raw === undefined) {
                              return
                            }
                            if (typeof raw === 'string') {
                              map[field.name] = raw
                            } else if (
                              typeof raw === 'number' ||
                              typeof raw === 'boolean'
                            ) {
                              map[field.name] = String(raw)
                            } else {
                              try {
                                map[field.name] = JSON.stringify(raw)
                              } catch {
                                // ignore
                              }
                            }
                          })
                          initialValues = map
                        }
                      } catch {
                        // ignore parse errors
                      }
                    }

                    setSchemaEditorState((current) =>
                      current
                        ? {
                            ...current,
                            schemaPath: nextPath,
                            fields,
                            initialValues,
                            rawSchema: text,
                          }
                        : null,
                    )
                    setSchemaEditorValues(initialValues)
                    setSchemaEditorError(null)
                  } catch (error) {
                    setSchemaEditorError(
                      error instanceof Error
                        ? `Failed to load schema: ${error.message}`
                        : 'Failed to load selected schema file',
                    )
                  }
                }}
                >
                  {schemaEditorState.schemaOptions.map((option) => (
                    <option key={option} value={option}>
                      {option}
                    </option>
                  ))}
                </select>
              </div>
            )}
            <div className="max-h-64 overflow-y-auto space-y-2 mb-3">
              <button
                type="button"
                className="mb-2 rounded border border-white/25 bg-black/40 px-2 py-1 text-[10px] text-white/70 hover:bg-black/60"
                onClick={() => setSchemaEditorShowRaw((prev) => !prev)}
              >
                {schemaEditorShowRaw ? 'Hide raw schema JSON' : 'Show raw schema JSON'}
              </button>
              {schemaEditorShowRaw && (
                <pre className="mb-2 max-h-40 overflow-auto rounded bg-black/80 p-2 text-[10px] text-sky-100 whitespace-pre-wrap border border-white/15">
                  {schemaEditorState.rawSchema}
                </pre>
              )}
              {schemaEditorState.fields.map((field) => {
                const value =
                  schemaEditorValues[field.name] ??
                  schemaEditorState.initialValues[field.name] ??
                  ''
                return (
                  <label key={field.name} className="block space-y-1 text-xs">
                    <div className="flex items-center justify-between gap-2">
                      <span className="block text-white/70">
                        {field.name}
                        {field.description && (
                          <span className="text-white/40"> — {field.description}</span>
                        )}
                      </span>
                    </div>
                    <input
                      className="w-full rounded bg-white/10 px-2 py-1 text-white text-xs"
                      type="text"
                      value={value}
                      onChange={(event) =>
                        setSchemaEditorValues((prev) => ({
                          ...prev,
                          [field.name]: event.target.value,
                        }))
                      }
                      placeholder="Enter literal or {{ template }} value"
                    />
                  </label>
                )
              })}
            </div>
            {schemaEditorError && (
              <div className="mb-2 text-[11px] text-red-400">{schemaEditorError}</div>
            )}
            <div className="flex items-center justify-end gap-2">
              <button
                type="button"
                className="rounded px-2 py-1 text-[11px] text-white/70 hover:bg-white/10"
                onClick={() => {
                  setSchemaEditorState(null)
                  setSchemaEditorValues({})
                  setSchemaEditorError(null)
                }}
              >
                Cancel
              </button>
              <button
                type="button"
                className="rounded bg-sky-500/80 px-3 py-1 text-[11px] text-white hover:bg-sky-500"
                onClick={() => applySchemaEditor()}
              >
                Apply
              </button>
            </div>
          </div>
        </div>
      )}

      {showVariablesModal && selectedId && (
        <ScenarioVariablesModal
          scenarioId={selectedId}
          onClose={() => setShowVariablesModal(false)}
          onSaved={(warnings) => {
            if (warnings.length > 0) {
              setToast(`Saved variables.yaml (${warnings.length} warning(s))`)
            } else {
              setToast('Saved variables.yaml')
            }
          }}
        />
      )}

      {showSutsModal && selectedId && (
        <ScenarioSutsModal
          scenarioId={selectedId}
          onClose={() => setShowSutsModal(false)}
          onSaved={(sutId) => setToast(`Saved SUT '${sutId}'`)}
        />
      )}
    </div>
  )
}
