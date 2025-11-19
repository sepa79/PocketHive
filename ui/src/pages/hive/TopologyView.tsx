/* eslint-disable @typescript-eslint/ban-ts-comment */
// @ts-nocheck
import { useEffect, useRef, useState, useMemo, useCallback, Fragment } from 'react'
import {
  ReactFlow,
  MarkerType,
  Background,
  Handle,
  Position,
  type Node,
  type Edge,
  type ReactFlowInstance,
  type NodeProps,
  type NodeChange,
  applyNodeChanges,
} from '@xyflow/react'
import '@xyflow/react/dist/style.css'
import {
  subscribeTopology,
  subscribeComponents,
  updateNodePosition,
  type Topology,
} from '../../lib/stompClient'
import { buildRoleAppearanceMap, type RoleAppearanceMap } from '../../lib/capabilities'
import type { Component } from '../../types/hive'
import './TopologyView.css'
import { useCapabilities } from '../../contexts/CapabilitiesContext'

interface GraphNode {
  id: string
  type: string
  x?: number
  y?: number
  enabled?: boolean
  swarmId?: string
}

interface GraphLink {
  source: string
  target: string
  queue: string
}

interface GraphData {
  nodes: GraphNode[]
  links: GraphLink[]
}

interface Props {
  selectedId?: string
  onSelect?: (id: string) => void
  swarmId?: string
  onSwarmSelect?: (id: string) => void
}

type NodeShape =
  | 'circle'
  | 'square'
  | 'triangle'
  | 'diamond'
  | 'pentagon'
  | 'hexagon'
  | 'star'

const shapeOrder: NodeShape[] = [
  'square',
  'triangle',
  'diamond',
  'pentagon',
  'hexagon',
  'star',
]

const DEFAULT_COLOR = '#60a5fa'
const DISABLED_COLOR = '#64748b'

const FALLBACK_HORIZONTAL_SPACING = 280
const FALLBACK_VERTICAL_SPACING = 220

function average(values: number[]): number | undefined {
  if (!values.length) return undefined
  const total = values.reduce((sum, value) => sum + value, 0)
  return total / values.length
}

function compareNodes(a: GraphNode, b: GraphNode) {
  const typeCompare = (a.type || '').localeCompare(b.type || '')
  if (typeCompare !== 0) {
    return typeCompare
  }
  return (a.id || '').localeCompare(b.id || '')
}

function normalizeRoleKey(value?: string): string {
  return typeof value === 'string' ? value.trim().toLowerCase() : ''
}

function fallbackColorForRole(role: string): string {
  const key = normalizeRoleKey(role)
  if (!key) return DEFAULT_COLOR
  let hash = 0
  for (let i = 0; i < key.length; i++) {
    hash = (hash << 5) - hash + key.charCodeAt(i)
    hash |= 0
  }
  const hue = Math.abs(hash) % 360
  const saturation = 65
  const lightness = 55
  return `hsl(${hue}, ${saturation}%, ${lightness}%)`
}

interface ShapeNodeData {
  label: string
  shape: NodeShape
  enabled?: boolean
  swarmId?: string
  componentType?: string
  componentId?: string
  status?: string
  meta?: Record<string, unknown>
  role?: string
  fill?: string
  [key: string]: unknown
}

function formatMetaValue(value: unknown): string | null {
  if (value === undefined || value === null) return null
  if (typeof value === 'boolean') return value ? 'Yes' : 'No'
  if (typeof value === 'number') return value.toString()
  if (typeof value === 'string') return value
  return null
}

function abbreviateName(name: string | undefined): string {
  if (!name) return ''
  return name
    .split(/[-_\s]+/)
    .filter((part) => part.length > 0)
    .map((part) => part[0]?.toUpperCase() ?? '')
    .join('')
    .slice(0, 2)
}

