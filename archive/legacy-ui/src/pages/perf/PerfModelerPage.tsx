// @ts-nocheck
import { useCallback, useEffect, useMemo, useState, useRef } from 'react'
import {
  ReactFlow,
  Background,
  Controls,
  addEdge,
  useNodesState,
  useEdgesState,
  useReactFlow,
  MarkerType,
  type Node,
  type Edge,
  type Connection,
} from '@xyflow/react'
import '@xyflow/react/dist/style.css'
import {
  VictoryAxis,
  VictoryChart,
  VictoryLine,
  VictoryTheme,
} from 'victory'
import { PerfNode, type PerfNodeUIData } from './PerfNode'
import {
  createDefaultPerfNodeData,
  computePerfMetrics,
  type PerfNodeData,
} from '../../lib/perfModel'
import { computePerfGraph } from '../../lib/perfGraph'
import YAML from 'yaml'

type PerfFlowNode = Node<PerfNodeUIData>
type PerfFlowEdge = Edge

const STORAGE_KEY = 'pockethive-capacity-model-v1'

export default function PerfModelerPage() {
  const [nodes, setNodes, onNodesChange] = useNodesState<PerfFlowNode>([])
  const [edges, setEdges, onEdgesChange] = useEdgesState<PerfFlowEdge>([])
  const [isCapacityModalOpen, setCapacityModalOpen] = useState(false)
  const [capacityMode, setCapacityMode] = useState('baseline')
  const [beePlaying, setBeePlaying] = useState(false)
  const [beeSpeed, setBeeSpeed] = useState(0.25)
  const [simTimeMs, setSimTimeMs] = useState(0)
  const simTimerRef = useRef<number | null>(null)
  const [isModelModalOpen, setModelModalOpen] = useState(false)
  const [modelText, setModelText] = useState('')
  const [modelError, setModelError] = useState<string | null>(null)
  const [hasHydrated, setHasHydrated] = useState(false)

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

  const resetToDefaultGraph = useCallback(() => {
    setNodes([
      createPerfNode('in', 'in', { x: 0, y: 80 }, handleNodeChange, handleNodeRemove, {
        name: 'Synthetic IN',
        inputMode: 'concurrency',
        clientConcurrency: 100,
        incomingTps: 200,
        internalLatencyMs: 200,
      }),
      createPerfNode(
        'service',
        'service',
        { x: 320, y: 0 },
        handleNodeChange,
        handleNodeRemove,
        {
          name: 'Service',
        },
      ),
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

  const applyModelObject = useCallback(
    (parsed: any) => {
      if (!parsed || !Array.isArray(parsed.nodes) || !Array.isArray(parsed.edges)) {
        return
      }

      const rawNodes = parsed.nodes as any[]
      const rawEdges = parsed.edges as any[]

      const newNodes: PerfFlowNode[] = []
      const nodeIds = new Set<string>()

      for (const raw of rawNodes) {
        const id = String(raw.id)
        const rawKind = raw.kind
        const kind =
          rawKind === 'in' || rawKind === 'out' || rawKind === 'service'
            ? rawKind
            : 'service'
        const x = Number(raw.x)
        const y = Number(raw.y)
        const config = raw.config ?? {}

        if (config.isDb) {
          continue
        }

        const overrides: any = {
          name: config.name ?? id,
          inputMode: config.inputMode,
          incomingTps: config.incomingTps,
          clientConcurrency: config.clientConcurrency,
          transport: config.transport,
          maxConcurrentIn: config.maxConcurrentIn,
          internalLatencyMs: config.internalLatencyMs,
          depLatencyMs: config.depLatencyMs,
          depPool: config.depPool,
          httpClient: config.httpClient,
          dbEnabled: config.dbEnabled,
          depsParallel: config.depsParallel,
          includeOutDeps: config.includeOutDeps,
        }

        const node = createPerfNode(
          id,
          kind,
          {
            x: Number.isFinite(x) ? x : 0,
            y: Number.isFinite(y) ? y : 0,
          },
          handleNodeChange,
          handleNodeRemove,
          overrides,
        )
        newNodes.push(node)
        nodeIds.add(id)
      }

      for (const raw of rawNodes) {
        const config = raw.config ?? {}
        if (!config.isDb) continue
        const id = String(raw.id)
        const x = Number(raw.x)
        const y = Number(raw.y)
        const name = config.name ?? 'DB'

        const overrides: any = {
          depLatencyMs: config.depLatencyMs,
          depPool: config.depPool,
        }

        const dbNode: PerfFlowNode = {
          id,
          type: 'perfNode',
          position: {
            x: Number.isFinite(x) ? x : 0,
            y: Number.isFinite(y) ? y : 0,
          },
          data: {
            ...createDefaultPerfNodeData(name),
            ...overrides,
            kind: 'out',
            isDb: true,
            onChange: handleNodeChange,
            onRemove: () => {},
          },
        }
        newNodes.push(dbNode)
        nodeIds.add(id)
      }

      const newEdges: PerfFlowEdge[] = []
      for (const raw of rawEdges) {
        const source = String(raw.source)
        const target = String(raw.target)
        if (!nodeIds.has(source) || !nodeIds.has(target)) continue
        const id = raw.id ? String(raw.id) : `${source}->${target}`
        newEdges.push(createPerfEdge(id, source, target))
      }

      setNodes(newNodes)
      setEdges(newEdges)
    },
    [handleNodeChange, handleNodeRemove, setNodes, setEdges],
  )

  useEffect(() => {
    if (hasHydrated) {
      return
    }

    let loaded = false
    try {
      const raw = window.localStorage.getItem(STORAGE_KEY)
      if (raw) {
        const parsed = JSON.parse(raw)
        if (parsed && typeof parsed === 'object') {
          applyModelObject(parsed)
          loaded = true
        }
      }
    } catch {
      // ignore and fall back to default
    }

    if (!loaded) {
      resetToDefaultGraph()
    }

    setHasHydrated(true)
  }, [applyModelObject, resetToDefaultGraph, hasHydrated])

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

  const addInNode = () => {
    setNodes((current) => {
      const count = current.filter((node) => node.data.kind === 'in').length
      const x = 0
      const y = 80 + count * 80
      const id = `in-${Date.now()}-${count}`
      return [
        ...current,
        createPerfNode(id, 'in', { x, y }, handleNodeChange, handleNodeRemove, {
          name: `Synthetic IN ${count + 1}`,
          inputMode: 'concurrency',
          clientConcurrency: 100,
          incomingTps: 200,
          internalLatencyMs: 200,
        }),
      ]
    })
  }

  const addOutNode = () => {
    setNodes((current) => {
      const count = current.filter((node) => node.data.kind === 'out' && !node.data.isDb).length
      const x = 640 + (count % 2) * 80
      const y = 160 + Math.floor(count / 2) * 60
      const id = `out-${Date.now()}-${count}`
      return [
        ...current,
        createPerfNode(id, 'out', { x, y }, handleNodeChange, handleNodeRemove, {
          name: `OUT ${count + 1}`,
          depLatencyMs: 40,
          internalLatencyMs: 0,
        }),
      ]
    })
  }

  const clearAll = () => {
    setNodes([])
    setEdges([])
  }

  const handleResetView = () => {
    resetToDefaultGraph()
    setCapacityMode('baseline')
    setCapacityModalOpen(false)
    setBeePlaying(false)
    setBeeSpeed(0.25)
    setSimTimeMs(0)
  }

  const buildModelExport = useCallback(() => {
    const exportNodes = nodes.map((node) => {
      const data = node.data as any
      const kind =
        data.kind ?? (node.id === 'in' ? 'in' : node.id === 'out' ? 'out' : 'service')
      const config: any = {
        name: data.name,
        inputMode: data.inputMode,
        incomingTps: data.incomingTps,
        clientConcurrency: data.clientConcurrency,
        transport: data.transport,
        maxConcurrentIn: data.maxConcurrentIn,
        internalLatencyMs: data.internalLatencyMs,
        depLatencyMs: data.depLatencyMs,
        depPool: data.depPool,
        httpClient: data.httpClient,
        dbEnabled: data.dbEnabled,
        depsParallel: data.depsParallel,
      }
      if (typeof data.includeOutDeps !== 'undefined') {
        config.includeOutDeps = data.includeOutDeps
      }
      if (data.isDb) {
        config.isDb = true
      }
      return {
        id: node.id,
        kind,
        x: node.position.x,
        y: node.position.y,
        config,
      }
    })
    const exportEdges = edges.map((edge) => ({
      id: edge.id,
      source: edge.source,
      target: edge.target,
    }))
    return {
      version: 1,
      nodes: exportNodes,
      edges: exportEdges,
    }
  }, [nodes, edges])

  useEffect(() => {
    if (!hasHydrated) {
      return
    }
    try {
      const model = buildModelExport()
      window.localStorage.setItem(STORAGE_KEY, JSON.stringify(model))
    } catch {
      // ignore persistence errors
    }
  }, [hasHydrated, nodes, edges, buildModelExport])

  const openExportModel = () => {
    const model = buildModelExport()
    const text = YAML.stringify(model)
    setModelText(text)
    setModelError(null)
    setModelModalOpen(true)
  }

  const applyImportedModel = () => {
    setModelError(null)
    let parsed: any
    try {
      parsed = YAML.parse(modelText)
    } catch (error: any) {
      setModelError(
        `Parse error: ${
          error && typeof error.message === 'string' ? error.message : String(error)
        }`,
      )
      return
    }

    if (!parsed || !Array.isArray(parsed.nodes) || !Array.isArray(parsed.edges)) {
      setModelError('Expected an object with "nodes" and "edges" arrays.')
      return
    }

    applyModelObject(parsed)
    setModelModalOpen(false)
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

  const beeModel = useMemo(
    () => buildBeeAnimationModel(nodes, edges),
    [nodes, edges],
  )

  useEffect(() => {
    if (!beePlaying || beeModel.totalMs <= 0) {
      if (simTimerRef.current !== null) {
        clearInterval(simTimerRef.current)
        simTimerRef.current = null
      }
      return
    }
    const intervalMs = 50
    const id = window.setInterval(() => {
      setSimTimeMs((prev) => {
        const next = prev + intervalMs * beeSpeed
        return beeModel.totalMs > 0 ? next % beeModel.totalMs : 0
      })
    }, intervalMs)
    simTimerRef.current = id
    return () => {
      clearInterval(id)
      if (simTimerRef.current === id) {
        simTimerRef.current = null
      }
    }
  }, [beePlaying, beeSpeed, beeModel.totalMs])

  const beeNodeCounts = useMemo(() => {
    const counts = new Map<string, number>()
    if (beeModel.totalMs <= 0 || beeModel.bees.length === 0) return counts
    for (const bee of beeModel.bees) {
      const segments = bee.segments
      if (!segments.length) continue
      const t = simTimeMs % beeModel.totalMs
      let current = segments[0]
      for (const seg of segments) {
        if (t >= seg.startMs && t < seg.endMs) {
          current = seg
          break
        }
        if (t >= seg.endMs) {
          current = seg
        }
      }
      const prev = counts.get(current.nodeId) ?? 0
      counts.set(current.nodeId, prev + 1)
    }
    return counts
  }, [beeModel, simTimeMs])

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

  const beeEdgeIds = useMemo(() => beeModel.edgeIds, [beeModel])

  const displayEdges = useMemo(() => {
    if (beeEdgeIds.size === 0) return edges
    return edges.map((edge) =>
      beeEdgeIds.has(edge.id)
        ? {
            ...edge,
            style: {
              ...(edge.style ?? {}),
              stroke: '#f97316',
              strokeWidth: 2,
            },
          }
        : edge,
    )
  }, [edges, beeEdgeIds])

  const smallCapacityModel = useMemo(
    () => computeServiceCapacityModel(nodes, edges, 1, 1),
    [nodes, edges],
  )

  const capacityModalModel = useMemo(() => {
    let incomingScale = 1
    let latencyScale = 1

    if (capacityMode === 'incoming2x') {
      incomingScale = 2
    } else if (capacityMode === 'latency2x') {
      latencyScale = 2
    } else if (capacityMode === 'both2x') {
      incomingScale = 2
      latencyScale = 2
    }

    return computeServiceCapacityModel(nodes, edges, incomingScale, latencyScale)
  }, [nodes, edges, capacityMode])

  return (
    <>
      <div className="flex h-full min-h-0 bg-[#020617] text-white">
        <aside className="w-72 border-r border-white/10 bg-[#020617] flex flex-col min-h-0">
          <div className="flex-1 min-h-0 overflow-y-auto px-4 py-4 space-y-3">
            <div>
              <h1 className="text-sm font-semibold text-white/90">
                Capacity modeler
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
                className="w-full rounded border border-slate-600 bg-slate-900/80 px-3 py-1.5 text-xs text-slate-200 hover:bg-slate-800"
                onClick={addInNode}
              >
                Add IN
              </button>
              <button
                type="button"
                className="w-full rounded border border-slate-600 bg-slate-900/80 px-3 py-1.5 text-xs text-slate-200 hover:bg-slate-800"
                onClick={addOutNode}
              >
                Add OUT
              </button>
              <button
                type="button"
                className="w-full rounded border border-slate-700 bg-slate-900/80 px-3 py-1.5 text-xs text-slate-200 hover:bg-slate-800"
                onClick={clearAll}
              >
                Clear graph
              </button>
              <button
                type="button"
                className="w-full rounded border border-slate-700 bg-slate-900/80 px-3 py-1.5 text-xs text-slate-200 hover:bg-slate-800"
                onClick={handleResetView}
              >
                Reset view
              </button>
            </div>
            <div className="mt-3 border-t border-white/10 pt-3 space-y-2">
              <h2 className="text-xs font-semibold text-white/80">
                Single-service capacity curve
              </h2>
              <p className="text-[11px] text-slate-300">
                For the primary <span className="font-mono">Service</span> node,
                this graph shows theoretical throughput as we sweep incoming TPS
                from 10 to 1000 req/s using the same capacity model.
              </p>
              <div
                className="bg-slate-900/60 rounded border border-slate-700/60 px-2 py-1.5 cursor-pointer hover:border-amber-400/70 hover:bg-slate-900/80 transition-colors"
                onClick={() => setCapacityModalOpen(true)}
              >
                <VictoryChart
                  theme={VictoryTheme.material}
                  padding={{ top: 12, bottom: 36, left: 44, right: 12 }}
                  height={180}
                  domainPadding={8}
                >
                  <VictoryAxis
                    label="Incoming TPS"
                    style={{
                      axisLabel: { padding: 28, fill: '#e2e8f0', fontSize: 9 },
                      tickLabels: { fill: '#cbd5f5', fontSize: 8 },
                      axis: { stroke: 'rgba(148,163,184,0.5)' },
                      grid: { stroke: 'rgba(148,163,184,0.2)' },
                    }}
                  />
                  <VictoryAxis
                    dependentAxis
                    label="Effective TPS"
                    style={{
                      axisLabel: { padding: 32, fill: '#e2e8f0', fontSize: 9 },
                      tickLabels: { fill: '#cbd5f5', fontSize: 8 },
                      axis: { stroke: 'rgba(148,163,184,0.5)' },
                      grid: { stroke: 'rgba(148,163,184,0.2)' },
                    }}
                  />
                  <VictoryLine
                    data={smallCapacityModel.points}
                    style={{
                      data: { stroke: '#facc15', strokeWidth: 2 },
                    }}
                    interpolation="monotoneX"
                  />
                </VictoryChart>
              </div>
            </div>
            <div className="space-y-1.5 text-[11px] text-slate-300">
              <p>Tips:</p>
              <ul className="ml-4 list-disc space-y-0.5">
                <li>Drag nodes around the canvas.</li>
                <li>Drag from a node&apos;s handle to link dependencies.</li>
                <li>Set client concurrency on Synthetic IN nodes.</li>
                <li>Service and OUT nodes derive TPS from incoming edges.</li>
              </ul>
              <div className="mt-2 border-t border-slate-800 pt-2 space-y-1">
                <p className="text-[11px] text-slate-300">Bee path animation:</p>
                {beeModel.bees.length === 0 ? (
                  <p className="text-[10px] text-slate-500">
                    Connect a Synthetic IN to an OUT node to see the bee path.
                  </p>
                ) : (
                  <>
                    <div className="flex flex-wrap gap-1.5 mb-1">
                      <button
                        type="button"
                        className={`rounded px-2 py-0.5 text-[10px] border ${
                          beePlaying
                            ? 'border-amber-400 bg-amber-500/20 text-amber-50'
                            : 'border-slate-600 bg-slate-900 text-slate-200'
                        }`}
                        onClick={() => setBeePlaying((prev) => !prev)}
                      >
                        {beePlaying ? 'Pause bee' : 'Play bee'}
                      </button>
                      <button
                        type="button"
                        className="rounded px-2 py-0.5 text-[10px] border border-slate-600 bg-slate-900 text-slate-200"
                        onClick={() => setSimTimeMs(0)}
                        disabled={beeModel.bees.length === 0}
                      >
                        Reset
                      </button>
                    </div>
                    <div className="flex items-center gap-2">
                      <span className="text-[10px] text-slate-400">Speed</span>
                      <input
                        type="range"
                        min={0.01}
                        max={4}
                        step={0.01}
                        value={beeSpeed}
                        onChange={(event) => {
                          const next = Number(event.target.value)
                          setBeeSpeed(
                            Number.isFinite(next) && next > 0 ? next : 1,
                          )
                        }}
                        className="flex-1 accent-amber-400"
                      />
                      <span className="text-[10px] font-mono text-slate-300 w-10 text-right">
                        ×{beeSpeed.toFixed(2)}
                      </span>
                    </div>
                  </>
                )}
              </div>
            </div>
            <div className="mt-3 border-t border-white/10 pt-3 space-y-2">
              <h2 className="text-xs font-semibold text-white/80">
                Export / import
              </h2>
              <p className="text-[11px] text-slate-300">
                Export the current layout as YAML, or paste a saved definition to restore it later.
              </p>
              <button
                type="button"
                className="w-full rounded border border-slate-600 bg-slate-900/80 px-2 py-1 text-[11px] text-slate-200 hover:bg-slate-800"
                onClick={openExportModel}
              >
                Export / import model
              </button>
            </div>
          </div>
        </aside>
        <section className="flex-1 min-h-0">
          <ReactFlow
            nodes={displayNodes}
            edges={displayEdges}
            onNodesChange={onNodesChange}
            onEdgesChange={onEdgesChange}
            nodeTypes={nodeTypes}
            onConnect={onConnect}
            fitView
            fitViewOptions={{ padding: 0.2 }}
          >
            <Background />
            <Controls />
            <BeeOverlay
              nodes={displayNodes}
              beeModel={beeModel}
              simTimeMs={simTimeMs}
            />
          </ReactFlow>
        </section>
      </div>
      {isCapacityModalOpen && (
        <div className="fixed inset-0 z-40 flex items-center justify-center bg-black/70">
          <div
            role="dialog"
            aria-modal="true"
            className="w-full max-w-3xl rounded-lg bg-[#05070b] border border-white/20 p-4 text-sm text-white shadow-2xl"
          >
            <div className="flex items-center justify-between mb-3">
              <div>
                <h3 className="text-sm font-semibold text-white/90">
                  Capacity explorer
                </h3>
                <p className="mt-0.5 text-[11px] text-slate-300">
                  Visualise how the Service node&apos;s throughput changes as we scale
                  incoming load and dependency latency.
                </p>
              </div>
              <button
                type="button"
                className="text-white/60 hover:text-white text-lg leading-none px-2"
                onClick={() => setCapacityModalOpen(false)}
              >
                ×
              </button>
            </div>
            <div className="flex items-center gap-3 mb-2">
              <span className="text-[11px] text-slate-300">Mode:</span>
              <button
                type="button"
                className={`px-2 py-1 text-[11px] rounded border ${
                  capacityMode === 'baseline'
                    ? 'border-amber-400 bg-amber-500/20 text-amber-50'
                    : 'border-slate-600 bg-slate-900 text-slate-200'
                }`}
                onClick={() => setCapacityMode('baseline')}
              >
                Baseline
              </button>
              <button
                type="button"
                className={`px-2 py-1 text-[11px] rounded border ${
                  capacityMode === 'incoming2x'
                    ? 'border-amber-400 bg-amber-500/20 text-amber-50'
                    : 'border-slate-600 bg-slate-900 text-slate-200'
                }`}
                onClick={() => setCapacityMode('incoming2x')}
              >
                Incoming ×2
              </button>
              <button
                type="button"
                className={`px-2 py-1 text-[11px] rounded border ${
                  capacityMode === 'latency2x'
                    ? 'border-amber-400 bg-amber-500/20 text-amber-50'
                    : 'border-slate-600 bg-slate-900 text-slate-200'
                }`}
                onClick={() => setCapacityMode('latency2x')}
              >
                Dependency latency ×2
              </button>
              <button
                type="button"
                className={`px-2 py-1 text-[11px] rounded border ${
                  capacityMode === 'both2x'
                    ? 'border-amber-400 bg-amber-500/20 text-amber-50'
                    : 'border-slate-600 bg-slate-900 text-slate-200'
                }`}
                onClick={() => setCapacityMode('both2x')}
              >
                Both ×2
              </button>
            </div>
            <div className="mb-3 text-[11px] text-slate-300 space-y-1.5">
              <div>
                <span className="text-slate-400">Incoming TPS sweep:</span>{' '}
                <span className="font-mono text-slate-100">
                  {capacityModalModel.scaledIncomingMin.toFixed(0)} →{' '}
                  {capacityModalModel.scaledIncomingMax.toFixed(0)}
                </span>
                {capacityMode !== 'baseline' && (
                  <span className="text-slate-500">
                    {' '}
                    (baseline {capacityModalModel.baselineIncomingMin.toFixed(0)} →{' '}
                    {capacityModalModel.baselineIncomingMax.toFixed(0)})
                  </span>
                )}
              </div>
              <div>
                <span className="text-slate-400">Total dependency latency:</span>{' '}
                <span className="font-mono text-slate-100">
                  {capacityModalModel.baselineLatencyMs.toFixed(0)}ms →{' '}
                  {capacityModalModel.scaledLatencyMs.toFixed(0)}ms
                </span>
              </div>
            </div>
            <div className="bg-slate-950/70 rounded border border-slate-700/70 px-3 py-2">
              <VictoryChart
                theme={VictoryTheme.material}
                padding={{ top: 16, bottom: 40, left: 56, right: 20 }}
                height={260}
                domainPadding={12}
              >
                <VictoryAxis
                  label="Incoming TPS"
                  style={{
                    axisLabel: { padding: 32, fill: '#e2e8f0', fontSize: 10 },
                    tickLabels: { fill: '#cbd5f5', fontSize: 9 },
                    axis: { stroke: 'rgba(148,163,184,0.5)' },
                    grid: { stroke: 'rgba(148,163,184,0.2)' },
                  }}
                />
                <VictoryAxis
                  dependentAxis
                  label="TPS"
                  style={{
                    axisLabel: { padding: 40, fill: '#e2e8f0', fontSize: 10 },
                    tickLabels: { fill: '#cbd5f5', fontSize: 9 },
                    axis: { stroke: 'rgba(148,163,184,0.5)' },
                    grid: { stroke: 'rgba(148,163,184,0.2)' },
                  }}
                />
                <VictoryLine
                  data={capacityModalModel.points}
                  style={{
                    data: { stroke: '#facc15', strokeWidth: 2.2 },
                  }}
                  interpolation="monotoneX"
                />
                <VictoryLine
                  data={capacityModalModel.errorPoints}
                  style={{
                    data: {
                      stroke: '#f97316',
                      strokeWidth: 1.8,
                      strokeDasharray: '6,4',
                    },
                  }}
                  interpolation="monotoneX"
                />
              </VictoryChart>
            </div>
            <p className="mt-2 text-[11px] text-slate-400">
              Yellow line: effective TPS. Orange dashed line: IN drop rate in
              req/s once the service is saturated.
            </p>
          </div>
        </div>
      )}
      {isModelModalOpen && (
        <div className="fixed inset-0 z-40 flex items-center justify-center bg-black/70">
          <div
            role="dialog"
            aria-modal="true"
            className="w-full max-w-2xl rounded-lg bg-[#05070b] border border-white/20 p-4 text-sm text-white shadow-2xl"
          >
            <div className="flex items-center justify-between mb-3">
              <div>
                <h3 className="text-sm font-semibold text-white/90">
                  Export / import capacity model
                </h3>
                <p className="mt-0.5 text-[11px] text-slate-300">
                  Copy the YAML for the current layout, or paste a saved definition and apply it to replace the graph.
                </p>
              </div>
              <button
                type="button"
                className="text-white/60 hover:text-white text-lg leading-none px-2"
                onClick={() => setModelModalOpen(false)}
              >
                ×
              </button>
            </div>
            <div className="mb-2 flex items-center justify-between gap-2">
              <span className="text-[11px] text-slate-300">YAML model:</span>
              <button
                type="button"
                className="px-2 py-0.5 text-[11px] rounded border border-slate-600 bg-slate-900 text-slate-200 hover:bg-slate-800"
                onClick={openExportModel}
              >
                Refresh from graph
              </button>
            </div>
            <textarea
              className="w-full h-64 rounded border border-slate-700 bg-slate-950/70 px-2 py-1 text-[11px] font-mono text-slate-100 resize-none"
              placeholder="# Paste a saved capacity model here and click Apply to replace the graph."
              value={modelText}
              onChange={(event) => setModelText(event.target.value)}
            />
            {modelError && (
              <p className="mt-1 text-[11px] text-red-400">
                {modelError}
              </p>
            )}
            <div className="mt-3 flex justify-end gap-2">
              <button
                type="button"
                className="px-3 py-1 text-[11px] rounded border border-slate-600 bg-slate-900 text-slate-200 hover:bg-slate-800"
                onClick={() => setModelModalOpen(false)}
              >
                Close
              </button>
              <button
                type="button"
                className="px-3 py-1 text-[11px] rounded border border-amber-400 bg-amber-500/20 text-amber-50 hover:bg-amber-500/30"
                onClick={applyImportedModel}
              >
                Apply import
              </button>
            </div>
          </div>
        </div>
      )}
    </>
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

interface BeeSegment {
  fromId: string
  toId: string
  startMs: number
  endMs: number
}

interface BeeTimeline {
  beeId: string
  segments: BeeSegment[]
}

interface BeeAnimationModel {
  bees: BeeTimeline[]
  totalMs: number
  edgeIds: Set<string>
}

interface BeeSpriteProps {
  x: number
  y: number
  count: number
}

function BeeSprite({ x, y, count }: BeeSpriteProps) {
  const clampedCount = Math.min(Math.max(count, 1), 9)
  return (
    <g transform={`translate(${x}, ${y})`}>
      {/* body */}
      <rect
        x="-6"
        y="-6"
        width="12"
        height="12"
        rx="3"
        transform="rotate(45)"
        fill="none"
        stroke="#22d3ee"
        strokeWidth="1.4"
      />
      {/* wings */}
      <ellipse
        cx="-6"
        cy="-6"
        rx="4"
        ry="2.5"
        fill="none"
        stroke="#22d3ee"
        strokeWidth="1"
        opacity="0.9"
      />
      <ellipse
        cx="6"
        cy="-6"
        rx="4"
        ry="2.5"
        fill="none"
        stroke="#22d3ee"
        strokeWidth="1"
        opacity="0.9"
      />
      {clampedCount > 1 && (
        <text
          x="8"
          y="-5"
          fontSize="6"
          fill="#22d3ee"
          fontFamily="monospace"
        >
          ×{clampedCount}
        </text>
      )}
    </g>
  )
}

interface BeeOverlayProps {
  nodes: PerfFlowNode[]
  beeModel: BeeAnimationModel
  simTimeMs: number
}

function BeeOverlay({ nodes, beeModel, simTimeMs }: BeeOverlayProps) {
  const { getViewport } = useReactFlow()
  const viewport = getViewport()
  const { x, y, zoom } = viewport

  if (!beeModel.bees.length || beeModel.totalMs <= 0) {
    return null
  }

  const nodeById = new Map<string, PerfFlowNode>()
  for (const node of nodes) {
    nodeById.set(node.id, node)
  }

  const tNorm =
    beeModel.totalMs > 0
      ? ((simTimeMs % beeModel.totalMs) + beeModel.totalMs) % beeModel.totalMs
      : 0

  return (
    <svg
      className="pointer-events-none absolute inset-0 overflow-visible"
      style={{ zIndex: 20 }}
    >
      {beeModel.bees.map((bee) => {
        const segments = bee.segments
        if (!segments.length) return null

        const beeEnd = segments[segments.length - 1].endMs
        const tBee =
          beeEnd > 0
            ? ((tNorm % beeEnd) + beeEnd) % beeEnd
            : tNorm

        let current = segments[0]
        for (const seg of segments) {
          if (tBee >= seg.startMs && tBee < seg.endMs) {
            current = seg
            break
          }
          if (tBee >= seg.endMs) {
            current = seg
          }
        }
        const fromNode = nodeById.get(current.fromId)
        const toNode = nodeById.get(current.toId) ?? fromNode
        if (!fromNode || !toNode) return null
        const duration = Math.max(current.endMs - current.startMs, 1)
        const localT = Math.min(
          Math.max((tBee - current.startMs) / duration, 0),
          1,
        )
        const graphX =
          fromNode.position.x +
          (toNode.position.x - fromNode.position.x) * localT +
          130
        const graphY =
          fromNode.position.y +
          (toNode.position.y - fromNode.position.y) * localT -
          12
        const screenX = x + graphX * zoom
        const screenY = y + graphY * zoom
        return (
          <BeeSprite
            key={bee.beeId}
            x={screenX}
            y={screenY}
            count={1}
          />
        )
      })}
    </svg>
  )
}

function buildBeeAnimationModel(nodes: PerfFlowNode[], edges: PerfFlowEdge[]): BeeAnimationModel {
  if (!nodes.length || !edges.length) {
    return { bees: [], totalMs: 0, edgeIds: new Set<string>() }
  }

  const nodeById = new Map<string, PerfFlowNode>()
  for (const node of nodes) {
    nodeById.set(node.id, node)
  }

  const inNode = nodes.find((node) => {
    const kind = node.data.kind ?? (node.id === 'in' ? 'in' : node.id === 'out' ? 'out' : 'service')
    return kind === 'in'
  })
  if (!inNode) {
    return { bees: [], totalMs: 0, edgeIds: new Set<string>() }
  }

  const outgoing = new Map<string, string[]>()
  for (const edge of edges) {
    if (!outgoing.has(edge.source)) outgoing.set(edge.source, [])
    outgoing.get(edge.source)!.push(edge.target)
  }

  const forwardPath: string[] = [inNode.id]
  const visitedServices = new Set<string>()

  let currentId = inNode.id
  while (true) {
    const neighbourIds = outgoing.get(currentId) ?? []
    const serviceNeighbours = neighbourIds
      .map((nextId) => nodeById.get(nextId))
      .filter((n): n is PerfFlowNode => {
        if (!n) return false
        const kind =
          n.data.kind ?? (n.id === 'in' ? 'in' : n.id === 'out' ? 'out' : 'service')
        return kind === 'service' && !visitedServices.has(n.id)
      })

    if (serviceNeighbours.length === 0) {
      break
    }

    const nextService = serviceNeighbours[0]
    forwardPath.push(nextService.id)
    visitedServices.add(nextService.id)
    currentId = nextService.id
  }

  const tailId = forwardPath[forwardPath.length - 1]
  const tailNeighbours = outgoing.get(tailId) ?? []
  const tailOut = tailNeighbours
    .map((nextId) => nodeById.get(nextId))
    .find((n): n is PerfFlowNode => {
      if (!n) return false
      const kind =
        n.data.kind ?? (n.id === 'in' ? 'in' : n.id === 'out' ? 'out' : 'service')
      return kind === 'out' && !n.data.isDb
    })
  if (tailOut) {
    forwardPath.push(tailOut.id)
  }

  if (!forwardPath || forwardPath.length < 2) {
    return { bees: [], totalMs: 0, edgeIds: new Set<string>() }
  }

  const backwardPath = forwardPath.slice(0, -1).reverse()
  const roundTrip = forwardPath.concat(backwardPath)

  const bees: BeeTimeline[] = []
  const edgeIds = new Set<string>()
  const mainBee: BeeTimeline = { beeId: 'bee-0', segments: [] }
  bees.push(mainBee)
  let nextBeeIndex = 1
  let currentTime = 0
  const travelMs = 40

  const addDwellSegment = (timeline: BeeTimeline, nodeId: string, duration: number) => {
    const start = currentTime
    const end = start + Math.max(duration, 1)
    timeline.segments.push({ fromId: nodeId, toId: nodeId, startMs: start, endMs: end })
    currentTime = end
  }

  const spawnCallBee = (sourceId: string, targetId: string, start: number, latencyMs: number) => {
    const beeId = `bee-${nextBeeIndex++}`
    const timeline: BeeTimeline = { beeId, segments: [] }
    bees.push(timeline)

    const departStart = start
    const departEnd = departStart + travelMs
    const dwellEnd = departEnd + Math.max(latencyMs, 1)
    const returnEnd = dwellEnd + travelMs

    timeline.segments.push({
      fromId: sourceId,
      toId: targetId,
      startMs: departStart,
      endMs: departEnd,
    })
    timeline.segments.push({
      fromId: targetId,
      toId: targetId,
      startMs: departEnd,
      endMs: dwellEnd,
    })
    timeline.segments.push({
      fromId: targetId,
      toId: sourceId,
      startMs: dwellEnd,
      endMs: returnEnd,
    })

    const edge = edges.find((e) => e.source === sourceId && e.target === targetId)
    if (edge) {
      edgeIds.add(edge.id)
    }
    const reverseEdge = edges.find((e) => e.source === targetId && e.target === sourceId)
    if (reverseEdge) {
      edgeIds.add(reverseEdge.id)
    }
  }

  for (let i = 0; i < roundTrip.length; i += 1) {
    const nodeId = roundTrip[i]
    const node = nodeById.get(nodeId)
    if (!node) continue
    const kind = node.data.kind ?? (node.id === 'in' ? 'in' : node.id === 'out' ? 'out' : 'service')

    const isForwardLeg = i < forwardPath.length
    const nextOnMainPath = isForwardLeg && i + 1 < forwardPath.length ? forwardPath[i + 1] : null

    if (kind === 'in') {
      addDwellSegment(mainBee, nodeId, 40)
    } else if (kind === 'service') {
      const serviceConfig = node.data
      const internalLatency = Math.max(0, serviceConfig.internalLatencyMs ?? 0)
      addDwellSegment(mainBee, nodeId, internalLatency)

      if (isForwardLeg) {
        const includeOutDeps = serviceConfig.includeOutDeps !== false
        const outsForService = edges
          .filter((e) => e.source === nodeId)
          .map((e) => nodeById.get(e.target))
          .filter((n): n is PerfFlowNode => !!n)

        const dbTargets = outsForService.filter((n) => {
          const k = n.data.kind ?? (n.id === 'in' ? 'in' : n.id === 'out' ? 'out' : 'service')
          return k === 'out' && n.data.isDb
        })

        const nonDbTargets = outsForService.filter((n) => {
          const k = n.data.kind ?? (n.id === 'in' ? 'in' : n.id === 'out' ? 'out' : 'service')
          if (k === 'out' && n.data.isDb) return false
          if (nextOnMainPath && n.id === nextOnMainPath) return false
          return true
        })

        if (dbTargets.length > 0) {
          const primaryDb = dbTargets[0]
          const primaryLatency = Math.max(primaryDb.data.depLatencyMs ?? 0, 1)

          const departStart = currentTime
          const departEnd = departStart + travelMs
          mainBee.segments.push({
            fromId: nodeId,
            toId: primaryDb.id,
            startMs: departStart,
            endMs: departEnd,
          })
          currentTime = departEnd

          const dwellEnd = currentTime + primaryLatency
          mainBee.segments.push({
            fromId: primaryDb.id,
            toId: primaryDb.id,
            startMs: currentTime,
            endMs: dwellEnd,
          })
          currentTime = dwellEnd

          const returnEnd = currentTime + travelMs
          mainBee.segments.push({
            fromId: primaryDb.id,
            toId: nodeId,
            startMs: currentTime,
            endMs: returnEnd,
          })
          currentTime = returnEnd

          const forwardEdge = edges.find(
            (e) => e.source === nodeId && e.target === primaryDb.id,
          )
          if (forwardEdge) {
            edgeIds.add(forwardEdge.id)
          }
          const reverseEdge = edges.find(
            (e) => e.source === primaryDb.id && e.target === nodeId,
          )
          if (reverseEdge) {
            edgeIds.add(reverseEdge.id)
          }
        }

        if (includeOutDeps && nonDbTargets.length > 0) {
          const depsParallel = serviceConfig.depsParallel === true

          if (depsParallel) {
            const branchStart = currentTime
            for (const dep of nonDbTargets) {
              const k = dep.data.kind ?? (dep.id === 'in' ? 'in' : dep.id === 'out' ? 'out' : 'service')

              let latencyMs = 40
              if (k === 'out') {
                latencyMs = Math.max(dep.data.depLatencyMs ?? 0, 1)
              } else if (k === 'service') {
                latencyMs = Math.max(dep.data.internalLatencyMs ?? 0, 40)
              }

              spawnCallBee(nodeId, dep.id, branchStart, latencyMs)
            }
          } else {
            for (const dep of nonDbTargets) {
              const k = dep.data.kind ?? (dep.id === 'in' ? 'in' : dep.id === 'out' ? 'out' : 'service')

              let latencyMs = 40
              if (k === 'out') {
                latencyMs = Math.max(dep.data.depLatencyMs ?? 0, 1)
              } else if (k === 'service') {
                latencyMs = Math.max(dep.data.internalLatencyMs ?? 0, 40)
              }

              const departStart = currentTime
              const departEnd = departStart + travelMs
              mainBee.segments.push({
                fromId: nodeId,
                toId: dep.id,
                startMs: departStart,
                endMs: departEnd,
              })
              currentTime = departEnd

              const dwellEnd = currentTime + latencyMs
              mainBee.segments.push({
                fromId: dep.id,
                toId: dep.id,
                startMs: currentTime,
                endMs: dwellEnd,
              })
              currentTime = dwellEnd

              const returnEnd = currentTime + travelMs
              mainBee.segments.push({
                fromId: dep.id,
                toId: nodeId,
                startMs: currentTime,
                endMs: returnEnd,
              })
              currentTime = returnEnd

              const forwardEdge = edges.find(
                (e) => e.source === nodeId && e.target === dep.id,
              )
              if (forwardEdge) {
                edgeIds.add(forwardEdge.id)
              }
              const reverseEdge = edges.find(
                (e) => e.source === dep.id && e.target === nodeId,
              )
              if (reverseEdge) {
                edgeIds.add(reverseEdge.id)
              }
            }
          }
        }
      }
    } else if (kind === 'out') {
      const dwellMs = Math.max(node.data.depLatencyMs ?? 0, 40)
      addDwellSegment(mainBee, nodeId, dwellMs)
    }

    const nextNodeId = i + 1 < roundTrip.length ? roundTrip[i + 1] : null
    if (nextNodeId) {
      const start = currentTime
      const end = start + travelMs
      mainBee.segments.push({
        fromId: nodeId,
        toId: nextNodeId,
        startMs: start,
        endMs: end,
      })
      currentTime = end

      const edge = edges.find((e) => e.source === nodeId && e.target === nextNodeId)
      if (edge) {
        edgeIds.add(edge.id)
      }
    }
  }

  const totalMs =
    bees.reduce((max, bee) => {
      if (!bee.segments.length) return max
      const last = bee.segments[bee.segments.length - 1]
      return Math.max(max, last.endMs)
    }, 0) || 0

  return { bees, totalMs, edgeIds }
}

function computeServiceCapacityModel(
  nodes: PerfFlowNode[],
  edges: PerfFlowEdge[],
  incomingScale: number,
  latencyScale: number,
) {
  const serviceNode = nodes.find((node) => node.id === 'service')
  if (!serviceNode) {
    return {
      points: [],
      baselineIncomingMin: 0,
      baselineIncomingMax: 0,
      scaledIncomingMin: 0,
      scaledIncomingMax: 0,
      baselineLatencyMs: 0,
      scaledLatencyMs: 0,
    }
  }
  const serviceConfig: PerfNodeData = serviceNode.data

  const outs = edges.filter((edge) => edge.source === serviceNode.id)
  const httpBaselineLatencies: number[] = []
  const httpScaledLatencies: number[] = []
  let dbBaselineLatencyMs = 0
  let dbScaledLatencyMs = 0
  let dbCapacity = Number.POSITIVE_INFINITY
  const includeOutDeps = serviceConfig.includeOutDeps !== false

  for (const edge of outs) {
    const targetNode = nodes.find((node) => node.id === edge.target)
    if (!targetNode) continue
    if (targetNode.data.kind === 'out') {
      const rawLatency = targetNode.data.depLatencyMs ?? 0
      const baseLatency = Math.max(0, rawLatency)
      const depLatency = Math.max(0, rawLatency * latencyScale)
      if (targetNode.id.endsWith('-db')) {
        dbBaselineLatencyMs += baseLatency
        dbScaledLatencyMs += depLatency

        const pool = targetNode.data.depPool ?? 0
        const latencySec = depLatency / 1000
        if (Number.isFinite(pool) && pool > 0 && Number.isFinite(latencySec) && latencySec > 0) {
          const nodeCapacity = pool / latencySec
          if (nodeCapacity < dbCapacity) {
            dbCapacity = nodeCapacity
          }
        }
      } else if (includeOutDeps) {
        httpBaselineLatencies.push(baseLatency)
        httpScaledLatencies.push(depLatency)
      }
    }
  }

  let baselineTotalDepLatencyMs = 0
  let totalDepLatencyMs = 0
  if (httpBaselineLatencies.length > 0) {
    if (serviceConfig.depsParallel) {
      baselineTotalDepLatencyMs = Math.max(...httpBaselineLatencies)
      totalDepLatencyMs = Math.max(...httpScaledLatencies)
    } else {
      baselineTotalDepLatencyMs = httpBaselineLatencies.reduce((acc, value) => acc + value, 0)
      totalDepLatencyMs = httpScaledLatencies.reduce((acc, value) => acc + value, 0)
    }
  }

  baselineTotalDepLatencyMs += dbBaselineLatencyMs
  totalDepLatencyMs += dbScaledLatencyMs

  const effectiveConfig: PerfNodeData = {
    ...serviceConfig,
    maxConcurrentIn:
      serviceConfig.transport === 'netty'
        ? serviceConfig.maxConcurrentIn * 2
        : serviceConfig.maxConcurrentIn,
    depPool:
      serviceConfig.httpClient === 'webclient'
        ? serviceConfig.depPool * 2
        : serviceConfig.depPool,
  }

  const points = []
  const errorPoints = []
  const maxIncoming = 1000 * Math.max(incomingScale, 0.1)
  const step = 10 * Math.max(incomingScale, 0.1)

  for (let incoming = 10; incoming <= maxIncoming; incoming += step) {
    const baseMetrics = computePerfMetrics({
      ...effectiveConfig,
      inputMode: 'tps',
      incomingTps: incoming,
      depLatencyMs: totalDepLatencyMs,
    })
    const cappedMaxOverall = Math.min(baseMetrics.maxTpsOverall, dbCapacity)
    const y = Math.min(incoming, cappedMaxOverall)
    const errorY = Math.max(0, incoming - y)
    points.push({ x: incoming, y })
    errorPoints.push({ x: incoming, y: errorY })
  }
  return {
    points,
    errorPoints,
    baselineIncomingMin: 10,
    baselineIncomingMax: 1000,
    scaledIncomingMin: 10,
    scaledIncomingMax: maxIncoming,
    baselineLatencyMs: baselineTotalDepLatencyMs,
    scaledLatencyMs: totalDepLatencyMs,
  }
}
