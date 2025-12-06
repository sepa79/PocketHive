// @ts-nocheck
import { useCallback, useEffect, useMemo } from 'react'
import {
  ReactFlow,
  Background,
  Controls,
  addEdge,
  useNodesState,
  useEdgesState,
  MarkerType,
  type Node,
  type Edge,
  type Connection,
} from '@xyflow/react'
import '@xyflow/react/dist/style.css'
import { PerfNode, type PerfNodeUIData } from './PerfNode'
import { createDefaultPerfNodeData, type PerfNodeData } from '../../lib/perfModel'
import { computePerfGraph } from '../../lib/perfGraph'

type PerfFlowNode = Node<PerfNodeUIData>
type PerfFlowEdge = Edge

export default function PerfModelerPage() {
  const [nodes, setNodes, onNodesChange] = useNodesState<PerfFlowNode>([])
  const [edges, setEdges, onEdgesChange] = useEdgesState<PerfFlowEdge>([])

  const handleNodeChange = useCallback(
    (id: string, patch: Partial<PerfNodeData>) => {
      const enableDb = patch.dbEnabled === true
      const disableDb = patch.dbEnabled === false

      setNodes((current) => {
        const next: PerfFlowNode[] = current.map((node) =>
          node.id === id ? { ...node, data: { ...node.data, ...patch } } : node,
        )

        if (enableDb) {
          const service = next.find((n) => n.id === id)
          if (!service) return next
          const dbId = `${id}-db`
          const hasDb = next.some((n) => n.id === dbId)
          if (hasDb) return next
          const dbNode: PerfFlowNode = {
            id: dbId,
            type: 'perfNode',
            position: {
              x: service.position.x + 260,
              y: service.position.y + 80,
            },
            data: {
              ...createDefaultPerfNodeData('DB'),
              kind: 'out',
              isDb: true,
              depLatencyMs: 20,
              depPool: 30,
              onChange: handleNodeChange,
              onRemove: () => {
                // DB nodes are removed via the service checkbox; hide the X action.
              },
            },
          }
          return [...next, dbNode]
        }

        if (disableDb) {
          const dbId = `${id}-db`
          return next.filter((n) => n.id !== dbId)
        }

        return next
      })

      if (enableDb) {
        setEdges((current) => {
          const dbId = `${id}-db`
          const exists = current.some((e) => e.source === id && e.target === dbId)
          if (exists) return current
          return [...current, createPerfEdge(`${id}->${dbId}`, id, dbId)]
        })
      } else if (disableDb) {
        setEdges((current) => {
          const dbId = `${id}-db`
          return current.filter(
            (e) => !(e.source === id && e.target === dbId),
          )
        })
      }
    },
  [setNodes, setEdges],
  )

  const handleNodeRemove = useCallback(
    (id: string) => {
      setNodes((current) => current.filter((node) => node.id !== id))
      setEdges((current) =>
        current.filter((edge) => edge.source !== id && edge.target !== id),
      )
    },
    [setNodes, setEdges],
  )

  useEffect(() => {
    setNodes([
      createPerfNode('in', 'in', { x: 0, y: 80 }, handleNodeChange, handleNodeRemove, {
        name: 'Synthetic IN',
        inputMode: 'concurrency',
        clientConcurrency: 100,
        incomingTps: 200,
        internalLatencyMs: 200,
      }),
      createPerfNode('service', 'service', { x: 320, y: 0 }, handleNodeChange, handleNodeRemove, {
        name: 'Service',
      }),
      createPerfNode('out', 'out', { x: 640, y: 80 }, handleNodeChange, handleNodeRemove, {
        name: 'Synthetic OUT',
        depLatencyMs: 30,
        internalLatencyMs: 5,
      }),
    ])
    setEdges([
      createPerfEdge('in->service', 'in', 'service'),
      createPerfEdge('service->out', 'service', 'out'),
    ])
  }, [setNodes, setEdges, handleNodeChange, handleNodeRemove])

  const nodeTypes = { perfNode: PerfNode }

  const onConnect = useCallback(
    (connection: Connection) => {
      setEdges((eds) => addEdge(createPerfEdgeFromConnection(connection), eds))
    },
    [setEdges],
  )

  const addComponent = () => {
    setNodes((current) => {
      const count = current.length
      const x = 200 + count * 40
      const y = 200 + (count % 3) * 40
      const id = `node-${Date.now()}-${count}`
      return [
        ...current,
        createPerfNode(id, 'service', { x, y }, handleNodeChange, handleNodeRemove, {
          name: `Component ${count + 1}`,
        }),
      ]
    })
  }

  const clearAll = () => {
    setNodes([])
    setEdges([])
  }

  const graph = useMemo(() => {
    if (!nodes.length) return {}
    const simpleNodes = nodes.map((node) => ({
      id: node.id,
      kind: node.data.kind ?? (node.id === 'in' ? 'in' : node.id === 'out' ? 'out' : 'service'),
      config: node.data,
    }))
    const simpleEdges = edges.map((edge) => ({
      id: edge.id,
      source: edge.source,
      target: edge.target,
    }))
    return computePerfGraph(simpleNodes, simpleEdges)
  }, [nodes, edges])

  const displayNodes = useMemo(() => {
    return nodes.map((node) => {
      const result = graph[node.id]
      return {
        ...node,
        data: {
          ...node.data,
          metrics: result?.metrics,
          incomingTpsGraph: result?.incomingTps ?? 0,
          outputTps: result?.outputTps ?? 0,
        },
      }
    })
  }, [nodes, graph])

  return (
    <div className="flex h-full min-h-0 bg-[#020617] text-white">
      <aside className="w-72 border-r border-white/10 bg-[#020617] px-4 py-4 space-y-3">
        <div>
          <h1 className="text-sm font-semibold text-white/90">
            Throughput modeler
          </h1>
          <p className="mt-1 text-xs text-slate-300">
            Build a synthetic platform view using components with threads
            and latencies. Concurrency is defined at synthetic IN nodes and
            propagated along connections; each component applies its own
            capacity limits and dependency latency.
          </p>
        </div>
        <div className="space-y-2">
          <button
            type="button"
            className="w-full rounded border border-amber-400/60 bg-amber-500/20 px-3 py-1.5 text-xs font-medium text-amber-50 hover:bg-amber-500/30"
            onClick={addComponent}
          >
            Add component
          </button>
          <button
            type="button"
            className="w-full rounded border border-slate-700 bg-slate-900/80 px-3 py-1.5 text-xs text-slate-200 hover:bg-slate-800"
            onClick={clearAll}
          >
            Clear graph
          </button>
        </div>
        <div className="space-y-1.5 text-[11px] text-slate-300">
          <p>Tips:</p>
          <ul className="ml-4 list-disc space-y-0.5">
            <li>Drag nodes around the canvas.</li>
            <li>Drag from a node&apos;s handle to link dependencies.</li>
            <li>Set client concurrency on Synthetic IN nodes.</li>
            <li>Service and OUT nodes derive TPS from incoming edges.</li>
          </ul>
        </div>
      </aside>
      <section className="flex-1 min-h-0">
        <ReactFlow
          nodes={displayNodes}
          edges={edges}
          onNodesChange={onNodesChange}
          onEdgesChange={onEdgesChange}
          nodeTypes={nodeTypes}
          onConnect={onConnect}
          fitView
          fitViewOptions={{ padding: 0.2 }}
        >
          <Background />
          <Controls />
        </ReactFlow>
      </section>
    </div>
  )
}

function createPerfNode(
  id: string,
  kind: 'in' | 'service' | 'out',
  position: { x: number; y: number },
  onChange: (id: string, patch: Partial<PerfNodeData>) => void,
  onRemove: (id: string) => void,
  overrides?: Partial<PerfNodeData>,
): PerfFlowNode {
  const base = createDefaultPerfNodeData(overrides?.name ?? id)
  const data: PerfNodeUIData = {
    ...base,
    ...overrides,
    kind,
    onChange,
    onRemove,
  }
  return {
    id,
    type: 'perfNode',
    position,
    data,
  }
}

function createPerfEdge(id: string, source: string, target: string): PerfFlowEdge {
  return {
    id,
    source,
    target,
    type: 'smoothstep',
    markerEnd: { type: MarkerType.ArrowClosed, color: '#facc15' },
    style: { stroke: '#facc15', strokeWidth: 1.5 },
  }
}

function createPerfEdgeFromConnection(conn: Connection): PerfFlowEdge {
  const id = `${conn.source ?? 'unknown'}->${conn.target ?? 'unknown'}-${Date.now()}`
  return createPerfEdge(id, String(conn.source), String(conn.target))
}
