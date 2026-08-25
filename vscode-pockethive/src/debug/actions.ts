import { ConnectionContractError } from '../connection/contracts';

export const DEBUG_ACTION_LABELS = Object.freeze({
  WORKERS: 'Workers',
  LOGS: 'Logs',
  VERSION: 'Version',
  INSPECT: 'Inspect',
  ASSESSMENT: 'Runtime assessment',
  RABBIT_TOPOLOGY: 'Rabbit topology',
  TIMELINE: 'Timeline',
  CLEANUP_PLAN: 'Cleanup plan',
} as const);

export const DEBUG_ACTIONS = Object.freeze([
  { label: DEBUG_ACTION_LABELS.WORKERS, tool: 'runtime_list_workers', needsWorker: false },
  { label: DEBUG_ACTION_LABELS.LOGS, tool: 'runtime_tail_worker_logs', needsWorker: true },
  { label: DEBUG_ACTION_LABELS.VERSION, tool: 'runtime_get_worker_version', needsWorker: true },
  { label: DEBUG_ACTION_LABELS.INSPECT, tool: 'runtime_inspect_worker', needsWorker: true },
  { label: DEBUG_ACTION_LABELS.ASSESSMENT, tool: 'runtime_assess_swarm', needsWorker: false },
  { label: DEBUG_ACTION_LABELS.RABBIT_TOPOLOGY, tool: 'runtime_rabbit_topology_snapshot', needsWorker: false },
  { label: DEBUG_ACTION_LABELS.TIMELINE, tool: 'runtime_swarm_timeline', needsWorker: false },
  { label: DEBUG_ACTION_LABELS.CLEANUP_PLAN, tool: 'runtime_cleanup_plan', needsWorker: false },
] as const);

export function debugToolCall(
  label: string,
  swarmId: string | undefined,
  runtimeId: string | undefined,
  tailLines?: number,
): { name: string; arguments: Record<string, unknown> } {
  const action = DEBUG_ACTIONS.find(item => item.label === label);
  if (!action) throw new ConnectionContractError('DEBUG_ACTION_UNKNOWN', label);
  if (!swarmId?.trim()) throw new ConnectionContractError('DEBUG_SWARM_REQUIRED', label);
  if (action.needsWorker && !runtimeId?.trim()) {
    throw new ConnectionContractError('DEBUG_WORKER_REQUIRED', label);
  }
  const args: Record<string, unknown> = { swarmId: swarmId.trim() };
  if (action.needsWorker) args.runtimeId = runtimeId!.trim();
  if (action.tool === 'runtime_tail_worker_logs') {
    if (!Number.isInteger(tailLines) || tailLines! < 1 || tailLines! > 1000) {
      throw new ConnectionContractError('DEBUG_TAIL_LINES_INVALID', label);
    }
    args.tailLines = tailLines;
  }
  return { name: action.tool, arguments: args };
}

export function exactWorkerRuntimeId(value: unknown, instance: string): string {
  const normalizedInstance = instance.trim();
  if (!normalizedInstance) {
    throw new ConnectionContractError('DEBUG_WORKER_INSTANCE_REQUIRED', 'Worker instance is required');
  }
  if (!value || typeof value !== 'object' || !Array.isArray((value as Record<string, unknown>).workers)) {
    throw new ConnectionContractError(
      'DEBUG_WORKER_LIST_INVALID',
      'runtime_list_workers did not return its canonical workers collection',
    );
  }
  const matches = ((value as Record<string, unknown>).workers as unknown[]).filter(item => {
    if (!item || typeof item !== 'object') return false;
    return (item as Record<string, unknown>).instance === normalizedInstance;
  });
  if (matches.length === 0) {
    throw new ConnectionContractError(
      'DEBUG_WORKER_NOT_FOUND',
      `No exact runtime target was reported for worker instance ${normalizedInstance}`,
    );
  }
  if (matches.length > 1) {
    throw new ConnectionContractError(
      'DEBUG_WORKER_AMBIGUOUS',
      `Multiple runtime targets were reported for worker instance ${normalizedInstance}`,
    );
  }
  const runtimeId = (matches[0] as Record<string, unknown>).runtimeId;
  if (typeof runtimeId !== 'string' || !runtimeId.trim()) {
    throw new ConnectionContractError(
      'DEBUG_WORKER_RUNTIME_ID_MISSING',
      `Worker instance ${normalizedInstance} did not report an exact runtimeId`,
    );
  }
  return runtimeId.trim();
}
