import { useEffect, useState } from 'react'
import { ForceGraph2D } from 'react-force-graph'
import { subscribeTopology, updateNodePosition, type Topology } from '../../lib/stompClient'
import './TopologyView.css'

interface GraphData {
  nodes: { id: string; x?: number; y?: number }[]
  links: { source: string; target: string; queue: string }[]
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

  return (
    <div className="topology-container">
      <ForceGraph2D
        graphData={data}
        enableNodeDrag
        cooldownTicks={0}
        nodeLabel={(n: { id: string }) => n.id}
        linkLabel={(l: { queue: string }) => l.queue}
        onNodeDragEnd={(n: { id: string; x: number; y: number }) =>
          updateNodePosition(n.id, n.x, n.y)}
      />
    </div>
  )
}
