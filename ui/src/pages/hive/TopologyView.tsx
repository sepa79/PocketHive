import { useEffect, useMemo, useRef, useState } from 'react'
import ForceGraph2D from 'react-force-graph-2d'
import { renderToStaticMarkup } from 'react-dom/server'
import {
  subscribeTopology,
  subscribeComponents,
  updateNodePosition,
  type Topology,
} from '../../lib/stompClient'
import type { Component } from '../../types/hive'
import { NodeRenderer, type NodeData } from '../../features/hive/topology/NodeRenderer'
import { BeeSizes, type Status } from '../../features/hive/topology/theme'
import './TopologyView.css'

interface GraphLink {
  source: string
  target: string
  queue: string
}

interface GraphData {
  nodes: NodeData[]
  links: GraphLink[]
}

function toStatus(status?: string): Status {
  const s = status?.toLowerCase()
  return (s === 'ok' || s === 'warn' || s === 'err' || s === 'ghost' ? s : 'ghost') as Status
}

export default function TopologyView() {
  const [topology, setTopology] = useState<Topology>({ nodes: [], edges: [] })
  const [components, setComponents] = useState<Component[]>([])

  useEffect(() => {
    const unsub = subscribeTopology(setTopology)
    return () => unsub()
  }, [])

  useEffect(() => {
    const unsub = subscribeComponents(setComponents)
    return () => unsub()
  }, [])

  const graphData = useMemo<GraphData>(() => {
    const compMap = Object.fromEntries(components.map((c) => [c.id, c]))
    return {
      nodes: topology.nodes.map((n) => {
        const c = compMap[n.id]
        return {
          id: n.id,
          role: (c?.name ?? 'Worker') as NodeData['role'],
          name: c?.id ?? n.id,
          status: toStatus(c?.status),
          x: n.x ?? 0,
          y: n.y ?? 0,
          progress: 0,
        }
      }),
      links: topology.edges.map((e) => ({ source: e.from, target: e.to, queue: e.queue })),
    }
  }, [topology, components])

  // react-force-graph doesn't export its ref type, so use `any` to hold the instance
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const fgRef = useRef<any>(null)
  const imgCache = useRef<Record<string, HTMLImageElement>>({})
  const size = Math.max(BeeSizes.w, BeeSizes.h) + BeeSizes.halo * 2

  const paintNode = (node: NodeData, ctx: CanvasRenderingContext2D) => {
    let img = imgCache.current[node.id]
    if (!img) {
      const svg = renderToStaticMarkup(
        <svg xmlns="http://www.w3.org/2000/svg" width={size} height={size} viewBox={`${-size / 2} ${-size / 2} ${size} ${size}`}>
          <NodeRenderer node={{ ...node, x: 0, y: 0 }} />
        </svg>,
      )
      img = new Image()
      img.src = `data:image/svg+xml;base64,${btoa(unescape(encodeURIComponent(svg)))}`
      img.onload = () => fgRef.current?.refresh()
      imgCache.current[node.id] = img
    }
    if (img.complete) {
      ctx.drawImage(img, node.x - size / 2, node.y - size / 2, size, size)
    }
  }

  return (
    <div className="topology-container">
      <ForceGraph2D
        ref={fgRef}
        graphData={graphData}
        enableNodeDrag
        cooldownTicks={0}
        nodeLabel="id"
        linkLabel={(l) => (l as GraphLink).queue}
        nodeCanvasObject={(n, ctx) => paintNode(n as NodeData, ctx)}
        nodePointerAreaPaint={(n, _color, ctx) => paintNode(n as NodeData, ctx)}
        onNodeDragEnd={(n) =>
          updateNodePosition(String((n as NodeData).id), n.x ?? 0, n.y ?? 0)}
      />
    </div>
  )
}
