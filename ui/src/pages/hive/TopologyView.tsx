import { useEffect, useRef, useMemo, useCallback } from 'react'
import {
  ReactFlow,
  Background,
  type Edge,
  MarkerType,
} from '@xyflow/react'
import '@xyflow/react/dist/style.css'
import { buildRoleAppearanceMap, type RoleAppearanceMap } from '../../lib/capabilities'
import './TopologyView.css'
import { useCapabilities } from '../../contexts/CapabilitiesContext'
import { useSwarmMetadata } from '../../contexts/SwarmMetadataContext'
import type { Component } from '../../types/hive'
import type { SwarmSummary } from '../../types/orchestrator'
import {
  ShapeNode,
  SwarmGroupNode,
  type NodeShape,
} from './TopologyShapes'
import TopologyLegend from './TopologyLegend'
import { type GraphNode } from './TopologyBuilder'
import {
  buildGuardQueuesBySwarm,
  buildGuardedEdgesForSwarm,
} from './TopologyGuardEdges'
import { normalizeSwarmId } from './TopologyUtils'
import { makeTopologyStyleHelpers, normalizeRoleKey } from './TopologyStyles'
import { useTopologyData } from './useTopologyData'
import {
  useTopologyLayout,
  type FlowNode,
} from './useTopologyLayout'

interface Props {
  selectedId?: string
  onSelect?: (id: string) => void
  swarmId?: string
  onSwarmSelect?: (id: string) => void
}

const EDGE_LABEL_STYLE = {
  fill: '#fff',
  fontSize: 6,
  whiteSpace: 'normal' as const,
}

export default function TopologyView({ selectedId, onSelect, swarmId, onSwarmSelect }: Props) {
  const shapeMapRef = useRef<Record<string, NodeShape>>({ sut: 'circle' })
  const { data, queueDepths, componentsById } = useTopologyData(swarmId)
  const { manifests, ensureCapabilities } = useCapabilities()
  const { swarms } = useSwarmMetadata()

  useEffect(() => {
    void ensureCapabilities()
  }, [ensureCapabilities])

  const roleAppearances = useMemo<RoleAppearanceMap>(() => {
    return buildRoleAppearanceMap(manifests)
  }, [manifests])

  const { getFill, getRoleLabel, getRoleAbbreviation, getShape } = useMemo(
    () => makeTopologyStyleHelpers(roleAppearances, shapeMapRef.current),
    [roleAppearances],
  )

  const handleDetails = useCallback(
    (targetSwarm: string) => {
      onSwarmSelect?.(targetSwarm)
    },
    [onSwarmSelect],
  )

  // layout of nodes is handled by useTopologyLayout

  const layout = useTopologyLayout({
    data,
    componentsById,
    queueDepths,
    swarmId,
    selectedId,
    getFill,
    getRoleAbbreviation,
    getRoleLabel,
    getShape,
    handleDetails,
  })

  const nodeById = useMemo(() => {
    const map = new Map<string, GraphNode>()
    data.nodes.forEach((node) => map.set(node.id, node))
    return map
  }, [data.nodes])

  const guardQueuesBySwarm = useMemo(() => {
    return buildGuardQueuesBySwarm(componentsById)
  }, [componentsById])

  const edges: Edge[] = useMemo(() => {
    const seen = new Set<string>()
    if (!swarmId) {
      const controllerIds = new Map<string, string>()
      data.nodes.forEach((node) => {
        const normalized = normalizeSwarmId(node.swarmId)
        if (node.type === 'swarm-controller' && normalized) {
          controllerIds.set(normalized, node.id)
        }
      })
      return data.links.reduce<Edge[]>((acc, link) => {
        const sourceNode = nodeById.get(link.source)
        const targetNode = nodeById.get(link.target)
        if (!sourceNode || !targetNode) return acc
        const sourceSwarm = normalizeSwarmId(sourceNode.swarmId)
        const targetSwarm = normalizeSwarmId(targetNode.swarmId)
        const sourceId = sourceSwarm && controllerIds.get(sourceSwarm)
          ? controllerIds.get(sourceSwarm)!
          : sourceNode.id
        const targetId = targetSwarm && controllerIds.get(targetSwarm)
          ? controllerIds.get(targetSwarm)!
          : targetNode.id
        if (sourceId === targetId) return acc
        const key = `${sourceId}|${targetId}|${link.queue}`
        if (seen.has(key)) return acc
        seen.add(key)
        const depth = queueDepths[link.queue] ?? 0
        const color = depth > 0 ? '#ff6666' : '#66aaff'
        const width = 2 + Math.log(depth + 1)
        acc.push({
          id: `${sourceId}-${targetId}-${link.queue}`,
          source: sourceId,
          target: targetId,
          label: link.queue,
          style: { stroke: color, strokeWidth: width },
          markerEnd: { type: MarkerType.ArrowClosed, color },
          labelBgPadding: [2, 2],
          labelBgBorderRadius: 2,
          labelStyle: EDGE_LABEL_STYLE,
          labelBgStyle: { fill: 'rgba(0,0,0,0.6)' },
        })
        return acc
      }, [])
    }
    const normalizedSwarm = normalizeSwarmId(swarmId)
    const guardQueues = normalizedSwarm ? guardQueuesBySwarm.get(normalizedSwarm) : undefined
    if (!normalizedSwarm) {
      return []
    }
    const guardEdges = buildGuardedEdgesForSwarm(
      data,
      queueDepths,
      normalizedSwarm,
      guardQueues,
    )
    const sutEdges = buildSutEdgesForSwarm(
      data,
      componentsById,
      normalizedSwarm,
      swarms,
    )
    return [...guardEdges, ...sutEdges]
  }, [data, nodeById, queueDepths, swarmId, guardQueuesBySwarm, componentsById, swarms])

  const types = Array.from(new Set(data.nodes.map((n) => n.type)))

  const legendItems = types.map((t) => {
    const shape = getShape(t)
    const fill = getFill(t)
    const displayLabel = roleAppearances[normalizeRoleKey(t)]?.label ?? t
    return {
      key: t,
      label: displayLabel,
      shape,
      fill,
    }
  })

  return (
    <div ref={layout.containerRef} className="topology-container">
        <ReactFlow
          nodes={layout.nodes}
          edges={edges}
          onNodesChange={layout.onNodesChange}
          nodeTypes={{ shape: ShapeNode as any, swarmGroup: SwarmGroupNode as any }}
          onInit={layout.onInit}
          onNodeDragStart={layout.onNodeDragStart}
          onNodeDragStop={layout.onNodeDragStop}
          onNodeClick={(
            _e: unknown,
            node: FlowNode,
          ) => {
            if (node.type === 'swarmGroup') return
            onSelect?.(node.id)
          }}
          fitView
        >
          <Background />
        </ReactFlow>
      <button className="reset-view" onClick={layout.fitViewToNodes}>
        Reset View
      </button>
      <TopologyLegend items={legendItems} />
    </div>
  )
}

