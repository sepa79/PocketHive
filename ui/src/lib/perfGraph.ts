import {
  computePerfMetrics,
  type PerfMetrics,
  type PerfNodeData,
} from './perfModel'

export type PerfNodeKind = 'in' | 'service' | 'out'

export interface PerfGraphNode {
  id: string
  kind: PerfNodeKind
  config: PerfNodeData
}

export interface PerfGraphEdge {
  id: string
  source: string
  target: string
}

export interface PerfGraphNodeResult {
  incomingTps: number
  metrics: PerfMetrics
  outputTps: number
}

export type PerfGraphResult = Record<string, PerfGraphNodeResult>

export function computePerfGraph(
  nodes: PerfGraphNode[],
  edges: PerfGraphEdge[],
): PerfGraphResult {
  const nodeById = new Map<string, PerfGraphNode>()
  const outgoingBySource = new Map<string, PerfGraphEdge[]>()
  const indegree = new Map<string, number>()
  const incomingTps = new Map<string, number>()
  const serviceCapacityCache = new Map<string, number>()

  for (const node of nodes) {
    nodeById.set(node.id, node)
    outgoingBySource.set(node.id, [])
    indegree.set(node.id, 0)
    incomingTps.set(node.id, 0)
  }

  for (const edge of edges) {
    if (!nodeById.has(edge.source) || !nodeById.has(edge.target)) {
      continue
    }
    const list = outgoingBySource.get(edge.source)
    if (list) {
      list.push(edge)
    }
    indegree.set(edge.target, (indegree.get(edge.target) ?? 0) + 1)
  }

  const queue: string[] = []
  indegree.forEach((deg, id) => {
    if (deg === 0) queue.push(id)
  })

  const results: PerfGraphResult = {}
  const processed = new Set<string>()

  while (queue.length > 0) {
    const id = queue.shift()!
    const node = nodeById.get(id)
    if (!node) continue
    processed.add(id)

    const inTps = incomingTps.get(id) ?? 0
    const result = computeNodeResult(
      node,
      inTps,
      outgoingBySource,
      nodeById,
      serviceCapacityCache,
    )
    results[id] = result

    const outs = outgoingBySource.get(id) ?? []
    if (outs.length > 0) {
      const normalEdges: PerfGraphEdge[] = []
      const dbEdges: PerfGraphEdge[] = []

      for (const edge of outs) {
        const target = nodeById.get(edge.target)
        if (!target) continue
        if (target.id.endsWith('-db')) {
          dbEdges.push(edge)
        } else {
          normalEdges.push(edge)
        }
      }

      const normalCount = normalEdges.length
      const share = normalCount > 0 ? result.outputTps / normalCount : 0

      for (const edge of normalEdges) {
        const prev = incomingTps.get(edge.target) ?? 0
        incomingTps.set(edge.target, prev + share)
        const currentIn = indegree.get(edge.target)
        if (currentIn === undefined) continue
        const nextDeg = currentIn - 1
        indegree.set(edge.target, nextDeg)
        if (nextDeg === 0) {
          queue.push(edge.target)
        }
      }

      for (const edge of dbEdges) {
        const prev = incomingTps.get(edge.target) ?? 0
        incomingTps.set(edge.target, prev + result.outputTps)
        const currentIn = indegree.get(edge.target)
        if (currentIn === undefined) continue
        const nextDeg = currentIn - 1
        indegree.set(edge.target, nextDeg)
        if (nextDeg === 0) {
          queue.push(edge.target)
        }
      }
    }
  }

  for (const node of nodes) {
    if (processed.has(node.id)) continue
    const inTps = incomingTps.get(node.id) ?? 0
    results[node.id] = computeNodeResult(
      node,
      inTps,
      outgoingBySource,
      nodeById,
      serviceCapacityCache,
    )
  }

  return results
}

function applyServiceEffectiveConfig(config: PerfNodeData): PerfNodeData {
  let effectiveMaxConcurrentIn = config.maxConcurrentIn
  if (config.transport === 'netty') {
    effectiveMaxConcurrentIn = config.maxConcurrentIn * 2
  }

  let effectiveDepPool = config.depPool
  if (config.httpClient === 'webclient') {
    effectiveDepPool = config.depPool * 2
  }

  return {
    ...config,
    maxConcurrentIn: effectiveMaxConcurrentIn,
    depPool: effectiveDepPool,
  }
}

