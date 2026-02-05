import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { subscribeStatusSnapshots } from '../lib/controlPlane/stateStore'
import {
  Background,
  Controls,
  Handle,
  MarkerType,
  Position,
  ReactFlow,
  applyEdgeChanges,
  applyNodeChanges,
  type Edge,
  type Node,
  type NodeTypes,
  type OnEdgesChange,
  type OnNodesChange,
  type ReactFlowInstance,
} from '@xyflow/react'
import '@xyflow/react/dist/style.css'

type ScenarioBeePort = { id: string; direction: 'in' | 'out' }

type ScenarioBee = {
  id: string | null
  role: string | null
  image: string | null
  work: {
    in: Record<string, string> | undefined
    out: Record<string, string> | undefined
  } | null
  ports: ScenarioBeePort[] | null
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

type QueueStatsEntry = {
  depth: number
  consumers: number
  oldestAgeSec?: number
}

type WorkerIoState = {
  input?: string
  output?: string
}

type WorkerSnapshot = {
  role: string
  instance: string
  timestamp: string
  rawEnvelope: unknown
  enabled?: boolean
  tps?: number
  startedAt?: string
  ioState?: WorkerIoState
  workQueues?: {
    in?: string[]
    out?: string[]
    routes?: string[]
  }
  queueStats?: Record<string, QueueStatsEntry>
}

type PositionedNode = {
  id: string
  label: string
  role: string
  x: number
  y: number
}

type PositionedEdge = {
  id: string
  from: string
  to: string
  label: string
  depth: number | null
  consumers: number | null
}

const ORCHESTRATOR_BASE = '/orchestrator/api'
const POSITIONS_STORAGE_PREFIX = 'PH_UI_SWARM_VIEW_POS:'

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

function toStringOrNull(value: unknown): string | null {
  return typeof value === 'string' && value.trim().length > 0 ? value.trim() : null
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

function shorten(value: string | null | undefined, limit: number) {
  if (!value) return null
  if (value.length <= limit) return value
  return `${value.slice(0, Math.max(0, limit - 1))}…`
}

function isIoKnown(value: string | null | undefined) {
  if (!value) return false
  const v = value.trim().toLowerCase()
  return v.length > 0 && v !== 'unknown'
}

type FlowNodeData = {
  title: string
  subtitle: string
  dim: boolean
}

function WorkerNode({ data, selected }: { data: FlowNodeData; selected: boolean }) {
  const cls = selected
    ? 'swarmFlowNode swarmFlowNodeSelected'
    : data.dim
      ? 'swarmFlowNode swarmFlowNodeDim'
      : 'swarmFlowNode'
  return (
    <div className={cls}>
      <Handle type="target" position={Position.Left} className="swarmFlowHandle" />
      <Handle type="source" position={Position.Right} className="swarmFlowHandle" />
      <div className="swarmFlowNodeTitle">{data.title}</div>
      <div className="swarmFlowNodeSubtitle" title={data.subtitle}>
        {data.subtitle}
      </div>
    </div>
  )
}

const nodeTypes: NodeTypes = { worker: WorkerNode as any }

function loadStoredPositions(swarmId: string) {
  if (!swarmId) return {} as Record<string, { x: number; y: number }>
  try {
    const raw = window.sessionStorage.getItem(`${POSITIONS_STORAGE_PREFIX}${swarmId}`)
    if (!raw) return {}
    const parsed = JSON.parse(raw) as unknown
    if (!isRecord(parsed)) return {}
    const result: Record<string, { x: number; y: number }> = {}
    for (const [key, value] of Object.entries(parsed)) {
      if (!key || !isRecord(value)) continue
      const x = typeof value.x === 'number' && Number.isFinite(value.x) ? value.x : null
      const y = typeof value.y === 'number' && Number.isFinite(value.y) ? value.y : null
      if (x === null || y === null) continue
      result[key] = { x, y }
    }
    return result
  } catch {
    return {}
  }
}

function storePositions(swarmId: string, positions: Record<string, { x: number; y: number }>) {
  if (!swarmId) return
  try {
    window.sessionStorage.setItem(`${POSITIONS_STORAGE_PREFIX}${swarmId}`, JSON.stringify(positions))
  } catch {
    // ignore
  }
}

function scoreQueueCandidate(queueName: string, swarmId: string, suffix: string) {
  const normalizedQueue = queueName.toLowerCase()
  const normalizedSwarm = swarmId.toLowerCase()
  const normalizedSuffix = suffix.toLowerCase()
  let score = 0
  if (normalizedQueue.includes(normalizedSwarm)) score += 5
  if (normalizedQueue.endsWith(`.${normalizedSuffix}`)) score += 4
  if (normalizedQueue.endsWith(`-${normalizedSuffix}`)) score += 3
  if (normalizedQueue.endsWith(`_${normalizedSuffix}`)) score += 3
  if (normalizedQueue.endsWith(normalizedSuffix)) score += 2
  if (normalizedQueue.includes(normalizedSuffix)) score += 1
  return score
}

function resolveQueueBySuffix(queueStatsKeys: string[], swarmId: string, suffix: string) {
  if (!suffix) return null
  let best: { key: string; score: number } | null = null
  for (const key of queueStatsKeys) {
    const score = scoreQueueCandidate(key, swarmId, suffix)
    if (score <= 0) continue
    if (!best || score > best.score) {
      best = { key, score }
    }
  }
  return best?.key ?? null
}

function asScenarioDefinition(data: unknown): ScenarioDefinition | null {
  if (!isRecord(data)) return null
  const templateRaw = isRecord(data.template) ? data.template : null
  const beesRaw = Array.isArray(templateRaw?.bees) ? (templateRaw?.bees as unknown[]) : null
  const bees: ScenarioBee[] | undefined = beesRaw
    ? beesRaw
        .map((bee) => {
          if (!isRecord(bee)) return null
          const work = isRecord(bee.work) ? bee.work : null
          const workIn = isRecord(work?.in) ? (work?.in as Record<string, string>) : undefined
          const workOut = isRecord(work?.out) ? (work?.out as Record<string, string>) : undefined
          const portsRaw = Array.isArray(bee.ports) ? (bee.ports as unknown[]) : null
          const ports: ScenarioBeePort[] | null = portsRaw
            ? portsRaw
                .map((entry) => {
                  if (!isRecord(entry)) return null
                  const id = toStringOrNull(entry.id)
                  const direction = toStringOrNull(entry.direction)
                  if (!id || (direction !== 'in' && direction !== 'out')) return null
                  return { id, direction }
                })
                .filter((entry): entry is ScenarioBeePort => entry !== null)
            : null
          return {
            id: toStringOrNull(bee.id),
            role: toStringOrNull(bee.role),
            image: toStringOrNull(bee.image),
            work: work ? { in: workIn, out: workOut } : null,
            ports,
          } satisfies ScenarioBee
        })
        .filter((entry): entry is ScenarioBee => entry !== null)
    : undefined

  const topologyRaw = isRecord(data.topology) ? data.topology : null
  const edgesRaw = Array.isArray(topologyRaw?.edges) ? (topologyRaw?.edges as unknown[]) : null
  const edges: ScenarioTopologyEdge[] | undefined = edgesRaw
    ? edgesRaw
        .map((edge) => {
          if (!isRecord(edge)) return null
          const from = isRecord(edge.from) ? edge.from : null
          const to = isRecord(edge.to) ? edge.to : null
          return {
            id: toStringOrNull(edge.id),
            from: from
              ? {
                  beeId: toStringOrNull(from.beeId),
                  port: toStringOrNull(from.port),
                }
              : null,
            to: to
              ? {
                  beeId: toStringOrNull(to.beeId),
                  port: toStringOrNull(to.port),
                }
              : null,
          } satisfies ScenarioTopologyEdge
        })
        .filter((entry): entry is ScenarioTopologyEdge => entry !== null)
    : undefined

  return {
    id: toStringOrNull(data.id),
    name: toStringOrNull(data.name),
    description: toStringOrNull(data.description),
    template: templateRaw
      ? {
          image: toStringOrNull(templateRaw.image),
          bees,
        }
      : null,
    topology: topologyRaw
      ? {
          version: typeof topologyRaw.version === 'number' ? topologyRaw.version : null,
          edges,
        }
      : null,
  }
}

function parseWorkerSnapshot(envelope: unknown): WorkerSnapshot | null {
  if (!isRecord(envelope)) return null
  const scope = isRecord(envelope.scope) ? envelope.scope : null
  const data = isRecord(envelope.data) ? envelope.data : null
  const role = scope ? toStringOrNull(scope.role) : null
  const instance = scope ? toStringOrNull(scope.instance) : null
  const timestamp = toStringOrNull(envelope.timestamp)
  if (!role || !instance || !timestamp || !data) return null

  const ioStateRaw = isRecord(data.ioState) && isRecord(data.ioState.work) ? data.ioState.work : null
  const ioState: WorkerIoState | undefined = ioStateRaw
    ? {
        input: toStringOrNull(ioStateRaw.input) ?? undefined,
        output: toStringOrNull(ioStateRaw.output) ?? undefined,
      }
    : undefined

  const ioWorkRaw = isRecord(data.io) && isRecord(data.io.work) ? data.io.work : null
  const queuesRaw = ioWorkRaw && isRecord(ioWorkRaw.queues) ? ioWorkRaw.queues : null
  const workQueues =
    queuesRaw && (Array.isArray(queuesRaw.in) || Array.isArray(queuesRaw.out) || Array.isArray(queuesRaw.routes))
      ? {
          in: Array.isArray(queuesRaw.in) ? (queuesRaw.in.filter((v) => typeof v === 'string') as string[]) : undefined,
          out: Array.isArray(queuesRaw.out) ? (queuesRaw.out.filter((v) => typeof v === 'string') as string[]) : undefined,
          routes: Array.isArray(queuesRaw.routes)
            ? (queuesRaw.routes.filter((v) => typeof v === 'string') as string[])
            : undefined,
        }
      : undefined

  const queueStatsRaw = ioWorkRaw && isRecord(ioWorkRaw.queueStats) ? ioWorkRaw.queueStats : null
  const queueStats: Record<string, QueueStatsEntry> | undefined = queueStatsRaw
    ? (() => {
        const result: Record<string, QueueStatsEntry> = {}
        for (const [key, value] of Object.entries(queueStatsRaw)) {
          if (!key || !isRecord(value)) continue
          const depth = typeof value.depth === 'number' ? value.depth : null
          const consumers = typeof value.consumers === 'number' ? value.consumers : null
          if (depth === null || consumers === null) continue
          const oldestAgeSec = typeof value.oldestAgeSec === 'number' ? value.oldestAgeSec : undefined
          result[key] = { depth, consumers, ...(oldestAgeSec == null ? {} : { oldestAgeSec }) }
        }
        return result
      })()
    : undefined

  const enabled = typeof data.enabled === 'boolean' ? data.enabled : undefined
  const tps = typeof data.tps === 'number' ? data.tps : undefined
  const startedAt = toStringOrNull(data.startedAt) ?? undefined

  return { role, instance, timestamp, rawEnvelope: envelope, enabled, tps, startedAt, ioState, workQueues, queueStats }
}

function topoLayers(nodes: string[], edges: Array<{ from: string; to: string }>) {
  const inDegree = new Map<string, number>(nodes.map((n) => [n, 0]))
  const outgoing = new Map<string, string[]>(nodes.map((n) => [n, []]))
  edges.forEach((e) => {
    if (!inDegree.has(e.from) || !inDegree.has(e.to)) return
    outgoing.get(e.from)!.push(e.to)
    inDegree.set(e.to, (inDegree.get(e.to) ?? 0) + 1)
  })
  const queue: string[] = nodes.filter((n) => (inDegree.get(n) ?? 0) === 0)
  const layer = new Map<string, number>()
  queue.forEach((n) => layer.set(n, 0))
  while (queue.length) {
    const n = queue.shift()!
    const base = layer.get(n) ?? 0
    for (const out of outgoing.get(n) ?? []) {
      inDegree.set(out, (inDegree.get(out) ?? 0) - 1)
      const next = Math.max(layer.get(out) ?? 0, base + 1)
      layer.set(out, next)
      if ((inDegree.get(out) ?? 0) === 0) {
        queue.push(out)
      }
    }
  }
  // cycles fallback: keep everything at layer 0
  const maxLayer = Math.max(0, ...Array.from(layer.values()))
  const layers: string[][] = Array.from({ length: maxLayer + 1 }, () => [])
  nodes.forEach((id) => {
    const idx = layer.get(id)
    layers[(idx ?? 0)].push(id)
  })
  return layers
}

function buildGraphModel(args: {
  swarmId: string
  scenario: ScenarioDefinition
  snapshotsByRole: Map<string, WorkerSnapshot[]>
}) {
  const bees = args.scenario.template?.bees ?? []
  const edges = args.scenario.topology?.edges ?? []
  const beeById = new Map<string, ScenarioBee>()
  bees.forEach((bee, idx) => {
    const key = bee.id ?? (bee.role ? `${bee.role}-${idx + 1}` : `bee-${idx + 1}`)
    if (key) {
      beeById.set(key, bee)
    }
  })

  const nodeIds = Array.from(beeById.keys())
  const topoEdges = edges
    .map((e) => {
      const from = e.from?.beeId ?? null
      const to = e.to?.beeId ?? null
      if (!from || !to) return null
      return { id: e.id ?? `${from}->${to}`, from, to, fromPort: e.from?.port ?? null, toPort: e.to?.port ?? null }
    })
    .filter((e): e is { id: string; from: string; to: string; fromPort: string | null; toPort: string | null } => e !== null)

  const layers = topoLayers(nodeIds, topoEdges.map((e) => ({ from: e.from, to: e.to })))
  const nodePositions = new Map<string, { x: number; y: number }>()

  const startX = 60
  const startY = 50
  const colGap = 220
  const rowGap = 110
  layers.forEach((layerNodes, col) => {
    layerNodes.forEach((id, row) => {
      nodePositions.set(id, { x: startX + col * colGap, y: startY + row * rowGap })
    })
  })

  const positionedNodes: PositionedNode[] = nodeIds.map((id) => {
    const bee = beeById.get(id)
    const pos = nodePositions.get(id) ?? { x: startX, y: startY }
    const role = (bee?.role ?? id).trim()
    const label = bee?.role ? bee.role : id
    return { id, label, role, x: pos.x, y: pos.y }
  })

  const allQueueStats = new Map<string, QueueStatsEntry>()
  for (const snapshots of args.snapshotsByRole.values()) {
    for (const snap of snapshots) {
      if (!snap.queueStats) continue
      for (const [queue, entry] of Object.entries(snap.queueStats)) {
        const existing = allQueueStats.get(queue)
        if (!existing || entry.depth > existing.depth) {
          allQueueStats.set(queue, entry)
        }
      }
    }
  }

  const queueStatKeys = Array.from(allQueueStats.keys())

  const positionedEdges: PositionedEdge[] = topoEdges.map((edge) => {
    const fromBee = beeById.get(edge.from) ?? null
    const toBee = beeById.get(edge.to) ?? null
    const fromSuffix = edge.fromPort && fromBee?.work?.out ? fromBee.work.out[edge.fromPort] ?? null : null
    const toSuffix = edge.toPort && toBee?.work?.in ? toBee.work.in[edge.toPort] ?? null : null
    const suffix = fromSuffix && toSuffix && fromSuffix === toSuffix ? fromSuffix : fromSuffix ?? toSuffix ?? null
    const resolvedQueue = suffix ? resolveQueueBySuffix(queueStatKeys, args.swarmId, suffix) : null
    const stat = resolvedQueue ? allQueueStats.get(resolvedQueue) ?? null : null
    const depth = stat ? stat.depth : null
    const consumers = stat ? stat.consumers : null
    const labelParts = [
      edge.fromPort && edge.toPort ? `${edge.fromPort}→${edge.toPort}` : null,
      suffix ? suffix : null,
      depth !== null || consumers !== null ? `d ${depth ?? '—'} · c ${consumers ?? '—'}` : null,
    ].filter((p): p is string => Boolean(p))
    return {
      id: edge.id,
      from: edge.from,
      to: edge.to,
      label: labelParts.join(' '),
      depth,
      consumers,
    }
  })

  return { nodes: positionedNodes, edges: positionedEdges, beeById, allQueueStats }
}

export function SwarmViewPage() {
  const { swarmId: swarmIdParam } = useParams<{ swarmId: string }>()
  const swarmId = swarmIdParam ? swarmIdParam.trim() : ''

  const [scenario, setScenario] = useState<ScenarioDefinition | null>(null)
  const [scenarioError, setScenarioError] = useState<string | null>(null)
  const [scenarioLoading, setScenarioLoading] = useState(false)

  const [workers, setWorkers] = useState<WorkerSnapshot[]>([])
  const workersRef = useRef<WorkerSnapshot[]>([])
  const coalesceTimer = useRef<number | null>(null)
  const [selectedNodeId, setSelectedNodeId] = useState<string | null>(null)
  const [rawModal, setRawModal] = useState<{ title: string; json: string } | null>(null)
  const [flowNodes, setFlowNodes] = useState<Node<FlowNodeData>[]>([])
  const [flowEdges, setFlowEdges] = useState<Edge[]>([])
  const storedPositionsRef = useRef<Record<string, { x: number; y: number }>>({})
  const didInitialFitRef = useRef(false)
  const isBusyTop = scenarioLoading

  useEffect(() => {
    setSelectedNodeId(null)
    setRawModal(null)
    storedPositionsRef.current = loadStoredPositions(swarmId)
    didInitialFitRef.current = false
  }, [swarmId])

  const refreshWorkers = useCallback((next: WorkerSnapshot[]) => {
    workersRef.current = next
    if (coalesceTimer.current != null) {
      return
    }
    coalesceTimer.current = window.setTimeout(() => {
      coalesceTimer.current = null
      setWorkers(workersRef.current)
    }, 300)
  }, [])

  useEffect(() => {
    if (!swarmId) return
    const unsubscribe = subscribeStatusSnapshots((entries) => {
      const next: WorkerSnapshot[] = []
      for (const entry of entries) {
        const envelope = entry.envelope
        if (envelope.scope.swarmId !== swarmId) continue
        const parsed = parseWorkerSnapshot(envelope)
        if (parsed) next.push(parsed)
      }
      // keep only latest per (role, instance)
      const latest = new Map<string, WorkerSnapshot>()
      next.forEach((snap) => {
        const key = `${snap.role}:${snap.instance}`
        const existing = latest.get(key)
        if (!existing || Date.parse(snap.timestamp) >= Date.parse(existing.timestamp)) {
          latest.set(key, snap)
        }
      })
      refreshWorkers(Array.from(latest.values()))
    })
    return () => {
      unsubscribe()
      if (coalesceTimer.current != null) {
        window.clearTimeout(coalesceTimer.current)
        coalesceTimer.current = null
      }
    }
  }, [refreshWorkers, swarmId])

  useEffect(() => {
    if (!swarmId) {
      setScenario(null)
      setScenarioError('Missing swarmId.')
      setScenarioLoading(false)
      return
    }
    let cancelled = false
    const load = async () => {
      setScenarioLoading(true)
      try {
        const response = await fetch(`${ORCHESTRATOR_BASE}/swarms`, {
          headers: { Accept: 'application/json' },
        })
        if (!response.ok) throw new Error(`Failed to load swarms (HTTP ${response.status})`)
        const swarms = (await response.json()) as unknown
        const templateId =
          Array.isArray(swarms)
            ? (() => {
                const found = swarms.find((s) => isRecord(s) && s.id === swarmId)
                return found && isRecord(found) ? toStringOrNull(found.templateId) : null
              })()
            : null
        if (!templateId) {
          throw new Error('Swarm has no templateId yet (start swarm-controller or refresh).')
        }
        const scenarioResponse = await fetch(
          `/scenario-manager/scenarios/${encodeURIComponent(templateId)}`,
          { headers: { Accept: 'application/json' } },
        )
        if (!scenarioResponse.ok) {
          throw new Error(`Failed to load scenario ${templateId} (HTTP ${scenarioResponse.status})`)
        }
        const payload = await scenarioResponse.json()
        const parsed = asScenarioDefinition(payload)
        if (!parsed) {
          throw new Error('Scenario payload could not be parsed.')
        }
        if (!cancelled) {
          setScenarioError(null)
          setScenario(parsed)
        }
      } catch (err) {
        if (!cancelled) {
          setScenarioError(err instanceof Error ? err.message : 'Failed to load scenario')
        }
      } finally {
        if (!cancelled) setScenarioLoading(false)
      }
    }
    void load()
    return () => {
      cancelled = true
    }
  }, [swarmId])

  const snapshotsByRole = useMemo(() => {
    const map = new Map<string, WorkerSnapshot[]>()
    for (const snap of workers) {
      const key = snap.role.trim().toLowerCase()
      const bucket = map.get(key) ?? []
      bucket.push(snap)
      map.set(key, bucket)
    }
    for (const bucket of map.values()) {
      bucket.sort((a, b) => Date.parse(b.timestamp) - Date.parse(a.timestamp))
    }
    return map
  }, [workers])

  const graph = useMemo(() => {
    if (!scenario) return null
    return buildGraphModel({ swarmId, scenario, snapshotsByRole })
  }, [scenario, snapshotsByRole, swarmId])

  const onNodesChange: OnNodesChange = useCallback(
    (changes) => {
      setFlowNodes((nodes) => applyNodeChanges(changes, nodes) as Node<FlowNodeData>[])
    },
    [setFlowNodes],
  )

  const onEdgesChange: OnEdgesChange = useCallback(
    (changes) => {
      setFlowEdges((edges) => applyEdgeChanges(changes, edges) as Edge[])
    },
    [setFlowEdges],
  )

  const persistNodePosition = useCallback(
    (id: string, position: { x: number; y: number }) => {
      const next = { ...storedPositionsRef.current, [id]: position }
      storedPositionsRef.current = next
      storePositions(swarmId, next)
    },
    [swarmId],
  )

  const onFlowInit = useCallback((instance: ReactFlowInstance<Node<FlowNodeData>, Edge>) => {
    if (didInitialFitRef.current) return
    didInitialFitRef.current = true
    instance.fitView({ padding: 0.2, maxZoom: 1.6 })
  }, [])

  useEffect(() => {
    if (!graph) return
    const stored = storedPositionsRef.current
    const selected = selectedNodeId

    setFlowNodes((prev) => {
      const prevById = new Map(prev.map((n) => [n.id, n] as const))
      return graph.nodes.map((n) => {
        const existing = prevById.get(n.id)
        const storedPos = stored[n.id]
        const position = storedPos ?? existing?.position ?? { x: n.x, y: n.y }
        const snaps = snapshotsByRole.get(n.role.trim().toLowerCase()) ?? []
        const snap = snaps[0]
        const subtitle = snap
          ? `${shorten(snap.instance, 22) ?? snap.instance} · ${formatAge(snap.timestamp)}`
          : 'no status'
        const dim = selected ? selected !== n.id : false
        const existingData = existing?.data
        const nextData: FlowNodeData = { title: n.label, subtitle, dim }
        return {
          id: n.id,
          type: 'worker',
          position,
          data: existingData && existingData.title === nextData.title && existingData.subtitle === nextData.subtitle && existingData.dim === nextData.dim ? existingData : nextData,
        } satisfies Node<FlowNodeData>
      })
    })

    setFlowEdges(() => {
      return graph.edges.map((e) => {
        const depth = e.depth ?? 0
        const stroke = depth > 0 ? 'rgba(255, 95, 95, 0.85)' : 'rgba(51, 225, 255, 0.7)'
        const strokeWidth = 1.5 + Math.log(depth + 1)
        const dim = selected ? (e.from === selected || e.to === selected ? 1 : 0.25) : 1
        return {
          id: e.id,
          source: e.from,
          target: e.to,
          type: 'smoothstep',
          animated: false,
          markerEnd: { type: MarkerType.ArrowClosed, color: stroke },
          style: { stroke, strokeWidth, opacity: dim },
          label: e.label,
          labelStyle: { fill: 'rgba(255,255,255,0.8)', fontSize: 11 },
          labelBgStyle: { fill: 'rgba(0,0,0,0.55)', stroke: 'rgba(255,255,255,0.08)', strokeWidth: 1 },
          labelBgPadding: [6, 3],
          labelBgBorderRadius: 6,
        } satisfies Edge
      })
    })
  }, [graph, selectedNodeId, snapshotsByRole])

  const cardModels = useMemo(() => {
    const bees = scenario?.template?.bees ?? []
    const beeOrder = bees.map((bee, idx) => {
      const id = bee.id ?? (bee.role ? `${bee.role}-${idx + 1}` : `bee-${idx + 1}`)
      const roleKey = (bee.role ?? '').trim().toLowerCase()
      return { id, roleKey, bee }
    })

    const cards = beeOrder.map((entry) => {
      const snaps = entry.roleKey ? snapshotsByRole.get(entry.roleKey) ?? [] : []
      const snap = snaps.length ? snaps[0] : null
      const queues = snap?.workQueues
      const stats = snap?.queueStats ?? {}
      const queueNames = [...new Set([...(queues?.in ?? []), ...(queues?.out ?? [])])]
      const topQueues = queueNames
        .map((name) => ({ name, stat: stats[name] ?? null }))
        .sort((a, b) => (b.stat?.depth ?? 0) - (a.stat?.depth ?? 0))
        .slice(0, 4)
      return {
        nodeId: entry.id,
        role: entry.bee.role ?? entry.id,
        instance: snap?.instance ?? null,
        ioNamesIn: entry.bee.work?.in ? Object.keys(entry.bee.work.in).filter((k) => k && k.trim().length > 0) : [],
        ioNamesOut: entry.bee.work?.out ? Object.keys(entry.bee.work.out).filter((k) => k && k.trim().length > 0) : [],
        rawEnvelope: snap?.rawEnvelope ?? null,
        enabled: snap?.enabled,
        tps: snap?.tps,
        ioState: snap?.ioState,
        seenAt: snap?.timestamp ?? null,
        topQueues,
      }
    })
    return cards
  }, [scenario?.template?.bees, snapshotsByRole])

  if (!swarmId) {
    return (
      <div className="page">
        <div className="card">Missing swarmId.</div>
      </div>
    )
  }

  return (
    <div className="page swarmViewPage">
      <div className="row between swarmViewHeader">
        <div className="row" style={{ gap: 12 }}>
          <div className="h1" style={{ margin: 0 }}>
            Swarm view
          </div>
          <span className="pill pillInfo" title="Swarm id">
            {swarmId}
          </span>
        </div>
        <div className="row" style={{ gap: 8 }}>
          <Link className="actionButton actionButtonGhost" to={`/hive/${encodeURIComponent(swarmId)}`}>
            Back to details
          </Link>
          <span className="spinnerSlot" aria-hidden="true" title={isBusyTop ? 'Working…' : undefined}>
            <span className={isBusyTop ? 'spinner' : 'spinner spinnerHidden'} />
          </span>
        </div>
      </div>

      {scenarioLoading ? <div className="muted">Loading scenario topology…</div> : null}
      {scenarioError ? <div className="card">{scenarioError}</div> : null}

      {scenario && (
        <div className="swarmViewGrid">
          <div className="card swarmViewCards">
            <div className="row between" style={{ marginBottom: 8 }}>
              <div className="h2">Workers</div>
              <div className="muted" title="Based on control-plane status-full/status-delta events.">
                {workers.length ? `${workers.length} live scope(s)` : 'no status yet'}
              </div>
            </div>
            <div className="swarmCardList">
              {cardModels.map((card) => {
                const isSelected = selectedNodeId === card.nodeId
                return (
                  <button
                    key={card.nodeId}
                    type="button"
                    className={isSelected ? 'swarmWorkerCard swarmWorkerCardSelected' : 'swarmWorkerCard'}
                    onClick={() => setSelectedNodeId((prev) => (prev === card.nodeId ? null : card.nodeId))}
                    title="Click to highlight in graph"
                  >
                    <div className="row between" style={{ gap: 10 }}>
                      <div className="swarmWorkerTitle">
                        <div className="swarmWorkerRole">{card.role}</div>
                        <div
                          className="muted swarmWorkerMeta"
                          title={`${card.instance ?? '—'}${card.seenAt ? ` · seen ${card.seenAt}` : ''}`}
                        >
                          {card.instance ? `(${shorten(card.instance, 22)})` : '(—)'}
                          {card.seenAt ? ` · seen ${formatAge(card.seenAt)}` : ' · seen —'}
                        </div>
                      </div>
                      <div className="swarmWorkerBadges">
                        <button
                          type="button"
                          className="actionButton actionButtonGhost actionButtonTiny"
                          title="Show raw control-plane status envelope (JSON)."
                          onClick={(event) => {
                            event.stopPropagation()
                            try {
                              setRawModal({
                                title: `${card.role}${card.instance ? ` (${card.instance})` : ''}`,
                                json: JSON.stringify(card.rawEnvelope, null, 2),
                              })
                            } catch (err) {
                              setRawModal({
                                title: `${card.role}${card.instance ? ` (${card.instance})` : ''}`,
                                json: err instanceof Error ? err.message : 'Failed to stringify envelope',
                              })
                            }
                          }}
                        >
                          RAW
                        </button>
                        <span className={`chip ${card.enabled === false ? 'chip-event' : 'chip-outcome'}`}>
                          {card.enabled == null ? 'enabled?' : card.enabled ? 'enabled' : 'disabled'}
                        </span>
                        <span className="chip chip-metric">tps {card.tps ?? '—'}</span>
                      </div>
                    </div>
	                    <div className="swarmWorkerIo">
	                      {isIoKnown(card.ioState?.input) || isIoKnown(card.ioState?.output) ? (
	                        <>
	                          {isIoKnown(card.ioState?.input) ? (
	                            <span className="chip chip-metric" title={`ioState.work.input: ${card.ioState?.input ?? '—'}`}>
	                              in {card.ioState?.input}
	                            </span>
	                          ) : null}
	                          {isIoKnown(card.ioState?.output) ? (
	                            <span className="chip chip-metric" title={`ioState.work.output: ${card.ioState?.output ?? '—'}`}>
	                              out {card.ioState?.output}
	                            </span>
	                          ) : null}
	                        </>
	                      ) : (
	                        <span
	                          className="muted"
	                          title="No IO state reported yet (ioState.work.* is missing or 'unknown')."
	                        >
	                          I/O: —
	                        </span>
	                      )}
	                    </div>
                    <div className="swarmWorkerQueues">
                      {card.topQueues.length ? (
                        card.topQueues.map((q) => (
                          <div key={q.name} className="swarmQueueRow" title={q.name}>
                            <span className="swarmQueueName">{q.name}</span>
                            <span className="swarmQueueStat">
                              {q.stat ? `d ${q.stat.depth} · c ${q.stat.consumers}` : '—'}
                            </span>
                          </div>
                        ))
                      ) : (
                        <div className="muted">Queues: —</div>
                      )}
                    </div>
                  </button>
                )
              })}
            </div>
          </div>

          <div className="card swarmViewCanvas">
            <div className="row between" style={{ marginBottom: 8 }}>
              <div className="h2">Topology</div>
              <div className="muted" title="Logical topology from Scenario Manager; edge style uses live queue stats when available.">
                {flowEdges.length} edge(s)
              </div>
            </div>

            <div className="swarmGraphSurface" role="img" aria-label="Swarm topology view">
              <ReactFlow
                nodes={flowNodes}
                edges={flowEdges}
                nodeTypes={nodeTypes}
                onNodesChange={onNodesChange}
                onEdgesChange={onEdgesChange}
                onInit={onFlowInit}
                minZoom={0.2}
                maxZoom={2.4}
                onNodeClick={(_event, node) => setSelectedNodeId((prev) => (prev === node.id ? null : node.id))}
                onNodeDragStop={(_event, node) => persistNodePosition(node.id, node.position)}
                proOptions={{ hideAttribution: true }}
              >
                <Background />
                <Controls />
              </ReactFlow>
            </div>
          </div>
        </div>
      )}

      {rawModal ? (
        <div className="modalBackdrop" role="presentation" onClick={() => setRawModal(null)}>
          <div className="modal" role="dialog" aria-modal="true" onClick={(event) => event.stopPropagation()}>
            <div className="modalHeader">
              <div>
                <div className="h2">RAW JSON</div>
                <div className="muted">{rawModal.title}</div>
              </div>
              <button type="button" className="actionButton actionButtonGhost" onClick={() => setRawModal(null)}>
                Close
              </button>
            </div>
            <div className="modalSection">
              <pre className="codePre" style={{ maxHeight: '70vh' }}>
                {rawModal.json}
              </pre>
            </div>
          </div>
        </div>
      ) : null}
    </div>
  )
}