const HTTP_WORKER_ROLES = new Set(['processor', 'http-builder'])

function buildSutEdgesForSwarm(
  data: { nodes: GraphNode[]; links: { source: string; target: string; queue: string }[] },
  componentsById: Record<string, Component>,
  normalizedSwarmId: string,
  swarms: SwarmSummary[],
): Edge[] {
  const swarm = swarms.find(
    (s) => normalizeSwarmId(s.id) === normalizedSwarmId,
  )
  const sutId = swarm?.sutId
  if (!sutId) return []

  const sutNodeId = `sut:${normalizedSwarmId}`
  const sutNodeExists = data.nodes.some((n) => n.id === sutNodeId)
  if (!sutNodeExists) return []

  const edges: Edge[] = []

  for (const node of data.nodes) {
    const nodeSwarm = normalizeSwarmId(node.swarmId)
    if (nodeSwarm !== normalizedSwarmId) continue
    if (node.id === sutNodeId) continue
    const comp = componentsById[node.id]
    const role = comp?.role?.trim().toLowerCase()
    if (!role || !HTTP_WORKER_ROLES.has(role)) continue

    const color = '#c084fc'
    edges.push({
      id: `sut-${normalizedSwarmId}-${node.id}`,
      source: node.id,
      target: sutNodeId,
      label: 'sut',
      style: {
        stroke: color,
        strokeWidth: 1.5,
        strokeDasharray: '3 3',
      },
      markerEnd: { type: MarkerType.ArrowClosed, color },
      labelStyle: EDGE_LABEL_STYLE,
      labelBgPadding: [2, 2],
      labelBgBorderRadius: 2,
      labelBgStyle: { fill: 'rgba(0,0,0,0.6)' },
    })
  }

  return edges
}
