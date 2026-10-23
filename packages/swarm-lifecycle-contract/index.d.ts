// Generated from docs/spec/swarm-lifecycle.schema.json and control-events.schema.json. Do not edit.

export type NonEmptyString = string

export type NullableNonEmptyString = NonEmptyString | null

export type CanonicalNonEmptyString = string

export type NullableCanonicalNonEmptyString = CanonicalNonEmptyString | null

export type RuntimeIntent = "PRESENT" | "ABSENT"

export type WorkloadIntent = "RUNNING" | "STOPPED" | "UNKNOWN"

export type ControllerState = "PROVISIONING" | "READY" | "FAILED" | "UNKNOWN"

export type WorkloadState = "UNAVAILABLE" | "STARTING" | "RUNNING" | "STOPPING" | "STOPPED" | "UNKNOWN"

export type Health = "HEALTHY" | "DEGRADED" | "FAILED" | "UNKNOWN"

export type RuntimeResourceState = "PRESENT" | "REMOVING" | "ABSENT" | "UNKNOWN"

export type OperationType = "CREATE" | "START" | "STOP" | "REMOVE" | "CONFIG_UPDATE"

export type OperationState = "ACCEPTED" | "DISPATCHED" | "SUCCEEDED" | "REJECTED" | "FAILED" | "TIMED_OUT"

export type TerminalStatus = "Succeeded" | "Rejected" | "Failed" | "TimedOut"

export type Target = {
  readonly "role": NonEmptyString
  readonly "instance": NonEmptyString
}

export type TerminalResult = {
  readonly "status": TerminalStatus
  readonly "retryable": boolean
  readonly "context": {
    readonly [key: string]: unknown
  }
}

export type RuntimeMetadata = {
  readonly "templateId": NonEmptyString
  readonly "runId": NonEmptyString
  readonly "containerId"?: NullableNonEmptyString
  readonly "image"?: NullableNonEmptyString
  readonly "stackName"?: NullableNonEmptyString
}

export type ControlResponse = {
  readonly "correlationId": NonEmptyString
  readonly "idempotencyKey": NonEmptyString
  readonly "operationUrl": NonEmptyString
  readonly "outcomeTopic": NonEmptyString
  readonly "timeoutMs": number
}

export type ControlRequest = {
  readonly "idempotencyKey": NonEmptyString
}

export type SwarmCreateRequest = {
  readonly "templateId": CanonicalNonEmptyString
  readonly "idempotencyKey": CanonicalNonEmptyString
  readonly "autoPullImages": boolean
  readonly "sutId": NullableCanonicalNonEmptyString
  readonly "variablesProfileId": NullableCanonicalNonEmptyString
  readonly "networkMode": "DIRECT" | "PROXIED"
  readonly "networkProfileId": NullableCanonicalNonEmptyString
}

export type SwarmOperation = {
  readonly "swarmId": NonEmptyString
  readonly "type": OperationType
  readonly "target": Target
  readonly "runtime": RuntimeMetadata
  readonly "correlationId": NonEmptyString
  readonly "idempotencyKey": NonEmptyString
  readonly "state": OperationState
  readonly "createdAt": string
  readonly "dispatchedAt": string | null
  readonly "deadlineAt": string
  readonly "completedAt": string | null
  readonly "terminalResult": TerminalResult | null
}

export type WorkerSummary = {
  readonly "role": NonEmptyString
  readonly "instance": NonEmptyString
  readonly "image": NullableNonEmptyString
}

export type SwarmStateView = {
  readonly "id": NonEmptyString
  readonly "runId": NonEmptyString
  readonly "runtimeIntent": RuntimeIntent
  readonly "workloadIntent": WorkloadIntent
  readonly "controllerState": ControllerState
  readonly "workloadState": WorkloadState
  readonly "health": Health
  readonly "runtimeResourceState": RuntimeResourceState
  readonly "observedAt": string | null
  readonly "observationStale": boolean
  readonly "activeOperation": SwarmOperation & {
    readonly "state"?: "ACCEPTED" | "DISPATCHED"
  } | null
  readonly "templateId": NullableNonEmptyString
  readonly "controllerImage": NullableNonEmptyString
  readonly "bees": ReadonlyArray<WorkerSummary>
  readonly "observation": Readonly<Record<string, unknown>>
}

export type RemoveResourceType = "CONTROLLER_RUNTIME" | "WORKER_RUNTIME" | "RABBIT_QUEUE" | "RABBIT_EXCHANGE" | "RABBIT_BINDING" | "WORK_RESOURCE" | "NETWORK_BINDING" | "RUNTIME_DIRECTORY" | "REGISTRY_ENTRY" | "TERMINAL_EVIDENCE"

export type ResourcePlane = "CONTROL" | "WORK" | "NONE"

