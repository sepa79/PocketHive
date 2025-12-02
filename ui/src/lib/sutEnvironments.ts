export type SutEndpoint = {
  id: string
  kind?: string | null
  baseUrl?: string | null
}

export type SutEnvironment = {
  id: string
  name?: string | null
  type?: string | null
  endpoints?: Record<string, SutEndpoint | undefined> | null
  ui?: {
    panelId?: string | null
  } | null
}

export function normalizeSutList(raw: unknown): SutEnvironment[] {
  if (!raw || typeof raw !== 'object') return []
  if (Array.isArray(raw)) {
    return raw
      .map((item) => normalizeSut(item))
      .filter((env): env is SutEnvironment => env !== null)
  }
  // Scenario Manager currently returns a flat array; other shapes are ignored.
  return []
}

export function normalizeSut(raw: unknown): SutEnvironment | null {
  if (!raw || typeof raw !== 'object') return null
  const obj = raw as Record<string, unknown>
  const id = typeof obj.id === 'string' ? obj.id.trim() : ''
  if (!id) return null
  const name =
    typeof obj.name === 'string' && obj.name.trim().length > 0
      ? obj.name.trim()
      : null
  const type =
    typeof obj.type === 'string' && obj.type.trim().length > 0
      ? obj.type.trim()
      : null

  let ui: SutEnvironment['ui'] = null
  if (obj.ui && typeof obj.ui === 'object') {
    const rawUi = obj.ui as Record<string, unknown>
    const panelId =
      typeof rawUi.panelId === 'string' && rawUi.panelId.trim().length > 0
        ? rawUi.panelId.trim()
        : null
    ui = { panelId }
  }

  let endpoints: SutEnvironment['endpoints'] = null
  if (obj.endpoints && typeof obj.endpoints === 'object') {
    const rawEndpoints = obj.endpoints as Record<string, unknown>
    const normalized: Record<string, SutEndpoint> = {}
    for (const [key, value] of Object.entries(rawEndpoints)) {
      if (!value || typeof value !== 'object') continue
      const ep = value as Record<string, unknown>
      normalized[key] = {
        id: key,
        kind:
          typeof ep.kind === 'string' && ep.kind.trim().length > 0
            ? ep.kind.trim()
            : null,
        baseUrl:
          typeof ep.baseUrl === 'string' && ep.baseUrl.trim().length > 0
            ? ep.baseUrl.trim()
            : null,
      }
    }
    endpoints = normalized
  }

  return { id, name, type, endpoints, ui }
}
