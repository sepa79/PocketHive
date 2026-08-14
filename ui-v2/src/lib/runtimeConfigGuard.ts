function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

export function changesRedisDatasetListName(patch: Record<string, unknown>): boolean {
  const inputs = isRecord(patch.inputs) ? patch.inputs : null
  const redis = inputs && isRecord(inputs.redis) ? inputs.redis : null
  return redis !== null && Object.prototype.hasOwnProperty.call(redis, 'listName')
}

export function isStoppedWorkloadState(state: string | null | undefined): boolean {
  return typeof state === 'string' && state.trim().toUpperCase() === 'STOPPED'
}
