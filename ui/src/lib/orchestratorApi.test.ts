import { describe, it, expect, vi, type Mock } from 'vitest'
import type { Component } from '../types/hive'
import {
  createSwarm,
  startSwarm,
  stopSwarm,
  removeSwarm,
  sendConfigUpdate,
  enableSwarmManagers,
  disableSwarmManagers,
} from './orchestratorApi'

vi.mock('./api', () => ({
  apiFetch: vi.fn().mockResolvedValue({ ok: true }),
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

  it('posts swarm remove', async () => {
    await removeSwarm('sw1')
    const call = (apiFetch as unknown as Mock).mock.calls.pop()!
    expect(call[0]).toBe('/orchestrator/swarms/sw1/remove')
    expect(call[1]?.method).toBe('POST')
    const body = JSON.parse(call[1]?.body as string)
    expect(typeof body.idempotencyKey).toBe('string')
    expect(body.idempotencyKey.length).toBeGreaterThan(0)
  })

  it('posts swarm manager toggles with command target', async () => {
    await enableSwarmManagers()
    let call = (apiFetch as unknown as Mock).mock.calls.pop()!
    expect(call[0]).toBe('/orchestrator/swarm-managers/enabled')
    expect(call[1]?.method).toBe('POST')
    const enableBody = JSON.parse(call[1]?.body as string)
    expect(enableBody.commandTarget).toBe('swarm')
    expect(enableBody.enabled).toBe(true)
    expect(typeof enableBody.idempotencyKey).toBe('string')
    expect(enableBody.target).toBeUndefined()

    await disableSwarmManagers()
    call = (apiFetch as unknown as Mock).mock.calls.pop()!
    expect(call[0]).toBe('/orchestrator/swarm-managers/enabled')
    expect(call[1]?.method).toBe('POST')
    const disableBody = JSON.parse(call[1]?.body as string)
    expect(disableBody.commandTarget).toBe('swarm')
    expect(disableBody.enabled).toBe(false)
    expect(typeof disableBody.idempotencyKey).toBe('string')
    expect(disableBody.target).toBeUndefined()
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
