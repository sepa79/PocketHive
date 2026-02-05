import { useCallback, useEffect, useMemo, useState } from 'react'
import { useToolsBar } from '../components/ToolsBarContext'
import { useNavigate, useParams } from 'react-router-dom'
import { CreateSwarmModal } from './hive/CreateSwarmModal'
import {
  buildManifestIndex,
  findManifestForImage,
  normalizeManifests,
  type CapabilityManifest,
} from '../lib/capabilities'
import { detectUiBasename } from '../lib/routing/basename'

const HIVE_EXPLAIN_KEY = 'PH_UI_HIVE_EXPLAIN'

type BeeSummary = {
  role: string
  image: string | null
}

type SwarmSummary = {
  id: string
  status?: string | null
  health?: string | null
  heartbeat?: string | null
  workEnabled?: boolean
  controllerEnabled?: boolean
  templateId?: string | null
  controllerImage?: string | null
  bees?: BeeSummary[]
}

type StatusFullSnapshotResponse = {
  receivedAt: string
  staleAfterSec: number
  envelope: unknown
}

type RuntimeMeta = {
  templateId: string | null
  runId: string | null
  containerId: string | null
  image: string | null
  stackName: string | null
}

type SwarmWorkerSummary = {
  role: string | null
  instance: string | null
  enabled: boolean | null
  tps: number | null
  stale: boolean | null
  lastSeenAt: string | null
  workIo: { input: string | null; output: string | null } | null
  runtime: RuntimeMeta | null
}

type SwarmSnapshotView = {
  receivedAt: string
  staleAfterSec: number
  envelopeTimestamp: string | null
  runtime: RuntimeMeta | null
  enabled: boolean | null
  startedAt: string | null
  swarmStatus: string | null
  swarmHealth: string | null
  workers: SwarmWorkerSummary[]
}

type ScenarioBee = {
  id: string | null
  role: string | null
  image: string | null
  work: {
    in: Record<string, string> | undefined
    out: Record<string, string> | undefined
  } | null
  ports: { id: string; direction: 'in' | 'out' }[] | null
}

type ScenarioTopologyEdge = {
  id: string | null
  from: { beeId: string | null; port: string | null } | null
  to: { beeId: string | null; port: string | null } | null
}

type ScenarioDefinition = {
  id: string | null
  name: string | null
  description: string | null
  template?: {
    image: string | null
    bees?: ScenarioBee[]
  } | null
  topology?: {
    version: number | null
    edges?: ScenarioTopologyEdge[]
  } | null
}

type SwarmAction = 'start' | 'stop' | 'remove'

const ORCHESTRATOR_BASE = '/orchestrator/api'
const CAPABILITIES_ENDPOINT = '/scenario-manager/api/capabilities?all=true'

function createIdempotencyKey() {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) {
    return crypto.randomUUID()
  }
  return `ph-${Date.now()}-${Math.random().toString(16).slice(2)}`
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

function toStringOrNull(value: unknown): string | null {
  return typeof value === 'string' && value.trim().length > 0 ? value.trim() : null
}

function toNumberOrNull(value: unknown): number | null {
  if (typeof value !== 'number' || !Number.isFinite(value)) return null
  return value
}

function toBooleanOrNull(value: unknown): boolean | null {
  if (typeof value !== 'boolean') return null
  return value
}

function extractRuntimeMeta(value: unknown): RuntimeMeta | null {
  if (!isRecord(value)) return null
  const templateId = toStringOrNull(value.templateId)
  const runId = toStringOrNull(value.runId)
  const containerId = toStringOrNull(value.containerId)
  const image = toStringOrNull(value.image)
  const stackName = toStringOrNull(value.stackName)
  if (!templateId && !runId && !containerId && !image && !stackName) return null
  return { templateId, runId, containerId, image, stackName }
}

function extractSnapshotView(snapshot: StatusFullSnapshotResponse | null): SwarmSnapshotView | null {
  if (!snapshot) return null
  const envelope = snapshot.envelope
  if (!isRecord(envelope)) {
    return {
      receivedAt: snapshot.receivedAt,
      staleAfterSec: snapshot.staleAfterSec,
      envelopeTimestamp: null,
      runtime: null,
      enabled: null,
      startedAt: null,
      swarmStatus: null,
      swarmHealth: null,
      workers: [],
    }
  }

  const envelopeTimestamp = toStringOrNull(envelope.timestamp)
  const runtime = extractRuntimeMeta(envelope.runtime)

  const data = isRecord(envelope.data) ? envelope.data : null
  const enabled = data ? toBooleanOrNull(data.enabled) : null
  const startedAt = data ? toStringOrNull(data.startedAt) : null
  const context = data && isRecord(data.context) ? data.context : null
  const swarmStatus = context ? toStringOrNull(context.swarmStatus) : null
  const swarmHealth = context ? toStringOrNull(context.swarmHealth) : null

  const workersRaw = context && Array.isArray(context.workers) ? (context.workers as unknown[]) : []
  const workers: SwarmWorkerSummary[] = workersRaw
    .map((entry) => {
      if (!isRecord(entry)) return null
      const role = toStringOrNull(entry.role)
      const instance = toStringOrNull(entry.instance)
      const workerEnabled = toBooleanOrNull(entry.enabled)
      const tps = toNumberOrNull(entry.tps)
      const stale = toBooleanOrNull(entry.stale)
      const lastSeenAt = toStringOrNull(entry.lastSeenAt)
      const workIoState = isRecord(entry.ioState) && isRecord(entry.ioState.work) ? entry.ioState.work : null
      const workIo = workIoState
        ? {
            input: toStringOrNull(workIoState.input),
            output: toStringOrNull(workIoState.output),
          }
        : null
      const workerRuntime = extractRuntimeMeta(entry.runtime)
      return {
        role,
        instance,
        enabled: workerEnabled,
        tps,
        stale,
        lastSeenAt,
        workIo,
        runtime: workerRuntime,
      } satisfies SwarmWorkerSummary
    })
    .filter((entry): entry is SwarmWorkerSummary => entry !== null)

  return {
    receivedAt: snapshot.receivedAt,
    staleAfterSec: snapshot.staleAfterSec,
    envelopeTimestamp,
    runtime,
    enabled,
    startedAt,
    swarmStatus,
    swarmHealth,
    workers,
  }
}

