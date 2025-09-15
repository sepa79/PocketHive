import { apiFetch } from './api'
import type { Component } from '../types/hive'

export async function createSwarm(id: string, templateId: string) {
  const body = JSON.stringify({ id, templateId, idempotencyKey: crypto.randomUUID() })
  await apiFetch('/orchestrator/swarms', {
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
  await apiFetch(`/orchestrator/components/${component.name}/${component.id}/config`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(config),
  })
}