function ShapeNode({ data, selected }: NodeProps<ShapeNodeData>) {
  const size = 10
  const fill =
    data.enabled === false
      ? DISABLED_COLOR
      : typeof data.fill === 'string' && data.fill.trim().length > 0
      ? data.fill
      : DEFAULT_COLOR
  const isOrchestrator = data.componentType === 'orchestrator'
  const role = data.role || data.componentType
  const componentId =
    typeof data.componentId === 'string' ? data.componentId.trim() : ''
  const fallbackLabel = typeof data.label === 'string' ? data.label : ''
  const normalizedRole = typeof role === 'string' ? role.trim() : ''
  const normalizedFallback = fallbackLabel.trim()
  const shouldUseFallback =
    fallbackLabel.length > 0 && normalizedFallback !== normalizedRole
  const displayLabel = shouldUseFallback ? fallbackLabel : componentId || fallbackLabel
  const meta =
    data.meta && typeof data.meta === 'object'
      ? (data.meta as Record<string, unknown>)
      : undefined
  const rawTps = meta?.tps
  const numericTps =
    typeof rawTps === 'number'
      ? rawTps
      : typeof rawTps === 'string'
      ? Number(rawTps)
      : undefined
  const tps = Number.isFinite(numericTps as number) ? Math.round(numericTps as number) : undefined
  const metaEntries = useMemo(() => {
    if (!isOrchestrator) {
      return []
    }
    const meta =
      data.meta && typeof data.meta === 'object'
        ? (data.meta as Record<string, unknown>)
        : undefined
    const swarmCountValue =
      meta?.swarmCount ??
      meta?.activeSwarmCount ??
      meta?.activeSwarms ??
      meta?.['swarm-count'] ??
      meta?.['active-swarms']
    const formattedSwarmCount = formatMetaValue(swarmCountValue)
    if (formattedSwarmCount === null) {
      return []
    }
    return [{ key: 'Active swarms', value: formattedSwarmCount }]
  }, [data.meta, isOrchestrator])
  return (
    <div
      className={`shape-node${selected ? ' selected' : ''}${
        isOrchestrator ? ' shape-node--orchestrator' : ''
      }`}
    >
      {!isOrchestrator && typeof tps === 'number' && (
        <div className="shape-node__badge" title="Throughput (TPS)">
          {tps}
        </div>
      )}
      <Handle type="target" position={Position.Left} />
      <svg className="shape-icon" width={2 * size} height={2 * size}>
        {data.shape === 'square' && (
          <rect x={0} y={0} width={2 * size} height={2 * size} fill={fill} stroke="black" />
        )}
        {data.shape === 'triangle' && (
          <polygon
            points={`${size},0 ${2 * size},${2 * size} 0,${2 * size}`}
            fill={fill}
            stroke="black"
          />
        )}
        {data.shape === 'diamond' && (
          <polygon
            points={`${size},0 ${2 * size},${size} ${size},${2 * size} 0,${size}`}
            fill={fill}
            stroke="black"
          />
        )}
        {data.shape === 'pentagon' && (
          <polygon points={polygonPoints(5, size)} fill={fill} stroke="black" />
        )}
        {data.shape === 'hexagon' && (
          <polygon points={polygonPoints(6, size)} fill={fill} stroke="black" />
        )}
        {data.shape === 'star' && <polygon points={starPoints(size)} fill={fill} stroke="black" />}
        {data.shape === 'circle' && <circle cx={size} cy={size} r={size} fill={fill} stroke="black" />}
      </svg>
      <div className="shape-node__content">
        <span className="label">{displayLabel}</span>
        {role && <span className="shape-node__role">{role}</span>}
        {isOrchestrator && metaEntries.length > 0 && (
          <dl className="shape-node__meta">
            {metaEntries.map((entry) => (
              <Fragment key={`${entry.key}:${entry.value}`}>
                <dt className="shape-node__meta-term">{entry.key}</dt>
                <dd className="shape-node__meta-value">{entry.value}</dd>
              </Fragment>
            ))}
          </dl>
        )}
      </div>
      <Handle type="source" position={Position.Right} />
    </div>
  )
}

interface SwarmGroupComponentData {
  id: string
  name: string
  shape: NodeShape
  enabled?: boolean
  componentType?: string
  fill?: string
  abbreviation?: string
  queueCount?: number
  tps?: number
}

interface SwarmGroupEdgeData {
  source: string
  target: string
  queue: string
  depth: number
}

interface SwarmGroupNodeData {
  label: string
  swarmId: string
  controllerId: string
  components: SwarmGroupComponentData[]
  edges: SwarmGroupEdgeData[]
  onDetails?: (swarmId: string) => void
  selectedId?: string
}

