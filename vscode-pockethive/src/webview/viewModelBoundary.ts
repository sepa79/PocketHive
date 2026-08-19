export const VIEW_FIELD_BYTE_LIMIT = 64 * 1024;

const BOUNDED_FIELDS = [
  'workspaceData',
  'swarmPrimaryActions',
  'journalResult',
  'swarmHistoryResult',
  'swarmOperationResult',
  'debugResult',
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
  return result as T;
}

function boundField(value: unknown): unknown {
  if (value === undefined) return undefined;
  const redacted = redact(value);
  const serialized = JSON.stringify(redacted);
  return Buffer.byteLength(serialized) <= VIEW_FIELD_BYTE_LIMIT ? redacted : TOO_LARGE;
}

function redact(value: unknown): unknown {
  if (Array.isArray(value)) return value.slice(0, 1000).map(redact);
  if (!value || typeof value !== 'object') return value;
  const result: Record<string, unknown> = {};
  for (const [key, item] of Object.entries(value as Record<string, unknown>).slice(0, 1000)) {
    result[key] = /authorization|token|secret|password/i.test(key) ? '[REDACTED]' : redact(item);
  }
  return result;
}