function formatWorkPortMap(value?: Record<string, string>) {
  if (!value) return []
  const entries = Object.entries(value)
    .map(([portId, suffix]) => {
      const id = typeof portId === 'string' ? portId.trim() : ''
      const val = typeof suffix === 'string' ? suffix.trim() : ''
      if (!id || !val) return null
      return `${id} → ${val}`
    })
    .filter((entry): entry is string => entry !== null)
  entries.sort((a, b) => a.localeCompare(b))
  return entries
}

function WorkIoTable({
  inputs,
  outputs,
  swarmId,
  explain,
}: {
  inputs?: Record<string, string> | undefined
  outputs?: Record<string, string> | undefined
  swarmId?: string | null
  explain?: boolean
}) {
  const inputLines = formatWorkPortMap(inputs)
  const outputLines = formatWorkPortMap(outputs)
  const shortInputsTitle = 'Queue suffixes (work.in) keyed by input port id.'
  const shortOutputsTitle = 'Queue suffixes (work.out) keyed by output port id.'
  const longInputsTitle = `Inputs (scenario work.in)\nMap: portId → queue suffix.\nSuffix is resolved by Swarm Controller using swarmId (${swarmId ?? '—'}) and naming rules (see scenario contract).`
  const longOutputsTitle = `Outputs (scenario work.out)\nMap: portId → queue suffix.\nSuffix is resolved by Swarm Controller using swarmId (${swarmId ?? '—'}) and naming rules (see scenario contract).`
  if (inputLines.length === 0 && outputLines.length === 0) {
    return <div className="muted">I/O: —</div>
  }
  return (
    <div className="ioTable">
      <div className="ioHead" title={explain ? longInputsTitle : shortInputsTitle}>
        Inputs
      </div>
      <div className="ioHead" title={explain ? longOutputsTitle : shortOutputsTitle}>
        Outputs
      </div>
      <div className="ioCell">
        {inputLines.length ? (
          inputLines.map((line) => (
            <div key={line} title={explain ? `input: ${line}` : undefined}>
              {line}
            </div>
          ))
        ) : (
          <div className="muted">—</div>
        )}
      </div>
      <div className="ioCell">
        {outputLines.length ? (
          outputLines.map((line) => (
            <div key={line} title={explain ? `output: ${line}` : undefined}>
              {line}
            </div>
          ))
        ) : (
          <div className="muted">—</div>
        )}
      </div>
    </div>
  )
}

function asScenarioDefinition(data: unknown): ScenarioDefinition | null {
  if (!isRecord(data)) return null
  const template = isRecord(data.template) ? data.template : null
  const bees = Array.isArray(template?.bees)
    ? (template?.bees as unknown[])
        .map((bee) => {
          if (!isRecord(bee)) return null
          const work = isRecord(bee.work) ? bee.work : null
          const workIn = isRecord(work?.in) ? (work?.in as Record<string, string>) : undefined
          const workOut = isRecord(work?.out) ? (work?.out as Record<string, string>) : undefined
          const ports = Array.isArray(bee.ports)
            ? (bee.ports as unknown[])
                .map((port) => {
                  if (!isRecord(port)) return null
                  const id = toStringOrNull(port.id)
                  const direction = toStringOrNull(port.direction)
                  if (!id || (direction !== 'in' && direction !== 'out')) return null
                  return { id, direction }
                })
                .filter((port): port is { id: string; direction: 'in' | 'out' } => port !== null)
            : null
          return {
            id: toStringOrNull(bee.id),
            role: toStringOrNull(bee.role),
            image: toStringOrNull(bee.image),
            work: workIn || workOut ? { in: workIn, out: workOut } : null,
            ports,
          }
        })
        .filter((bee): bee is ScenarioBee => bee !== null)
    : undefined
  const topology = isRecord(data.topology) ? data.topology : null
  const edges = Array.isArray(topology?.edges)
    ? (topology?.edges as unknown[])
        .map((edge) => {
          if (!isRecord(edge)) return null
          const from = isRecord(edge.from) ? edge.from : null
          const to = isRecord(edge.to) ? edge.to : null
          return {
            id: toStringOrNull(edge.id),
            from: from
              ? { beeId: toStringOrNull(from.beeId), port: toStringOrNull(from.port) }
              : null,
            to: to ? { beeId: toStringOrNull(to.beeId), port: toStringOrNull(to.port) } : null,
          }
        })
        .filter((edge): edge is ScenarioTopologyEdge => edge !== null)
    : undefined

  return {
    id: toStringOrNull(data.id),
    name: toStringOrNull(data.name),
    description: toStringOrNull(data.description),
    template: template
      ? {
          image: toStringOrNull(template.image),
          bees,
        }
      : null,
    topology: topology
      ? {
          version: typeof topology.version === 'number' ? topology.version : null,
          edges,
        }
      : null,
  }
}

async function readErrorMessage(response: Response): Promise<string> {
  try {
    const text = await response.text()
    if (!text) return `HTTP ${response.status}`
    try {
      const data = JSON.parse(text) as { message?: unknown }
      if (data && typeof data.message === 'string') {
        return data.message
      }
    } catch {
      return text
    }
    return text
  } catch {
    return `HTTP ${response.status}`
  }
}

type HalEyeState = 'ok' | 'warn' | 'alert' | 'missing'

function halEyeState(status: string | null | undefined, health: string | null | undefined): HalEyeState {
  const normalizedStatus = typeof status === 'string' ? status.trim().toUpperCase() : ''
  const normalizedHealth = typeof health === 'string' ? health.trim().toUpperCase() : ''
  if (normalizedStatus === 'FAILED' || normalizedHealth === 'FAILED' || normalizedHealth === 'UNHEALTHY') return 'alert'
  if (!normalizedHealth || normalizedHealth === 'UNKNOWN') return 'missing'
  if (normalizedHealth === 'DEGRADED') return 'warn'
  if (normalizedHealth === 'RUNNING' || normalizedHealth === 'HEALTHY') return 'ok'
  return 'missing'
}

function halEyeTitle(status: string | null | undefined, health: string | null | undefined): string {
  const statusLabel = typeof status === 'string' && status.trim().length > 0 ? status.trim().toUpperCase() : 'UNKNOWN'
  const healthRaw = typeof health === 'string' && health.trim().length > 0 ? health.trim().toUpperCase() : 'UNKNOWN'
  const healthLabel = healthRaw === 'RUNNING' || healthRaw === 'HEALTHY' ? 'OK' : healthRaw
  return `Status: ${statusLabel}\nHealth: ${healthLabel}`
}

