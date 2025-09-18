import { apiFetch } from './api'
import type { Component } from '../types/hive'

export async function createSwarm(id: string, templateId: string) {
  const payload: Record<string, unknown> = {
    templateId,
    idempotencyKey: crypto.randomUUID(),
  }

  const body = JSON.stringify(payload)
  await apiFetch(`/orchestrator/swarms/${id}/create`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body,
  })
}

export async function startSwarm(id: string) {
  const body = JSON.stringify({ idempotencyKey: crypto.randomUUID() })
  await apiFetch(`/orchestrator/swarms/${id}/start`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body,
  })
}

export async function stopSwarm(id: string) {
  const body = JSON.stringify({ idempotencyKey: crypto.randomUUID() })
  await apiFetch(`/orchestrator/swarms/${id}/stop`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body,
  })
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
