import { ConnectionContractError } from '../connection/contracts';

export const DEBUG_ACTIONS = Object.freeze([
  { label: 'Workers', tool: 'runtime_list_workers', needsWorker: false },
  { label: 'Logs', tool: 'runtime_tail_worker_logs', needsWorker: true },
  { label: 'Versions', tool: 'runtime_get_worker_version', needsWorker: true },
  { label: 'Inspect', tool: 'runtime_inspect_worker', needsWorker: true },
  { label: 'Runtime drift', tool: 'runtime_diff_swarm_runtime', needsWorker: false },
  { label: 'Control plane', tool: 'runtime_control_plane_status', needsWorker: false },
  { label: 'Rabbit topology', tool: 'runtime_rabbit_topology_snapshot', needsWorker: false },
  { label: 'Timeline', tool: 'runtime_swarm_timeline', needsWorker: false },
  { label: 'Manifest', tool: 'runtime_manifest_validate', needsWorker: false },
  { label: 'Cleanup plan', tool: 'runtime_cleanup_plan', needsWorker: false },
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
