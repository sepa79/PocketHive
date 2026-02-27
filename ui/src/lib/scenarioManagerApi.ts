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

function readPortMap(value: unknown): Record<string, string> | null {
  if (!isRecord(value)) return null
  const result: Record<string, string> = {}
  Object.entries(value).forEach(([key, raw]) => {
    const portId = key.trim()
    const entry = asString(raw)
    if (portId.length > 0 && entry) {
      result[portId] = entry
    }
  })
  return Object.keys(result).length > 0 ? result : null
}

function readPortList(value: unknown): ScenarioTemplateBeePortRef[] | null {
  if (!Array.isArray(value)) return null
  const ports: ScenarioTemplateBeePortRef[] = []
  for (const entry of value) {
    if (!isRecord(entry)) continue
    const record = entry as Record<string, unknown>
    const id = asString(record['id'])
    const direction = asString(record['direction'])
    if (!id && !direction) continue
    ports.push({ id, direction })
  }
  return ports.length > 0 ? ports : null
}

function readTopology(value: unknown): ScenarioTemplateTopologyRef | null {
  if (!isRecord(value)) return null
  const record = value as Record<string, unknown>
  const versionRaw = record['version']
  const version = typeof versionRaw === 'number' ? versionRaw : null
  const edgesRaw = Array.isArray(record['edges']) ? (record['edges'] as unknown[]) : []
  const edges: ScenarioTemplateTopologyEdge[] = []
  for (const entry of edgesRaw) {
    if (!isRecord(entry)) continue
    const edgeRec = entry as Record<string, unknown>
    const id = asString(edgeRec['id'])
    const from = readTopologyEndpoint(edgeRec['from'])
    const to = readTopologyEndpoint(edgeRec['to'])
    const selector = readTopologySelector(edgeRec['selector'])
    if (!id && !from && !to) continue
    edges.push({ id, from, to, selector })
  }
  if (version == null && edges.length === 0) return null
  return { version, edges }
}

function readTopologyEndpoint(value: unknown): ScenarioTemplateTopologyEndpoint | null {
  if (!isRecord(value)) return null
  const record = value as Record<string, unknown>
  const beeId = asString(record['beeId'])
  const port = asString(record['port'])
  if (!beeId && !port) return null
  return { beeId, port }
}

function readTopologySelector(value: unknown): ScenarioTemplateTopologySelector | null {
  if (!isRecord(value)) return null
  const record = value as Record<string, unknown>
  const policy = asString(record['policy'])
  const expr = asString(record['expr'])
  if (!policy && !expr) return null
  return { policy, expr }
}

function normalizeSummary(input: unknown): ScenarioSummary | null {
  if (!isRecord(input)) return null
  const record = input as Record<string, unknown>
  const id = asString(record['id'])
  if (!id) return null
  const name = asString(record['name']) ?? id
  const folderPath = asString(record['folderPath'])
  return { id, name, folderPath }
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

export async function listScenarioFolders(): Promise<string[]> {
  const response = await apiFetch('/scenario-manager/scenarios/folders', {
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
  const response = await apiFetch('/scenario-manager/scenarios/folders', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ path }),
  })
  await ensureOk(response, 'Failed to create scenario folder')
}

export async function deleteScenarioFolder(path: string): Promise<void> {
  const params = new URLSearchParams({ path })
  const response = await apiFetch(`/scenario-manager/scenarios/folders?${params.toString()}`, {
    method: 'DELETE',
  })
  await ensureOk(response, 'Failed to delete scenario folder')
}

export async function moveScenarioToFolder(scenarioId: string, path: string | null): Promise<void> {
  const response = await apiFetch(`/scenario-manager/scenarios/${encodeURIComponent(scenarioId)}/move`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ path }),
  })
  await ensureOk(response, 'Failed to move scenario')
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

