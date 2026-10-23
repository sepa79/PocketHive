import { afterEach, describe, expect, it, vi } from 'vitest'
import { fetchPlannerCatalogue } from './plannerApi'

// Same wire shape consumed by PocketPlannerClientTest at the PH integration boundary.
const catalogue = {
  schemaVersion: 1, instanceId: 'qa', revision: 7,
  plans: [{ id: 'plan', name: 'Plan', testPlan: 'pp:qa:plan',
    streams: [{ id: 'stream', name: 'Stream' }],
    activities: [{ id: 'minimal', name: 'Minimal', streamId: 'stream', tag: 'pp.activity:' + 'A'.repeat(43) }],
  }],
}

afterEach(() => vi.unstubAllGlobals())

describe('Planner catalogue admission', () => {
  it('preserves the owner-supplied catalogue and references', async () => {
    const fetch = vi.fn(async () => Response.json(catalogue))
    vi.stubGlobal('fetch', fetch)
    expect(await fetchPlannerCatalogue()).toEqual(catalogue)
    expect(fetch).toHaveBeenCalledWith('/orchestrator/api/planner/catalogue', expect.objectContaining({ method: 'GET' }))
  })

  const plan = catalogue.plans[0]
  const invalidPlans = [
    ['null plan', null],
    ['null stream', { ...plan, streams: [null] }],
    ['invalid stream identity', { ...plan, streams: [{ id: 4, name: 'Stream' }] }],
    ['invalid stream display name', { ...plan, streams: [{ id: 'stream', name: {} }] }],
    ['null activity', { ...plan, activities: [null] }],
    ['missing activity identity', { ...plan, activities: [{ ...plan.activities[0], id: undefined }] }],
    ['invalid activity display name', { ...plan, activities: [{ ...plan.activities[0], name: {} }] }],
    ['invalid activity stream reference', { ...plan, activities: [{ ...plan.activities[0], streamId: null }] }],
    ['invalid activity tag', { ...plan, activities: [{ ...plan.activities[0], tag: false }] }],
  ] as const

  it.each(invalidPlans)('rejects %s before publishing it to consumers', async (_name, invalidPlan) => {
    vi.stubGlobal('fetch', vi.fn(async () => Response.json({ ...catalogue, plans: [invalidPlan] })))
    await expect(fetchPlannerCatalogue()).rejects.toThrow('Invalid PocketPlanner catalogue')
  })
})
