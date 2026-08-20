import { ConnectionContractError } from '../connection/contracts';

export const SWARM_OPERATIONS = Object.freeze({
  START: 'START',
  STOP: 'STOP',
  REMOVE: 'REMOVE',
} as const);

export type SwarmOperation = typeof SWARM_OPERATIONS[keyof typeof SWARM_OPERATIONS];
export const MAX_SWARM_ACTIONS = 1000;
export const MAX_SWARM_ID_LENGTH = 512;

const CONTROLLER_STATES = new Set(['PROVISIONING', 'READY', 'FAILED', 'UNKNOWN']);
const WORKLOAD_STATES = new Set(['UNAVAILABLE', 'STARTING', 'RUNNING', 'STOPPING', 'STOPPED', 'UNKNOWN']);
const RUNTIME_RESOURCE_STATES = new Set(['PRESENT', 'REMOVING', 'ABSENT', 'UNKNOWN']);
const ACTIVE_OPERATION_STATES = new Set(['ACCEPTED', 'DISPATCHED']);

const LIFECYCLE_TOOLS: Readonly<Record<SwarmOperation, string>> = Object.freeze({
  [SWARM_OPERATIONS.START]: 'swarm_start',
  [SWARM_OPERATIONS.STOP]: 'swarm_stop',
  [SWARM_OPERATIONS.REMOVE]: 'swarm_remove',
});

const PRIMARY_OPERATIONS_BY_STATUS: Readonly<Record<string, SwarmOperation | undefined>> = Object.freeze({
  RUNNING: SWARM_OPERATIONS.STOP,
  READY: SWARM_OPERATIONS.START,
  STOPPED: SWARM_OPERATIONS.START,
});

export function primaryOperationForStatus(status: string): SwarmOperation | undefined {
  return PRIMARY_OPERATIONS_BY_STATUS[status.trim().toUpperCase()];
}

export function swarmDisplayStatus(value: unknown): string {
  const record = objectValue(value);
  if (!record) return 'UNKNOWN';
  const runtimeResourceState = enumField(record.runtimeResourceState, RUNTIME_RESOURCE_STATES);
  if (runtimeResourceState === 'REMOVING') return 'REMOVING';
  const controllerState = enumField(record.controllerState, CONTROLLER_STATES);
  const workloadState = enumField(record.workloadState, WORKLOAD_STATES);
  if (controllerState === 'PROVISIONING' || controllerState === 'FAILED') return controllerState;
  if (workloadState === 'RUNNING'
      || workloadState === 'STARTING'
      || workloadState === 'STOPPING'
      || workloadState === 'UNAVAILABLE') {
    return workloadState;
  }
  if (workloadState === 'STOPPED') return controllerState === 'READY' ? 'READY' : 'STOPPED';
  return controllerState ?? workloadState ?? 'UNKNOWN';
}

export function primaryOperationForSwarm(value: unknown): SwarmOperation | undefined {
  const record = objectValue(value);
  if (!record) return undefined;
  if (enumField(record.runtimeResourceState, RUNTIME_RESOURCE_STATES) === 'REMOVING') return undefined;
  if (hasActiveLifecycleOperation(record.activeOperation)) return undefined;
  if (record.observationStale !== false) return undefined;
  if (enumField(record.controllerState, CONTROLLER_STATES) !== 'READY') return undefined;
  const workloadState = enumField(record.workloadState, WORKLOAD_STATES);
  if (workloadState === 'RUNNING') return SWARM_OPERATIONS.STOP;
  if (workloadState === 'STOPPED') return SWARM_OPERATIONS.START;
  return undefined;
}

export function primaryActionsForSwarms(value: unknown): Readonly<Record<string, SwarmOperation>> {
  if (!Array.isArray(value)) return Object.freeze({});
  const actions: Record<string, SwarmOperation> = {};
  for (const item of value.slice(0, MAX_SWARM_ACTIONS)) {
    const record = objectValue(item);
    if (!record || typeof record.id !== 'string') continue;
    const id = record.id.trim();
    if (!id || id.length > MAX_SWARM_ID_LENGTH) continue;
    const action = primaryOperationForSwarm(record);
    if (action) actions[id] = action;
  }
  return Object.freeze(actions);
}

export function swarmIdsForOperation(value: unknown, operation: SwarmOperation): string[] {
  return Object.entries(primaryActionsForSwarms(value))
    .filter(([, action]) => action === operation)
    .map(([id]) => id);
}

export function lifecycleToolCall(
  operation: SwarmOperation,
  swarmId: string,
  idempotencyKey: string,
): { readonly name: string; readonly arguments: Record<string, unknown> } {
  const target = required(swarmId, 'SWARM_ID_REQUIRED');
  const key = required(idempotencyKey, 'IDEMPOTENCY_KEY_REQUIRED');
  return {
    name: LIFECYCLE_TOOLS[operation],
    arguments: { swarmId: target, idempotencyKey: key },
  };
}

function required(value: string, code: string): string {
  const result = value.trim();
  if (!result) throw new ConnectionContractError(code, code);
  return result;
}

function objectValue(value: unknown): Record<string, unknown> | undefined {
  return value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : undefined;
}

function enumField(value: unknown, allowed: ReadonlySet<string>): string | undefined {
  return typeof value === 'string' && allowed.has(value) ? value : undefined;
}

function hasActiveLifecycleOperation(value: unknown): boolean {
  const record = objectValue(value);
  return record !== undefined && enumField(record.state, ACTIVE_OPERATION_STATES) !== undefined;
}