function computeNodeResult(
  node: PerfGraphNode,
  incomingTps: number,
  outgoingBySource: Map<string, PerfGraphEdge[]>,
  nodeById: Map<string, PerfGraphNode>,
  serviceCapacityCache: Map<string, number>,
): PerfGraphNodeResult {
  const base: PerfNodeData = { ...node.config }
  const normalizedIncoming = Math.max(0, incomingTps)

  if (node.kind === 'in') {
    const mode = base.inputMode === 'tps' ? 'tps' : 'concurrency'
    if (mode === 'tps') {
      const metrics = computePerfMetrics({
        ...base,
        inputMode: 'tps',
        incomingTps: Math.max(0, base.incomingTps),
        clientConcurrency: 0,
        maxConcurrentIn: Number.POSITIVE_INFINITY,
        depLatencyMs: 0,
        depPool: Number.POSITIVE_INFINITY,
      })
      return {
        incomingTps: 0,
        metrics,
        outputTps: metrics.effectiveTps,
      }
    }

    let serviceTimeMs = 0
    let serviceCapacity = Number.POSITIVE_INFINITY
    const outs = outgoingBySource.get(node.id) ?? []
    for (const edge of outs) {
      const target = nodeById.get(edge.target)
      if (target && target.kind === 'service') {
        serviceTimeMs = estimateServicePathLatencyMs(
          target,
          outgoingBySource,
          nodeById,
        )
        serviceCapacity = computeServiceChainCapacity(
          target,
          outgoingBySource,
          nodeById,
          serviceCapacityCache,
          new Set<string>(),
        )
        break
      }
    }
    if (serviceTimeMs <= 0) {
      serviceTimeMs = 1000
    }

    const baseMetrics = computePerfMetrics({
      ...base,
      inputMode: 'concurrency',
      clientConcurrency: Math.max(0, base.clientConcurrency),
      incomingTps: 0,
      internalLatencyMs: serviceTimeMs,
      depLatencyMs: 0,
      maxConcurrentIn: Number.POSITIVE_INFINITY,
      depPool: Number.POSITIVE_INFINITY,
    })

    const idealTps = baseMetrics.clientIdealTps ?? 0
    const clampedTps = Math.min(idealTps, serviceCapacity)

    const metrics: PerfMetrics = {
      ...baseMetrics,
      effectiveTps: clampedTps,
      utilInbound: 0,
      utilDep: 0,
      inboundStatus: 'ok',
      depStatus: 'ok',
    }

    return {
      incomingTps: 0,
      metrics,
      outputTps: clampedTps,
    }
  }

  if (node.kind === 'out') {
    const metrics = computePerfMetrics({
      ...base,
      inputMode: 'tps',
      incomingTps: normalizedIncoming,
      maxConcurrentIn: Number.POSITIVE_INFINITY,
      internalLatencyMs: 0,
      depLatencyMs: Math.max(0, base.depLatencyMs),
      depPool: base.depPool,
    })
    return {
      incomingTps: normalizedIncoming,
      metrics,
      outputTps: metrics.effectiveTps,
    }
  }

  const totalDepLatencyMs = computeServiceDepLatencyMs(node, outgoingBySource, nodeById)

  const effectiveConfig = applyServiceEffectiveConfig(base)

  const baseMetrics = computePerfMetrics({
    ...effectiveConfig,
    inputMode: 'tps',
    incomingTps: normalizedIncoming,
    depLatencyMs: totalDepLatencyMs,
  })
  const dbCapacity = computeDbCapacityForService(node, outgoingBySource, nodeById)

  let capacity = Math.min(baseMetrics.maxTpsOverall, dbCapacity)

  const outs = outgoingBySource.get(node.id) ?? []
  for (const edge of outs) {
    const target = nodeById.get(edge.target)
    if (!target || target.kind !== 'service') continue
    const downstreamCapacity = computeServiceChainCapacity(
      target,
      outgoingBySource,
      nodeById,
      serviceCapacityCache,
      new Set<string>(),
    )
    if (downstreamCapacity < capacity) {
      capacity = downstreamCapacity
    }
  }

  const effectiveTps = Math.min(normalizedIncoming, capacity)

  const metrics: PerfMetrics = {
    ...baseMetrics,
    maxTpsOverall: capacity,
    effectiveTps,
  }
  return {
    incomingTps: normalizedIncoming,
    metrics,
    outputTps: metrics.effectiveTps,
  }
}

function computeServiceDepLatencyMs(
  node: PerfGraphNode,
  outgoingBySource: Map<string, PerfGraphEdge[]>,
  nodeById: Map<string, PerfGraphNode>,
): number {
  return computeServiceDepLatencyMsInner(
    node,
    outgoingBySource,
    nodeById,
    new Set<string>(),
  )
}

