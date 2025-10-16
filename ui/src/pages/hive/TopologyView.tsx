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
import type { Component } from '../../types/hive'
import './TopologyView.css'

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

interface ShapeNodeData {
  label: string
  shape: NodeShape
  enabled?: boolean
  queueCount: number
  swarmId?: string
  componentType?: string
  componentId?: string
  status?: string
  meta?: Record<string, unknown>
  role?: string
  [key: string]: unknown
}

function formatMetaValue(value: unknown): string | null {
  if (value === undefined || value === null) return null
  if (typeof value === 'boolean') return value ? 'Yes' : 'No'
  if (typeof value === 'number') return value.toString()
  if (typeof value === 'string') return value
  return null
}

function ShapeNode({ data, selected }: NodeProps<ShapeNodeData>) {
  const size = 10
  const fill = data.enabled === false ? '#999999' : '#ffcc00'
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
      {data.queueCount > 0 && <span className="badge">{data.queueCount}</span>}
      <Handle type="source" position={Position.Right} />
    </div>
  )
}

interface SwarmGroupComponentData {
  id: string
  name: string
  shape: NodeShape
  enabled?: boolean
  queueCount: number
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
      const fill = comp.enabled === false ? '#999999' : '#ffcc00'
      const iconRadius = comp.id === data.controllerId ? 14 : 11
      const label = comp.name
        .split(/[-_]/)
        .filter((part) => part.length > 0)
        .map((part) => part[0]?.toUpperCase() ?? '')
        .join('')
        .slice(0, 2)
      const badgeValue = comp.queueCount > 99 ? '99+' : comp.queueCount.toString()
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
            {label || '?'}
          </text>
          {comp.queueCount > 0 && (
            <g>
              <circle
                cx={comp.x + iconRadius * 0.85}
                cy={comp.y - iconRadius * 0.85}
                r={7}
                className="swarm-group__badge"
              />
              <text
                x={comp.x + iconRadius * 0.85}
                y={comp.y - iconRadius * 0.85 + 2}
                textAnchor="middle"
                className="swarm-group__badge-text"
              >
                {badgeValue}
              </text>
            </g>
          )}
        </g>
      )
    },
    [data.controllerId, data.selectedId],
  )

  return (
    <div className={`swarm-group${hasSelected ? ' selected' : ''}`}>
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

export default function TopologyView({ selectedId, onSelect, swarmId, onSwarmSelect }: Props) {
  const [data, setData] = useState<GraphData>({ nodes: [], links: [] })
  const shapeMapRef = useRef<Record<string, NodeShape>>({ sut: 'circle' })
  const [queueDepths, setQueueDepths] = useState<Record<string, number>>({})
  const [queueCounts, setQueueCounts] = useState<Record<string, number>>({})
  const [componentsById, setComponentsById] = useState<Record<string, Component>>({})
  const flowRef = useRef<ReactFlowInstance<FlowNode, Edge> | null>(null)
  const containerRef = useRef<HTMLDivElement>(null)
  const [rfNodes, setRfNodes] = useState<FlowNode[]>([])

  useEffect(() => {
    const unsub = subscribeComponents((comps: Component[]) => {
      const depths: Record<string, number> = {}
      const counts: Record<string, number> = {}
      const map: Record<string, Component> = {}
      comps.forEach((c) => {
        map[c.id] = c
        counts[c.id] = c.queues.length
        c.queues.forEach((q) => {
          if (typeof q.depth === 'number') {
            const d = depths[q.name]
            depths[q.name] = d === undefined ? q.depth : Math.max(d, q.depth)
          }
        })
      })
      setQueueDepths(depths)
      setQueueCounts(counts)
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
        .map((id) => topo.nodes.find((n) => n.id === id)!)
        .map((n, idx) => ({ ...n, x: n.x ?? idx * 80, y: n.y ?? 0 }))
      const unconnectedNodes = topo.nodes
        .filter((n) => !visited.has(n.id))
        .map((n, idx) => ({ ...n, x: n.x ?? idx * 80, y: n.y ?? 80 }))
      let nodes = [...connectedNodes, ...unconnectedNodes]
      if (swarmId) {
        nodes = nodes.filter((n) =>
          swarmId === 'default' ? !n.swarmId : n.swarmId === swarmId,
        )
      }
      const ids = new Set(nodes.map((n) => n.id))
      const links = topo.edges
        .filter((e) => ids.has(e.from) && ids.has(e.to))
        .map((e) => ({ source: e.from, target: e.to, queue: e.queue }))
      setData({ nodes, links })
    })
    return () => unsub()
  }, [swarmId])

  useEffect(() => {
    const el = containerRef.current
    if (!el) return
    const update = () => flowRef.current?.fitView({ padding: 20 })
    update()
    if (typeof ResizeObserver !== 'undefined') {
      const ro = new ResizeObserver(update)
      ro.observe(el)
      return () => ro.disconnect()
    }
    window.addEventListener('resize', update)
    return () => window.removeEventListener('resize', update)
  }, [])

  const getShape = (type: string): NodeShape => {
    const map = shapeMapRef.current
    if (!map[type]) {
      const used = new Set(Object.values(map))
      const next = shapeOrder.find((s) => !used.has(s)) ?? 'circle'
      map[type] = next
    }
    return map[type]
  }

  const handleDetails = useCallback(
    (targetSwarm: string) => {
      onSwarmSelect?.(targetSwarm)
    },
    [onSwarmSelect],
  )

  useEffect(() => {
    setRfNodes((prev) => {
      const prevPositions = new Map(prev.map((node) => [node.id, node.position]))
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
          const previous = prevPositions.get(node.id)
          const position = {
            x: node.x ?? previous?.x ?? 0,
            y: node.y ?? previous?.y ?? 0,
          }
          const component = componentsById[node.id]
          const role = component?.role?.trim() || node.type
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
              queueCount: queueCounts[node.id] ?? 0,
              swarmId: node.swarmId,
              componentType: node.type,
              componentId: node.id,
              status: component?.status,
              meta: component?.config,
              role,
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
              components: members.map((member) => ({
                id: member.id,
                name: member.type,
                shape: getShape(member.type),
                enabled: member.enabled,
                queueCount: queueCounts[member.id] ?? 0,
              })),
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
        const previous = prevPositions.get(node.id)
        const position = {
          x: node.x ?? previous?.x ?? 0,
          y: node.y ?? previous?.y ?? 0,
        }
        const component = componentsById[node.id]
        const role = component?.role?.trim() || node.type
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
            queueCount: queueCounts[node.id] ?? 0,
            swarmId: node.swarmId,
            componentType: node.type,
            componentId: node.id,
            status: component?.status,
            meta: component?.config,
            role,
          },
          type: 'shape',
          selected: selectedId === node.id,
        }
      }) as FlowNode[]
    })
  }, [componentsById, data.links, data.nodes, queueCounts, queueDepths, handleDetails, selectedId, swarmId])

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

  useEffect(() => {
    if (rfNodes.length) flowRef.current?.fitView({ padding: 20 })
  }, [rfNodes.length])

  const types = Array.from(new Set(data.nodes.map((n) => n.type)))

  return (
    <div ref={containerRef} className="topology-container">
        <ReactFlow<FlowNode, Edge>
          nodes={rfNodes}
          edges={edges}
          onNodesChange={onNodesChange}
          nodeTypes={{ shape: ShapeNode, swarmGroup: SwarmGroupNode }}
          onInit={(inst: ReactFlowInstance<FlowNode, Edge>) =>
            (flowRef.current = inst)
          }
          onNodeDragStop={(
            _e: unknown,
            node: FlowNode,
          ) => updateNodePosition(node.id, node.position.x, node.position.y)}
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
      <button className="reset-view" onClick={() => flowRef.current?.fitView({ padding: 20 })}>
        Reset View
      </button>
      <div className="topology-legend">
        {types.map((t) => {
          const shape = getShape(t)
          return (
            <div key={t} className="legend-item">
              <svg width="12" height="12" className="legend-icon">
                {shape === 'square' && (
                  <rect x="1" y="1" width="10" height="10" fill="#ffcc00" stroke="black" />
                )}
                {shape === 'triangle' && (
                  <polygon points="6,1 11,11 1,11" fill="#ffcc00" stroke="black" />
                )}
                {shape === 'diamond' && (
                  <polygon points="6,1 11,6 6,11 1,6" fill="#ffcc00" stroke="black" />
                )}
                {shape === 'pentagon' && (
                  <polygon points={polygonPoints(5, 5)} fill="#ffcc00" stroke="black" />
                )}
                {shape === 'hexagon' && (
                  <polygon points={polygonPoints(6, 5)} fill="#ffcc00" stroke="black" />
                )}
                {shape === 'star' && (
                  <polygon points={starPoints(5)} fill="#ffcc00" stroke="black" />
                )}
                {shape === 'circle' && (
                  <circle cx="6" cy="6" r="5" fill="#ffcc00" stroke="black" />
                )}
              </svg>
              <span>{t}</span>
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
        <div className="legend-item">
          <div className="legend-icon node-badge">
            <svg width="12" height="12">
              <circle cx="6" cy="6" r="5" fill="#ffcc00" stroke="black" />
            </svg>
            <span className="badge">n</span>
          </div>
          <span>#queues</span>
        </div>
      </div>
    </div>
  )
}

