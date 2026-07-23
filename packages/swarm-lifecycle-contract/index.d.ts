// Generated from docs/spec/swarm-lifecycle.schema.json. Do not edit.

export type NonEmptyString = string

export type NullableNonEmptyString = NonEmptyString | null

export type RuntimeIntent = "PRESENT" | "ABSENT"

export type WorkloadIntent = "RUNNING" | "STOPPED"

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

export type ControlResponse = {
  readonly "correlationId": NonEmptyString
  readonly "idempotencyKey": NonEmptyString
  readonly "operationUrl": NonEmptyString
  readonly "outcomeTopic": NonEmptyString
  readonly "timeoutMs": number
}

export type ControlRequest = {
  readonly "idempotencyKey": NonEmptyString
  readonly "notes": NullableNonEmptyString
}

export type SwarmCreateRequest = {
  readonly "templateId": NonEmptyString
  readonly "idempotencyKey": NonEmptyString
  readonly "notes": NullableNonEmptyString
  readonly "autoPullImages": boolean | null
  readonly "sutId": NullableNonEmptyString
  readonly "variablesProfileId": NullableNonEmptyString
  readonly "networkMode": "DIRECT" | "PROXIED"
  readonly "networkProfileId": NullableNonEmptyString
}

export type SwarmOperation = {
  readonly "swarmId": NonEmptyString
  readonly "type": OperationType
  readonly "target": Target
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
  readonly "observation": Readonly<Record<string, unknown>> | null
}

export type RemoveResourceType = "CONTROLLER_RUNTIME" | "WORKER_RUNTIME" | "RABBIT_QUEUE" | "RABBIT_EXCHANGE" | "RABBIT_BINDING" | "RUNTIME_DIRECTORY" | "REGISTRY_ENTRY" | "TERMINAL_EVIDENCE"

export type RemoveResource = {
  readonly "type": RemoveResourceType
  readonly "id": NonEmptyString
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
}>

export function parseControlResponse(value: unknown): Readonly<ControlResponse>
export function parseOperationState(value: unknown): OperationState
export function parseTerminalStatus(value: unknown): TerminalStatus
export function parseControllerState(value: unknown): ControllerState
export function parseWorkloadState(value: unknown): WorkloadState
export function parseLifecycleAxes(value: unknown): LifecycleAxes
