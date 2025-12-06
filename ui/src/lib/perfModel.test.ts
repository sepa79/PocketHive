import { describe, it, expect } from 'vitest'
import {
  computePerfMetrics,
  classifyUtilisation,
  type PerfNodeData,
} from './perfModel'

function makeBaseData(overrides: Partial<PerfNodeData> = {}): PerfNodeData {
  return {
    name: 'component',
    inputMode: 'tps',
    incomingTps: 50,
    clientConcurrency: 100,
    transport: 'tomcat',
    maxConcurrentIn: 20,
    internalLatencyMs: 50,
    depLatencyMs: 150,
    depPool: 10,
    httpClient: 'httpclient',
    dbEnabled: false,
    ...overrides,
  }
}

describe('computePerfMetrics', () => {
  it('matches single-component calculator formulas for TPS mode', () => {
    const data = makeBaseData({
      inputMode: 'tps',
      incomingTps: 50,
    })

    const metrics = computePerfMetrics(data)

    // serviceTimeMs = internalLatencyMs + depLatencyMs
    expect(metrics.serviceTimeMs).toBe(200)
    expect(metrics.serviceTimeSec).toBeCloseTo(0.2, 6)

    // maxTpsInbound = maxConcurrentIn / serviceTimeSec
    expect(metrics.maxTpsInbound).toBeCloseTo(100, 6)

    // depLatencySec = depLatencyMs / 1000
    expect(metrics.depLatencySec).toBeCloseTo(0.15, 6)

    // maxTpsDependency = depPool / depLatencySec
    expect(metrics.maxTpsDependency).toBeCloseTo(66.6666, 3)

    // maxTpsOverall = min(maxTpsInbound, maxTpsDependency)
    expect(metrics.maxTpsOverall).toBeCloseTo(66.6666, 3)

    // effectiveTps = min(incomingTps, maxTpsOverall)
    expect(metrics.effectiveTps).toBeCloseTo(50, 6)

    // utilisation
    expect(metrics.utilInbound).toBeCloseTo(0.5, 6)
    expect(metrics.utilDep).toBeCloseTo(0.75, 6)
  })

  it('matches single-component calculator formulas for concurrency mode', () => {
    const data = makeBaseData({
      inputMode: 'concurrency',
      clientConcurrency: 100,
    })

    const metrics = computePerfMetrics(data)

    // clientIdealTps = clientConcurrency / serviceTimeSec
    expect(metrics.clientIdealTps).not.toBeNull()
    expect(metrics.clientIdealTps ?? 0).toBeCloseTo(500, 6)

    // effectiveTps = min(clientIdealTps, maxTpsOverall)
    expect(metrics.effectiveTps).toBeCloseTo(metrics.maxTpsOverall, 6)

    // full dependency saturation, inbound still below 70%
    expect(metrics.utilInbound).toBeLessThan(0.7)
    expect(metrics.utilDep).toBeCloseTo(1, 6)
  })
})

describe('classifyUtilisation', () => {
  it('classifies utilisation into OK / High / Overloaded', () => {
    expect(classifyUtilisation(0)).toBe('ok')
    expect(classifyUtilisation(0.3)).toBe('ok')
    expect(classifyUtilisation(0.7)).toBe('high')
    expect(classifyUtilisation(0.85)).toBe('high')
    expect(classifyUtilisation(0.95)).toBe('overloaded')
  })
})
