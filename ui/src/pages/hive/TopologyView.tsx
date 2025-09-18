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

function ShapeNode({ data, selected }: NodeProps<ShapeNodeData>) {
  const size = 10
  const fill = data.enabled === false ? '#999999' : '#ffcc00'
  const beeName = data.beeName ?? data.label ?? data.instanceId ?? ''
  const roleLabel = humanize(data.role ?? '')
  const swarmLabel =
    data.swarmId && data.swarmId !== 'default'
      ? `Swarm ${data.swarmId}`
      : 'Default swarm'
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

export default function TopologyView({ selectedId, onSelect, swarmId, onSwarmSelect }: Props) {
  const [data, setData] = useState<GraphData>({ nodes: [], links: [] })
  const shapeMapRef = useRef<Record<string, NodeShape>>({ sut: 'circle' })
  const [queueDepths, setQueueDepths] = useState<Record<string, number>>({})
  const [queueCounts, setQueueCounts] = useState<Record<string, number>>({})
  const flowRef = useRef<ReactFlowInstance | null>(null)
  const containerRef = useRef<HTMLDivElement>(null)
  const [rfNodes, setRfNodes] = useState<Node<ShapeNodeData>[]>([])

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

  useEffect(() => {
    setRfNodes((prev) =>
      data.nodes.map((n) => {
        const existing = prev.find((p) => p.id === n.id)
        return {
          id: n.id,
          position: existing?.position ?? { x: n.x ?? 0, y: n.y ?? 0 },
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
          type: 'shape',
          selected: selectedId === n.id,
        } as Node<ShapeNodeData>
      }),
    )
  }, [data.nodes, queueCounts, selectedId])

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
        <ReactFlow<Node<ShapeNodeData>, Edge>
          nodes={rfNodes}
          edges={edges}
          onNodesChange={onNodesChange}
          nodeTypes={{ shape: ShapeNode }}
          onInit={(inst: ReactFlowInstance<Node<ShapeNodeData>, Edge>) =>
            (flowRef.current = inst)
          }
          onNodeDragStop={(
            _e: unknown,
            node: Node<ShapeNodeData>,
          ) => updateNodePosition(node.id, node.position.x, node.position.y)}
          onNodeClick={(
            _e: unknown,
            node: Node<ShapeNodeData>,
          ) => {
            const d = node.data as ShapeNodeData
            if (swarmId) onSelect?.(node.id)
            else onSwarmSelect?.(d.swarmId ?? 'default')
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