function formatAge(iso: string | null | undefined): string {
  if (!iso) return '—'
  const ts = Date.parse(iso)
  if (!Number.isFinite(ts)) return iso
  const diffMs = Date.now() - ts
  if (diffMs < 0) return '—'
  const sec = Math.floor(diffMs / 1000)
  if (sec < 10) return 'just now'
  if (sec < 60) return `${sec}s ago`
  const min = Math.floor(sec / 60)
  if (min < 60) return `${min}m ago`
  const hr = Math.floor(min / 60)
  if (hr < 48) return `${hr}h ago`
  const days = Math.floor(hr / 24)
  return `${days}d ago`
}

export function HivePage() {
  const navigate = useNavigate()
  const { swarmId: selectedSwarmIdParam } = useParams<{ swarmId?: string }>()
  const selectedSwarmId = selectedSwarmIdParam?.trim() ? selectedSwarmIdParam.trim() : null
  const [swarms, setSwarms] = useState<SwarmSummary[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)
  const [showCreate, setShowCreate] = useState(false)
  const [busySwarm, setBusySwarm] = useState<string | null>(null)
  const [busyAction, setBusyAction] = useState<SwarmAction | null>(null)
  const [selectedScenario, setSelectedScenario] = useState<ScenarioDefinition | null>(null)
  const [scenarioError, setScenarioError] = useState<string | null>(null)
  const [scenarioLoading, setScenarioLoading] = useState(false)
  const [showHelp, setShowHelp] = useState(false)
  const [explainMode, setExplainMode] = useState<boolean>(() => {
    if (typeof window === 'undefined') return false
    try {
      return window.sessionStorage.getItem(HIVE_EXPLAIN_KEY) === '1'
    } catch {
      return false
    }
  })
  const [selectedBeeKey, setSelectedBeeKey] = useState<string | null>(null)
  const [snapshot, setSnapshot] = useState<StatusFullSnapshotResponse | null>(null)
  const [snapshotError, setSnapshotError] = useState<string | null>(null)
  const [snapshotLoading, setSnapshotLoading] = useState(false)
  const [capabilities, setCapabilities] = useState<CapabilityManifest[]>([])
  const [capabilitiesLoaded, setCapabilitiesLoaded] = useState(false)
  const [capabilitiesError, setCapabilitiesError] = useState<string | null>(null)
  const [tapBusy, setTapBusy] = useState<Record<string, boolean>>({})
  const [tapIoSelection, setTapIoSelection] = useState<Record<string, { in?: string | null; out?: string | null }>>({})

  const tapBusyKey = useCallback((role: string, direction: 'IN' | 'OUT', ioName: string | null) => {
    return `${role}::${direction}::${ioName ?? ''}`
  }, [])

  const openTapViewer = useCallback(
    async (role: string, direction: 'IN' | 'OUT', ioName: string | null) => {
      if (!selectedSwarmId) {
        throw new Error('Missing swarm id.')
      }
      const payload = {
        swarmId: selectedSwarmId,
        role,
        direction,
        ioName,
        maxItems: 50,
        ttlSeconds: 60,
      }
      const response = await fetch(`${ORCHESTRATOR_BASE}/debug/taps`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
      })
      if (!response.ok) {
        throw new Error(await readErrorMessage(response))
      }
      const created = (await response.json()) as { tapId?: string }
      const tapId = created.tapId
      if (!tapId) {
        throw new Error('Tap created but tapId is missing in response.')
      }
      const base = detectUiBasename(window.location.pathname)
      window.open(`${base}/debug/taps/${encodeURIComponent(tapId)}`, '_blank', 'noopener,noreferrer')
    },
    [selectedSwarmId],
  )

  const loadSwarms = useCallback(async () => {
    setLoading(true)
    try {
      const response = await fetch(`${ORCHESTRATOR_BASE}/swarms`, {
        headers: { Accept: 'application/json' },
      })
      if (!response.ok) {
        throw new Error(await readErrorMessage(response))
      }
      const payload = (await response.json()) as SwarmSummary[]
      setError(null)
      setSwarms(Array.isArray(payload) ? payload : [])
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load swarms')
    } finally {
      setLoading(false)
    }
  }, [])

  const loadSnapshot = useCallback(async () => {
    if (!selectedSwarmId) {
      setSnapshot(null)
      setSnapshotError(null)
      setSnapshotLoading(false)
      return
    }
    setSnapshotLoading(true)
    try {
      const response = await fetch(
        `${ORCHESTRATOR_BASE}/swarms/${encodeURIComponent(selectedSwarmId)}`,
        { headers: { Accept: 'application/json' } },
      )
      if (response.status === 404) {
        setSnapshotError('No cached status-full snapshot yet for this swarm.')
        return
      }
      if (!response.ok) {
        throw new Error(await readErrorMessage(response))
      }
      const payload = (await response.json()) as Partial<StatusFullSnapshotResponse>
      const receivedAt = typeof payload.receivedAt === 'string' ? payload.receivedAt : ''
      const staleAfterSec = typeof payload.staleAfterSec === 'number' ? payload.staleAfterSec : 0
      setSnapshotError(null)
      setSnapshot({
        receivedAt,
        staleAfterSec,
        envelope: payload.envelope,
      })
    } catch (err) {
      setSnapshotError(err instanceof Error ? err.message : 'Failed to load status-full snapshot')
    } finally {
      setSnapshotLoading(false)
    }
  }, [selectedSwarmId])

  const loadCapabilities = useCallback(async () => {
    if (capabilitiesLoaded) return
    setCapabilitiesError(null)
    try {
      const response = await fetch(CAPABILITIES_ENDPOINT, {
        headers: { Accept: 'application/json' },
      })
      if (!response.ok) {
        setCapabilities([])
        setCapabilitiesLoaded(true)
        setCapabilitiesError('Failed to load capabilities')
        return
      }
      const payload = await response.json()
      const normalized = normalizeManifests(payload)
      setCapabilities(normalized)
      setCapabilitiesLoaded(true)
    } catch (err) {
      setCapabilities([])
      setCapabilitiesLoaded(true)
      setCapabilitiesError(err instanceof Error ? err.message : 'Failed to load capabilities')
    }
  }, [capabilitiesLoaded])

  useEffect(() => {
    void loadSwarms()
  }, [loadSwarms])

  const runSwarmAction = useCallback(
    async (swarm: SwarmSummary, action: SwarmAction) => {
      if (!swarm.id) return
      setBusySwarm(swarm.id)
      setBusyAction(action)
      try {
        const response = await fetch(
          `${ORCHESTRATOR_BASE}/swarms/${encodeURIComponent(swarm.id)}/${action}`,
          {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ idempotencyKey: createIdempotencyKey() }),
          },
        )
        if (!response.ok) {
          throw new Error(await readErrorMessage(response))
        }
        setMessage(`${action} request accepted for ${swarm.id}.`)
        void loadSwarms()
      } catch (err) {
        setMessage(err instanceof Error ? err.message : `Failed to ${action} swarm.`)
      } finally {
        setBusySwarm(null)
        setBusyAction(null)
      }
    },
    [loadSwarms],
  )

  useEffect(() => {
    const handle = window.setInterval(() => {
      void loadSwarms()
    }, 5000)
    return () => window.clearInterval(handle)
  }, [loadSwarms])

  useEffect(() => {
    void loadSnapshot()
    if (!selectedSwarmId) {
      return
    }
    const handle = window.setInterval(() => {
      void loadSnapshot()
    }, 5000)
    return () => window.clearInterval(handle)
  }, [loadSnapshot, selectedSwarmId])

  const loadScenarioDetail = useCallback(
    async (scenarioId: string | null) => {
      if (!scenarioId) {
        setSelectedScenario(null)
        setScenarioError(null)
        setScenarioLoading(false)
        return
      }
      setScenarioLoading(true)
      setScenarioError(null)
      try {
        const response = await fetch(
          `/scenario-manager/scenarios/${encodeURIComponent(scenarioId)}`,
          {
            headers: { Accept: 'application/json' },
          },
        )
        if (!response.ok) {
          throw new Error(await readErrorMessage(response))
        }
        const payload = await response.json()
        setSelectedScenario(asScenarioDefinition(payload))
      } catch (err) {
        setSelectedScenario(null)
        setScenarioError(err instanceof Error ? err.message : 'Failed to load scenario')
      } finally {
        setScenarioLoading(false)
      }
    },
    [],
  )

  const snapshotView = useMemo(() => extractSnapshotView(snapshot), [snapshot])
  const scenarioIdFromSnapshot = snapshotView?.runtime?.templateId ?? null
  const manifestIndex = useMemo(() => buildManifestIndex(capabilities), [capabilities])

  useEffect(() => {
    if (!selectedSwarmId) {
      setSelectedBeeKey(null)
      void loadScenarioDetail(null)
      return
    }
  }, [loadScenarioDetail, selectedSwarmId])

  useEffect(() => {
    // Avoid UI flicker when the cached status snapshot is temporarily missing/refreshing.
    // Only load (or reload) scenario details when we have a concrete templateId.
    if (!selectedSwarmId) return
    if (!scenarioIdFromSnapshot) return
    void loadScenarioDetail(scenarioIdFromSnapshot)
    void loadCapabilities()
  }, [loadCapabilities, loadScenarioDetail, scenarioIdFromSnapshot, selectedSwarmId])

  useEffect(() => {
    if (typeof window === 'undefined') return
    try {
      window.sessionStorage.setItem(HIVE_EXPLAIN_KEY, explainMode ? '1' : '0')
    } catch {
      // ignore
    }
  }, [explainMode])

  const toolsBar = useMemo(
    () => (
      <div className="pageToolsBarContent">
        <button type="button" className="actionButton" onClick={loadSwarms}>
          Refresh
        </button>
        <button
          type="button"
          className={showCreate ? 'actionButton' : 'actionButton actionButtonGhost'}
          onClick={() => setShowCreate(true)}
        >
          New swarm
        </button>
        <button
          type="button"
          className={explainMode ? 'actionButton' : 'actionButton actionButtonGhost'}
          title={explainMode ? 'Help (extended tooltips enabled)' : 'Help'}
          onClick={() => setShowHelp(true)}
        >
          ?
        </button>
        <span className="spinnerSlot" aria-hidden="true" title={loading || busySwarm ? 'Working…' : undefined}>
          <span className={loading || busySwarm ? 'spinner' : 'spinner spinnerHidden'} />
        </span>
      </div>
    ),
    [busySwarm, explainMode, loadSwarms, loading, showCreate],
  )

  useToolsBar(toolsBar)

  const swarmCountLabel = loading
    ? 'Loading...'
    : swarms.length === 1
      ? '1 swarm'
      : `${swarms.length} swarms`

  return (
    <div className="page hivePage">
      {showHelp ? (
        <div className="modalBackdrop" role="presentation" onClick={() => setShowHelp(false)}>
          <div className="modal" role="dialog" aria-modal="true" onClick={(event) => event.stopPropagation()}>
            <div className="modalHeader">
              <div>
                <div className="h2">Hive help</div>
                <div className="muted">Short notes about what you see.</div>
              </div>
              <button type="button" className="actionButton actionButtonGhost" onClick={() => setShowHelp(false)}>
                Close
              </button>
            </div>

            <div className="modalSection">
              <label className="field" style={{ maxWidth: 420 }}>
                <span className="fieldLabel">Extended tooltips</span>
                <div className="row" style={{ gap: 10, alignItems: 'center' }}>
                  <input
                    type="checkbox"
                    checked={explainMode}
                    onChange={(event) => setExplainMode(event.target.checked)}
                  />
                  <span className="muted">Show detailed hover tooltips (I/O ports, runtime freshness, capabilities).</span>
                </div>
              </label>
            </div>

            <div className="modalSection">
              <div className="fieldLabel">Data sources</div>
              <div className="muted">
                Runtime = live status from Swarm Controller (cached by Orchestrator). Topology = scenario definition from Scenario Manager.
              </div>
            </div>

            <div className="modalSection">
              <div className="fieldLabel">Ports / I/O</div>
              <div className="muted">
                In the scenario template, <code>work.in</code>/<code>work.out</code> are maps from <code>portId</code> to a queue
                suffix. The <code>ports</code> list is logical (used for topology edges), not a runtime binding list.
              </div>
            </div>

            <div className="modalSection">
              <div className="fieldLabel">Capabilities</div>
              <div className="muted">
                Worker config fields come from a capability manifest in Scenario Manager, matched to the worker’s runtime image.
              </div>
            </div>
          </div>
        </div>
      ) : null}

      <div>
        <h1 className="h1">Hive</h1>
        <div className="muted">Swarms and runtime controls.</div>
      </div>

      {error && <div className="card swarmMessage">{error}</div>}
      {message && <div className="card swarmMessage">{message}</div>}

      <CreateSwarmModal open={showCreate} onClose={() => setShowCreate(false)} onCreated={loadSwarms} />

      <div className="card swarmTableCard">
        <div className="row between">
          <div>
            <div className="h2">Swarms</div>
            <div className="muted">Current orchestrator view.</div>
          </div>
          <div className="muted">{swarmCountLabel}</div>
        </div>
        <div className="swarmTable">
          <div className="swarmRow swarmHeader">
            <div className="swarmCell">Swarm</div>
            <div className="swarmCell">State</div>
            <div className="swarmCell">Template</div>
            <div className="swarmCell">Bees</div>
            <div className="swarmCell swarmActions">Actions</div>
          </div>
          {swarms.map((swarm) => {
            const beeRoles =
              swarm.bees && swarm.bees.length > 0
                ? swarm.bees.map((bee) => bee.role).filter(Boolean).join(', ')
                : '—'
            const isBusy = busySwarm === swarm.id
            return (
              <div key={swarm.id} className="swarmCard">
                <div className="swarmRow">
	                  <div className="swarmCell">
	                    <div className="swarmName">{swarm.id}</div>
	                    <div className="muted">{swarm.controllerImage ?? 'controller: unknown'}</div>
	                  </div>
	                  <div className="swarmCell swarmMeta">
	                    <span
	                      className="hal-eye"
	                      data-state={halEyeState(swarm.status, swarm.health)}
	                      title={halEyeTitle(swarm.status, swarm.health)}
	                      aria-label={halEyeTitle(swarm.status, swarm.health).replace('\n', '; ')}
	                    />
	                  </div>
	                  <div className="swarmCell">
	                    <div className="swarmTemplate">{swarm.templateId ?? '—'}</div>
	                  </div>
	                  <div className="swarmCell swarmBees">{beeRoles}</div>
	                  <div className="swarmCell swarmActions">
	                    <button
	                      type="button"
	                      className="actionButton actionButtonGhost"
                      onClick={() => {
                        if (selectedSwarmId === swarm.id) {
                          navigate('/hive')
                          return
                        }
                        navigate(`/hive/${encodeURIComponent(swarm.id)}`)
                      }}
	                    >
	                      {selectedSwarmId === swarm.id ? 'Hide' : 'Details'}
	                    </button>
	                    <button
	                      type="button"
	                      className="actionButton actionButtonGhost"
	                      onClick={() => navigate(`/hive/${encodeURIComponent(swarm.id)}/view`)}
	                      title="Open compact swarm view (workers + topology)."
	                    >
	                      View
	                    </button>
	                    {selectedSwarmId === swarm.id ? (
	                      <button
	                        type="button"
	                        className="actionButton actionButtonGhost"
	                        onClick={() => void loadSnapshot()}
	                      >
	                        Refresh details
	                      </button>
	                    ) : null}
	                    <button
	                      type="button"
	                      className="actionButton"
	                      disabled={isBusy}
                      onClick={() => runSwarmAction(swarm, 'start')}
                    >
                      <span className="actionButtonContent">
                        <span>Start</span>
                      </span>
                    </button>
                    <button
                      type="button"
                      className="actionButton actionButtonGhost"
                      disabled={isBusy}
                      onClick={() => runSwarmAction(swarm, 'stop')}
                    >
                      <span className="actionButtonContent">
                        <span>Stop</span>
                      </span>
                    </button>
                    <button
                      type="button"
                      className="actionButton actionButtonDanger"
                      disabled={isBusy}
                      onClick={() => runSwarmAction(swarm, 'remove')}
                    >
                      <span className="actionButtonContent">
                        <span>Remove</span>
                      </span>
                    </button>
                  </div>
                </div>
	                {selectedSwarmId === swarm.id && (
	                  <div className="swarmDetail">
	                    {snapshotLoading && !snapshotView && (
	                      <div className="muted" style={{ marginTop: 8 }}>
	                        Loading runtime snapshot…
	                      </div>
                    )}
                    {snapshotError && (
                      <div className="card swarmMessage" style={{ marginTop: 8 }}>
                        {snapshotError}
                      </div>
                    )}
	                    {snapshotView && (
	                      <div className="card" style={{ marginTop: 10 }}>
	                        <div className="row between">
	                          <div className="h2">Runtime snapshot</div>
	                          <div
	                            className="muted"
	                            title={
	                              explainMode
	                                ? 'receivedAt = when Orchestrator cached the latest swarm-controller status-full.\nstaleAfterSec = after this age, the snapshot should be treated as stale.'
	                                : 'Snapshot freshness (receivedAt/staleAfterSec).'
	                            }
	                          >
	                            receivedAt: {snapshotView.receivedAt || '—'} · staleAfterSec:{' '}
	                            {Number.isFinite(snapshotView.staleAfterSec) ? snapshotView.staleAfterSec : '—'}
	                          </div>
	                        </div>
                        <div className="kvGrid" style={{ marginTop: 10 }}>
                          <div className="kv">
                            <div className="k">Swarm status</div>
                            <div className="v">{snapshotView.swarmStatus ?? '—'}</div>
                          </div>
                          <div className="kv">
                            <div className="k">Swarm health</div>
                            <div className="v">{snapshotView.swarmHealth ?? '—'}</div>
                          </div>
                          <div className="kv">
                            <div className="k">Enabled</div>
                            <div className="v">
                              {snapshotView.enabled === null
                                ? '—'
                                : snapshotView.enabled
                                  ? 'true'
                                  : 'false'}
                            </div>
                          </div>
                          <div className="kv">
                            <div className="k">Envelope timestamp</div>
                            <div className="v">{snapshotView.envelopeTimestamp ?? '—'}</div>
                          </div>
                          <div className="kv">
                            <div className="k">Template ID</div>
                            <div className="v">{snapshotView.runtime?.templateId ?? '—'}</div>
                          </div>
                          <div className="kv">
                            <div className="k">Run ID</div>
                            <div className="v">{snapshotView.runtime?.runId ?? '—'}</div>
                          </div>
                          <div className="kv">
                            <div className="k">Controller image</div>
                            <div className="v">{snapshotView.runtime?.image ?? '—'}</div>
                          </div>
                          <div className="kv">
                            <div className="k">Stack name</div>
                            <div className="v">{snapshotView.runtime?.stackName ?? '—'}</div>
                          </div>
                        </div>
	                      </div>
	                    )}

	                    {scenarioLoading && <div className="muted">Loading scenario topology…</div>}
	                    {scenarioError && <div className="muted">{scenarioError}</div>}
	                    {!scenarioLoading && !scenarioError && selectedScenario && (
                      <>
                        <div className="swarmDetailHeader">
                          <div>
                            <div className="swarmTemplateTitle">
                              {selectedScenario.name ?? scenarioIdFromSnapshot ?? 'Scenario'}
                            </div>
                            <div className="swarmTemplateId">{scenarioIdFromSnapshot ?? '—'}</div>
                          </div>
                          <div className="swarmDetailMeta">
                            <span className="pill pillInfo">
                              Bees {selectedScenario.template?.bees?.length ?? 0}
                            </span>
                            <span className="pill pillInfo">
                              Edges {selectedScenario.topology?.edges?.length ?? 0}
                            </span>
                          </div>
                        </div>
                        <div className="swarmDetailGrid">
                          <div className="swarmDetailSection">
                            <div className="fieldLabel">Bees</div>
                            {selectedScenario.template?.bees?.length ? (
                              <div className="swarmBeeList">
                                {selectedScenario.template.bees.map((bee, idx) => {
                                  const label = bee.role ?? bee.id ?? `bee-${idx + 1}`
                                  const key = bee.id ?? bee.role ?? `bee-${idx + 1}`
                                  const isActive = (selectedBeeKey ?? (selectedScenario.template?.bees?.[0]
                                    ? selectedScenario.template.bees[0].id ??
                                      selectedScenario.template.bees[0].role ??
                                      'bee-1'
                                    : null)) === key
                                  return (
                                    <button
                                      key={`${label}-${idx}`}
                                      type="button"
                                      className={
                                        isActive
                                          ? 'swarmBeeItem swarmBeeItemSelected'
                                          : 'swarmBeeItem'
                                      }
                                      onClick={() => setSelectedBeeKey(key)}
                                    >
                                      <div className="swarmBeeHeader">
                                        <span className="swarmBeeRole">{label}</span>
                                        <span className="swarmBeeImage">{bee.image ?? '—'}</span>
	                                      </div>
	                                      <div className="swarmBeeMeta">
	                                        <WorkIoTable
	                                          inputs={bee.work?.in}
	                                          outputs={bee.work?.out}
	                                          swarmId={selectedSwarmId}
	                                          explain={explainMode}
	                                        />
	                                      </div>
	                                    </button>
	                                  )
	                                })}
                              </div>
                            ) : (
                              <div className="muted">No bees listed.</div>
                            )}
                          </div>
                          <div className="swarmDetailSection">
                            <div className="fieldLabel">Selected worker</div>
                            {selectedScenario.template?.bees?.length ? (() => {
                              const bees = selectedScenario.template?.bees ?? []
                              const fallback = bees[0]
                              const activeKey =
                                selectedBeeKey ??
                                (fallback ? fallback.id ?? fallback.role ?? 'bee-1' : null)
	                              const activeBee =
	                                bees.find(
	                                  (bee, idx) =>
	                                    (bee.id ?? bee.role ?? `bee-${idx + 1}`) === activeKey,
	                                ) ?? fallback
	                              if (!activeBee) return <div className="muted">No bee selected.</div>
	                              const roleKey = (activeBee.role ?? '').trim().toLowerCase()
	                              const runtimeWorker =
	                                roleKey && snapshotView
	                                  ? snapshotView.workers.find(
	                                      (worker) =>
	                                        (worker.role ?? '').trim().toLowerCase() === roleKey,
	                                    ) ?? null
	                                  : null
	                              const runtimeImage = runtimeWorker?.runtime?.image ?? null
	                              const manifest = runtimeImage
	                                ? findManifestForImage(runtimeImage, manifestIndex)
	                                : null
	                              const ports = activeBee.ports
	                                ? activeBee.ports
	                                    .map((port) => `${port.id}:${port.direction}`)
	                                    .join(', ')
	                                : '—'
	                              const configEntries = Array.isArray(manifest?.config)
	                                ? manifest.config
	                                    .map((entry) => {
	                                      if (!entry || typeof entry !== 'object') return null
	                                      const value = entry as Record<string, unknown>
	                                      const name =
	                                        typeof value.name === 'string' && value.name.trim().length > 0
	                                          ? value.name.trim()
	                                          : null
	                                      const type =
	                                        typeof value.type === 'string' && value.type.trim().length > 0
	                                          ? value.type.trim()
	                                          : null
	                                      return name ? { name, type } : null
	                                    })
	                                    .filter(
	                                      (entry): entry is { name: string; type: string | null } =>
	                                        entry !== null,
	                                    )
	                                : []
		                              return (
		                                <div className="swarmWorkerDetail">
		                                  <div className="swarmBeeHeader">
		                                    <span className="swarmBeeRole">
		                                      {activeBee.role ?? activeBee.id ?? 'worker'}
		                                      {runtimeWorker?.instance ? (
		                                        <span
		                                          className="swarmBeeInstance"
		                                          title={`instance: ${runtimeWorker.instance}`}
		                                        >
		                                          ({runtimeWorker.instance})
		                                        </span>
		                                      ) : null}
		                                    </span>
		                                    <span className="swarmBeeImage">{runtimeImage ?? activeBee.image ?? '—'}</span>
		                                  </div>
		                                  {(() => {
		                                    const role =
		                                      typeof activeBee.role === 'string' && activeBee.role.trim().length > 0
		                                        ? activeBee.role.trim()
		                                        : null
		                                    const selectionKey =
		                                      selectedSwarmId && activeKey
		                                        ? `${selectedSwarmId}::${activeKey}`
		                                        : null
		                                    const selection = selectionKey ? tapIoSelection[selectionKey] ?? null : null
		                                    const ioNamesIn = activeBee.work?.in
		                                      ? Object.keys(activeBee.work.in)
		                                          .map((name) => name.trim())
		                                          .filter((name) => name.length > 0)
		                                          .sort((a, b) => a.localeCompare(b))
		                                      : []
		                                    const ioNamesOut = activeBee.work?.out
		                                      ? Object.keys(activeBee.work.out)
		                                          .map((name) => name.trim())
		                                          .filter((name) => name.length > 0)
		                                          .sort((a, b) => a.localeCompare(b))
		                                      : []
		                                    const hasIn = ioNamesIn.length > 0
		                                    const hasOut = ioNamesOut.length > 0
		                                    const selectedIn = typeof selection?.in === 'string' ? selection.in : null
		                                    const selectedOut = typeof selection?.out === 'string' ? selection.out : null
		                                    const ioNameIn = hasIn
		                                      ? selectedIn && ioNamesIn.includes(selectedIn)
		                                        ? selectedIn
		                                        : ioNamesIn[0]
		                                      : null
		                                    const ioNameOut = hasOut
		                                      ? selectedOut && ioNamesOut.includes(selectedOut)
		                                        ? selectedOut
		                                        : ioNamesOut[0]
		                                      : null
		                                    const canTapIn = Boolean(selectedSwarmId && role && hasIn)
		                                    const canTapOut = Boolean(selectedSwarmId && role && hasOut)
		                                    const tapOutKey = canTapOut && role ? tapBusyKey(role, 'OUT', ioNameOut) : null
		                                    const tapInKey = canTapIn && role ? tapBusyKey(role, 'IN', ioNameIn) : null
		                                    return (
		                                      <div className="chipRow">
		                                        <button
		                                          type="button"
		                                          className="actionButton actionButtonGhost actionButtonTiny"
		                                          title={
		                                            !role
		                                              ? 'Tap requires bee.role to be set in scenario template.'
		                                              : !hasOut
		                                                ? 'No outputs configured for this worker (work.out is empty).'
		                                                : `Open Debug Tap Viewer (OUT, ioName=${ioNameOut}). Creates an ephemeral tap queue and opens the viewer in a new tab.`
		                                          }
		                                          disabled={!canTapOut || (tapOutKey ? tapBusy[tapOutKey] === true : false)}
		                                          onClick={() => {
		                                            if (!role || !ioNameOut) return
		                                            const key = tapBusyKey(role, 'OUT', ioNameOut)
		                                            if (tapBusy[key]) return
		                                            setTapBusy((prev) => ({ ...prev, [key]: true }))
		                                            openTapViewer(role, 'OUT', ioNameOut)
		                                              .catch((err) => {
		                                                console.error(err)
		                                                setMessage(
		                                                  err instanceof Error
		                                                    ? err.message
		                                                    : 'Failed to create debug tap.',
		                                                )
		                                              })
		                                              .finally(() => {
		                                                setTapBusy((prev) => {
		                                                  if (!prev[key]) return prev
		                                                  const next = { ...prev }
		                                                  delete next[key]
		                                                  return next
		                                                })
		                                              })
		                                          }}
		                                        >
		                                          <span className="actionButtonContent">
		                                            <span>Tap OUT</span>
		                                          </span>
		                                        </button>
		                                        {ioNamesOut.length > 1 ? (
		                                          <label
		                                            className="row"
		                                            style={{ gap: 6, alignItems: 'center' }}
		                                            title="Select which work.out ioName to tap."
		                                          >
		                                            <span className="muted">out</span>
		                                            <select
		                                              className="tapIoSelect"
		                                              value={ioNameOut ?? ''}
		                                              disabled={!hasOut || !selectionKey}
		                                              aria-label="Tap OUT ioName"
		                                              onChange={(e) => {
		                                                if (!selectionKey) return
		                                                const next = e.currentTarget.value
		                                                setTapIoSelection((prev) => ({
		                                                  ...prev,
		                                                  [selectionKey]: { ...(prev[selectionKey] ?? {}), out: next },
		                                                }))
		                                              }}
		                                            >
		                                              {ioNamesOut.map((name) => (
		                                                <option key={name} value={name}>
		                                                  {name}
		                                                </option>
		                                              ))}
		                                            </select>
		                                          </label>
		                                        ) : null}
		                                        <button
		                                          type="button"
		                                          className="actionButton actionButtonGhost actionButtonTiny"
		                                          title={
		                                            !role
		                                              ? 'Tap requires bee.role to be set in scenario template.'
		                                              : !hasIn
		                                                ? 'No inputs configured for this worker (work.in is empty).'
		                                                : `Open Debug Tap Viewer (IN, ioName=${ioNameIn}). Creates an ephemeral tap queue and opens the viewer in a new tab.`
		                                          }
		                                          disabled={!canTapIn || (tapInKey ? tapBusy[tapInKey] === true : false)}
		                                          onClick={() => {
		                                            if (!role || !ioNameIn) return
		                                            const key = tapBusyKey(role, 'IN', ioNameIn)
		                                            if (tapBusy[key]) return
		                                            setTapBusy((prev) => ({ ...prev, [key]: true }))
		                                            openTapViewer(role, 'IN', ioNameIn)
		                                              .catch((err) => {
		                                                console.error(err)
		                                                setMessage(
		                                                  err instanceof Error
		                                                    ? err.message
		                                                    : 'Failed to create debug tap.',
		                                                )
		                                              })
		                                              .finally(() => {
		                                                setTapBusy((prev) => {
		                                                  if (!prev[key]) return prev
		                                                  const next = { ...prev }
		                                                  delete next[key]
		                                                  return next
		                                                })
		                                              })
		                                          }}
		                                        >
		                                          <span className="actionButtonContent">
		                                            <span>Tap IN</span>
		                                          </span>
		                                        </button>
		                                        {ioNamesIn.length > 1 ? (
		                                          <label
		                                            className="row"
		                                            style={{ gap: 6, alignItems: 'center' }}
		                                            title="Select which work.in ioName to tap."
		                                          >
		                                            <span className="muted">in</span>
		                                            <select
		                                              className="tapIoSelect"
		                                              value={ioNameIn ?? ''}
		                                              disabled={!hasIn || !selectionKey}
		                                              aria-label="Tap IN ioName"
		                                              onChange={(e) => {
		                                                if (!selectionKey) return
		                                                const next = e.currentTarget.value
		                                                setTapIoSelection((prev) => ({
		                                                  ...prev,
		                                                  [selectionKey]: { ...(prev[selectionKey] ?? {}), in: next },
		                                                }))
		                                              }}
		                                            >
		                                              {ioNamesIn.map((name) => (
		                                                <option key={name} value={name}>
		                                                  {name}
		                                                </option>
		                                              ))}
		                                            </select>
		                                          </label>
		                                        ) : null}
		                                        {!role ? (
		                                          <span className="muted">tap: role missing</span>
		                                        ) : !hasIn && !hasOut ? (
		                                          <span className="muted">tap: no I/O</span>
		                                        ) : (
		                                          <span
		                                            className="muted"
		                                            title="Tap uses the first configured ioName for each direction (sorted)."
		                                          >
		                                            io {ioNameIn ?? '—'}/{ioNameOut ?? '—'}
		                                          </span>
		                                        )}
		                                      </div>
		                                    )
		                                  })()}
		                                  <div className="swarmBeeMeta">
		                                    <span
		                                      title={
		                                        explainMode
		                                          ? 'beeId is a stable id inside the scenario template.\nIt is used by topology.edges[].from/to.beeId.'
		                                          : 'Scenario beeId.'
		                                      }
		                                    >
		                                      id: {activeBee.id ?? '—'}
		                                    </span>
		                                    <span
		                                      title={
		                                        explainMode
		                                          ? 'Logical ports used by topology edges.\nWhen topology is present, work.in/out keys should match these port ids.'
		                                          : 'Logical ports (for topology).'
		                                      }
		                                    >
		                                      ports: {ports}
		                                    </span>
		                                    <WorkIoTable
		                                      inputs={activeBee.work?.in}
		                                      outputs={activeBee.work?.out}
		                                      swarmId={selectedSwarmId}
		                                      explain={explainMode}
		                                    />
		                                    {runtimeWorker ? (
		                                      <div
		                                        className="chipRow"
		                                        title={
		                                          explainMode
		                                            ? 'Runtime comes from the cached swarm-controller status-full snapshot (via Orchestrator).\nThe "seen" value is based on the worker lastSeenAt reported by the swarm controller.'
		                                            : 'Runtime snapshot.'
		                                        }
		                                      >
		                                        <span
		                                          className={`chip ${
		                                            runtimeWorker.enabled === false ? 'chip-event' : 'chip-outcome'
		                                          }`}
		                                          title={
		                                            runtimeWorker.enabled === null
		                                              ? 'Enabled flag missing in snapshot.'
		                                              : runtimeWorker.enabled === false
		                                                ? 'Worker is disabled.'
		                                                : 'Worker is enabled.'
		                                          }
		                                        >
		                                          {runtimeWorker.enabled === null
		                                            ? 'enabled?'
		                                            : runtimeWorker.enabled === false
		                                              ? 'disabled'
		                                              : 'enabled'}
		                                        </span>
		                                        <span className="chip chip-metric" title={'Current worker throughput (TPS).'}>
		                                          tps {runtimeWorker.tps ?? '—'}
		                                        </span>
		                                        <span
		                                          className={`chip ${
		                                            runtimeWorker.stale ? 'chip-metric' : 'chip-outcome'
		                                          }`}
		                                          title={
		                                            explainMode
		                                              ? `lastSeenAt: ${runtimeWorker.lastSeenAt ?? '—'}\nIf lastSeenAt is older than ~15s, the worker is treated as stale.`
		                                              : `lastSeenAt: ${runtimeWorker.lastSeenAt ?? '—'}`
		                                          }
		                                        >
		                                          seen {formatAge(runtimeWorker.lastSeenAt)}
		                                        </span>
		                                      </div>
		                                    ) : (
		                                      <span className="muted">
		                                        runtime: no worker snapshot found for role (yet)
		                                      </span>
		                                    )}
		                                    {!capabilitiesLoaded ? (
		                                      <span className="muted">capabilities: loading…</span>
		                                    ) : capabilitiesError ? (
		                                      <span className="muted">capabilities: {capabilitiesError}</span>
		                                    ) : !runtimeImage ? (
		                                      <span className="muted">capabilities: runtime image missing</span>
		                                    ) : !manifest ? (
		                                      <span className="muted">
		                                        capabilities: manifest not found for image
		                                      </span>
		                                    ) : (
		                                      <>
		                                        <div
		                                          className="chipRow"
		                                          title={
		                                            explainMode
		                                              ? 'Config/actions come from the capability manifest.\nManifest is matched to the worker runtime image.'
		                                              : 'Capabilities summary.'
		                                          }
		                                        >
		                                          <span className="chip chip-metric">
		                                            config fields {manifest.config.length}
		                                          </span>
		                                          <span className="chip chip-metric">
		                                            actions {manifest.actions.length ? manifest.actions.length : 'none'}
		                                          </span>
		                                        </div>
		                                        {configEntries.length ? (
		                                          <span>
		                                            config fields:{' '}
		                                            {configEntries
		                                              .slice(0, 10)
		                                              .map((entry) =>
		                                                entry.type ? `${entry.name}:${entry.type}` : entry.name,
		                                              )
	                                              .join(', ')}
	                                            {configEntries.length > 10 ? '…' : ''}
	                                          </span>
		                                        ) : (
		                                          <span className="muted">config fields: —</span>
		                                        )}
	                                      </>
	                                    )}
	                                  </div>
	                                </div>
	                              )
	                            })() : (
	                              <div className="muted">No bees listed.</div>
                            )}
                          </div>
                          <div className="swarmDetailSection swarmDetailSectionWide">
                            <div className="fieldLabel">Topology</div>
                            {selectedScenario.topology?.edges?.length ? (
                              <div className="swarmEdgeList">
                                {selectedScenario.topology.edges.map((edge, idx) => {
                                  const from = edge.from
                                    ? `${edge.from.beeId ?? 'bee'}:${edge.from.port ?? 'port'}`
                                    : '—'
                                  const to = edge.to
                                    ? `${edge.to.beeId ?? 'bee'}:${edge.to.port ?? 'port'}`
                                    : '—'
                                  return (
                                    <div key={`${edge.id ?? idx}`} className="swarmEdgeItem">
                                      <span>{edge.id ?? `edge-${idx + 1}`}</span>
                                      <span className="muted">
                                        {from} → {to}
                                      </span>
                                    </div>
                                  )
                                })}
                              </div>
                            ) : (
                              <div className="muted">No topology edges defined.</div>
                            )}
                          </div>
                        </div>
                      </>
                    )}
                    {!scenarioLoading && !scenarioError && !selectedScenario && (
                      <div className="muted">Scenario details unavailable.</div>
                    )}
                  </div>
                )}
              </div>
            )
          })}
          {loading && swarms.length === 0 && <div className="muted">Loading swarms...</div>}
          {!loading && swarms.length === 0 && (
            <div className="muted">No swarms found. Create one to get started.</div>
          )}
        </div>
      </div>
    </div>
  )
}
