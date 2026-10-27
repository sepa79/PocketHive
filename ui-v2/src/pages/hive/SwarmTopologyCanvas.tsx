import { useMemo } from 'react'
import {
  Background,
  Controls,
  MarkerType,
  ReactFlow,
  type NodeTypes,
  type OnEdgesChange,
  type OnNodesChange,
  type ReactFlowInstance,
  type XYPosition,
} from '@xyflow/react'
import '@xyflow/react/dist/style.css'
import { useTheme } from '../../lib/useTheme'
import type { SwarmTopologyFlowEdge, SwarmTopologyFlowNode } from './SwarmTopologyModel'
import { SwarmTopologyNode } from './SwarmTopologyNode'

type Props = {
  nodes: SwarmTopologyFlowNode[]
  edges: SwarmTopologyFlowEdge[]
  selectedNodeId: string | null
  onNodesChange: OnNodesChange<SwarmTopologyFlowNode>
  onEdgesChange: OnEdgesChange<SwarmTopologyFlowEdge>
  onInit: (instance: ReactFlowInstance<SwarmTopologyFlowNode, SwarmTopologyFlowEdge>) => void
  onSelectNode: (id: string) => void
  onNodePositionChange: (id: string, position: XYPosition) => void
}

const nodeTypes: NodeTypes = { worker: SwarmTopologyNode }

/**
 * Responsibility: render the swarm graph, controls and theme-derived edge palette.
 * Must not: fetch runtime state, persist positions, decide graph semantics, or write the theme.
 * Contract: RESP-UI-SWARM-TOPOLOGY-PRESENTATION — docs/architecture/runtime-responsibilities.md#resp-ui-swarm-topology-presentation.
 */
export function SwarmTopologyCanvas({ nodes, edges, selectedNodeId, onNodesChange, onEdgesChange, onInit, onSelectNode, onNodePositionChange }: Props) {
  const theme = useTheme()
  const presentedEdges = useMemo<SwarmTopologyFlowEdge[]>(() => edges.map((edge) => {
    const stroke = edge.data.depth > 0 ? 'var(--status-error)' : 'var(--accent)'
    return {
      ...edge,
      markerEnd: { type: MarkerType.ArrowClosed, color: stroke },
      style: {
        stroke,
        strokeWidth: 1.5 + Math.log(edge.data.depth + 1),
        opacity: selectedNodeId && edge.source !== selectedNodeId && edge.target !== selectedNodeId ? 0.25 : 1,
      },
      labelStyle: { fill: 'var(--text)', fontSize: 11 },
      labelBgStyle: { fill: 'var(--bg)', stroke: 'var(--border)', strokeWidth: 1 },
      labelBgPadding: [6, 3] as [number, number],
      labelBgBorderRadius: 6,
    }
  }), [edges, selectedNodeId])

  return (
    <div className="card swarmViewCanvas">
      <div className="row between" style={{ marginBottom: 8 }}>
        <div className="h2">Topology</div>
        <div className="muted" title="Logical topology from Scenario Manager; edge style uses live queue stats when available.">
          {edges.length} edge(s)
        </div>
      </div>
      <div className="swarmGraphSurface" role="img" aria-label="Swarm topology view">
        <ReactFlow
          nodes={nodes}
          edges={presentedEdges}
          nodeTypes={nodeTypes}
          colorMode={theme}
          onNodesChange={onNodesChange}
          onEdgesChange={onEdgesChange}
          onInit={onInit}
          minZoom={0.2}
          maxZoom={2.4}
          onNodeClick={(_event, node) => onSelectNode(node.id)}
          onNodeDragStop={(_event, node) => onNodePositionChange(node.id, node.position)}
          proOptions={{ hideAttribution: true }}
        >
          <Background />
          <Controls />
        </ReactFlow>
      </div>
    </div>
  )
}
