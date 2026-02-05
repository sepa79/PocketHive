type ApiError = Error & { status?: number }

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

function asString(value: unknown): string | null {
  if (typeof value !== 'string') return null
  const trimmed = value.trim()
  return trimmed.length > 0 ? trimmed : null
}

async function ensureOk(response: Response, fallback: string) {
  if (response.ok) return
  let message = ''
  try {
    const text = await response.text()
    message = text ? text : message
  } catch {
    // ignore
  }
  const error: ApiError = new Error(message || fallback)
  error.status = response.status
  throw error
}

export type ScenarioSummary = {
  id: string
  name: string
  folderPath: string | null
}

function normalizeScenarioSummary(input: unknown): ScenarioSummary | null {
  if (!isRecord(input)) return null
  const id = asString(input['id'])
  if (!id) return null
  const name = asString(input['name']) ?? id
  const folderPath = asString(input['folderPath'])
  return { id, name, folderPath }
}

export async function listScenarios(opts?: { includeDefunct?: boolean }): Promise<ScenarioSummary[]> {
  const includeDefunct = opts?.includeDefunct ?? true
  const params = new URLSearchParams({ includeDefunct: includeDefunct ? 'true' : 'false' })
  const response = await fetch(`/scenario-manager/scenarios?${params.toString()}`, {
    headers: { Accept: 'application/json' },
  })
  await ensureOk(response, 'Failed to load scenarios')
  try {
    const payload = (await response.json()) as unknown
    if (!Array.isArray(payload)) return []
    return payload
      .map((entry) => normalizeScenarioSummary(entry))
      .filter((entry): entry is ScenarioSummary => Boolean(entry))
  } catch {
    return []
  }
}

export async function listScenarioFolders(): Promise<string[]> {
  const response = await fetch('/scenario-manager/scenarios/folders', {
    headers: { Accept: 'application/json' },
  })
  await ensureOk(response, 'Failed to load scenario folders')
  try {
    const payload = (await response.json()) as unknown
    if (!Array.isArray(payload)) return []
    return payload
      .map((entry) => (typeof entry === 'string' ? entry.trim() : ''))
      .filter((entry) => entry.length > 0)
  } catch {
    return []
  }
}

export async function createScenarioFolder(path: string): Promise<void> {
  const response = await fetch('/scenario-manager/scenarios/folders', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ path }),
  })
  await ensureOk(response, 'Failed to create scenario folder')
}

export async function deleteScenarioFolder(path: string): Promise<void> {
  const params = new URLSearchParams({ path })
  const response = await fetch(`/scenario-manager/scenarios/folders?${params.toString()}`, {
    method: 'DELETE',
  })
  await ensureOk(response, 'Failed to delete scenario folder')
}

export async function moveScenarioToFolder(scenarioId: string, path: string | null): Promise<void> {
  const response = await fetch(`/scenario-manager/scenarios/${encodeURIComponent(scenarioId)}/move`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ path }),
  })
  await ensureOk(response, 'Failed to move scenario')
}

