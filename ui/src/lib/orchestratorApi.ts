import { apiFetch } from './api'
import type { Component } from '../types/hive'
import type { SwarmSummary, BeeSummary } from '../types/orchestrator'

interface SwarmManagersTogglePayload {
  idempotencyKey: string
  commandTarget: 'swarm'
  enabled: boolean
}

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
    // ignore body parsing errors
  }

  const error: ApiError = new Error(message || fallback)
  error.status = response.status
  throw error
}

export async function createSwarm(id: string, templateId: string) {
  const payload: Record<string, unknown> = {
    templateId,
    idempotencyKey: crypto.randomUUID(),
  }

  const body = JSON.stringify(payload)
  const response = await apiFetch(`/orchestrator/swarms/${id}/create`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body,
  })

  if (!response.ok) {
    let message = ''
    try {
      const text = await response.text()
      if (text) {
        try {
          const data = JSON.parse(text) as { message?: unknown }
          if (data && typeof data === 'object' && typeof data.message === 'string') {
            message = data.message
          }
        } catch {
          message = text
        }
      }
    } catch {
      // ignore body parsing errors
    }

    if (!message) {
      message = response.status === 409 ? 'Swarm already exists' : 'Failed to create swarm'
    }

    const error: ApiError = new Error(message)
    error.status = response.status
    throw error
  }
}

export async function startSwarm(id: string) {
  const body = JSON.stringify({ idempotencyKey: crypto.randomUUID() })
  const response = await apiFetch(`/orchestrator/swarms/${id}/start`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body,
  })
  await ensureOk(response, 'Failed to start swarm')
}

export async function stopSwarm(id: string) {
  const body = JSON.stringify({ idempotencyKey: crypto.randomUUID() })
  const response = await apiFetch(`/orchestrator/swarms/${id}/stop`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body,
  })
  await ensureOk(response, 'Failed to stop swarm')
}

export async function removeSwarm(id: string) {
  const body = JSON.stringify({ idempotencyKey: crypto.randomUUID() })
  const response = await apiFetch(`/orchestrator/swarms/${id}/remove`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body,
  })
  await ensureOk(response, 'Failed to remove swarm')
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

function asString(value: unknown): string | null {
  if (typeof value !== 'string') return null
  const trimmed = value.trim()
  return trimmed.length > 0 ? trimmed : null
}

function normalizeBee(input: unknown): BeeSummary | null {
  if (!isRecord(input)) return null
  const record = input as Record<string, unknown>
  const role = asString(record['role'])
  if (!role) return null
  const image = asString(record['image'])
  return { role, image }
}

function normalizeSwarmSummary(input: unknown): SwarmSummary | null {
  if (!isRecord(input)) return null
  const record = input as Record<string, unknown>
  const id = asString(record['id'])
  const status = asString(record['status'])
  if (!id || !status) return null
  const beesValue = Array.isArray(record['bees']) ? (record['bees'] as unknown[]) : []
  const bees = beesValue
    .map((entry) => normalizeBee(entry))
    .filter((entry): entry is BeeSummary => Boolean(entry))

  return {
    id,
    status,
    health: asString(record['health']),
    heartbeat: asString(record['heartbeat']),
    workEnabled: record['workEnabled'] === true,
    controllerEnabled: record['controllerEnabled'] === true,
    templateId: asString(record['templateId']),
    controllerImage: asString(record['controllerImage']),
    bees,
  }
}

async function parseSwarmSummaries(response: Response): Promise<SwarmSummary[]> {
  try {
    const payload = await response.json()
    if (!Array.isArray(payload)) {
      return []
    }
    return payload
      .map((entry) => normalizeSwarmSummary(entry))
      .filter((entry): entry is SwarmSummary => Boolean(entry))
  } catch {
    return []
  }
}

async function parseSwarmSummary(response: Response): Promise<SwarmSummary | null> {
  try {
    const payload = await response.json()
    return normalizeSwarmSummary(payload)
  } catch {
    return null
  }
}

export async function listSwarms(): Promise<SwarmSummary[]> {
  const response = await apiFetch('/orchestrator/swarms', {
    headers: { Accept: 'application/json' },
  })
  await ensureOk(response, 'Failed to load swarm metadata')
  return parseSwarmSummaries(response)
}

export async function getSwarm(id: string): Promise<SwarmSummary | null> {
  const response = await apiFetch(`/orchestrator/swarms/${id}`, {
    headers: { Accept: 'application/json' },
  })
  if (response.status === 404) {
    return null
  }
  await ensureOk(response, 'Failed to load swarm metadata')
  return parseSwarmSummary(response)
}

async function setSwarmManagersEnabled(enabled: boolean) {
  const payload: SwarmManagersTogglePayload = {
    idempotencyKey: crypto.randomUUID(),
    commandTarget: 'swarm',
    enabled,
  }
  await apiFetch('/orchestrator/swarm-managers/enabled', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
}

export async function enableSwarmManagers() {
  await setSwarmManagersEnabled(true)
}

export async function disableSwarmManagers() {
  await setSwarmManagersEnabled(false)
}

export async function sendConfigUpdate(component: Component, config: unknown) {
  const payload: Record<string, unknown> = {
    idempotencyKey: crypto.randomUUID(),
    patch: config,
  }
  if (component.swarmId) {
    payload.swarmId = component.swarmId
  }
  await apiFetch(`/orchestrator/components/${component.role}/${component.id}/config`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
}