function SwarmGroupNode({ data }: NodeProps<SwarmGroupNodeData>) {
  const size = 180
  const center = size / 2
  const controller = data.components.find((c) => c.id === data.controllerId)
  const ringMembers = controller
    ? data.components.filter((c) => c.id !== controller.id)
    : data.components
  const ringRadius = ringMembers.length > 1 || controller
    ? Math.min(center - 24, 60)
    : 0
  const placements = useMemo(() => {
    const list: (SwarmGroupComponentData & { x: number; y: number })[] = []
    if (controller) {
      list.push({ ...controller, x: center, y: center })
    }
    if (ringMembers.length === 0 && !controller && data.components[0]) {
      list.push({ ...data.components[0], x: center, y: center })
      return list
    }
    const denominator = Math.max(ringMembers.length, 1)
    ringMembers.forEach((comp, idx) => {
      const angle = -Math.PI / 2 + (2 * Math.PI * idx) / denominator
      const radius = ringRadius
      const x = radius ? center + radius * Math.cos(angle) : center
      const y = radius ? center + radius * Math.sin(angle) : center
      list.push({ ...comp, x, y })
    })
    return list
  }, [center, controller, data.components, ringMembers, ringRadius])

  const byId = useMemo(() => {
    const map = new Map<string, (SwarmGroupComponentData & { x: number; y: number })>()
    placements.forEach((p) => map.set(p.id, p))
    return map
  }, [placements])

  const hasSelected = data.selectedId
    ? data.components.some((c) => c.id === data.selectedId)
    : false

  const renderShape = useCallback(
    (comp: SwarmGroupComponentData & { x: number; y: number }) => {
      const fill =
        comp.enabled === false
          ? DISABLED_COLOR
          : typeof comp.fill === 'string' && comp.fill.trim().length > 0
          ? comp.fill
          : DEFAULT_COLOR
      const iconRadius = comp.id === data.controllerId ? 14 : 11
      const abbreviation =
        typeof comp.abbreviation === 'string' && comp.abbreviation.trim().length > 0
          ? comp.abbreviation.trim()
          : abbreviateName(comp.name)
      const tps =
        typeof comp.tps === 'number' && Number.isFinite(comp.tps)
          ? Math.round(comp.tps)
          : undefined
      return (
        <g key={comp.id}>
          {comp.id === data.selectedId && (
            <circle
              className="swarm-group__selection"
              cx={comp.x}
              cy={comp.y}
              r={iconRadius + 6}
            />
          )}
          {comp.shape === 'square' && (
            <rect
              x={comp.x - iconRadius}
              y={comp.y - iconRadius}
              width={iconRadius * 2}
              height={iconRadius * 2}
              fill={fill}
              stroke="black"
            />
          )}
          {comp.shape === 'triangle' && (
            <polygon
              points={`${comp.x},${comp.y - iconRadius} ${comp.x + iconRadius},${comp.y + iconRadius} ${comp.x - iconRadius},${comp.y + iconRadius}`}
              fill={fill}
              stroke="black"
            />
          )}
          {comp.shape === 'diamond' && (
            <polygon
              points={`${comp.x},${comp.y - iconRadius} ${comp.x + iconRadius},${comp.y} ${comp.x},${comp.y + iconRadius} ${comp.x - iconRadius},${comp.y}`}
              fill={fill}
              stroke="black"
            />
          )}
          {comp.shape === 'pentagon' && (
            <polygon
              points={polygonPoints(5, iconRadius)
                .split(' ')
                .map((pair) => {
                  const [px, py] = pair.split(',').map(Number)
                  return `${px - iconRadius + comp.x},${py - iconRadius + comp.y}`
                })
                .join(' ')}
              fill={fill}
              stroke="black"
            />
          )}
          {comp.shape === 'hexagon' && (
            <polygon
              points={polygonPoints(6, iconRadius)
                .split(' ')
                .map((pair) => {
                  const [px, py] = pair.split(',').map(Number)
                  return `${px - iconRadius + comp.x},${py - iconRadius + comp.y}`
                })
                .join(' ')}
              fill={fill}
              stroke="black"
            />
          )}
          {comp.shape === 'star' && (
            <polygon
              points={starPoints(iconRadius)
                .split(' ')
                .map((pair) => {
                  const [px, py] = pair.split(',').map(Number)
                  return `${px - iconRadius + comp.x},${py - iconRadius + comp.y}`
                })
                .join(' ')}
              fill={fill}
              stroke="black"
            />
          )}
          {comp.shape === 'circle' && (
            <circle cx={comp.x} cy={comp.y} r={iconRadius} fill={fill} stroke="black" />
          )}
          <text
            x={comp.x}
            y={comp.y + 3}
            textAnchor="middle"
            className="swarm-group__icon-label"
          >
            {abbreviation || '?'}
          </text>
          {typeof tps === 'number' && (
            <>
              {/*
                Small pill-shaped background behind the TPS label to keep it readable
                regardless of the underlying icon color.
              */}
              <rect
                x={comp.x + iconRadius - 10}
                y={comp.y - iconRadius + 5}
                width={18}
                height={10}
                rx={6}
                ry={6}
                fill="rgba(15,23,42,0.9)"
                stroke="#38bdf8"
                strokeWidth={0.7}
              />
              <text
                x={comp.x + iconRadius - 5}
                y={comp.y - iconRadius + 9}
                textAnchor="middle"
                className="swarm-group__icon-label"
              >
                {tps}
              </text>
            </>
          )}
        </g>
      )
    },
    [data.controllerId, data.selectedId],
  )

  return (
    <div className={`swarm-group${hasSelected ? ' selected' : ''}`}>
      <Handle
        type="target"
        position={Position.Left}
        className="swarm-group__handle swarm-group__handle--target"
      />
      <div className="swarm-group__header">
        <span className="swarm-group__title">{data.label}</span>
        <button
          className="swarm-group__details"
          onClick={(e) => {
            e.stopPropagation()
            data.onDetails?.(data.swarmId)
          }}
        >
          Details
        </button>
      </div>
      <svg
        className="swarm-group__svg"
        viewBox={`0 0 ${size} ${size}`}
        preserveAspectRatio="xMidYMid meet"
      >
        {data.edges.map((edge) => {
          const from = byId.get(edge.source)
          const to = byId.get(edge.target)
          if (!from || !to) return null
          const color = edge.depth > 0 ? '#ff6666' : '#66aaff'
          const width = 1 + Math.log(edge.depth + 1)
          return (
            <line
              key={`${edge.source}-${edge.target}-${edge.queue}`}
              x1={from.x}
              y1={from.y}
              x2={to.x}
              y2={to.y}
              stroke={color}
              strokeWidth={width}
              strokeLinecap="round"
              className="swarm-group__edge"
            />
          )
        })}
        {placements.map((comp) => renderShape(comp))}
      </svg>
      <Handle
        type="source"
        position={Position.Right}
        className="swarm-group__handle swarm-group__handle--source"
      />
    </div>
  )
}

type FlowNodeData = ShapeNodeData | SwarmGroupNodeData
type FlowNode = Node<FlowNodeData>

