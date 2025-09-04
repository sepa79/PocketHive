import { useEffect, useState } from 'react'
import ForceGraph2D from 'react-force-graph-2d'
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

export default function TopologyView() {
  const [data, setData] = useState<GraphData>({ nodes: [], links: [] })

  useEffect(() => {
    const unsub = subscribeTopology((topo: Topology) => {
      setData({
        nodes: topo.nodes.map((n) => ({ ...n })),
        links: topo.edges.map((e) => ({ source: e.from, target: e.to, queue: e.queue })),
      })
    })
    return () => unsub()
  }, [])

  const shapeMap: Record<string, 'circle' | 'square' | 'triangle' | 'diamond'> = {
    generator: 'triangle',
    processor: 'square',
    postprocessor: 'diamond',
    sut: 'circle',
  }

  const drawNode = (node: GraphNode, ctx: CanvasRenderingContext2D) => {
    const shape = shapeMap[node.type] || 'circle'
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
    } else {
      ctx.arc(node.x ?? 0, node.y ?? 0, size, 0, 2 * Math.PI)
    }
    ctx.fillStyle = '#ffcc00'
    ctx.strokeStyle = '#000'
    ctx.lineWidth = 1
    ctx.fill()
    ctx.stroke()
  }

  const types = Array.from(new Set(data.nodes.map((n) => n.type)))

  return (
    <div className="topology-container">
      <ForceGraph2D
        graphData={data as unknown as GraphData}
        enableNodeDrag
        cooldownTicks={0}
        nodeLabel="id"
        linkLabel={(l) => (l as GraphLink).queue}
        nodeCanvasObject={(node, ctx) => drawNode(node as GraphNode, ctx)}
        onNodeDragEnd={(n) =>
          updateNodePosition(String(n.id), n.x ?? 0, n.y ?? 0)}
      />
      <div className="topology-legend">
        {types.map((t) => {
          const shape = shapeMap[t] || 'circle'
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
                {shape === 'circle' && (
                  <circle cx="6" cy="6" r="5" fill="#ffcc00" stroke="black" />
                )}
              </svg>
              <span>{t}</span>
            </div>
          )
        })}
      </div>
    </div>
  )
}
