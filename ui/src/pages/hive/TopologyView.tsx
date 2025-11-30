/* eslint-disable @typescript-eslint/ban-ts-comment */
// @ts-nocheck
import { useEffect, useRef, useState, useMemo, useCallback } from 'react'
import {
  ReactFlow,
  MarkerType,
  Background,
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
import { buildRoleAppearanceMap, type RoleAppearanceMap } from '../../lib/capabilities'
import type { Component } from '../../types/hive'
import './TopologyView.css'
import { useCapabilities } from '../../contexts/CapabilitiesContext'
import {
  ShapeNode,
  SwarmGroupNode,
  type ShapeNodeData,
  type SwarmGroupNodeData,
  DEFAULT_COLOR,
  DISABLED_COLOR,
  type NodeShape,
} from './TopologyShapes'
import {
  buildGraph,
  type GraphData,
  type GraphNode,
  type GraphLink,
} from './TopologyBuilder'

interface Props {
  selectedId?: string
  onSelect?: (id: string) => void
  swarmId?: string
  onSwarmSelect?: (id: string) => void
}

const shapeOrder: NodeShape[] = [
  'square',
  'triangle',
  'diamond',
  'pentagon',
  'hexagon',
  'star',
]

const EDGE_LABEL_STYLE = {
  fill: '#fff',
  fontSize: 6,
  // Single-line labels are enough now; keep whitespace normal so text
  // stays vertically centred within the label bubble.
  whiteSpace: 'normal' as const,
}

function normalizeEdgeLabel(label: string): string {
  return label
    .split('\n')
    .map((line) => line.trim())
    .filter((line) => line.length > 0)
    .join('\n')
}

function normalizeRoleKey(value?: string): string {
  return typeof value === 'string' ? value.trim().toLowerCase() : ''
}

function fallbackColorForRole(role: string): string {
  const key = normalizeRoleKey(role)
  if (!key) return DEFAULT_COLOR
  let hash = 0
  for (let i = 0; i < key.length; i++) {
    hash = (hash << 5) - hash + key.charCodeAt(i)
    hash |= 0
  }
  const hue = Math.abs(hash) % 360
  const saturation = 65
  const lightness = 55
  return `hsl(${hue}, ${saturation}%, ${lightness}%)`
}

function queueMatchesAlias(queueName: string | undefined, alias: string | undefined): boolean {
  if (!queueName || !alias) return false
  if (queueName === alias) return true
  return queueName.endsWith(`.${alias}`)
}

function getNumber(value: unknown): number | undefined {
  if (typeof value === 'number' && Number.isFinite(value)) {
    return value
  }
  if (typeof value === 'string') {
    const trimmed = value.trim()
    if (!trimmed) return undefined
    const parsed = Number(trimmed)
    return Number.isFinite(parsed) ? parsed : undefined
  }
  return undefined
}

function getString(value: unknown): string | undefined {
  if (typeof value !== 'string') return undefined
  const trimmed = value.trim()
  return trimmed.length > 0 ? trimmed : undefined
}

type FlowNodeData = ShapeNodeData | SwarmGroupNodeData
type FlowNode = Node<FlowNodeData>

const OUTSIDE_SWARMS = new Set(['default', 'hive'])

function normalizeSwarmId(id?: string) {
  if (!id || OUTSIDE_SWARMS.has(id)) return undefined
  return id
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

type GuardQueuesConfig = {
  primary?: string
  backpressure?: string
  targetDepth?: number
  minDepth?: number
  maxDepth?: number
  highDepth?: number
  recoveryDepth?: number
  minRate?: number
  maxRate?: number
}

function buildGuardedEdgesForSwarm(
  data: GraphData,
  queueDepths: Record<string, number>,
  swarmId: string,
  guardQueues?: GuardQueuesConfig,
): Edge[] {
  const baseEdges: Edge[] = data.links.map((link) => {
    const depth = queueDepths[link.queue] ?? 0
    const color = depth > 0 ? '#ff6666' : '#66aaff'
    const width = 2 + Math.log(depth + 1)

    return {
      id: `${link.source}-${link.target}-${link.queue}`,
      source: link.source,
      target: link.target,
      label: link.queue,
      style: { stroke: color, strokeWidth: width },
      markerEnd: { type: MarkerType.ArrowClosed, color },
      labelBgPadding: [2, 2],
      labelBgBorderRadius: 2,
      labelStyle: EDGE_LABEL_STYLE,
      labelBgStyle: { fill: 'rgba(0,0,0,0.6)' },
    }
  })

  if (!guardQueues) {
    return baseEdges
  }

  const controllerNode = data.nodes.find(
    (node) =>
      node.type === 'swarm-controller' &&
      (node.swarmId === swarmId || normalizeSwarmId(node.swarmId) === swarmId),
  )

  if (!controllerNode) {
    return baseEdges
  }

  const { primary: primaryAlias, backpressure: backpressureAlias } = guardQueues

  if (primaryAlias) {
    const depthParts: string[] = []
    if (guardQueues.minDepth !== undefined && guardQueues.maxDepth !== undefined) {
      depthParts.push(`depth ${guardQueues.minDepth}..${guardQueues.maxDepth}`)
    }
    if (guardQueues.targetDepth !== undefined) {
      depthParts.push(`target ${guardQueues.targetDepth}`)
    }
    const depthLabel = depthParts.length > 0 ? depthParts.join(' ') : 'guard'
    const normalizedDepthLabel = normalizeEdgeLabel(depthLabel)

    const hasRateRange =
      guardQueues.minRate !== undefined && guardQueues.maxRate !== undefined
    const rateLabel = hasRateRange
      ? `rate ${guardQueues.minRate}..${guardQueues.maxRate}`
      : 'rate'
    const normalizedRateLabel = normalizeEdgeLabel(rateLabel)

    const seenPrimaryRateTargets = new Set<string>()
    const seenPrimaryDepthTargets = new Set<string>()

    data.links
      .filter((link) => queueMatchesAlias(link.queue, primaryAlias))
      .forEach((link) => {
        const producerId = link.source
        if (!seenPrimaryRateTargets.has(producerId)) {
          seenPrimaryRateTargets.add(producerId)
          baseEdges.push({
            id: `guard-rate-${controllerNode.id}-${producerId}`,
            source: controllerNode.id,
            target: producerId,
            label: normalizedRateLabel,
            style: {
              stroke: '#22c55e',
              strokeWidth: 2.5,
              strokeDasharray: '4 2',
            },
            markerEnd: { type: MarkerType.ArrowClosed, color: '#22c55e' },
            labelBgPadding: [2, 2],
            labelBgBorderRadius: 2,
            labelStyle: EDGE_LABEL_STYLE,
            labelBgStyle: { fill: 'rgba(15,23,42,0.9)' },
          })
        }

        ;[link.target].forEach((targetId) => {
          const key = `primary-depth|${targetId}`
          if (seenPrimaryDepthTargets.has(key)) return
          seenPrimaryDepthTargets.add(key)
          baseEdges.push({
            id: `guard-depth-${controllerNode.id}-${targetId}`,
            source: controllerNode.id,
            target: targetId,
            label: normalizedDepthLabel,
            style: {
              stroke: '#f97316',
              strokeWidth: 2.5,
              strokeDasharray: '4 2',
            },
            markerEnd: { type: MarkerType.ArrowClosed, color: '#f97316' },
            labelBgPadding: [2, 2],
            labelBgBorderRadius: 2,
            labelStyle: EDGE_LABEL_STYLE,
            labelBgStyle: { fill: 'rgba(15,23,42,0.9)' },
          })
        })
      })
  }

  if (backpressureAlias) {
    const bpParts: string[] = ['backpressure']
    if (guardQueues.highDepth !== undefined) {
      bpParts.push(`high ${guardQueues.highDepth}`)
    }
    if (guardQueues.recoveryDepth !== undefined) {
      bpParts.push(`recovery ${guardQueues.recoveryDepth}`)
    }
    const bpLabel = bpParts.join(' ')
    const normalizedBpLabel = normalizeEdgeLabel(bpLabel)
    const seenBpTargets = new Set<string>()

    data.links
      .filter((link) => queueMatchesAlias(link.queue, backpressureAlias))
      .forEach((link) => {
        const targetId = link.target
        const key = `bp|${targetId}`
        if (seenBpTargets.has(key)) return
        seenBpTargets.add(key)
        baseEdges.push({
          id: `guard-bp-${controllerNode.id}-${targetId}`,
          source: controllerNode.id,
          target: targetId,
          label: normalizedBpLabel,
          style: {
            stroke: '#a855f7',
            strokeWidth: 2.5,
            strokeDasharray: '4 2',
          },
          markerEnd: { type: MarkerType.ArrowClosed, color: '#a855f7' },
          labelBgPadding: [2, 2],
          labelBgBorderRadius: 2,
          labelStyle: EDGE_LABEL_STYLE,
          labelBgStyle: { fill: 'rgba(15,23,42,0.9)' },
        })
      })
  }

  return baseEdges
}

export default function TopologyView({ selectedId, onSelect, swarmId, onSwarmSelect }: Props) {
  const [data, setData] = useState<GraphData>({ nodes: [], links: [] })
  const shapeMapRef = useRef<Record<string, NodeShape>>({ sut: 'circle' })
  const [queueDepths, setQueueDepths] = useState<Record<string, number>>({})
  const [componentsById, setComponentsById] = useState<Record<string, Component>>({})
  const flowRef = useRef<ReactFlowInstance<FlowNode, Edge> | null>(null)
  const containerRef = useRef<HTMLDivElement>(null)
  const [rfNodes, setRfNodes] = useState<FlowNode[]>([])
  const draggingIdsRef = useRef<Set<string>>(new Set())
  const pendingFitRef = useRef(false)
  const { manifests, ensureCapabilities } = useCapabilities()

  const fitViewToNodes = useCallback(() => {
    const instance = flowRef.current
    if (!instance) return
    const nodes = instance.getNodes?.() ?? []
    if (!nodes.length) return
    instance.fitView({ padding: 0.2, includeHiddenNodes: false })
    const zoom = instance.getZoom?.()
    if (typeof zoom === 'number' && zoom < 0.9) {
      instance.zoomTo?.(0.9)
    }
  }, [])

  useEffect(() => {
    return () => {
      draggingIdsRef.current.clear()
    }
  }, [])

  useEffect(() => {
    void ensureCapabilities()
  }, [ensureCapabilities])

  const roleAppearances = useMemo<RoleAppearanceMap>(() => {
    return buildRoleAppearanceMap(manifests)
  }, [manifests])

  useEffect(() => {
    const unsub = subscribeComponents((comps: Component[]) => {
      const depths: Record<string, number> = {}
      const map: Record<string, Component> = {}
      comps.forEach((c) => {
        map[c.id] = c
        c.queues.forEach((q) => {
          if (typeof q.depth === 'number') {
            const d = depths[q.name]
            depths[q.name] = d === undefined ? q.depth : Math.max(d, q.depth)
          }
        })
      })
      setQueueDepths(depths)
      setComponentsById(map)
    })
    return () => unsub()
  }, [])

  useEffect(() => {
    const unsub = subscribeTopology((topo: Topology) => {
      const graph = buildGraph(topo, swarmId)
      setData(graph)
      pendingFitRef.current = true
    })
    return () => unsub()
  }, [swarmId])

  useEffect(() => {
    const el = containerRef.current
    if (!el) return
    const update = () => fitViewToNodes()
    update()
    if (typeof ResizeObserver !== 'undefined') {
      const ro = new ResizeObserver(update)
      ro.observe(el)
      return () => ro.disconnect()
    }
    window.addEventListener('resize', update)
    return () => window.removeEventListener('resize', update)
  }, [fitViewToNodes])

  const getFill = useCallback(
    (type?: string, enabled?: boolean) => {
      if (enabled === false) return DISABLED_COLOR
      const key = normalizeRoleKey(type)
      if (!key) return DEFAULT_COLOR
      const appearance = roleAppearances[key]
      const color = appearance?.color?.trim()
      return color && color.length > 0 ? color : fallbackColorForRole(key)
    },
    [roleAppearances],
  )

  const getRoleLabel = useCallback(
    (componentRole: string | undefined, type: string) => {
      const key = normalizeRoleKey(type)
      const componentKey = normalizeRoleKey(componentRole)
      if (key === 'wiremock' || componentKey === 'wiremock') {
        return 'System Under Test'
      }
      const appearanceLabel = roleAppearances[key]?.label?.trim()
      if (appearanceLabel && appearanceLabel.length > 0) {
        return appearanceLabel
      }
      const normalizedComponent = componentRole?.trim()
      if (normalizedComponent && normalizedComponent.length > 0) {
        return normalizedComponent
      }
      return type
    },
    [roleAppearances],
  )

  const getRoleAbbreviation = useCallback(
    (type: string) => {
      const key = normalizeRoleKey(type)
      const abbreviation = roleAppearances[key]?.abbreviation?.trim()
      return abbreviation && abbreviation.length > 0 ? abbreviation : ''
    },
    [roleAppearances],
  )

  const getShape = useCallback(
    (type: string): NodeShape => {
      const key = normalizeRoleKey(type)
      const map = shapeMapRef.current
      const preferred = roleAppearances[key]?.shape
      if (preferred) {
        map[key] = preferred
        return preferred
      }
      if (!map[key]) {
        const used = new Set(Object.values(map))
        const next = shapeOrder.find((s) => !used.has(s)) ?? 'circle'
        map[key] = next
      }
      return map[key]
    },
    [roleAppearances],
  )

  const handleDetails = useCallback(
    (targetSwarm: string) => {
      onSwarmSelect?.(targetSwarm)
    },
    [onSwarmSelect],
  )

  useEffect(() => {
    setRfNodes((prev) => {
      const prevPositions = new Map(prev.map((node) => [node.id, node.position]))
      const prevNodesById = new Map(prev.map((node) => [node.id, node]))
      const dragging = draggingIdsRef.current
      if (!swarmId) {
        const controllers = new Map<string, GraphNode>()
        data.nodes.forEach((node) => {
          const normalized = normalizeSwarmId(node.swarmId)
          if (node.type === 'swarm-controller' && normalized) {
            controllers.set(normalized, node)
          }
        })
        const grouped = new Map<string, GraphNode[]>()
        const nodes: FlowNode[] = []
        data.nodes.forEach((node) => {
          const normalized = normalizeSwarmId(node.swarmId)
          if (normalized && controllers.has(normalized)) {
            const list = grouped.get(normalized) ?? []
            list.push(node)
            grouped.set(normalized, list)
            return
          }
          if (dragging.has(node.id)) {
            const existing = prevNodesById.get(node.id)
            if (existing) {
              nodes.push(existing)
              return
            }
          }
          const previous = prevPositions.get(node.id)
          const position = {
            x: node.x ?? previous?.x ?? 0,
            y: node.y ?? previous?.y ?? 0,
          }
          const component = componentsById[node.id]
          const role = getRoleLabel(component?.role, node.type)
          const label =
            component?.name?.trim() ||
            component?.id?.trim() ||
            node.id
          nodes.push({
            id: node.id,
            position,
            data: {
              label,
              shape: getShape(node.type),
              enabled: node.enabled,
              swarmId: node.swarmId,
              componentType: node.type,
              componentId: node.id,
              status: component?.status,
              meta: component?.config,
              role,
              fill: getFill(node.type, node.enabled),
            },
            type: 'shape',
            selected: selectedId === node.id,
          })
        })
        grouped.forEach((members, swarmKey) => {
          const controller = controllers.get(swarmKey)
          if (!controller) return
          const memberSet = new Set(members.map((m) => m.id))
          const groupEdges = data.links
            .filter((link) => memberSet.has(link.source) && memberSet.has(link.target))
            .map((link) => ({
              source: link.source,
              target: link.target,
              queue: link.queue,
              depth: queueDepths[link.queue] ?? 0,
            }))
          if (dragging.has(controller.id)) {
            const existing = prevNodesById.get(controller.id)
            if (existing) {
              nodes.push(existing)
              return
            }
          }
          const previous = prevPositions.get(controller.id)
          const position = {
            x: controller.x ?? previous?.x ?? 0,
            y: controller.y ?? previous?.y ?? 0,
          }
          nodes.push({
            id: controller.id,
            position,
            data: {
              label: swarmKey,
              swarmId: swarmKey,
              controllerId: controller.id,
              components: members.map((member) => {
                const componentData = componentsById[member.id]
                const roleLabel = getRoleLabel(componentData?.role, member.type)
                const config =
                  componentData && typeof componentData.config === 'object'
                    ? (componentData.config as Record<string, unknown>)
                    : undefined
                const rawTps = config?.tps
                const numericTps =
                  typeof rawTps === 'number'
                    ? rawTps
                    : typeof rawTps === 'string'
                    ? Number(rawTps)
                    : undefined
                return {
                  id: member.id,
                  name: roleLabel,
                  shape: getShape(member.type),
                  enabled: member.enabled,
                  componentType: member.type,
                  fill: getFill(member.type, member.enabled),
                  abbreviation: getRoleAbbreviation(member.type),
                  queueCount: componentData?.queues?.length ?? 0,
                  tps:
                    numericTps !== undefined && Number.isFinite(numericTps)
                      ? (numericTps as number)
                      : undefined,
                }
              }),
              edges: groupEdges,
              onDetails: handleDetails,
              selectedId,
            },
            type: 'swarmGroup',
            selectable: false,
          })
        })
        return nodes
      }
      return data.nodes.map((node) => {
        if (dragging.has(node.id)) {
          const existing = prevNodesById.get(node.id)
          if (existing) return existing
        }
        const previous = prevPositions.get(node.id)
        const position = {
          x: node.x ?? previous?.x ?? 0,
          y: node.y ?? previous?.y ?? 0,
        }
        const component = componentsById[node.id]
        const role = getRoleLabel(component?.role, node.type)
        const label =
          component?.name?.trim() ||
          component?.id?.trim() ||
          node.id
        return {
          id: node.id,
          position,
          data: {
            label,
            shape: getShape(node.type),
            enabled: node.enabled,
            swarmId: node.swarmId,
            componentType: node.type,
            componentId: node.id,
            status: component?.status,
            meta: component?.config,
            role,
            fill: getFill(node.type, node.enabled),
          },
          type: 'shape',
          selected: selectedId === node.id,
        }
      }) as FlowNode[]
    })
  }, [
    componentsById,
    data.links,
    data.nodes,
    queueDepths,
    handleDetails,
    selectedId,
    swarmId,
    getFill,
    getRoleAbbreviation,
    getRoleLabel,
    getShape,
  ])

  const nodeById = useMemo(() => {
    const map = new Map<string, GraphNode>()
    data.nodes.forEach((node) => map.set(node.id, node))
    return map
  }, [data.nodes])

  const guardQueuesBySwarm = useMemo(() => {
    const map = new Map<string, GuardQueuesConfig>()
    Object.values(componentsById).forEach((component) => {
      const role = typeof component.role === 'string' ? component.role.trim().toLowerCase() : ''
      if (role !== 'swarm-controller') return
      const swarm = typeof component.swarmId === 'string' ? component.swarmId.trim() : ''
      if (!swarm) return
      const rawConfig =
        component.config && typeof component.config === 'object'
          ? (component.config as Record<string, unknown>)
          : undefined
      const trafficPolicy =
        rawConfig && rawConfig.trafficPolicy && typeof rawConfig.trafficPolicy === 'object'
          ? (rawConfig.trafficPolicy as Record<string, unknown>)
          : undefined
      const bufferGuard =
        trafficPolicy &&
        trafficPolicy.bufferGuard &&
        typeof trafficPolicy.bufferGuard === 'object'
          ? (trafficPolicy.bufferGuard as Record<string, unknown>)
          : undefined
      if (!bufferGuard) return
      const primary = getString(bufferGuard.queueAlias)
      const targetDepth = getNumber(bufferGuard.targetDepth)
      const minDepth = getNumber(bufferGuard.minDepth)
      const maxDepth = getNumber(bufferGuard.maxDepth)
      let minRate: number | undefined
      let maxRate: number | undefined
      const adjust =
        bufferGuard.adjust && typeof bufferGuard.adjust === 'object'
          ? (bufferGuard.adjust as Record<string, unknown>)
          : undefined
      if (adjust) {
        minRate = getNumber(adjust.minRatePerSec)
        maxRate = getNumber(adjust.maxRatePerSec)
      }
      let backpressure: string | undefined
      let highDepth: number | undefined
      let recoveryDepth: number | undefined
      const bp =
        bufferGuard.backpressure && typeof bufferGuard.backpressure === 'object'
          ? (bufferGuard.backpressure as Record<string, unknown>)
          : undefined
      if (bp) {
        backpressure = getString(bp.queueAlias)
        highDepth = getNumber(bp.highDepth)
        recoveryDepth = getNumber(bp.recoveryDepth)
      }
      if (!primary && !backpressure) return
      map.set(swarm, {
        primary,
        backpressure,
        targetDepth,
        minDepth,
        maxDepth,
        highDepth,
        recoveryDepth,
        minRate,
        maxRate,
      })
    })
    return map
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
    const guardQueues = swarmId ? guardQueuesBySwarm.get(swarmId) : undefined
    return buildGuardedEdgesForSwarm(data, queueDepths, swarmId, guardQueues)
  }, [data.links, data.nodes, nodeById, queueDepths, swarmId, guardQueuesBySwarm])

  const onNodesChange = useCallback(
    (changes: NodeChange[]) => setRfNodes((nds) => applyNodeChanges(changes, nds)),
    [],
  )

  const layoutSignature = useMemo(() => {
    return rfNodes
      .map((node) => `${node.id}:${Math.round(node.position.x)}:${Math.round(node.position.y)}`)
      .join('|')
  }, [rfNodes])

  useEffect(() => {
    if (!rfNodes.length) {
      return
    }
    if (!pendingFitRef.current) {
      return
    }
    if (draggingIdsRef.current.size > 0) {
      return
    }
    pendingFitRef.current = false
    fitViewToNodes()
  }, [fitViewToNodes, layoutSignature, rfNodes.length])

  const types = Array.from(new Set(data.nodes.map((n) => n.type)))

  return (
    <div ref={containerRef} className="topology-container">
        <ReactFlow<FlowNode, Edge>
          nodes={rfNodes}
          edges={edges}
          onNodesChange={onNodesChange}
          nodeTypes={{ shape: ShapeNode, swarmGroup: SwarmGroupNode }}
          onInit={(inst: ReactFlowInstance<FlowNode, Edge>) => {
            flowRef.current = inst
            pendingFitRef.current = true
            fitViewToNodes()
          }}
          onNodeDragStart={(_e: unknown, node: FlowNode) => {
            draggingIdsRef.current.add(node.id)
          }}
          onNodeDragStop={(
            _e: unknown,
            node: FlowNode,
          ) => {
            updateNodePosition(node.id, node.position.x, node.position.y)
            draggingIdsRef.current.delete(node.id)
          }}
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
      <button className="reset-view" onClick={fitViewToNodes}>
        Reset View
      </button>
      <div className="topology-legend">
        {types.map((t) => {
          const shape = getShape(t)
          const fill = getFill(t)
          const displayLabel = roleAppearances[normalizeRoleKey(t)]?.label ?? t
          return (
            <div key={t} className="legend-item">
              <svg width="12" height="12" className="legend-icon">
                {shape === 'square' && (
                  <rect x="1" y="1" width="10" height="10" fill={fill} stroke="black" />
                )}
                {shape === 'triangle' && (
                  <polygon points="6,1 11,11 1,11" fill={fill} stroke="black" />
                )}
                {shape === 'diamond' && (
                  <polygon points="6,1 11,6 6,11 1,6" fill={fill} stroke="black" />
                )}
                {shape === 'pentagon' && (
                  <polygon points={polygonPoints(5, 5)} fill={fill} stroke="black" />
                )}
                {shape === 'hexagon' && (
                  <polygon points={polygonPoints(6, 5)} fill={fill} stroke="black" />
                )}
                {shape === 'star' && (
                  <polygon points={starPoints(5)} fill={fill} stroke="black" />
                )}
                {shape === 'circle' && (
                  <circle cx="6" cy="6" r="5" fill={fill} stroke="black" />
                )}
              </svg>
              <span>{displayLabel}</span>
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
      </div>
    </div>
  )
}