const OUTSIDE_SWARMS = new Set(['default', 'hive'])

function normalizeSwarmId(id?: string) {
  if (!id || OUTSIDE_SWARMS.has(id)) return undefined
  return id
}

function polygonPoints(sides: number, r: number) {
  const cx = r
  const cy = r
  const pts: string[] = []
  for (let i = 0; i < sides; i++) {
    const angle = -Math.PI / 2 + (2 * Math.PI * i) / sides
    pts.push(`${cx + r * Math.cos(angle)},${cy + r * Math.sin(angle)}`)
  }
  return pts.join(' ')
}

function starPoints(r: number) {
  const outer = r
  const inner = r / 2
  const cx = r
  const cy = r
  const pts: string[] = []
  let rot = -Math.PI / 2
  const step = Math.PI / 5
  for (let i = 0; i < 10; i++) {
    const radius = i % 2 === 0 ? outer : inner
    pts.push(`${cx + Math.cos(rot) * radius},${cy + Math.sin(rot) * radius}`)
    rot += step
  }
  return pts.join(' ')
}

function applyFallbackPositions(nodes: GraphNode[], links: GraphLink[]): GraphNode[] {
  if (nodes.length === 0) {
    return nodes
  }

  const nodeMap = new Map(nodes.map((node) => [node.id, node]))
  const adjacency = new Map<string, string[]>()
  const reverseAdjacency = new Map<string, string[]>()
  const indegree = new Map<string, number>()

  links.forEach((link) => {
    if (!nodeMap.has(link.source) || !nodeMap.has(link.target)) {
      return
    }
    const children = adjacency.get(link.source) ?? []
    children.push(link.target)
    adjacency.set(link.source, children)
    const parents = reverseAdjacency.get(link.target) ?? []
    parents.push(link.source)
    reverseAdjacency.set(link.target, parents)
    indegree.set(link.target, (indegree.get(link.target) ?? 0) + 1)
  })

  nodes.forEach((node) => {
    if (!adjacency.has(node.id)) {
      adjacency.set(node.id, [])
    }
    if (!reverseAdjacency.has(node.id)) {
      reverseAdjacency.set(node.id, [])
    }
  })

  const queue: string[] = []
  const remaining = new Map(indegree)
  const level = new Map<string, number>()

  nodes.forEach((node) => {
    if ((remaining.get(node.id) ?? 0) === 0) {
      queue.push(node.id)
      level.set(node.id, 0)
    }
  })

  if (queue.length === 0) {
    nodes.forEach((node) => {
      queue.push(node.id)
      if (!level.has(node.id)) {
        level.set(node.id, 0)
      }
    })
  }

  while (queue.length) {
    const id = queue.shift()!
    const currentLevel = level.get(id) ?? 0
    const neighbors = adjacency.get(id) ?? []
    neighbors.forEach((next) => {
      const candidate = currentLevel + 1
      const existingLevel = level.get(next)
      if (existingLevel === undefined || candidate > existingLevel) {
        level.set(next, candidate)
      }
      const nextRemaining = (remaining.get(next) ?? 0) - 1
      remaining.set(next, nextRemaining)
      if (nextRemaining === 0) {
        queue.push(next)
      }
    })
  }

  let maxLevel = 0
  level.forEach((value) => {
    if (value > maxLevel) {
      maxLevel = value
    }
  })

  const unassigned = nodes.filter((node) => !level.has(node.id))
  unassigned.forEach((node, index) => {
    const nextLevel = maxLevel + 1 + index
    level.set(node.id, nextLevel)
    maxLevel = nextLevel
  })

  const groups = new Map<number, GraphNode[]>()
  nodes.forEach((node) => {
    const nodeLevel = level.get(node.id) ?? 0
    const list = groups.get(nodeLevel) ?? []
    list.push(node)
    groups.set(nodeLevel, list)
  })

  const orderedLevels = Array.from(groups.keys()).sort((a, b) => a - b)
  const levelIndexMap = new Map<number, number>()
  orderedLevels.forEach((lvl, idx) => levelIndexMap.set(lvl, idx))
  const totalWidth = (orderedLevels.length - 1) * FALLBACK_HORIZONTAL_SPACING
  const offsetX = totalWidth / 2

  const columns = orderedLevels.map((lvl) => {
    const members = groups.get(lvl) ?? []
    return [...members]
  })

  const rowOrder = new Map<string, number>()

  columns.forEach((column, columnIndex) => {
    const baseSorted = [...column].sort(compareNodes)
    if (columnIndex === 0) {
      baseSorted.forEach((node, index) => {
        rowOrder.set(node.id, index)
      })
      columns[columnIndex] = baseSorted
      return
    }
    const sorted = [...column].sort((a, b) => {
      const parentsA = reverseAdjacency.get(a.id) ?? []
      const parentsB = reverseAdjacency.get(b.id) ?? []
      const scoreA = average(
        parentsA
          .map((id) => rowOrder.get(id))
          .filter((value): value is number => value !== undefined),
      )
      const scoreB = average(
        parentsB
          .map((id) => rowOrder.get(id))
          .filter((value): value is number => value !== undefined),
      )
      if (scoreA !== undefined && scoreB !== undefined && scoreA !== scoreB) {
        return scoreA - scoreB
      }
      if (scoreA !== undefined && scoreB === undefined) return -1
      if (scoreA === undefined && scoreB !== undefined) return 1
      return compareNodes(a, b)
    })
    sorted.forEach((node, index) => {
      rowOrder.set(node.id, index)
    })
    columns[columnIndex] = sorted
  })

  for (let columnIndex = columns.length - 2; columnIndex >= 0; columnIndex -= 1) {
    const column = columns[columnIndex]
    const sorted = [...column].sort((a, b) => {
      const childrenA = adjacency.get(a.id) ?? []
      const childrenB = adjacency.get(b.id) ?? []
      const scoreA = average(
        childrenA
          .map((id) => rowOrder.get(id))
          .filter((value): value is number => value !== undefined),
      )
      const scoreB = average(
        childrenB
          .map((id) => rowOrder.get(id))
          .filter((value): value is number => value !== undefined),
      )
      if (scoreA !== undefined && scoreB !== undefined && scoreA !== scoreB) {
        return scoreA - scoreB
      }
      if (scoreA !== undefined && scoreB === undefined) return -1
      if (scoreA === undefined && scoreB !== undefined) return 1
      return compareNodes(a, b)
    })
    sorted.forEach((node, index) => {
      rowOrder.set(node.id, index)
    })
    columns[columnIndex] = sorted
  }

  const fallbackPositions = new Map<string, { x: number; y: number }>()

  orderedLevels.forEach((lvl) => {
    const columnIndex = levelIndexMap.get(lvl) ?? 0
    const columnX = columnIndex * FALLBACK_HORIZONTAL_SPACING - offsetX
    const members = columns[columnIndex]
    if (!members || members.length === 0) return
    const columnHeight = (members.length - 1) * FALLBACK_VERTICAL_SPACING
    const offsetY = columnHeight / 2
    members.forEach((member, rowIndex) => {
      const y = rowIndex * FALLBACK_VERTICAL_SPACING - offsetY
      fallbackPositions.set(member.id, { x: columnX, y })
    })
  })

  return nodes.map((node) => {
    const fallback = fallbackPositions.get(node.id)
    const hasX = typeof node.x === 'number' && Number.isFinite(node.x)
    const hasY = typeof node.y === 'number' && Number.isFinite(node.y)
    if (!fallback) {
      return node
    }
    return {
      ...node,
      x: hasX ? node.x : fallback.x,
      y: hasY ? node.y : fallback.y,
    }
  })
}

