import { ConnectionContractError } from '../connection/contracts';

export const SWARM_OPERATIONS = Object.freeze({
  START: 'START',
  STOP: 'STOP',
  REMOVE: 'REMOVE',
} as const);

export type SwarmOperation = typeof SWARM_OPERATIONS[keyof typeof SWARM_OPERATIONS];
export const MAX_SWARM_ACTIONS = 1000;
export const MAX_SWARM_ID_LENGTH = 512;

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

export function primaryActionsForSwarms(value: unknown): Readonly<Record<string, SwarmOperation>> {
  if (!Array.isArray(value)) return Object.freeze({});
  const actions: Record<string, SwarmOperation> = {};
  for (const item of value.slice(0, MAX_SWARM_ACTIONS)) {
    if (!item || typeof item !== 'object' || Array.isArray(item)) continue;
    const record = item as Record<string, unknown>;
    if (typeof record.id !== 'string' || typeof record.status !== 'string') continue;
    const id = record.id.trim();
    if (!id || id.length > MAX_SWARM_ID_LENGTH) continue;
    const action = primaryOperationForStatus(record.status);
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
