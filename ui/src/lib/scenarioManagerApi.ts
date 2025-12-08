import { apiFetch } from './api'
import type { ScenarioSummary } from '../types/scenarios'

type ApiError = Error & { status?: number }

async function ensureOk(response: Response, fallback: string) {
  if (response.ok) return
  let message = ''
  try {
    const text = await response.text()
    if (text) {
      try {
        const data = JSON.parse(text) as { message?: unknown }
        if (data && typeof data === 'object' && typeof data.message === 'string') {
          message = data.message
        } else if (!message) {
          message = text
        }
      } catch {
        message = text
      }
    }
  } catch {
    // ignore
  }
  const error: ApiError = new Error(message || fallback)
  error.status = response.status
  throw error
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

function asString(value: unknown): string | null {
  if (typeof value !== 'string') return null
  const trimmed = value.trim()
  return trimmed.length > 0 ? trimmed : null
}

function normalizeSummary(input: unknown): ScenarioSummary | null {
  if (!isRecord(input)) return null
  const record = input as Record<string, unknown>
  const id = asString(record['id'])
  if (!id) return null
  const name = asString(record['name']) ?? id
  return { id, name }
}

export async function listScenarios(): Promise<ScenarioSummary[]> {
  const response = await apiFetch('/scenario-manager/scenarios?includeDefunct=true', {
    headers: { Accept: 'application/json' },
  })
  await ensureOk(response, 'Failed to load scenarios')
  try {
    const payload = (await response.json()) as unknown
    if (!Array.isArray(payload)) return []
    return payload
      .map((entry) => normalizeSummary(entry))
      .filter((entry): entry is ScenarioSummary => Boolean(entry))
  } catch {
    return []
  }
}

export async function createScenario(payload: {
  id: string
  name: string
  description?: string | null
}): Promise<ScenarioSummary> {
  const body = {
    id: payload.id,
    name: payload.name,
    description:
      typeof payload.description === 'string' && payload.description.trim().length > 0
        ? payload.description.trim()
        : null,
  }
  const response = await apiFetch('/scenario-manager/scenarios', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  await ensureOk(response, 'Failed to create scenario')
  const data = (await response.json()) as unknown
  const summary = normalizeSummary(data)
  if (!summary) {
    throw new Error('Scenario manager returned invalid scenario payload')
  }
  return summary
}

export async function downloadScenarioBundle(id: string): Promise<Blob> {
  const response = await apiFetch(`/scenario-manager/scenarios/${encodeURIComponent(id)}/bundle`, {
    headers: { Accept: 'application/zip' },
  })
  await ensureOk(response, 'Failed to download scenario bundle')
  return response.blob()
}

export async function uploadScenarioBundle(file: File): Promise<ScenarioSummary> {
  const response = await apiFetch('/scenario-manager/scenarios/bundles', {
    method: 'POST',
    headers: { 'Content-Type': 'application/zip' },
    body: file,
  })
  await ensureOk(response, 'Failed to upload scenario bundle')
  const data = (await response.json()) as unknown
  const summary = normalizeSummary(data)
  if (!summary) {
    throw new Error('Scenario manager returned invalid scenario payload')
  }
  return summary
}

export async function replaceScenarioBundle(id: string, file: File): Promise<ScenarioSummary> {
  const response = await apiFetch(
    `/scenario-manager/scenarios/${encodeURIComponent(id)}/bundle`,
    {
      method: 'PUT',
      headers: { 'Content-Type': 'application/zip' },
      body: file,
    },
  )
  await ensureOk(response, 'Failed to update scenario bundle')
  const data = (await response.json()) as unknown
  const summary = normalizeSummary(data)
  if (!summary) {
    throw new Error('Scenario manager returned invalid scenario payload')
  }
  return summary
}

export async function fetchScenarioRaw(id: string): Promise<string> {
  const response = await apiFetch(
    `/scenario-manager/scenarios/${encodeURIComponent(id)}/raw`,
    {
      headers: { Accept: 'text/plain' },
    },
  )
  await ensureOk(response, 'Failed to load scenario')
  return response.text()
}

export async function saveScenarioRaw(id: string, body: string): Promise<void> {
  const response = await apiFetch(
    `/scenario-manager/scenarios/${encodeURIComponent(id)}/raw`,
    {
      method: 'PUT',
      headers: { 'Content-Type': 'text/plain;charset=UTF-8' },
      body,
    },
  )
  await ensureOk(response, 'Failed to save scenario')
}

export interface ScenarioPlanStep {
  stepId: string | null
  name: string | null
  time: string | null
  type: string | null
  config?: Record<string, unknown> | null
}

export interface ScenarioPlanView {
  bees: {
    instanceId: string | null
    role: string | null
    steps: ScenarioPlanStep[]
  }[]
  swarm: ScenarioPlanStep[]
}

export interface ScenarioTemplateBeeRef {
  instanceId: string | null
  role: string | null
  image: string | null
  config?: Record<string, unknown> | null
}

export interface ScenarioTemplateRef {
  image: string | null
  bees: ScenarioTemplateBeeRef[]
}

export interface ScenarioPayload {
  id: string
  name: string
  description?: string | null
  plan?: unknown
  templateRoles?: string[]
  template?: ScenarioTemplateRef | null
}

export async function getScenario(id: string): Promise<ScenarioPayload | null> {
  const response = await apiFetch(
    `/scenario-manager/scenarios/${encodeURIComponent(id)}`,
    {
      headers: { Accept: 'application/json' },
    },
  )
  if (response.status === 404) {
    return null
  }
  await ensureOk(response, 'Failed to load scenario')
  try {
    const payload = (await response.json()) as unknown
    if (!isRecord(payload)) return null
    const record = payload as Record<string, unknown>
    const idValue = asString(record['id'])
    const nameValue = asString(record['name']) ?? idValue
    if (!idValue || !nameValue) return null
    let templateRoles: string[] | undefined
    let template: ScenarioTemplateRef | null = null
    const templateValue = record['template']
    if (isRecord(templateValue)) {
      const tpl = templateValue as Record<string, unknown>
      const image = asString(tpl['image'])
      const beesRaw = Array.isArray(tpl['bees']) ? (tpl['bees'] as unknown[]) : []
      const bees: ScenarioTemplateBeeRef[] = []
      const roles: string[] = []
      for (const entry of beesRaw) {
        if (!isRecord(entry)) continue
        const beeRec = entry as Record<string, unknown>
        const instanceId = asString(beeRec['instanceId'])
        const role = asString(beeRec['role'])
        const beeImage = asString(beeRec['image'])
        const configValue = beeRec['config']
        const config =
          isRecord(configValue) &&
          Object.keys(configValue as Record<string, unknown>).length > 0
            ? (configValue as Record<string, unknown>)
            : null
        bees.push({ instanceId, role, image: beeImage, config })
        if (role) {
          roles.push(role)
        }
      }
      if (roles.length > 0) {
        templateRoles = Array.from(new Set(roles)).sort()
      }
      template = { image: image ?? null, bees }
    }
    return {
      id: idValue,
      name: nameValue,
      description: asString(record['description']),
      plan: record['plan'],
      templateRoles,
      template,
    }
  } catch {
    return null
  }
}

export async function saveScenarioPlan(id: string, plan: unknown): Promise<void> {
  const response = await apiFetch(
    `/scenario-manager/scenarios/${encodeURIComponent(id)}/plan`,
    {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(plan ?? {}),
    },
  )
  await ensureOk(response, 'Failed to save scenario plan')
}

export function mergePlan(
  original: unknown,
  view: ScenarioPlanView | null,
): Record<string, unknown> {
  const base: Record<string, unknown> = isRecord(original)
    ? { ...(original as Record<string, unknown>) }
    : {}

  if (!view) {
    delete base.bees
    delete base.swarm
    return base
  }

  base.bees = view.bees.map((bee) => {
    const result: Record<string, unknown> = {}
    if (bee.instanceId) result.instanceId = bee.instanceId
    if (bee.role) result.role = bee.role
    result.steps = bee.steps.map((step) => {
      const entry: Record<string, unknown> = {}
      if (step.stepId) entry.stepId = step.stepId
      if (step.name) entry.name = step.name
      if (step.time) entry.time = step.time
      if (step.type) entry.type = step.type
      if (step.config && typeof step.config === 'object') {
        const cfg = step.config as Record<string, unknown>
        if (Object.keys(cfg).length > 0) {
          entry.config = cfg
        }
      }
      return entry
    })
    return result
  })

  base.swarm = view.swarm.map((step) => {
    const entry: Record<string, unknown> = {}
    if (step.stepId) entry.stepId = step.stepId
    if (step.name) entry.name = step.name
    if (step.time) entry.time = step.time
    if (step.type) entry.type = step.type
    if (step.config && typeof step.config === 'object') {
      const cfg = step.config as Record<string, unknown>
      if (Object.keys(cfg).length > 0) {
        entry.config = cfg
      }
    }
    return entry
  })

  return base
}

export function buildPlanView(plan: unknown): ScenarioPlanView | null {
  if (!isRecord(plan)) return null
  const root = plan as Record<string, unknown>
  const beesValue = Array.isArray(root['bees']) ? (root['bees'] as unknown[]) : []
  const swarmValue = Array.isArray(root['swarm']) ? (root['swarm'] as unknown[]) : []

  const bees = beesValue
    .map((beeEntry) => {
      if (!isRecord(beeEntry)) return null
      const rec = beeEntry as Record<string, unknown>
      const instanceId = asString(rec['instanceId'])
      const role = asString(rec['role'])
      const stepsRaw = Array.isArray(rec['steps']) ? (rec['steps'] as unknown[]) : []
      const steps: ScenarioPlanStep[] = stepsRaw
        .map((stepEntry) => {
          if (!isRecord(stepEntry)) return null as ScenarioPlanStep | null
          const stepRec = stepEntry as Record<string, unknown>
          const configValue = stepRec['config']
          const config =
            isRecord(configValue) && Object.keys(configValue as Record<string, unknown>).length > 0
              ? (configValue as Record<string, unknown>)
              : null
          return {
            stepId: asString(stepRec['stepId']),
            name: asString(stepRec['name']),
            time: asString(stepRec['time']),
            type: asString(stepRec['type']),
            config,
          }
        })
        .filter((step): step is ScenarioPlanStep => step !== null)
      return { instanceId, role, steps }
    })
    .filter(
      (bee): bee is { instanceId: string | null; role: string | null; steps: ScenarioPlanStep[] } =>
        bee !== null,
    )

  const swarm: ScenarioPlanStep[] = swarmValue
    .map((stepEntry) => {
      if (!isRecord(stepEntry)) return null as ScenarioPlanStep | null
      const rec = stepEntry as Record<string, unknown>
      const configValue = rec['config']
      const config =
        isRecord(configValue) && Object.keys(configValue as Record<string, unknown>).length > 0
          ? (configValue as Record<string, unknown>)
          : null
      return {
        stepId: asString(rec['stepId']),
        name: asString(rec['name']),
        time: asString(rec['time']),
        type: asString(rec['type']),
        config,
      }
    })
    .filter((step): step is ScenarioPlanStep => step !== null)

  if (bees.length === 0 && swarm.length === 0) {
    return null
  }
  return { bees, swarm }
}
