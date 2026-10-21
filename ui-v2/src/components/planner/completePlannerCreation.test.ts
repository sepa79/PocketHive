import { afterEach, describe, expect, it, vi } from 'vitest'
import { completePlannerCreation } from './completePlannerCreation'
import type { PlannerCreation } from './PlannerCreation'

const selection = { instanceId: 'qa', revision: 7, planId: 'plan', streamId: 'stream', activityId: 'minimal' }
const creation: PlannerCreation = { swarmId: 'swarm', accepted: {
  correlationId: 'corr', idempotencyKey: 'idem', operationUrl: '/api/swarms/swarm/operations/corr', outcomeTopic: 'outcome', timeoutMs: 3000,
} }
const operation = { swarmId: 'swarm', type: 'CREATE', correlationId: 'corr', idempotencyKey: 'idem', state: 'SUCCEEDED', runtime: { runId: 'exact-run' } }
afterEach(() => { vi.unstubAllGlobals(); vi.useRealTimers() })

describe('confirmed CREATE association', () => {
  it('uses the exact operation run, without another CREATE or fabricated metadata', async () => {
    const fetch = vi.fn().mockResolvedValueOnce(Response.json(operation)).mockResolvedValueOnce(Response.json({ runId: 'exact-run' }))
    vi.stubGlobal('fetch', fetch)
    await completePlannerCreation(creation, selection)
    expect(fetch.mock.calls[0][0]).toBe('/orchestrator/api/swarms/swarm/operations/corr')
    expect(fetch.mock.calls[1][0]).toBe('/orchestrator/api/planner/runs/swarm/exact-run')
    expect(JSON.parse(fetch.mock.calls[1][1].body)).toEqual(selection)
    expect(fetch.mock.calls.some(call => String(call[0]).endsWith('/create'))).toBe(false)
  })

  it('does not link an accepted, failed or mismatched operation', async () => {
    for (const response of [{ ...operation, state: 'FAILED' }, { ...operation, correlationId: 'other' }, { ...operation, swarmId: 'other' }]) {
      const fetch = vi.fn().mockResolvedValue(Response.json(response))
      vi.stubGlobal('fetch', fetch)
      await expect(completePlannerCreation(creation, selection)).rejects.toThrow()
      expect(fetch).toHaveBeenCalledTimes(1)
    }
  })

  it('waits for canonical success rather than treating acceptance as proof', async () => {
    vi.useFakeTimers()
    const fetch = vi.fn().mockResolvedValueOnce(Response.json({ ...operation, state: 'ACCEPTED' }))
      .mockResolvedValueOnce(Response.json(operation)).mockResolvedValueOnce(Response.json({ runId: 'exact-run' }))
    vi.stubGlobal('fetch', fetch)
    const result = completePlannerCreation(creation, selection)
    await vi.advanceTimersByTimeAsync(750)
    await result
    expect(fetch).toHaveBeenCalledTimes(3)
  })

  it('can retry a failed association without repeating CREATE', async () => {
    const fetch = vi.fn().mockResolvedValueOnce(Response.json(operation))
      .mockResolvedValueOnce(Response.json({ message: 'Planner changed' }, { status: 409 }))
      .mockResolvedValueOnce(Response.json(operation)).mockResolvedValueOnce(Response.json({ runId: 'exact-run' }))
    vi.stubGlobal('fetch', fetch)
    await expect(completePlannerCreation(creation, selection)).rejects.toThrow('Planner changed')
    await completePlannerCreation(creation, { ...selection, revision: 8 })
    expect(fetch.mock.calls.filter(call => call[1]?.method === 'POST')).toHaveLength(2)
    expect(fetch.mock.calls.some(call => String(call[0]).endsWith('/create'))).toBe(false)
  })
})