export async function listScenarioSchemas(id: string): Promise<string[]> {
  const response = await apiFetch(
    `/scenario-manager/scenarios/${encodeURIComponent(id)}/schemas`,
    {
      headers: { Accept: 'application/json' },
    },
  )
  await ensureOk(response, 'Failed to load scenario schemas')
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

export async function fetchScenarioSchema(id: string, path: string): Promise<string> {
  const params = new URLSearchParams({ path })
  const response = await apiFetch(
    `/scenario-manager/scenarios/${encodeURIComponent(id)}/schema?${params.toString()}`,
    {
      headers: { Accept: 'application/json' },
    },
  )
  await ensureOk(response, 'Failed to load scenario schema')
  return response.text()
}

export async function saveScenarioSchema(
  id: string,
  path: string,
  body: string,
): Promise<void> {
  const params = new URLSearchParams({ path })
  const response = await apiFetch(
    `/scenario-manager/scenarios/${encodeURIComponent(id)}/schema?${params.toString()}`,
    {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json;charset=UTF-8' },
      body,
    },
  )
  await ensureOk(response, 'Failed to save scenario schema')
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

export async function fetchScenarioVariables(id: string): Promise<string | null> {
  const response = await apiFetch(
    `/scenario-manager/scenarios/${encodeURIComponent(id)}/variables`,
    {
      headers: { Accept: 'text/plain' },
    },
  )
  if (response.status === 404) {
    return null
  }
  await ensureOk(response, 'Failed to load variables.yaml')
  return response.text()
}

export async function saveScenarioVariables(
  id: string,
  body: string,
): Promise<{ warnings: string[] }> {
  const response = await apiFetch(
    `/scenario-manager/scenarios/${encodeURIComponent(id)}/variables`,
    {
      method: 'PUT',
      headers: { 'Content-Type': 'text/plain;charset=UTF-8', Accept: 'application/json' },
      body,
    },
  )
  await ensureOk(response, 'Failed to save variables.yaml')
  try {
    const payload = (await response.json()) as unknown
    if (!isRecord(payload)) return { warnings: [] }
    const warnings = Array.isArray(payload['warnings'])
      ? payload['warnings']
          .map((entry) => (typeof entry === 'string' ? entry.trim() : ''))
          .filter((entry) => entry.length > 0)
      : []
    return { warnings }
  } catch {
    return { warnings: [] }
  }
}

export async function listScenarioBundleSuts(id: string): Promise<string[]> {
  const response = await apiFetch(
    `/scenario-manager/scenarios/${encodeURIComponent(id)}/suts`,
    { headers: { Accept: 'application/json' } },
  )
  if (response.status === 404) {
    return []
  }
  await ensureOk(response, 'Failed to list scenario SUTs')
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

export async function fetchScenarioSutRaw(
  scenarioId: string,
  sutId: string,
): Promise<string | null> {
  const response = await apiFetch(
    `/scenario-manager/scenarios/${encodeURIComponent(scenarioId)}/suts/${encodeURIComponent(sutId)}/raw`,
    { headers: { Accept: 'text/plain' } },
  )
  if (response.status === 404) {
    return null
  }
  await ensureOk(response, 'Failed to load sut.yaml')
  return response.text()
}

export async function saveScenarioSutRaw(
  scenarioId: string,
  sutId: string,
  body: string,
): Promise<void> {
  const response = await apiFetch(
    `/scenario-manager/scenarios/${encodeURIComponent(scenarioId)}/suts/${encodeURIComponent(sutId)}/raw`,
    {
      method: 'PUT',
      headers: { 'Content-Type': 'text/plain;charset=UTF-8' },
      body,
    },
  )
  await ensureOk(response, 'Failed to save sut.yaml')
}

export async function deleteScenarioSut(
  scenarioId: string,
  sutId: string,
): Promise<void> {
  const response = await apiFetch(
    `/scenario-manager/scenarios/${encodeURIComponent(scenarioId)}/suts/${encodeURIComponent(sutId)}`,
    { method: 'DELETE' },
  )
  await ensureOk(response, 'Failed to delete SUT')
}

export async function listTemplates(id: string): Promise<string[]> {
  const response = await apiFetch(
    `/scenario-manager/scenarios/${encodeURIComponent(id)}/templates`,
    {
      headers: { Accept: 'application/json' },
    },
  )
  await ensureOk(response, 'Failed to load HTTP templates')
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

export async function fetchTemplate(id: string, path: string): Promise<string> {
  const params = new URLSearchParams({ path })
  const response = await apiFetch(
    `/scenario-manager/scenarios/${encodeURIComponent(id)}/template?${params.toString()}`,
    {
      headers: { Accept: 'text/plain' },
    },
  )
  await ensureOk(response, 'Failed to load HTTP template')
  return response.text()
}

export async function saveTemplate(id: string, path: string, body: string): Promise<void> {
  const params = new URLSearchParams({ path })
  const response = await apiFetch(
    `/scenario-manager/scenarios/${encodeURIComponent(id)}/template?${params.toString()}`,
    {
      method: 'PUT',
      headers: { 'Content-Type': 'text/plain;charset=UTF-8' },
      body,
    },
  )
  await ensureOk(response, 'Failed to save HTTP template')
}

export async function renameTemplate(id: string, fromPath: string, toPath: string): Promise<void> {
  const params = new URLSearchParams({ from: fromPath, to: toPath })
  const response = await apiFetch(
    `/scenario-manager/scenarios/${encodeURIComponent(id)}/template/rename?${params.toString()}`,
    {
      method: 'POST',
    },
  )
  await ensureOk(response, 'Failed to rename HTTP template')
}

export async function deleteTemplate(id: string, path: string): Promise<void> {
  const params = new URLSearchParams({ path })
  const response = await apiFetch(
    `/scenario-manager/scenarios/${encodeURIComponent(id)}/template?${params.toString()}`,
    {
      method: 'DELETE',
    },
  )
  await ensureOk(response, 'Failed to delete HTTP template')
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
  id?: string | null
  instanceId: string | null
  role: string | null
  image: string | null
  work?: ScenarioTemplateBeeWorkRef | null
  ports?: ScenarioTemplateBeePortRef[] | null
  config?: Record<string, unknown> | null
}

export interface ScenarioTemplateRef {
  image: string | null
  bees: ScenarioTemplateBeeRef[]
}

export interface ScenarioTemplateBeeWorkRef {
  in?: Record<string, string>
  out?: Record<string, string>
}

export interface ScenarioTemplateBeePortRef {
  id: string | null
  direction: string | null
}

export interface ScenarioTemplateTopologyEndpoint {
  beeId: string | null
  port: string | null
}

export interface ScenarioTemplateTopologySelector {
  policy: string | null
  expr: string | null
}

export interface ScenarioTemplateTopologyEdge {
  id: string | null
  from?: ScenarioTemplateTopologyEndpoint | null
  to?: ScenarioTemplateTopologyEndpoint | null
  selector?: ScenarioTemplateTopologySelector | null
}

export interface ScenarioTemplateTopologyRef {
  version: number | null
  edges: ScenarioTemplateTopologyEdge[]
}

export interface ScenarioPayload {
  id: string
  name: string
  description?: string | null
  plan?: unknown
  templateRoles?: string[]
  template?: ScenarioTemplateRef | null
  topology?: ScenarioTemplateTopologyRef | null
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
        const id = asString(beeRec['id'])
        const instanceId = asString(beeRec['instanceId'])
        const role = asString(beeRec['role'])
        const beeImage = asString(beeRec['image'])
        let work: ScenarioTemplateBeeWorkRef | null = null
        const workValue = beeRec['work']
        if (isRecord(workValue)) {
          const workRec = workValue as Record<string, unknown>
          const inMap = readPortMap(workRec['in'])
          const outMap = readPortMap(workRec['out'])
          if (inMap || outMap) {
            work = {
              in: inMap ?? undefined,
              out: outMap ?? undefined,
            }
          }
        }
        const ports = readPortList(beeRec['ports'])
        const configValue = beeRec['config']
        const config =
          isRecord(configValue) &&
          Object.keys(configValue as Record<string, unknown>).length > 0
            ? (configValue as Record<string, unknown>)
            : null
        const bee: ScenarioTemplateBeeRef = {
          instanceId,
          role,
          image: beeImage,
          config,
        }
        if (id) {
          bee.id = id
        }
        if (work) {
          bee.work = work
        }
        if (ports) {
          bee.ports = ports
        }
        bees.push(bee)
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
      topology: readTopology(record['topology']),
    }
  } catch {
    return null
  }
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

  const mergeStep = (
    originalStep: unknown,
    step: ScenarioPlanStep,
  ): Record<string, unknown> => {
    const entry: Record<string, unknown> = isRecord(originalStep)
      ? { ...(originalStep as Record<string, unknown>) }
      : {}
    if (step.stepId) {
      entry.stepId = step.stepId
    } else {
      delete entry.stepId
    }
    if (step.name) {
      entry.name = step.name
    } else {
      delete entry.name
    }
    if (step.time) {
      entry.time = step.time
    } else {
      delete entry.time
    }
    if (step.type) {
      entry.type = step.type
    } else {
      delete entry.type
    }
    if (step.config && typeof step.config === 'object') {
      const cfg = step.config as Record<string, unknown>
      if (Object.keys(cfg).length > 0) {
        entry.config = cfg
      } else {
        delete entry.config
      }
    } else {
      delete entry.config
    }
    return entry
  }

  const originalBees = Array.isArray(base.bees) ? (base.bees as unknown[]) : []
  base.bees = view.bees.map((bee, index) => {
    const baseBee = isRecord(originalBees[index])
      ? { ...(originalBees[index] as Record<string, unknown>) }
      : {}
    if (bee.instanceId) {
      baseBee.instanceId = bee.instanceId
    } else {
      delete baseBee.instanceId
    }
    if (bee.role) {
      baseBee.role = bee.role
    } else {
      delete baseBee.role
    }
    const originalSteps = Array.isArray(baseBee.steps) ? (baseBee.steps as unknown[]) : []
    baseBee.steps = bee.steps.map((step, stepIndex) =>
      mergeStep(originalSteps[stepIndex], step),
    )
    return baseBee
  })

  const originalSwarm = Array.isArray(base.swarm) ? (base.swarm as unknown[]) : []
  base.swarm = view.swarm.map((step, index) => mergeStep(originalSwarm[index], step))

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
