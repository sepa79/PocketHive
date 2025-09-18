/* eslint-disable @typescript-eslint/ban-ts-comment */
// @ts-nocheck
import { useEffect, useRef, useState, useMemo, useCallback } from 'react'
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
  beeName?: string
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

const DEFAULT_SWARM_KEY = 'default'
const FULL_CARD_WIDTH = 220
const FULL_CARD_HEIGHT = 120
const ICON_CARD_SIZE = 64
const HEADER_OFFSET = 48
const PADDING = 24

const isOutsideSwarm = (swarmId?: string) => {
  if (!swarmId) return true
  return swarmId.toLowerCase() === 'hive'
}

const toSwarmKey = (swarmId?: string): string =>
  isOutsideSwarm(swarmId) ? DEFAULT_SWARM_KEY : (swarmId as string)

const isOrchestratorNode = (node: Pick<GraphNode, 'id' | 'type'>) => {
  const type = node.type?.toLowerCase?.() ?? ''
  const id = node.id?.toLowerCase?.() ?? ''
  return type.includes('orchestrator') || id.includes('orchestrator')
}

interface ShapeNodeData {
  label: string
  shape: NodeShape
  enabled?: boolean
  queueCount: number
  swarmId?: string
  beeName?: string
  role?: string
  instanceId?: string
}

interface SwarmCardData {
  swarmId: string
  label: string
  beeCount: number
  controllerName?: string
  controllerId?: string
}

interface EntryDimensions {
  width: number
  height: number
}

interface GraphEntry {
  node: Node<ShapeNodeData>
  graph: GraphNode
  absolute: { x: number; y: number }
  dimensions: EntryDimensions
  isController: boolean
}

function ShapeSvg({
  shape,
  size,
  fill,
  className,
}: {
  shape: NodeShape
  size: number
  fill: string
  className?: string
}) {
  const dimension = 2 * size
  return (
    <svg className={className} width={dimension} height={dimension}>
      {shape === 'square' && (
        <rect x={0} y={0} width={dimension} height={dimension} fill={fill} stroke="black" />
      )}
      {shape === 'triangle' && (
        <polygon
          points={`${size},0 ${dimension},${dimension} 0,${dimension}`}
          fill={fill}
          stroke="black"
        />
      )}
      {shape === 'diamond' && (
        <polygon
          points={`${size},0 ${dimension},${size} ${size},${dimension} 0,${size}`}
          fill={fill}
          stroke="black"
        />
      )}
      {shape === 'pentagon' && (
        <polygon points={polygonPoints(5, size)} fill={fill} stroke="black" />
      )}
      {shape === 'hexagon' && (
        <polygon points={polygonPoints(6, size)} fill={fill} stroke="black" />
      )}
      {shape === 'star' && <polygon points={starPoints(size)} fill={fill} stroke="black" />}
      {shape === 'circle' && <circle cx={size} cy={size} r={size} fill={fill} stroke="black" />}
    </svg>
  )
}

function ShapeNode({ data, selected }: NodeProps<ShapeNodeData>) {
  const size = 10
  const fill = data.enabled === false ? '#999999' : '#ffcc00'
  const beeName = data.beeName ?? data.label ?? data.instanceId ?? ''
  const roleLabel = humanize(data.role ?? '')
  const swarmLabel = isOutsideSwarm(data.swarmId)
    ? 'Outside swarms'
    : `Swarm ${data.swarmId}`
  const showInstanceId =
    data.instanceId && data.instanceId !== beeName ? data.instanceId : null
  const primaryDetails: { text: string; className?: string }[] = []
  if (roleLabel) {
    primaryDetails.push({ text: roleLabel, className: 'shape-node__role' })
  }
  if (data.enabled === false) {
    primaryDetails.push({ text: 'Disabled', className: 'shape-node__detail--disabled' })
  }
  const secondaryDetails: { text: string; className?: string }[] = [
    { text: swarmLabel },
  ]
  if (showInstanceId) {
    secondaryDetails.push({ text: showInstanceId, className: 'shape-node__detail--mono' })
  }
  return (
    <div className={`shape-node${selected ? ' selected' : ''}`}>
      <Handle type="target" position={Position.Left} />
      <ShapeSvg shape={data.shape} size={size} fill={fill} className="shape-icon" />
      <div className="shape-node__content">
        <div className="shape-node__header">
          <span className="shape-node__name" title={beeName}>
            {beeName}
          </span>
          {data.queueCount > 0 && (
            <span className="shape-node__badge">{data.queueCount}</span>
          )}
        </div>
        {primaryDetails.length > 0 && (
          <div className="shape-node__meta">
            {primaryDetails.map(({ text, className }, idx) => (
              <span
                key={`${text}-${idx}`}
                className={`shape-node__detail${className ? ` ${className}` : ''}`}
              >
                {text}
              </span>
            ))}
          </div>
        )}
        <div className="shape-node__meta shape-node__meta--secondary">
          {secondaryDetails.map(({ text, className }, idx) => (
            <span
              key={`${text}-${idx}`}
              className={`shape-node__detail${className ? ` ${className}` : ''}`}
            >
              {text}
            </span>
          ))}
        </div>
      </div>
      <Handle type="source" position={Position.Right} />
    </div>
  )
}

