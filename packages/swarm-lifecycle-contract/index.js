// Generated from docs/spec/swarm-lifecycle.schema.json. Do not edit.

export const lifecycleEnumValues = Object.freeze({
  "RuntimeIntent": [
    "PRESENT",
    "ABSENT"
  ],
  "WorkloadIntent": [
    "RUNNING",
    "STOPPED"
  ],
  "ControllerState": [
    "PROVISIONING",
    "READY",
    "FAILED",
    "UNKNOWN"
  ],
  "WorkloadState": [
    "UNAVAILABLE",
    "STARTING",
    "RUNNING",
    "STOPPING",
    "STOPPED",
    "UNKNOWN"
  ],
  "Health": [
    "HEALTHY",
    "DEGRADED",
    "FAILED",
    "UNKNOWN"
  ],
  "RuntimeResourceState": [
    "PRESENT",
    "REMOVING",
    "ABSENT",
    "UNKNOWN"
  ],
  "OperationType": [
    "CREATE",
    "START",
    "STOP",
    "REMOVE",
    "CONFIG_UPDATE"
  ],
  "OperationState": [
    "ACCEPTED",
    "DISPATCHED",
    "SUCCEEDED",
    "REJECTED",
    "FAILED",
    "TIMED_OUT"
  ],
  "TerminalStatus": [
    "Succeeded",
    "Rejected",
    "Failed",
    "TimedOut"
  ],
  "RemoveResourceType": [
    "CONTROLLER_RUNTIME",
    "WORKER_RUNTIME",
    "RABBIT_QUEUE",
    "RABBIT_EXCHANGE",
    "RABBIT_BINDING",
    "RUNTIME_DIRECTORY",
    "REGISTRY_ENTRY",
    "TERMINAL_EVIDENCE"
  ]
})

export function parseControlResponse(value) {
  const record = requiredRecord(value, 'ControlResponse')
  return Object.freeze({
    correlationId: requiredString(record.correlationId, 'correlationId'),
    idempotencyKey: requiredString(record.idempotencyKey, 'idempotencyKey'),
    operationUrl: requiredString(record.operationUrl, 'operationUrl'),
    outcomeTopic: requiredString(record.outcomeTopic, 'outcomeTopic'),
    timeoutMs: requiredPositiveInteger(record.timeoutMs, 'timeoutMs'),
  })
}

export function parseOperationState(value) {
  return requiredEnum(value, 'OperationState')
}

export function parseTerminalStatus(value) {
  return requiredEnum(value, 'TerminalStatus')
}

export function parseControllerState(value) {
  return requiredEnum(value, 'ControllerState')
}

export function parseWorkloadState(value) {
  return requiredEnum(value, 'WorkloadState')
}

export function parseLifecycleAxes(value) {
  const record = requiredRecord(value, 'SwarmStateView')
  return Object.freeze({
    controllerState: requiredEnum(record.controllerState, 'ControllerState'),
    workloadState: requiredEnum(record.workloadState, 'WorkloadState'),
    health: requiredEnum(record.health, 'Health'),
    observationStale: requiredBoolean(record.observationStale, 'observationStale'),
  })
}

function requiredRecord(value, name) {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw new Error(name + ' must be an object')
  }
  return value
}

function requiredString(value, field) {
  if (typeof value !== 'string' || value.trim().length === 0) {
    throw new Error('ControlResponse.' + field + ' must be a non-empty string')
  }
  return value.trim()
}

function requiredPositiveInteger(value, field) {
  if (!Number.isInteger(value) || value < 1) {
    throw new Error('ControlResponse.' + field + ' must be a positive integer')
  }
  return value
}

function requiredBoolean(value, field) {
  if (typeof value !== 'boolean') throw new Error('SwarmStateView.' + field + ' must be a boolean')
  return value
}

function requiredEnum(value, name) {
  if (typeof value !== 'string' || !lifecycleEnumValues[name].includes(value)) {
    throw new Error(name + " has unsupported value '" + String(value) + "'")
  }
  return value
}
