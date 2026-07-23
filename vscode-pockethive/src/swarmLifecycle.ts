export type SwarmLifecycleAction = 'start' | 'stop';

const OUTCOME_TYPE_BY_ACTION: Record<SwarmLifecycleAction, string> = {
  start: 'swarm-start',
  stop: 'swarm-stop',
};

export type SwarmLifecycleState = {
  controllerState: string;
  workloadState: string;
  health: string;
  observationStale: boolean;
};

export type SwarmLifecycleOutcome = {
  type: string;
  status: string;
  timestamp?: string;
  correlationId?: string;
  retryable?: boolean;
  context?: Record<string, unknown>;
};

export type SwarmReadyResult = {
  ready: boolean;
  controllerState: string;
  workloadState: string;
};

export function summarizeSwarmLifecycle(detail: unknown): SwarmLifecycleState {
  if (!detail || typeof detail !== 'object') {
    throw new Error('Swarm state must be an object');
  }
  const record = detail as Record<string, unknown>;
  return {
    controllerState: requiredString(record.controllerState, 'controllerState'),
    workloadState: requiredString(record.workloadState, 'workloadState'),
    health: requiredString(record.health, 'health'),
    observationStale: requiredBoolean(record.observationStale, 'observationStale'),
  };
}

export function isExpectedLifecycleState(action: SwarmLifecycleAction, state: SwarmLifecycleState): boolean {
  if (action === 'start') {
    return state.workloadState === 'RUNNING' && state.observationStale === false;
  }
  return state.workloadState === 'STOPPED' && state.observationStale === false;
}

export function shouldWaitForStartReadiness(state: SwarmLifecycleState): boolean {
  return state.controllerState !== 'READY'
    || state.workloadState !== 'STOPPED'
    || state.observationStale;
}

export function formatLifecycleState(state: SwarmLifecycleState): string {
  const parts = [`controller ${state.controllerState}`, `workload ${state.workloadState}`, `health ${state.health}`];
  if (state.observationStale) parts.push('observation stale');
  return parts.join(' / ');
}

export function extractLifecycleCorrelationId(response: unknown): string | undefined {
  const record = objectValue(response);
  if (!record) return undefined;
  return stringValue(record.correlationId);
}

export function findLifecycleOutcome(
  journal: unknown,
  action: SwarmLifecycleAction,
  correlationId?: string
): SwarmLifecycleOutcome | undefined {
  const expectedType = OUTCOME_TYPE_BY_ACTION[action];
  const entries = journalEntries(journal)
    .map((entry, index) => ({ entry, index }))
    .sort((left, right) => compareEntryTimeDesc(left.entry, right.entry) || left.index - right.index);

  const matching = entries
    .map(({ entry }) => outcomeFromEntry(entry))
    .filter((outcome): outcome is SwarmLifecycleOutcome => Boolean(outcome && outcome.type === expectedType));

  if (correlationId) {
    const exact = matching.find(outcome => outcome.correlationId === correlationId);
    if (exact) return exact;
  }
  return matching[0];
}

export function formatLifecycleOutcome(outcome: SwarmLifecycleOutcome): string {
  const parts = [outcome.status];
  const context = formatLifecycleContext(outcome.context);
  if (context) parts.push(`(${context})`);
  if (typeof outcome.retryable === 'boolean') parts.push(outcome.retryable ? 'retryable' : 'not retryable');
  return parts.join(' ');
}

export function summarizeReadyResult(result: unknown): SwarmReadyResult {
  const record = objectValue(result);
  if (!record) throw new Error('Swarm readiness result must be an object');
  return {
    ready: requiredBoolean(record.ready, 'ready'),
    controllerState: requiredString(record.controllerState, 'controllerState'),
    workloadState: requiredString(record.workloadState, 'workloadState'),
  };
}

export function formatReadyResult(result: SwarmReadyResult): string {
  const parts: string[] = [];
  parts.push(result.ready ? 'ready' : 'not ready');
  parts.push(`controller ${result.controllerState}`);
  parts.push(`workload ${result.workloadState}`);
  return parts.join(' / ') || 'not ready';
}

function journalEntries(value: unknown): Record<string, unknown>[] {
  if (Array.isArray(value)) {
    return value.map(objectValue).filter((entry): entry is Record<string, unknown> => Boolean(entry));
  }
  const record = objectValue(value);
  const items = record ? record.items : undefined;
  if (!Array.isArray(items)) return [];
  return items.map(objectValue).filter((entry): entry is Record<string, unknown> => Boolean(entry));
}

function outcomeFromEntry(entry: Record<string, unknown>): SwarmLifecycleOutcome | undefined {
  const envelope = objectValue(entry.envelope);
  const data = objectValue(entry.data) ?? objectValue(envelope?.data);
  const kind = stringValue(entry.kind) ?? stringValue(envelope?.kind);
  const type = stringValue(entry.type) ?? stringValue(envelope?.type);
  const status = stringValue(data?.status);
  if (kind !== 'outcome' || !type || !status) return undefined;
  return {
    type,
    status,
    timestamp: stringValue(entry.timestamp) ?? stringValue(envelope?.timestamp),
    correlationId: stringValue(entry.correlationId) ?? stringValue(envelope?.correlationId),
    retryable: booleanValue(data?.retryable),
    context: objectValue(data?.context),
  };
}

function compareEntryTimeDesc(left: Record<string, unknown>, right: Record<string, unknown>): number {
  const leftTime = Date.parse(stringValue(left.timestamp) ?? stringValue(objectValue(left.envelope)?.timestamp) ?? '');
  const rightTime = Date.parse(stringValue(right.timestamp) ?? stringValue(objectValue(right.envelope)?.timestamp) ?? '');
  const leftValid = Number.isFinite(leftTime);
  const rightValid = Number.isFinite(rightTime);
  if (leftValid && rightValid) return rightTime - leftTime;
  if (leftValid) return -1;
  if (rightValid) return 1;
  return 0;
}

function formatLifecycleContext(context?: Record<string, unknown>): string | undefined {
  if (!context) return undefined;
  const keys = ['initialized', 'ready', 'pendingConfigUpdates', 'status'];
  const pairs = keys
    .filter(key => context[key] !== undefined)
    .map(key => `${key}=${String(context[key])}`);
  return pairs.length ? pairs.join(', ') : undefined;
}

function objectValue(value: unknown): Record<string, unknown> | undefined {
  return value && typeof value === 'object' ? value as Record<string, unknown> : undefined;
}

function stringValue(value: unknown): string | undefined {
  return typeof value === 'string' && value.trim() ? value.trim() : undefined;
}

function booleanValue(value: unknown): boolean | undefined {
  return typeof value === 'boolean' ? value : undefined;
}

function requiredString(value: unknown, field: string): string {
  const parsed = stringValue(value);
  if (!parsed) throw new Error(`Swarm contract field '${field}' is required`);
  return parsed;
}

function requiredBoolean(value: unknown, field: string): boolean {
  const parsed = booleanValue(value);
  if (parsed === undefined) throw new Error(`Swarm contract field '${field}' is required`);
  return parsed;
}

function numberValue(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isFinite(value) ? value : undefined;
}
