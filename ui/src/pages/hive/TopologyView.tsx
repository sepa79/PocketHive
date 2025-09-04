import { useEffect, useRef, useState } from 'react'
import ForceGraph2D, { type ForceGraphMethods } from 'react-force-graph-2d'
import {
  subscribeTopology,
  updateNodePosition,
  type Topology,
} from '../../lib/stompClient'
import './TopologyView.css'

interface GraphNode {
  id: string
  type: string
  x?: number
  y?: number
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

export default function TopologyView() {
  const [data, setData] = useState<GraphData>({ nodes: [], links: [] })
  const containerRef = useRef<HTMLDivElement>(null)
  const graphRef = useRef<ForceGraphMethods<GraphNode, GraphLink> | undefined>(
    undefined,
  )
  const [dims, setDims] = useState({ width: 0, height: 0 })
  const shapeMapRef = useRef<Record<string, NodeShape>>({ sut: 'circle' })

  useEffect(() => {
    const unsub = subscribeTopology((topo: Topology) => {
      const nodes = topo.nodes.map((n, i) => ({
        ...n,
        x: n.x ?? i * 80,
        y: n.y ?? 0,
      }))
      setData({
        nodes,
        links: topo.edges.map((e) => ({ source: e.from, target: e.to, queue: e.queue })),
      })
    })
    return () => unsub()
  }, [])

  useEffect(() => {
    const el = containerRef.current
    if (!el) return
    const update = () =>
      setDims({ width: el.clientWidth, height: el.clientHeight })
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

  const drawPolygon = (
    ctx: CanvasRenderingContext2D,
    x: number,
    y: number,
    sides: number,
    size: number,
  ) => {
    for (let i = 0; i < sides; i++) {
      const angle = -Math.PI / 2 + (2 * Math.PI * i) / sides
      const px = x + size * Math.cos(angle)
      const py = y + size * Math.sin(angle)
      if (i === 0) ctx.moveTo(px, py)
      else ctx.lineTo(px, py)
    }
    ctx.closePath()
  }

  const drawStar = (
    ctx: CanvasRenderingContext2D,
    x: number,
    y: number,
    size: number,
  ) => {
    let rot = -Math.PI / 2
    const spikes = 5
    const step = Math.PI / spikes
    for (let i = 0; i < spikes * 2; i++) {
      const r = i % 2 === 0 ? size : size / 2
      const px = x + Math.cos(rot) * r
      const py = y + Math.sin(rot) * r
      if (i === 0) ctx.moveTo(px, py)
      else ctx.lineTo(px, py)
      rot += step
    }
    ctx.closePath()
  }

  const drawNode = (node: GraphNode, ctx: CanvasRenderingContext2D) => {
    const shape = getShape(node.type)
    const size = 8
    ctx.beginPath()
    if (shape === 'square') {
      ctx.rect((node.x ?? 0) - size, (node.y ?? 0) - size, size * 2, size * 2)
    } else if (shape === 'triangle') {
      ctx.moveTo(node.x ?? 0, (node.y ?? 0) - size)
      ctx.lineTo((node.x ?? 0) + size, (node.y ?? 0) + size)
      ctx.lineTo((node.x ?? 0) - size, (node.y ?? 0) + size)
      ctx.closePath()
    } else if (shape === 'diamond') {
      ctx.moveTo(node.x ?? 0, (node.y ?? 0) - size)
      ctx.lineTo((node.x ?? 0) + size, node.y ?? 0)
      ctx.lineTo(node.x ?? 0, (node.y ?? 0) + size)
      ctx.lineTo((node.x ?? 0) - size, node.y ?? 0)
      ctx.closePath()
    } else if (shape === 'pentagon') {
      drawPolygon(ctx, node.x ?? 0, node.y ?? 0, 5, size)
    } else if (shape === 'hexagon') {
      drawPolygon(ctx, node.x ?? 0, node.y ?? 0, 6, size)
    } else if (shape === 'star') {
      drawStar(ctx, node.x ?? 0, node.y ?? 0, size)
    } else {
      ctx.arc(node.x ?? 0, node.y ?? 0, size, 0, 2 * Math.PI)
    }
    ctx.fillStyle = '#ffcc00'
    ctx.strokeStyle = '#000'
    ctx.lineWidth = 1
    ctx.fill()
    ctx.stroke()
  }

  const polygonPoints = (sides: number) => {
    const r = 5
    const cx = 6
    const cy = 6
    const pts: string[] = []
    for (let i = 0; i < sides; i++) {
      const angle = -Math.PI / 2 + (2 * Math.PI * i) / sides
      pts.push(`${cx + r * Math.cos(angle)},${cy + r * Math.sin(angle)}`)
    }
    return pts.join(' ')
  }

  const starPoints = () => {
    const outer = 5
    const inner = 2.5
    const cx = 6
    const cy = 6
    const pts: string[] = []
    let rot = -Math.PI / 2
    const step = Math.PI / 5
    for (let i = 0; i < 10; i++) {
      const r = i % 2 === 0 ? outer : inner
      pts.push(`${cx + Math.cos(rot) * r},${cy + Math.sin(rot) * r}`)
      rot += step
    }
    return pts.join(' ')
  }

  const types = Array.from(new Set(data.nodes.map((n) => n.type)))

  useEffect(() => {
    if (data.nodes.length) graphRef.current?.zoomToFit?.(0, 20)
  }, [dims, data.nodes.length])

  return (
    <div ref={containerRef} className="topology-container">
      <ForceGraph2D
        ref={graphRef}
        width={dims.width}
        height={dims.height}
        graphData={data as unknown as GraphData}
        enableNodeDrag
        cooldownTicks={0}
        nodeLabel="id"
        linkLabel={(l) => (l as GraphLink).queue}
        linkColor={() => '#66aaff'}
        linkWidth={() => 2}
        linkDirectionalArrowLength={4}
        linkCanvasObjectMode={() => 'after'}
        linkCanvasObject={(link, ctx) => {
          const l = link as GraphLink & {
            source: { x: number; y: number }
            target: { x: number; y: number }
          }
          const { source, target, queue } = l
          if (!source || !target) return
          const x = (source.x + target.x) / 2
          const y = (source.y + target.y) / 2
          ctx.font = '6px sans-serif'
          const textWidth = ctx.measureText(queue).width
          ctx.fillStyle = 'rgba(0,0,0,0.6)'
          ctx.fillRect(x - textWidth / 2 - 2, y - 4, textWidth + 4, 8)
          ctx.textAlign = 'center'
          ctx.textBaseline = 'middle'
          ctx.fillStyle = '#fff'
          ctx.fillText(queue, x, y)
        }}
        nodeCanvasObject={(node, ctx) => drawNode(node as GraphNode, ctx)}
        onNodeDragEnd={(n) =>
          updateNodePosition(String(n.id), n.x ?? 0, n.y ?? 0)}
      />
      <button
        className="reset-view"
        onClick={() => graphRef.current?.zoomToFit?.(0, 20)}
      >
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
                  <polygon points={polygonPoints(5)} fill="#ffcc00" stroke="black" />
                )}
                {shape === 'hexagon' && (
                  <polygon points={polygonPoints(6)} fill="#ffcc00" stroke="black" />
                )}
                {shape === 'star' && (
                  <polygon points={starPoints()} fill="#ffcc00" stroke="black" />
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
          <svg width="12" height="12" className="legend-icon">
            <line x1="1" y1="6" x2="9" y2="6" stroke="#66aaff" strokeWidth="2" />
            <polygon points="9,4 11,6 9,8" fill="#66aaff" />
          </svg>
          <span>queue</span>
        </div>
      </div>
    </div>
  )
}