function computeServiceDepLatencyMsInner(
  node: PerfGraphNode,
  outgoingBySource: Map<string, PerfGraphEdge[]>,
  nodeById: Map<string, PerfGraphNode>,
  visiting: Set<string>,
): number {
  if (node.kind !== 'service') return 0

  if (visiting.has(node.id)) {
    return 0
  }
  visiting.add(node.id)

  const includeOutDeps = node.config.includeOutDeps !== false

  const outs = outgoingBySource.get(node.id) ?? []
  const branchLatencies: number[] = []
  let dbLatencySum = 0

  for (const edge of outs) {
    const target = nodeById.get(edge.target)
    if (!target) continue

    if (target.kind === 'out') {
      const latencyMs = Math.max(0, target.config.depLatencyMs)
      if (latencyMs <= 0) continue

      if (target.id.endsWith('-db')) {
        dbLatencySum += latencyMs
      } else {
        if (!includeOutDeps) continue
        branchLatencies.push(latencyMs)
      }
    } else if (target.kind === 'service') {
      if (!includeOutDeps) continue
      const pathLatency = estimateServicePathLatencyMsInner(
        target,
        outgoingBySource,
        nodeById,
        visiting,
      )
      if (pathLatency > 0) {
        branchLatencies.push(pathLatency)
      }
    }
  }

  const depsParallel = node.config.depsParallel === true

  let httpLatencyTotal = 0
  if (branchLatencies.length > 0) {
    httpLatencyTotal = depsParallel
      ? Math.max(...branchLatencies)
      : branchLatencies.reduce((acc, value) => acc + value, 0)
  }

  visiting.delete(node.id)
  return dbLatencySum + httpLatencyTotal
}

function computeDbCapacityForService(
  serviceNode: PerfGraphNode,
  outgoingBySource: Map<string, PerfGraphEdge[]>,
  nodeById: Map<string, PerfGraphNode>,
): number {
  if (serviceNode.kind !== 'service') return Number.POSITIVE_INFINITY
  const outs = outgoingBySource.get(serviceNode.id) ?? []
  let capacity = Number.POSITIVE_INFINITY
  for (const edge of outs) {
    const target = nodeById.get(edge.target)
    if (!target || target.kind !== 'out') continue
    if (!target.id.endsWith('-db')) continue
    const pool = target.config.depPool
    const latencySec = Math.max(0, target.config.depLatencyMs) / 1000
    if (!Number.isFinite(pool) || pool <= 0) continue
    if (!Number.isFinite(latencySec) || latencySec <= 0) continue
    const nodeCapacity = pool / latencySec
    if (nodeCapacity < capacity) {
      capacity = nodeCapacity
    }
  }
  return capacity
}

function computeServiceChainCapacity(
  serviceNode: PerfGraphNode,
  outgoingBySource: Map<string, PerfGraphEdge[]>,
  nodeById: Map<string, PerfGraphNode>,
  cache: Map<string, number>,
  visiting: Set<string>,
): number {
  const cached = cache.get(serviceNode.id)
  if (cached !== undefined) {
    return cached
  }

  if (visiting.has(serviceNode.id)) {
    // Cycle detected; treat as unbounded to avoid over-constraining.
    return Number.POSITIVE_INFINITY
  }

  visiting.add(serviceNode.id)

  const depLatencyMs = computeServiceDepLatencyMs(
    serviceNode,
    outgoingBySource,
    nodeById,
  )
  const effectiveConfig = applyServiceEffectiveConfig(serviceNode.config)
  const baseMetrics = computePerfMetrics({
    ...effectiveConfig,
    inputMode: 'tps',
    incomingTps: 0,
    depLatencyMs,
  })

  let capacity = baseMetrics.maxTpsOverall
  const dbCapacity = computeDbCapacityForService(
    serviceNode,
    outgoingBySource,
    nodeById,
  )
  if (dbCapacity < capacity) {
    capacity = dbCapacity
  }

  const outs = outgoingBySource.get(serviceNode.id) ?? []
  for (const edge of outs) {
    const target = nodeById.get(edge.target)
    if (!target || target.kind !== 'service') continue
    const downstreamCapacity = computeServiceChainCapacity(
      target,
      outgoingBySource,
      nodeById,
      cache,
      visiting,
    )
    if (downstreamCapacity < capacity) {
      capacity = downstreamCapacity
    }
  }

  visiting.delete(serviceNode.id)
  cache.set(serviceNode.id, capacity)
  return capacity
}

function estimateServicePathLatencyMs(
  serviceNode: PerfGraphNode,
  outgoingBySource: Map<string, PerfGraphEdge[]>,
  nodeById: Map<string, PerfGraphNode>,
): number {
  return estimateServicePathLatencyMsInner(
    serviceNode,
    outgoingBySource,
    nodeById,
    new Set<string>(),
  )
}

function estimateServicePathLatencyMsInner(
  serviceNode: PerfGraphNode,
  outgoingBySource: Map<string, PerfGraphEdge[]>,
  nodeById: Map<string, PerfGraphNode>,
  visiting: Set<string>,
): number {
  const baseInternal = Math.max(0, serviceNode.config.internalLatencyMs)
  const depLatency = computeServiceDepLatencyMsInner(
    serviceNode,
    outgoingBySource,
    nodeById,
    visiting,
  )
  const total = baseInternal + depLatency
  return total > 0 ? total : 1000
}
