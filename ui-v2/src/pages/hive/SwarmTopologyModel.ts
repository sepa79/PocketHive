import type { Edge, Node } from '@xyflow/react'

/**
 * Responsibility: define the swarm topology's shared presentation projection.
 * Must not: redefine scenario or runtime contracts, infer state, or own graph mutations.
 * Contract: RESP-UI-SWARM-TOPOLOGY-PRESENTATION — docs/architecture/runtime-responsibilities.md#resp-ui-swarm-topology-presentation.
 */
export type SwarmTopologyNodeData = {
  title: string
  subtitle: string
  dim: boolean
}

export type SwarmTopologyFlowNode = Node<SwarmTopologyNodeData, 'worker'>

type SwarmTopologyEdgeData = { depth: number }

export type SwarmTopologyFlowEdge = Edge<SwarmTopologyEdgeData> & { data: SwarmTopologyEdgeData }