function BeeIconNode({ data, selected }: NodeProps<ShapeNodeData>) {
  const size = 12
  const fill = data.enabled === false ? '#cbd5f5' : '#ffcc00'
  const titleParts = [data.beeName ?? data.label ?? data.instanceId]
  if (data.role) titleParts.push(humanize(data.role))
  const swarmLabel = isOutsideSwarm(data.swarmId)
    ? 'Outside swarms'
    : `Swarm ${data.swarmId}`
  titleParts.push(swarmLabel)
  const title = titleParts.filter(Boolean).join(' • ')
  return (
    <div className={`bee-icon-node${selected ? ' selected' : ''}`} title={title}>
      <Handle type="target" position={Position.Left} />
      <ShapeSvg shape={data.shape} size={size} fill={fill} className="bee-icon-node__shape" />
      {data.queueCount > 0 && <span className="bee-icon-node__badge">{data.queueCount}</span>}
      <Handle type="source" position={Position.Right} />
    </div>
  )
}

function humanize(value?: string) {
  if (!value) return ''
  return value
    .split(/[-_.\s]+/)
    .filter(Boolean)
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(' ')
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

function SwarmCardNode({ data }: NodeProps<SwarmCardData>) {
  const beeLabel = data.beeCount === 1 ? 'bee' : 'bees'
  return (
    <div className="swarm-card">
      <div className="swarm-card__header">
        <span className="swarm-card__title">{data.label}</span>
        <span className="swarm-card__count">{data.beeCount} {beeLabel}</span>
      </div>
      {data.controllerName && (
        <div className="swarm-card__meta">
          <span className="swarm-card__meta-label">Controller</span>
          <span className="swarm-card__meta-value" title={data.controllerName}>
            {data.controllerName}
          </span>
        </div>
      )}
      {data.controllerId && data.controllerId !== data.controllerName && (
        <div className="swarm-card__meta swarm-card__meta--secondary">
          <span className="swarm-card__meta-label">Instance</span>
          <span className="swarm-card__meta-value swarm-card__meta-value--mono">
            {data.controllerId}
          </span>
        </div>
      )}
    </div>
  )
}

export default function TopologyView({ selectedId, onSelect, swarmId, onSwarmSelect }: Props) {
  const [data, setData] = useState<GraphData>({ nodes: [], links: [] })
  const shapeMapRef = useRef<Record<string, NodeShape>>({ sut: 'circle' })
  const [queueDepths, setQueueDepths] = useState<Record<string, number>>({})
  const [queueCounts, setQueueCounts] = useState<Record<string, number>>({})
  const flowRef = useRef<ReactFlowInstance | null>(null)
  const containerRef = useRef<HTMLDivElement>(null)
  const [rfNodes, setRfNodes] = useState<Node<ShapeNodeData | SwarmCardData>[]>([])

  useEffect(() => {
    const unsub = subscribeComponents((comps: Component[]) => {
      const depths: Record<string, number> = {}
      const counts: Record<string, number> = {}
      comps.forEach((c) => {
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
        nodes = nodes.filter((n) => {
          const outside = isOutsideSwarm(n.swarmId)
          const isOrchestrator = isOrchestratorNode(n)
          if (swarmId === DEFAULT_SWARM_KEY) return outside || isOrchestrator
          if (isOrchestrator) return false
          return !outside && n.swarmId === swarmId
        })
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

  useEffect(() => {
    setRfNodes((prev) => {
      const prevMap = new Map(prev.map((p) => [p.id, p]))
      const detailView = Boolean(swarmId)
      const baseEntries: GraphEntry[] = data.nodes.map((n) => {
        const existing = prevMap.get(n.id)
        const absX = n.x ?? existing?.positionAbsolute?.x ?? existing?.position?.x ?? 0
        const absY = n.y ?? existing?.positionAbsolute?.y ?? existing?.position?.y ?? 0
        const absolute = { x: absX, y: absY }
        const isController = n.type === 'swarm-controller'
        const isOrchestrator = isOrchestratorNode(n)
        const showFullCard = detailView || isOrchestrator
        const nodeType = showFullCard ? 'shape' : 'beeIcon'
        const dimensions: EntryDimensions = showFullCard
          ? { width: FULL_CARD_WIDTH, height: FULL_CARD_HEIGHT }
          : { width: ICON_CARD_SIZE, height: ICON_CARD_SIZE }
        const node: Node<ShapeNodeData> = {
          id: n.id,
          position: { ...absolute },
          positionAbsolute: { ...absolute },
          data: {
            label: n.beeName ?? n.id,
            beeName: n.beeName ?? n.id,
            instanceId: n.id,
            role: n.type,
            shape: getShape(n.type),
            enabled: n.enabled,
            queueCount: queueCounts[n.id] ?? 0,
            swarmId: n.swarmId,
          },
          type: nodeType,
          selected: selectedId === n.id,
        }
        return { node, graph: n, absolute, dimensions, isController }
      })

      const groups = new Map<string, GraphEntry[]>()
      const outsideEntries: GraphEntry[] = []
      baseEntries.forEach((entry) => {
        const key = toSwarmKey(entry.graph.swarmId)
        const isOrchestrator = isOrchestratorNode(entry.graph)
        if (key === DEFAULT_SWARM_KEY || isOrchestrator) {
          outsideEntries.push(entry)
          return
        }
        const list = groups.get(key) ?? []
        list.push(entry)
        groups.set(key, list)
      })

      const containers: Node<SwarmCardData>[] = []
      const childNodes: Node<ShapeNodeData>[] = []

      groups.forEach((entries, key) => {
        if (!entries.length) return
        let minX = Infinity
        let minY = Infinity
        let maxX = -Infinity
        let maxY = -Infinity
        entries.forEach((entry) => {
          minX = Math.min(minX, entry.absolute.x)
          minY = Math.min(minY, entry.absolute.y)
          maxX = Math.max(maxX, entry.absolute.x + entry.dimensions.width)
          maxY = Math.max(maxY, entry.absolute.y + entry.dimensions.height)
        })
        const containerX = minX - PADDING
        const containerY = minY - PADDING - HEADER_OFFSET
        const innerWidth = maxX - minX
        const innerHeight = maxY - minY
        const containerWidth = Math.max(
          FULL_CARD_WIDTH + PADDING * 2,
          innerWidth + PADDING * 2,
        )
        const containerHeight = Math.max(
          HEADER_OFFSET + FULL_CARD_HEIGHT + PADDING * 2,
          HEADER_OFFSET + innerHeight + PADDING * 2,
        )
        const controller = entries.find((e) => e.isController)
        const containerId = `swarm:${key}`
        containers.push({
          id: containerId,
          type: 'swarmCard',
          position: { x: containerX, y: containerY },
          positionAbsolute: { x: containerX, y: containerY },
          data: {
            swarmId: key,
            label: `Swarm ${key}`,
            beeCount: entries.length,
            controllerName: controller?.graph.beeName ?? controller?.graph.id,
            controllerId: controller?.graph.id,
          },
          style: {
            width: containerWidth,
            height: containerHeight,
          },
          draggable: false,
          selectable: true,
        })
        entries.forEach((entry) => {
          entry.node.parentNode = containerId
          entry.node.extent = 'parent'
          entry.node.position = {
            x: entry.absolute.x - containerX,
            y: entry.absolute.y - containerY,
          }
          entry.node.positionAbsolute = { ...entry.absolute }
          childNodes.push(entry.node)
        })
      })

      const outsideNodes = outsideEntries.map((entry) => {
        entry.node.parentNode = undefined
        entry.node.extent = undefined
        entry.node.position = { ...entry.absolute }
        entry.node.positionAbsolute = { ...entry.absolute }
        return entry.node
      })

      return [...containers, ...childNodes, ...outsideNodes]
    })
  }, [data.nodes, queueCounts, selectedId, swarmId])

  const edges: Edge[] = useMemo(
    () =>
      data.links.map((l) => {
        const depth = queueDepths[l.queue] ?? 0
        const color = depth > 0 ? '#ff6666' : '#66aaff'
        const width = 2 + Math.log(depth + 1)
        return {
          id: `${l.source}-${l.target}-${l.queue}`,
          source: l.source,
          target: l.target,
          label: l.queue,
          style: { stroke: color, strokeWidth: width },
          markerEnd: { type: MarkerType.ArrowClosed, color },
          labelBgPadding: [2, 2],
          labelBgBorderRadius: 2,
          labelStyle: { fill: '#fff', fontSize: 6 },
          labelBgStyle: { fill: 'rgba(0,0,0,0.6)' },
        }
      }) as unknown as Edge[],
    [data.links, queueDepths],
  )

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
        <ReactFlow<Node<ShapeNodeData | SwarmCardData>, Edge>
          nodes={rfNodes}
          edges={edges}
          onNodesChange={onNodesChange}
          nodeTypes={{ shape: ShapeNode, beeIcon: BeeIconNode, swarmCard: SwarmCardNode }}
          onInit={(
            inst: ReactFlowInstance<Node<ShapeNodeData | SwarmCardData>, Edge>,
          ) => (flowRef.current = inst)}
          onNodeDragStop={(
            _e: unknown,
            node: Node<ShapeNodeData | SwarmCardData>,
          ) => {
            if (node.type === 'swarmCard') return
            const abs = node.positionAbsolute ?? node.position
            updateNodePosition(node.id, abs.x, abs.y)
          }}
          onNodeClick={(
            _e: unknown,
            node: Node<ShapeNodeData | SwarmCardData>,
          ) => {
            if (node.type === 'swarmCard') {
              if (!swarmId) {
                const d = node.data as SwarmCardData
                onSwarmSelect?.(toSwarmKey(d.swarmId))
              }
              return
            }
            const d = node.data as ShapeNodeData
            if (swarmId) onSelect?.(node.id)
            else onSwarmSelect?.(toSwarmKey(d.swarmId))
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

