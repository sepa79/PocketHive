const ORCHESTRATOR_BASE = '/orchestrator/api'

export type RuntimeResource = {
  runtimeId: string | null
  runtimeType: string | null
  name: string | null
  resourceKind: string | null
  swarmId: string | null
  runId: string | null
  role: string | null
  instance: string | null
  logicalName: string | null
  state: string | null
  running: boolean
  image: string | null
  imageTag: string | null
  imageDigest: string | null
  declaredVersion: string | null
  reportedVersion: string | null
  createdAt: string | null
  startedAt: string | null
  finishedAt: string | null
  registryStatus: string | null
  labels: Record<string, string> | null
}

export type RuntimeBlockedResource = {
  runtimeId: string | null
  runtimeType: string | null
  name: string | null
  state: string | null
  reason: string | null
  labels: Record<string, string> | null
}

export type RuntimeResourceListResponse = {
  computeAdapter: string | null
  swarmId: string | null
  runId: string | null
  counts: { workers: number; managers: number; blocked: number } | null
  workers: RuntimeResource[]
  managers: RuntimeResource[]
  blocked: RuntimeBlockedResource[]
}

export type RuntimeTarget = {
  runtimeId: string | null
  runtimeType: string | null
  name: string | null
  resourceKind: string | null
  swarmId: string | null
  runId: string | null
  role: string | null
  instance: string | null
  logicalName: string | null
  state: string | null
  image: string | null
  labels: Record<string, string> | null
}

export type RuntimeLogsResponse = {
  target: RuntimeTarget | null
  tailLines: number
  since: string | null
  redacted: boolean
  lineCount: number
  logs: string
}

export type RuntimeVersionResponse = {
  target: RuntimeTarget | null
  declaredVersion: string | null
  image: string | null
  imageTag: string | null
  imageDigest: string | null
  reportedVersion: string | null
  reportedVersionSource: string | null
}

export type RuntimeInspectResponse = {
  target: RuntimeTarget | null
  source: Record<string, unknown> | null
  state: Record<string, unknown> | null
  createdAt: string | null
  restartCount: number | null
  restartPolicy: string | null
  mounts: Array<Record<string, unknown>>
  networks: string[]
}

export type RabbitSourceSummary = {
  available: boolean
  reason: string | null
  error: string | null
}

export type RabbitQueueSnapshot = {
  name: string
  present: boolean
  messages: number | null
  consumers: number | null
  state: string | null
  durable: boolean | null
  autoDelete: boolean | null
  diagnosticOnly: boolean | null
  reason: string | null
}

export type RabbitExchangeSnapshot = {
  name: string
  present: boolean
  type: string | null
  durable: boolean | null
  autoDelete: boolean | null
  reason: string | null
}

export type RabbitTopologySnapshot = {
  computeAdapter: string | null
  swarmId: string | null
  runId: string | null
  manifest: RabbitSourceSummary | null
  rabbit: RabbitSourceSummary | null
  exactOnly: boolean
  queues: RabbitQueueSnapshot[]
  exchanges: RabbitExchangeSnapshot[]
  unmanagedDiagnostics: RabbitQueueSnapshot[]
}

type RuntimeTargetInput = {
  swarmId: string
  runId?: string | null
  runtimeId: string
  resourceKind?: string | null
  tailLines?: number
}

async function readErrorMessage(response: Response): Promise<string> {
  try {
    const contentType = response.headers.get('content-type') ?? ''
    if (contentType.includes('application/json')) {
      const payload = (await response.json()) as unknown
      if (payload && typeof payload === 'object' && 'message' in payload) {
        const message = (payload as Record<string, unknown>).message
        if (typeof message === 'string' && message.trim().length > 0) {
          return message.trim()
        }
      }
      return JSON.stringify(payload)
    }
    const text = await response.text()
    return text.trim().length > 0 ? text.trim() : `${response.status} ${response.statusText}`
  } catch {
    return `${response.status} ${response.statusText}`
  }
}

async function postJson<T>(path: string, body: Record<string, unknown>): Promise<T> {
  const response = await fetch(`${ORCHESTRATOR_BASE}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
    body: JSON.stringify(body),
  })
  if (!response.ok) {
    throw new Error(await readErrorMessage(response))
  }
  return (await response.json()) as T
}

function swarmRuntimeBody(swarmId: string, runId?: string | null, extra?: Record<string, unknown>) {
  return {
    swarmId,
    ...(runId ? { runId } : {}),
    ...(extra ?? {}),
  }
}

export function listRuntimeResources(swarmId: string, runId?: string | null) {
  return postJson<RuntimeResourceListResponse>(
    '/runtime/debug/resources/list',
    swarmRuntimeBody(swarmId, runId, { includeManagers: true }),
  )
}

export function getRuntimeLogs(input: RuntimeTargetInput) {
  return postJson<RuntimeLogsResponse>(
    '/runtime/debug/resources/logs',
    swarmRuntimeBody(input.swarmId, input.runId, {
      runtimeId: input.runtimeId,
      resourceKind: input.resourceKind,
      tailLines: input.tailLines ?? 200,
    }),
  )
}

export function getRuntimeVersion(input: RuntimeTargetInput) {
  return postJson<RuntimeVersionResponse>(
    '/runtime/debug/resources/version',
    swarmRuntimeBody(input.swarmId, input.runId, {
      runtimeId: input.runtimeId,
      resourceKind: input.resourceKind,
    }),
  )
}

export function inspectRuntime(input: RuntimeTargetInput) {
  return postJson<RuntimeInspectResponse>(
    '/runtime/debug/resources/inspect',
    swarmRuntimeBody(input.swarmId, input.runId, {
      runtimeId: input.runtimeId,
      resourceKind: input.resourceKind,
    }),
  )
}

export function getRabbitTopology(swarmId: string, runId?: string | null) {
  return postJson<RabbitTopologySnapshot>('/runtime/debug/rabbit/topology', swarmRuntimeBody(swarmId, runId))
}
