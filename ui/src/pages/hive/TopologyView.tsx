import { useEffect, useState } from 'react'
import { ForceGraph2D, type LinkObject, type NodeObject } from 'react-force-graph'
import {
  subscribeTopology,
  updateNodePosition,
  type Topology,
} from '../../lib/stompClient'
import './TopologyView.css'

interface GraphNode extends NodeObject {
  id: string
}

interface GraphLink extends LinkObject {
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

  return (
    <div className="topology-container">
      <ForceGraph2D
        graphData={data}
        enableNodeDrag
        cooldownTicks={0}
        nodeLabel="id"
        linkLabel={(l) => (l as GraphLink).queue}
        onNodeDragEnd={(n) =>
          updateNodePosition(String(n.id), n.x ?? 0, n.y ?? 0)}
      />
    </div>
  )
}
