import { describe, it, expect } from 'vitest'
import { computePerfGraph, type PerfGraphNode, type PerfGraphEdge } from './perfGraph'

import type { PerfNodeData } from './perfModel'

function makeConfig(overrides: Partial<PerfNodeData> = {}): PerfNodeData {
  return {
    name: 'component',
    inputMode: 'tps',
    incomingTps: 0,
    clientConcurrency: 0,
    transport: 'tomcat',
    maxConcurrentIn: 10,
    internalLatencyMs: 50,
    depLatencyMs: 150,
    depPool: 10,
    httpClient: 'httpclient',
    dbEnabled: false,
    depsParallel: false,
    ...overrides,
  }
}

describe('computePerfGraph', () => {
  it('propagates TPS from IN through Service to OUT', () => {
    const nodes: PerfGraphNode[] = [
      {
        id: 'in',
        kind: 'in',
        config: makeConfig({
          inputMode: 'concurrency',
          clientConcurrency: 100,
          internalLatencyMs: 50,
          maxConcurrentIn: 20,
        }),
      },
      {
        id: 'svc',
        kind: 'service',
        config: makeConfig({
          internalLatencyMs: 100,
          depLatencyMs: 100,
          maxConcurrentIn: 50,
          depPool: 10,
        }),
      },
      {
        id: 'out',
        kind: 'out',
        config: makeConfig({
          internalLatencyMs: 20,
        }),
      },
    ]

    const edges: PerfGraphEdge[] = [
      { id: 'e1', source: 'in', target: 'svc' },
      { id: 'e2', source: 'svc', target: 'out' },
    ]

    const result = computePerfGraph(nodes, edges)

    const inNode = result['in']
    const svcNode = result['svc']
    const outNode = result['out']

    expect(inNode).toBeDefined()
    expect(svcNode).toBeDefined()
    expect(outNode).toBeDefined()

    // IN node: concurrency-driven, non-zero throughput
    expect(inNode.outputTps).toBeGreaterThan(0)

    // Service node should see incoming TPS from IN
    expect(svcNode.incomingTps).toBeCloseTo(inNode.outputTps, 6)

    // Because of service limits, its effective TPS must be <= incoming
    expect(svcNode.metrics.effectiveTps).toBeLessThanOrEqual(svcNode.incomingTps)

    // OUT node should preserve TPS (no additional capacity limit)
    expect(outNode.incomingTps).toBeCloseTo(svcNode.metrics.effectiveTps, 6)
    expect(outNode.metrics.effectiveTps).toBeCloseTo(outNode.incomingTps, 6)
  })
})
