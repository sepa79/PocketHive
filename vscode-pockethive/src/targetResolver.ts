type TargetOptions = {
  preferredKeys: string[];
  nestedKeys?: string[];
};

const OBJECT_STRING = '[object Object]';

export function resolveSwarmId(value: unknown): string | undefined {
  return resolveTargetString(value, {
    preferredKeys: ['swarmId', 'id', 'name'],
    nestedKeys: ['swarm'],
  });
}

export function resolveScenarioId(value: unknown): string | undefined {
  return resolveTargetString(value, {
    preferredKeys: ['scenarioId', 'id', 'name'],
    nestedKeys: ['scenario'],
  });
}

export function resolveBundleName(value: unknown): string | undefined {
  return resolveTargetString(value, {
    preferredKeys: ['bundleName', 'name', 'id'],
    nestedKeys: ['bundle'],
  });
}

export function resolveEnvironmentName(value: unknown): string | undefined {
  return resolveTargetString(value, {
    preferredKeys: ['environmentName', 'name', 'id'],
    nestedKeys: ['environment'],
  });
}

export function resolveFolderPath(value: unknown): string | undefined {
  return resolveTargetString(value, {
    preferredKeys: ['path', 'fsPath', 'id'],
    nestedKeys: ['folder', 'resourceUri'],
  });
}

function resolveTargetString(value: unknown, options: TargetOptions, seen = new Set<unknown>()): string | undefined {
  if (!value) return undefined;
  if (typeof value === 'string') return nonBlank(value);
  if (typeof value !== 'object') return undefined;
  if (seen.has(value)) return undefined;
  seen.add(value);

  const record = value as Record<string, unknown>;
  for (const key of options.preferredKeys) {
    const resolved = resolveTargetString(record[key], options, seen);
    if (resolved) return resolved;
  }

  for (const key of options.nestedKeys ?? []) {
    const resolved = resolveTargetString(record[key], options, seen);
    if (resolved) return resolved;
  }

  const command = record.command as { arguments?: unknown[] } | undefined;
  if (Array.isArray(command?.arguments)) {
    const resolved = resolveTargetString(command.arguments[0], options, seen);
    if (resolved) return resolved;
  }

  const label = record.label;
  if (typeof label === 'string') return nonBlank(label);
  if (label && typeof label === 'object') {
    const resolved = resolveTargetString(label, { preferredKeys: ['label', ...options.preferredKeys] }, seen);
    if (resolved) return resolved;
  }

  return undefined;
}

function nonBlank(value: string): string | undefined {
  const trimmed = value.trim();
  if (!trimmed || trimmed === OBJECT_STRING) return undefined;
  return trimmed;
}
