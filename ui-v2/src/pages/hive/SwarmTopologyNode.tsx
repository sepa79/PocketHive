import { Handle, Position, type NodeProps } from '@xyflow/react'
import type { SwarmTopologyFlowNode } from './SwarmTopologyModel'

/**
 * Responsibility: render the existing worker-node presentation projection.
 * Must not: fetch status, choose graph layout, or mutate selection and positions.
 * Contract: RESP-UI-SWARM-TOPOLOGY-PRESENTATION — docs/architecture/runtime-responsibilities.md#resp-ui-swarm-topology-presentation.
 */
export function SwarmTopologyNode({ data, selected }: NodeProps<SwarmTopologyFlowNode>) {
  const cls = selected
    ? 'swarmFlowNode swarmFlowNodeSelected'
    : data.dim
      ? 'swarmFlowNode swarmFlowNodeDim'
      : 'swarmFlowNode'
  return (
    <div className={cls}>
      <Handle type="target" position={Position.Left} className="swarmFlowHandle" />
      <Handle type="source" position={Position.Right} className="swarmFlowHandle" />
      <div className="swarmFlowNodeTitle">{data.title}</div>
      <div className="swarmFlowNodeSubtitle" title={data.subtitle}>
        {data.subtitle}
      </div>
    </div>
  )
}