export type RemoveResource = {
  readonly "type": RemoveResourceType
  readonly "id": NonEmptyString
  readonly "plane": ResourcePlane
}

export type RemoveError = {
  readonly "code": NonEmptyString
  readonly "message": NonEmptyString
  readonly "resource": RemoveResource | null
}

export type RemoveRequest = {
  readonly "schema": "pockethive/swarm-remove-request/v1"
  readonly "swarmId": NonEmptyString
  readonly "runId": NonEmptyString
  readonly "controllerInstance": NonEmptyString
  readonly "correlationId": NonEmptyString
  readonly "idempotencyKey": NonEmptyString
  readonly "requestedAt": string
}

export type RemoveResult = {
  readonly "schema": "pockethive/swarm-remove-result/v2"
  readonly "swarmId": NonEmptyString
  readonly "runId": NonEmptyString
  readonly "controllerInstance": NonEmptyString
  readonly "correlationId": NonEmptyString
  readonly "idempotencyKey": NonEmptyString
  readonly "status": "Succeeded" | "Failed"
  readonly "retryable": boolean
  readonly "targetResources": ReadonlyArray<RemoveResource>
  readonly "errors": ReadonlyArray<RemoveError>
  readonly "completedAt": string
}

export type IoInputState = "ok" | "out-of-data" | "backpressure" | "upstream-error" | "unknown"

export type IoOutputState = "ok" | "blocked" | "throttled" | "downstream-error" | "unknown"

export type IoState = {
  readonly "input": IoInputState
  readonly "output": IoOutputState
  readonly "context"?: {
    readonly [key: string]: unknown
  }
}

export type MetricIoState = {
  readonly "work"?: IoState
  readonly "filesystem"?: IoState
}

export type WorkerObservation = {
  readonly "role": NonEmptyString
  readonly "instance": NonEmptyString
  readonly "enabled": boolean
  readonly "lastSeenAt": string
  readonly "stale": boolean
  readonly "tps": number
  readonly "runtime"?: RuntimeMetadata
  readonly "config"?: {
    readonly [key: string]: unknown
  }
  readonly "ioState": MetricIoState
}

export type ControllerStatusFullContext = {
  readonly "controllerState": ControllerState
  readonly "workloadState": WorkloadState
  readonly "health": Health
  readonly "startupReady": boolean
  readonly "startupArtifactSha256": NonEmptyString
  readonly "watermarkAt": string
  readonly "expectedWorkers": ReadonlyArray<Target>
  readonly "workers": ReadonlyArray<WorkerObservation>
  readonly "bindings"?: {
    readonly [key: string]: unknown
  }
  readonly "swarmDiagnostics"?: {
    readonly [key: string]: unknown
  }
  readonly "scenario"?: {
    readonly [key: string]: unknown
  }
  readonly "bufferGuard"?: {
    readonly [key: string]: unknown
  }
  readonly "location"?: NonEmptyString
  readonly "sutId"?: NullableNonEmptyString
  readonly "networkMode"?: "DIRECT" | "PROXIED"
  readonly "networkProfileId"?: NullableNonEmptyString
}

export type ControllerStatusDeltaContext = {
  readonly "controllerState": ControllerState
  readonly "workloadState": WorkloadState
  readonly "health": Health
  readonly "startupReady": boolean
  readonly "watermarkAt": string
  readonly "swarmDiagnostics"?: {
    readonly [key: string]: unknown
  }
  readonly "scenario"?: {
    readonly [key: string]: unknown
  }
  readonly "bufferGuard"?: {
    readonly [key: string]: unknown
  }
  readonly "location"?: NonEmptyString
}

export type LifecycleAxes = Readonly<Pick<SwarmStateView,
  'controllerState' | 'workloadState' | 'health' | 'observationStale'>>

export const lifecycleEnumValues: Readonly<{
  RuntimeIntent: readonly RuntimeIntent[]
  WorkloadIntent: readonly WorkloadIntent[]
  ControllerState: readonly ControllerState[]
  WorkloadState: readonly WorkloadState[]
  Health: readonly Health[]
  RuntimeResourceState: readonly RuntimeResourceState[]
  OperationType: readonly OperationType[]
  OperationState: readonly OperationState[]
  TerminalStatus: readonly TerminalStatus[]
  RemoveResourceType: readonly RemoveResourceType[]
  ResourcePlane: readonly ResourcePlane[]
}>

export function parseControlResponse(value: unknown): Readonly<ControlResponse>
export function parseOperationState(value: unknown): OperationState
export function parseTerminalStatus(value: unknown): TerminalStatus
export function parseControllerState(value: unknown): ControllerState
export function parseWorkloadState(value: unknown): WorkloadState
export function parseLifecycleAxes(value: unknown): LifecycleAxes
