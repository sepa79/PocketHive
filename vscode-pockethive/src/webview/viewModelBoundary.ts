export const VIEW_FIELD_BYTE_LIMIT = 64 * 1024;

const BOUNDED_FIELDS = [
  'environmentHealth',
  'swarmPrimaryActions',
  'createSwarmForm',
  'journalResult',
  'swarmHistoryResult',
  'swarmOperationResult',
  'debugWorkersResult',
  'scenarioFocusTree',
  'scenarioFocusInputs',
  'repositoryScenarios',
  'pendingBundle',
  'bundleResult',
] as const;

const TOO_LARGE = {
  error: {
    code: 'COMPANION_VIEW_DATA_TOO_LARGE',
    message: 'PocketHive data exceeded the VS Code companion field limit.',
  },
  truncated: true,
} as const;

export function boundCompanionViewModel<T extends Record<string, unknown>>(model: T): T {
  const result: Record<string, unknown> = { ...model };
  for (const field of BOUNDED_FIELDS) result[field] = boundField(model[field]);
  result.workspaceData = boundField(model.workspaceData);
  result.debugResult = boundField(model.debugResult);
  return result as T;
}

function boundField(value: unknown, byteLimit = VIEW_FIELD_BYTE_LIMIT): unknown {
  if (value === undefined) return undefined;
  const redacted = redact(value, 1000);
  const serialized = JSON.stringify(redacted);
  return Buffer.byteLength(serialized) <= byteLimit ? redacted : TOO_LARGE;
}

export function redactSensitiveValues(value: unknown): unknown {
  return redact(value);
}

function redact(value: unknown, collectionLimit?: number): unknown {
  if (Array.isArray(value)) {
    const items = value.slice(0, collectionLimit);
    return items.map(item => redact(item, collectionLimit));
  }
  if (!value || typeof value !== 'object') return value;
  const result: Record<string, unknown> = {};
  const entries = Object.entries(value as Record<string, unknown>);
  const bounded = entries.slice(0, collectionLimit);
  for (const [key, item] of bounded) {
    result[key] = /authorization|token|secret|password/i.test(key)
      ? '[REDACTED]'
      : redact(item, collectionLimit);
  }
  return result;
}
