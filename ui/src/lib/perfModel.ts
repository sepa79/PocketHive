export type InputMode = 'tps' | 'concurrency'

export type Transport = 'tomcat' | 'jetty' | 'netty'

export type HttpClientKind = 'httpclient' | 'webclient'

export interface PerfNodeData {
  name: string
  inputMode: InputMode
  incomingTps: number
  clientConcurrency: number
  transport: Transport
  maxConcurrentIn: number
  internalLatencyMs: number
  depLatencyMs: number
  depPool: number
  httpClient: HttpClientKind
  dbEnabled: boolean
}

export type UtilisationStatus = 'ok' | 'high' | 'overloaded'

export interface PerfMetrics {
  serviceTimeMs: number
  serviceTimeSec: number
  depLatencySec: number
  maxTpsInbound: number
  maxTpsDependency: number
  maxTpsOverall: number
  clientIdealTps: number | null
  effectiveTps: number
  utilInbound: number
  utilDep: number
  inboundStatus: UtilisationStatus
  depStatus: UtilisationStatus
}

export function computePerfMetrics(data: PerfNodeData): PerfMetrics {
  const serviceTimeMs = Math.max(0, data.internalLatencyMs + data.depLatencyMs)
  const serviceTimeSec = msToSeconds(serviceTimeMs)

  const depLatencySec = msToSeconds(Math.max(0, data.depLatencyMs))

  const maxTpsInbound =
    serviceTimeSec > 0 && Number.isFinite(serviceTimeSec)
      ? data.maxConcurrentIn / serviceTimeSec
      : Number.POSITIVE_INFINITY

  const maxTpsDependency =
    depLatencySec > 0 && Number.isFinite(depLatencySec)
      ? data.depPool / depLatencySec
      : Number.POSITIVE_INFINITY

  const maxTpsOverall = Math.min(maxTpsInbound, maxTpsDependency)

  let clientIdealTps: number | null = null
  let effectiveTps = 0

  if (data.inputMode === 'tps') {
    const incomingTps = Math.max(0, data.incomingTps)
    effectiveTps = Math.min(incomingTps, maxTpsOverall)
  } else {
    // concurrency / hammer mode
    if (serviceTimeSec > 0 && Number.isFinite(serviceTimeSec)) {
      clientIdealTps = data.clientConcurrency / serviceTimeSec
    } else {
      clientIdealTps = Number.POSITIVE_INFINITY
    }
    effectiveTps = Math.min(clientIdealTps, maxTpsOverall)
  }

  const utilInbound =
    maxTpsInbound > 0 && Number.isFinite(maxTpsInbound)
      ? effectiveTps / maxTpsInbound
      : 0

  const utilDep =
    maxTpsDependency > 0 && Number.isFinite(maxTpsDependency)
      ? effectiveTps / maxTpsDependency
      : 0

  const inboundStatus = classifyUtilisation(utilInbound)
  const depStatus = classifyUtilisation(utilDep)

  return {
    serviceTimeMs,
    serviceTimeSec,
    depLatencySec,
    maxTpsInbound,
    maxTpsDependency,
    maxTpsOverall,
    clientIdealTps,
    effectiveTps,
    utilInbound,
    utilDep,
    inboundStatus,
    depStatus,
  }
}

export function classifyUtilisation(value: number): UtilisationStatus {
  if (!Number.isFinite(value) || value <= 0) return 'ok'
  if (value < 0.7) return 'ok'
  if (value <= 0.9) return 'high'
  return 'overloaded'
}

function msToSeconds(ms: number): number {
  return ms / 1000
}

export function createDefaultPerfNodeData(name: string): PerfNodeData {
  return {
    name,
    inputMode: 'tps',
    incomingTps: 100,
    clientConcurrency: 50,
    transport: 'tomcat',
    maxConcurrentIn: 50,
    internalLatencyMs: 20,
    depLatencyMs: 80,
    depPool: 50,
    httpClient: 'httpclient',
    dbEnabled: false,
  }
}
