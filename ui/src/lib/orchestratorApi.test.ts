import { describe, it, expect, vi, beforeEach, type Mock } from 'vitest'
import type { Component } from '../types/hive'
import {
  createSwarm,
  startSwarm,
  stopSwarm,
  removeSwarm,
  sendConfigUpdate,
  enableSwarmManagers,
  disableSwarmManagers,
  listSwarms,
  getSwarm,
} from './orchestratorApi'

vi.mock('./api', () => ({
  apiFetch: vi.fn().mockResolvedValue({
    ok: true,
    json: async () => [],
  }),
}))

const { apiFetch } = await import('./api')

describe('orchestratorApi', () => {
  beforeEach(() => {
    ;(apiFetch as unknown as Mock).mockClear()
    ;(apiFetch as unknown as Mock).mockResolvedValue({
      ok: true,
      json: async () => [],
    })
  })

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
    const body = JSON.parse(call[1]?.body as string)
    expect(typeof body.idempotencyKey).toBe('string')
    expect(body.autoPullImages).toBeUndefined()
  })

  it('posts swarm start with autoPullImages when requested', async () => {
    await startSwarm('sw1', { autoPullImages: true })
    const call = (apiFetch as unknown as Mock).mock.calls.pop()!
    expect(call[0]).toBe('/orchestrator/swarms/sw1/start')
    expect(call[1]?.method).toBe('POST')
    const body = JSON.parse(call[1]?.body as string)
    expect(body.autoPullImages).toBe(true)
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

  it('throws when swarm command fails', async () => {
    ;(apiFetch as unknown as Mock).mockResolvedValueOnce({
      ok: false,
      text: async () => JSON.stringify({ message: 'boom' }),
    })
    await expect(startSwarm('sw1')).rejects.toThrow('boom')

    ;(apiFetch as unknown as Mock).mockResolvedValueOnce({
      ok: false,
      text: async () => 'nope',
    })
    await expect(stopSwarm('sw1')).rejects.toThrow('nope')

    ;(apiFetch as unknown as Mock).mockResolvedValueOnce({
      ok: false,
      text: async () => '',
    })
    await expect(removeSwarm('sw1')).rejects.toThrow('Failed to remove swarm')
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

  it('fetches and normalizes swarm summaries', async () => {
    ;(apiFetch as unknown as Mock).mockResolvedValueOnce({
      ok: true,
      json: async () => [
        {
          id: 'sw1',
          status: 'RUNNING',
          health: 'HEALTHY',
          heartbeat: '2024-01-01T00:00:00Z',
          workEnabled: true,
          controllerEnabled: false,
          templateId: 'tpl-1',
          controllerImage: 'ctrl:latest',
          bees: [
            { role: 'generator', image: 'gen:1' },
            { role: ' processor ', image: 'proc:1' },
            { role: '', image: 'ignored' },
          ],
        },
        { id: 'missing-status' },
      ],
    })

    const swarms = await listSwarms()
    expect((apiFetch as unknown as Mock).mock.calls[0][0]).toBe('/orchestrator/swarms')
    expect(swarms).toHaveLength(1)
    expect(swarms[0]).toMatchObject({
      id: 'sw1',
      status: 'RUNNING',
      health: 'HEALTHY',
      heartbeat: '2024-01-01T00:00:00Z',
      workEnabled: true,
      controllerEnabled: false,
      templateId: 'tpl-1',
      controllerImage: 'ctrl:latest',
    })
    expect(swarms[0].bees).toEqual([
      { role: 'generator', image: 'gen:1' },
      { role: 'processor', image: 'proc:1' },
    ])
  })

  it('returns null when swarm is not found', async () => {
    ;(apiFetch as unknown as Mock).mockResolvedValueOnce({
      ok: false,
      status: 404,
      json: async () => ({}),
    })

    const result = await getSwarm('unknown')
    expect((apiFetch as unknown as Mock).mock.calls[0][0]).toBe('/orchestrator/swarms/unknown')
    expect(result).toBeNull()
  })

  it('fetches an individual swarm summary', async () => {
    ;(apiFetch as unknown as Mock).mockResolvedValueOnce({
      ok: true,
      status: 200,
      json: async () => ({
        id: 'sw1',
        status: 'RUNNING',
        health: null,
        heartbeat: null,
        workEnabled: false,
        controllerEnabled: true,
        templateId: null,
        controllerImage: 'ctrl:1',
        bees: [{ role: 'generator', image: 'gen:1' }],
      }),
    })

    const result = await getSwarm('sw1')
    expect((apiFetch as unknown as Mock).mock.calls[0][0]).toBe('/orchestrator/swarms/sw1')
    expect(result).toEqual({
      id: 'sw1',
      status: 'RUNNING',
      health: null,
      heartbeat: null,
      workEnabled: false,
      controllerEnabled: true,
      templateId: null,
      controllerImage: 'ctrl:1',
      bees: [{ role: 'generator', image: 'gen:1' }],
    })
  })
})
