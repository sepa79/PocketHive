import { describe, it, expect, vi, type Mock } from 'vitest'
import type { Component } from '../types/hive'
import { createSwarm, startSwarm, stopSwarm, sendConfigUpdate } from './orchestratorApi'

vi.mock('./api', () => ({
  apiFetch: vi.fn().mockResolvedValue({}),
}))

const { apiFetch } = await import('./api')

describe('orchestratorApi', () => {
  it('posts swarm creation', async () => {
    await createSwarm('sw1', 'tpl')
    expect((apiFetch as unknown as Mock).mock.calls[0][0]).toBe('/orchestrator/swarms/sw1/create')
    const init = (apiFetch as unknown as Mock).mock.calls[0][1]
    expect(init?.method).toBe('POST')
    const body = JSON.parse(init?.body)
    expect(body.templateId).toBe('tpl')
    expect(body.notes).toBeUndefined()
    expect(body.idempotencyKey).toBeDefined()
  })

  it.todo('posts swarm creation with explicit notes once supported')

  it('posts swarm start', async () => {
    await startSwarm('sw1')
    const call = (apiFetch as unknown as Mock).mock.calls.pop()!
    expect(call[0]).toBe('/orchestrator/swarms/sw1/start')
    expect(call[1]?.method).toBe('POST')
  })

  it('posts swarm stop', async () => {
    await stopSwarm('sw1')
    const call = (apiFetch as unknown as Mock).mock.calls.pop()!
    expect(call[0]).toBe('/orchestrator/swarms/sw1/stop')
    expect(call[1]?.method).toBe('POST')
  })

  it('posts component config update', async () => {
    const comp: Component = {
      id: 'c1',
      name: 'c1',
      role: 'generator',
      swarmId: 'sw1',
      lastHeartbeat: 0,
      queues: [],
      config: {},
    }
    await sendConfigUpdate(comp, { enabled: true })
    const call = (apiFetch as unknown as Mock).mock.calls.pop()!
    expect(call[0]).toBe('/orchestrator/components/generator/c1/config')
    expect(call[1]?.method).toBe('POST')
    const body = JSON.parse(call[1]?.body as string)
    expect(body.idempotencyKey).toBeDefined()
    expect(body.patch).toEqual({ enabled: true })
    expect(body.swarmId).toBe('sw1')
  })
})
