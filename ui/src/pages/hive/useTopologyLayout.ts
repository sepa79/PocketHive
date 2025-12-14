import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type RefObject,
} from 'react'
import {
  type Node,
  type Edge,
  type ReactFlowInstance,
  type NodeChange,
  applyNodeChanges,
} from '@xyflow/react'
import type { Component } from '../../types/hive'
import type { GraphData, GraphNode } from './TopologyBuilder'
import { type NodeShape } from './TopologyShapes'
import { normalizeSwarmId } from './TopologyUtils'
import { updateNodePosition } from '../../lib/stompClient'

export type FlowNode = Node

export type LayoutArgs = {
  data: GraphData
  componentsById: Record<string, Component>
  queueDepths: Record<string, number>
  swarmId?: string
  selectedId?: string
  getFill: (type?: string, enabled?: boolean) => string
  getRoleAbbreviation: (type: string) => string
  getRoleLabel: (componentRole: string | undefined, type: string) => string
  getShape: (type: string) => NodeShape
  handleDetails: (swarmId: string) => void
}

export type LayoutResult = {
  nodes: FlowNode[]
  containerRef: RefObject<HTMLDivElement | null>
  fitViewToNodes: () => void
  onNodesChange: (changes: NodeChange[]) => void
  onInit: (inst: ReactFlowInstance<Node, Edge>) => void
  onNodeDragStart: (e: unknown, node: FlowNode) => void
  onNodeDragStop: (e: unknown, node: FlowNode) => void
}

export function useTopologyLayout(args: LayoutArgs): LayoutResult {
  const {
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
  } = args

  const [rfNodes, setRfNodes] = useState<FlowNode[]>([])
  const flowRef = useRef<ReactFlowInstance<Node, Edge> | null>(null)
  const containerRef = useRef<HTMLDivElement>(null)
  const draggingIdsRef = useRef<Set<string>>(new Set())
  const pendingFitRef = useRef(false)
  const errorFill = '#ef4444'

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
          const label = component?.name?.trim() || component?.id?.trim() || node.id
          const errored = Boolean(component?.lastErrorAt)
          const fill =
            node.enabled === false
              ? getFill(node.type, node.enabled)
              : errored
              ? errorFill
              : getFill(node.type, node.enabled)

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
              hasError: errored,
              fill,
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
                const errored = Boolean(componentData?.lastErrorAt)
                const fill =
                  member.enabled === false
                    ? getFill(member.type, member.enabled)
                    : errored
                    ? errorFill
                    : getFill(member.type, member.enabled)
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
                  fill,
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
        const label = component?.name?.trim() || component?.id?.trim() || node.id
        const errored = Boolean(component?.lastErrorAt)
        const fill =
          node.enabled === false
            ? getFill(node.type, node.enabled)
            : errored
            ? errorFill
            : getFill(node.type, node.enabled)
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
            hasError: errored,
            fill,
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
    handleDetails,
    queueDepths,
    selectedId,
    swarmId,
    getFill,
    getRoleAbbreviation,
    getRoleLabel,
    getShape,
  ])

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

  const handleInit = useCallback(
    (inst: ReactFlowInstance<FlowNode, Edge>) => {
      flowRef.current = inst
      pendingFitRef.current = true
      fitViewToNodes()
    },
    [fitViewToNodes],
  )

  const handleDragStart = useCallback((_e: unknown, node: FlowNode) => {
    draggingIdsRef.current.add(node.id)
  }, [])

  const handleDragStop = useCallback((_e: unknown, node: FlowNode) => {
    updateNodePosition(node.id, node.position.x, node.position.y)
    draggingIdsRef.current.delete(node.id)
  }, [])

  return {
    nodes: rfNodes,
    containerRef,
    fitViewToNodes,
    onNodesChange,
    onInit: handleInit,
    onNodeDragStart: handleDragStart,
    onNodeDragStop: handleDragStop,
  }
}
