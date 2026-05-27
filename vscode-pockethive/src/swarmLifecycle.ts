export type SwarmLifecycleAction = 'start' | 'stop';

const OUTCOME_TYPE_BY_ACTION: Record<SwarmLifecycleAction, string> = {
  start: 'swarm-start',
  stop: 'swarm-stop',
};

export type SwarmLifecycleState = {
  status: string;
  health?: string;
  enabled?: boolean;
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
  ready?: boolean;
  swarmStatus?: string;
  totals?: {
    desired?: number;
    healthy?: number;
    running?: number;
    enabled?: number;
  };
};

export function summarizeSwarmLifecycle(detail: unknown): SwarmLifecycleState {
  if (!detail || typeof detail !== 'object') {
    return { status: 'UNKNOWN' };
  }
  const record = detail as Record<string, unknown>;
  const envelope = objectValue(record.envelope);
  const data = objectValue(envelope?.data);
  const context = objectValue(data?.context);

  return {
    status: stringValue(context?.swarmStatus) ?? stringValue(record.status) ?? 'UNKNOWN',
    health: stringValue(context?.swarmHealth) ?? stringValue(record.health),
    enabled: booleanValue(data?.enabled) ?? booleanValue(record.workEnabled),
  };
}

export function isExpectedLifecycleState(action: SwarmLifecycleAction, state: SwarmLifecycleState): boolean {
  const status = state.status.toUpperCase();
  if (action === 'start') {
    return state.enabled === true || status === 'RUNNING';
  }
  return state.enabled === false || status === 'STOPPED' || status === 'READY';
}

export function shouldWaitForStartReadiness(state: SwarmLifecycleState): boolean {
  return state.status.toUpperCase() === 'READY' && state.enabled !== true;
}

export function formatLifecycleState(state: SwarmLifecycleState): string {
  const parts = [state.status];
  if (typeof state.enabled === 'boolean') parts.push(state.enabled ? 'enabled' : 'disabled');
  if (state.health) parts.push(`health ${state.health}`);
  return parts.join(' / ');
}

export function extractLifecycleCorrelationId(response: unknown): string | undefined {
  const record = objectValue(response);
  if (!record) return undefined;
  return stringValue(record.correlationId)
    ?? stringValue(objectValue(record.watch)?.correlationId)
    ?? stringValue(objectValue(record.envelope)?.correlationId);
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
  const totals = objectValue(record?.totals);
  return {
    ready: booleanValue(record?.ready),
    swarmStatus: stringValue(record?.swarmStatus),
    totals: totals
      ? {
          desired: numberValue(totals.desired),
          healthy: numberValue(totals.healthy),
          running: numberValue(totals.running),
          enabled: numberValue(totals.enabled),
        }
      : undefined,
  };
}

export function formatReadyResult(result: SwarmReadyResult): string {
  const parts: string[] = [];
  if (typeof result.ready === 'boolean') parts.push(result.ready ? 'ready' : 'not ready');
  if (result.swarmStatus) parts.push(`status ${result.swarmStatus}`);
  const totals = result.totals;
  if (totals) {
    const totalParts: string[] = [];
    if (typeof totals.desired === 'number') totalParts.push(`desired ${totals.desired}`);
    if (typeof totals.healthy === 'number') totalParts.push(`healthy ${totals.healthy}`);
    if (typeof totals.running === 'number') totalParts.push(`running ${totals.running}`);
    if (typeof totals.enabled === 'number') totalParts.push(`enabled ${totals.enabled}`);
    if (totalParts.length) parts.push(totalParts.join(', '));
  }
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

function numberValue(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isFinite(value) ? value : undefined;
}