export default function TopologyView({ selectedId, onSelect, swarmId, onSwarmSelect }: Props) {
  const [data, setData] = useState<GraphData>({ nodes: [], links: [] })
  const shapeMapRef = useRef<Record<string, NodeShape>>({ sut: 'circle' })
  const [queueDepths, setQueueDepths] = useState<Record<string, number>>({})
  const [componentsById, setComponentsById] = useState<Record<string, Component>>({})
  const flowRef = useRef<ReactFlowInstance<FlowNode, Edge> | null>(null)
  const containerRef = useRef<HTMLDivElement>(null)
  const [rfNodes, setRfNodes] = useState<FlowNode[]>([])
  const draggingIdsRef = useRef<Set<string>>(new Set())
  const pendingFitRef = useRef(false)
  const { manifests, ensureCapabilities } = useCapabilities()

  const fitViewToNodes = useCallback(() => {
    const instance = flowRef.current
    if (!instance) return
    const nodes = instance.getNodes?.() ?? []
    if (!nodes.length) return
    instance.fitView({ padding: 0.2, includeHiddenNodes: false })
    const zoom = instance.getZoom?.()
    if (typeof zoom === 'number' && zoom < 0.9) {
      instance.zoomTo?.(0.9)
    }
  }, [])

  useEffect(() => {
    return () => {
      draggingIdsRef.current.clear()
    }
  }, [])

  useEffect(() => {
    void ensureCapabilities()
  }, [ensureCapabilities])

  const roleAppearances = useMemo<RoleAppearanceMap>(() => {
    return buildRoleAppearanceMap(manifests)
  }, [manifests])

  useEffect(() => {
    const unsub = subscribeComponents((comps: Component[]) => {
      const depths: Record<string, number> = {}
      const map: Record<string, Component> = {}
      comps.forEach((c) => {
        map[c.id] = c
        c.queues.forEach((q) => {
          if (typeof q.depth === 'number') {
            const d = depths[q.name]
            depths[q.name] = d === undefined ? q.depth : Math.max(d, q.depth)
          }
        })
      })
      setQueueDepths(depths)
      setComponentsById(map)
    })
    return () => unsub()
  }, [])

  useEffect(() => {
    const unsub = subscribeTopology((topo: Topology) => {
      const adjacency = new Map<string, string[]>()
      topo.edges.forEach((e) => {
        const arr = adjacency.get(e.from) ?? []
        arr.push(e.to)
        adjacency.set(e.from, arr)
      })
      const generators = topo.nodes.filter((n) => n.type === 'generator')
      const visited = new Set<string>()
      const order: string[] = []
      const q: string[] = generators.map((g) => g.id)
      while (q.length) {
        const id = q.shift()!
        if (visited.has(id)) continue
        visited.add(id)
        order.push(id)
        ;(adjacency.get(id) ?? []).forEach((next) => q.push(next))
      }
      const connectedNodes = order
        .map((id) => topo.nodes.find((n) => n.id === id))
        .filter((n): n is GraphNode => Boolean(n))
        .map((n) => ({ ...n }))
      const unconnectedNodes = topo.nodes
        .filter((n) => !visited.has(n.id))
        .map((n) => ({ ...n }))
      let nodes = [...connectedNodes, ...unconnectedNodes]
      if (swarmId) {
        nodes = nodes.filter((n) =>
          swarmId === 'default' ? !n.swarmId : n.swarmId === swarmId,
        )
      }
      const nodeSet = new Set(nodes.map((n) => n.id))
      const relevantLinks = topo.edges.filter(
        (edge) => nodeSet.has(edge.from) && nodeSet.has(edge.to),
      )
      const positionedNodes = applyFallbackPositions(
        nodes,
        relevantLinks.map((edge) => ({
          source: edge.from,
          target: edge.to,
          queue: edge.queue,
        })),
      )
      const ids = new Set(positionedNodes.map((n) => n.id))
      const links = relevantLinks
        .filter((edge) => ids.has(edge.from) && ids.has(edge.to))
        .map((edge) => ({ source: edge.from, target: edge.to, queue: edge.queue }))
      setData({ nodes: positionedNodes, links })
      pendingFitRef.current = true
    })
    return () => unsub()
  }, [swarmId])

  useEffect(() => {
    const el = containerRef.current
    if (!el) return
    const update = () => fitViewToNodes()
    update()
    if (typeof ResizeObserver !== 'undefined') {
      const ro = new ResizeObserver(update)
      ro.observe(el)
      return () => ro.disconnect()
    }
    window.addEventListener('resize', update)
    return () => window.removeEventListener('resize', update)
  }, [fitViewToNodes])

  const getFill = useCallback(
    (type?: string, enabled?: boolean) => {
      if (enabled === false) return DISABLED_COLOR
      const key = normalizeRoleKey(type)
      if (!key) return DEFAULT_COLOR
      const appearance = roleAppearances[key]
      const color = appearance?.color?.trim()
      return color && color.length > 0 ? color : fallbackColorForRole(key)
    },
    [roleAppearances],
  )

  const getRoleLabel = useCallback(
    (componentRole: string | undefined, type: string) => {
      const key = normalizeRoleKey(type)
      const componentKey = normalizeRoleKey(componentRole)
      if (key === 'wiremock' || componentKey === 'wiremock') {
        return 'System Under Test'
      }
      const appearanceLabel = roleAppearances[key]?.label?.trim()
      if (appearanceLabel && appearanceLabel.length > 0) {
        return appearanceLabel
      }
      const normalizedComponent = componentRole?.trim()
      if (normalizedComponent && normalizedComponent.length > 0) {
        return normalizedComponent
      }
      return type
    },
    [roleAppearances],
  )

  const getRoleAbbreviation = useCallback(
    (type: string) => {
      const key = normalizeRoleKey(type)
      const abbreviation = roleAppearances[key]?.abbreviation?.trim()
      return abbreviation && abbreviation.length > 0 ? abbreviation : ''
    },
    [roleAppearances],
  )

  const getShape = useCallback(
    (type: string): NodeShape => {
      const key = normalizeRoleKey(type)
      const map = shapeMapRef.current
      const preferred = roleAppearances[key]?.shape
      if (preferred) {
        map[key] = preferred
        return preferred
      }
      if (!map[key]) {
        const used = new Set(Object.values(map))
        const next = shapeOrder.find((s) => !used.has(s)) ?? 'circle'
        map[key] = next
      }
      return map[key]
    },
    [roleAppearances],
  )

  const handleDetails = useCallback(
    (targetSwarm: string) => {
      onSwarmSelect?.(targetSwarm)
    },
    [onSwarmSelect],
  )

  useEffect(() => {
    setRfNodes((prev) => {
      const prevPositions = new Map(prev.map((node) => [node.id, node.position]))
      const prevNodesById = new Map(prev.map((node) => [node.id, node]))
      const dragging = draggingIdsRef.current
      if (!swarmId) {
        const controllers = new Map<string, GraphNode>()
        data.nodes.forEach((node) => {
          const normalized = normalizeSwarmId(node.swarmId)
          if (node.type === 'swarm-controller' && normalized) {
            controllers.set(normalized, node)
          }
        })
        const grouped = new Map<string, GraphNode[]>()
        const nodes: FlowNode[] = []
        data.nodes.forEach((node) => {
          const normalized = normalizeSwarmId(node.swarmId)
          if (normalized && controllers.has(normalized)) {
            const list = grouped.get(normalized) ?? []
            list.push(node)
            grouped.set(normalized, list)
            return
          }
          if (dragging.has(node.id)) {
            const existing = prevNodesById.get(node.id)
            if (existing) {
              nodes.push(existing)
              return
            }
          }
          const previous = prevPositions.get(node.id)
          const position = {
            x: node.x ?? previous?.x ?? 0,
            y: node.y ?? previous?.y ?? 0,
          }
          const component = componentsById[node.id]
          const role = getRoleLabel(component?.role, node.type)
          const label =
            component?.name?.trim() ||
            component?.id?.trim() ||
            node.id
          nodes.push({
            id: node.id,
            position,
            data: {
              label,
              shape: getShape(node.type),
              enabled: node.enabled,
              swarmId: node.swarmId,
              componentType: node.type,
              componentId: node.id,
              status: component?.status,
              meta: component?.config,
              role,
              fill: getFill(node.type, node.enabled),
            },
            type: 'shape',
            selected: selectedId === node.id,
          })
        })
        grouped.forEach((members, swarmKey) => {
          const controller = controllers.get(swarmKey)
          if (!controller) return
          const memberSet = new Set(members.map((m) => m.id))
          const groupEdges = data.links
            .filter((link) => memberSet.has(link.source) && memberSet.has(link.target))
            .map((link) => ({
              source: link.source,
              target: link.target,
              queue: link.queue,
              depth: queueDepths[link.queue] ?? 0,
            }))
          if (dragging.has(controller.id)) {
            const existing = prevNodesById.get(controller.id)
            if (existing) {
              nodes.push(existing)
              return
            }
          }
          const previous = prevPositions.get(controller.id)
          const position = {
            x: controller.x ?? previous?.x ?? 0,
            y: controller.y ?? previous?.y ?? 0,
          }
          nodes.push({
            id: controller.id,
            position,
            data: {
              label: swarmKey,
              swarmId: swarmKey,
              controllerId: controller.id,
              components: members.map((member) => {
                const componentData = componentsById[member.id]
                const roleLabel = getRoleLabel(componentData?.role, member.type)
                const config =
                  componentData && typeof componentData.config === 'object'
                    ? (componentData.config as Record<string, unknown>)
                    : undefined
                const rawTps = config?.tps
                const numericTps =
                  typeof rawTps === 'number'
                    ? rawTps
                    : typeof rawTps === 'string'
                    ? Number(rawTps)
                    : undefined
                return {
                  id: member.id,
                  name: roleLabel,
                  shape: getShape(member.type),
                  enabled: member.enabled,
                  componentType: member.type,
                  fill: getFill(member.type, member.enabled),
                  abbreviation: getRoleAbbreviation(member.type),
                  queueCount: componentData?.queues?.length ?? 0,
                  tps:
                    numericTps !== undefined && Number.isFinite(numericTps)
                      ? (numericTps as number)
                      : undefined,
                }
              }),
              edges: groupEdges,
              onDetails: handleDetails,
              selectedId,
            },
            type: 'swarmGroup',
            selectable: false,
          })
        })
        return nodes
      }
      return data.nodes.map((node) => {
        if (dragging.has(node.id)) {
          const existing = prevNodesById.get(node.id)
          if (existing) return existing
        }
        const previous = prevPositions.get(node.id)
        const position = {
          x: node.x ?? previous?.x ?? 0,
          y: node.y ?? previous?.y ?? 0,
        }
        const component = componentsById[node.id]
        const role = getRoleLabel(component?.role, node.type)
        const label =
          component?.name?.trim() ||
          component?.id?.trim() ||
          node.id
        return {
          id: node.id,
          position,
          data: {
            label,
            shape: getShape(node.type),
            enabled: node.enabled,
            swarmId: node.swarmId,
            componentType: node.type,
            componentId: node.id,
            status: component?.status,
            meta: component?.config,
            role,
            fill: getFill(node.type, node.enabled),
          },
          type: 'shape',
          selected: selectedId === node.id,
        }
      }) as FlowNode[]
    })
  }, [
    componentsById,
    data.links,
    data.nodes,
    queueDepths,
    handleDetails,
    selectedId,
    swarmId,
    getFill,
    getRoleAbbreviation,
    getRoleLabel,
    getShape,
  ])

  const nodeById = useMemo(() => {
    const map = new Map<string, GraphNode>()
    data.nodes.forEach((node) => map.set(node.id, node))
    return map
  }, [data.nodes])

  const edges: Edge[] = useMemo(() => {
    const seen = new Set<string>()
    if (!swarmId) {
      const controllerIds = new Map<string, string>()
      data.nodes.forEach((node) => {
        const normalized = normalizeSwarmId(node.swarmId)
        if (node.type === 'swarm-controller' && normalized) {
          controllerIds.set(normalized, node.id)
        }
      })
      return data.links.reduce<Edge[]>((acc, link) => {
        const sourceNode = nodeById.get(link.source)
        const targetNode = nodeById.get(link.target)
        if (!sourceNode || !targetNode) return acc
        const sourceSwarm = normalizeSwarmId(sourceNode.swarmId)
        const targetSwarm = normalizeSwarmId(targetNode.swarmId)
        const sourceId = sourceSwarm && controllerIds.get(sourceSwarm)
          ? controllerIds.get(sourceSwarm)!
          : sourceNode.id
        const targetId = targetSwarm && controllerIds.get(targetSwarm)
          ? controllerIds.get(targetSwarm)!
          : targetNode.id
        if (sourceId === targetId) return acc
        const key = `${sourceId}|${targetId}|${link.queue}`
        if (seen.has(key)) return acc
        seen.add(key)
        const depth = queueDepths[link.queue] ?? 0
        const color = depth > 0 ? '#ff6666' : '#66aaff'
        const width = 2 + Math.log(depth + 1)
        acc.push({
          id: `${sourceId}-${targetId}-${link.queue}`,
          source: sourceId,
          target: targetId,
          label: link.queue,
          style: { stroke: color, strokeWidth: width },
          markerEnd: { type: MarkerType.ArrowClosed, color },
          labelBgPadding: [2, 2],
          labelBgBorderRadius: 2,
          labelStyle: { fill: '#fff', fontSize: 6 },
          labelBgStyle: { fill: 'rgba(0,0,0,0.6)' },
        })
        return acc
      }, [])
    }
    return data.links.map((link) => {
      const depth = queueDepths[link.queue] ?? 0
      const color = depth > 0 ? '#ff6666' : '#66aaff'
      const width = 2 + Math.log(depth + 1)
      return {
        id: `${link.source}-${link.target}-${link.queue}`,
        source: link.source,
        target: link.target,
        label: link.queue,
        style: { stroke: color, strokeWidth: width },
        markerEnd: { type: MarkerType.ArrowClosed, color },
        labelBgPadding: [2, 2],
        labelBgBorderRadius: 2,
        labelStyle: { fill: '#fff', fontSize: 6 },
        labelBgStyle: { fill: 'rgba(0,0,0,0.6)' },
      }
    }) as Edge[]
  }, [data.links, data.nodes, nodeById, queueDepths, swarmId])

  const onNodesChange = useCallback(
    (changes: NodeChange[]) => setRfNodes((nds) => applyNodeChanges(changes, nds)),
    [],
  )

  const layoutSignature = useMemo(() => {
    return rfNodes
      .map((node) => `${node.id}:${Math.round(node.position.x)}:${Math.round(node.position.y)}`)
      .join('|')
  }, [rfNodes])

  useEffect(() => {
    if (!rfNodes.length) {
      return
    }
    if (!pendingFitRef.current) {
      return
    }
    if (draggingIdsRef.current.size > 0) {
      return
    }
    pendingFitRef.current = false
    fitViewToNodes()
  }, [fitViewToNodes, layoutSignature, rfNodes.length])

  const types = Array.from(new Set(data.nodes.map((n) => n.type)))

  return (
    <div ref={containerRef} className="topology-container">
        <ReactFlow<FlowNode, Edge>
          nodes={rfNodes}
          edges={edges}
          onNodesChange={onNodesChange}
          nodeTypes={{ shape: ShapeNode, swarmGroup: SwarmGroupNode }}
          onInit={(inst: ReactFlowInstance<FlowNode, Edge>) => {
            flowRef.current = inst
            pendingFitRef.current = true
            fitViewToNodes()
          }}
          onNodeDragStart={(_e: unknown, node: FlowNode) => {
            draggingIdsRef.current.add(node.id)
          }}
          onNodeDragStop={(
            _e: unknown,
            node: FlowNode,
          ) => {
            updateNodePosition(node.id, node.position.x, node.position.y)
            draggingIdsRef.current.delete(node.id)
          }}
          onNodeClick={(
            _e: unknown,
            node: FlowNode,
          ) => {
            if (node.type === 'swarmGroup') return
            onSelect?.(node.id)
          }}
          fitView
        >
          <Background />
        </ReactFlow>
      <button className="reset-view" onClick={fitViewToNodes}>
        Reset View
      </button>
      <div className="topology-legend">
        {types.map((t) => {
          const shape = getShape(t)
          const fill = getFill(t)
          const displayLabel = roleAppearances[normalizeRoleKey(t)]?.label ?? t
          return (
            <div key={t} className="legend-item">
              <svg width="12" height="12" className="legend-icon">
                {shape === 'square' && (
                  <rect x="1" y="1" width="10" height="10" fill={fill} stroke="black" />
                )}
                {shape === 'triangle' && (
                  <polygon points="6,1 11,11 1,11" fill={fill} stroke="black" />
                )}
                {shape === 'diamond' && (
                  <polygon points="6,1 11,6 6,11 1,6" fill={fill} stroke="black" />
                )}
                {shape === 'pentagon' && (
                  <polygon points={polygonPoints(5, 5)} fill={fill} stroke="black" />
                )}
                {shape === 'hexagon' && (
                  <polygon points={polygonPoints(6, 5)} fill={fill} stroke="black" />
                )}
                {shape === 'star' && (
                  <polygon points={starPoints(5)} fill={fill} stroke="black" />
                )}
                {shape === 'circle' && (
                  <circle cx="6" cy="6" r="5" fill={fill} stroke="black" />
                )}
              </svg>
              <span>{displayLabel}</span>
            </div>
          )
        })}
        <div className="legend-item">
          <svg width="30" height="6" className="legend-icon">
            <line x1="1" y1="3" x2="29" y2="3" stroke="#66aaff" strokeWidth="2" />
          </svg>
          <span>queue (empty)</span>
        </div>
        <div className="legend-item">
          <svg width="30" height="6" className="legend-icon">
            <line x1="1" y1="3" x2="29" y2="3" stroke="#ff6666" strokeWidth="4" />
          </svg>
          <span>queue (deep)</span>
        </div>
      </div>
    </div>
  )
}
