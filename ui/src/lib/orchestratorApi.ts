import { apiFetch } from './api'
import type { Component } from '../types/hive'

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
