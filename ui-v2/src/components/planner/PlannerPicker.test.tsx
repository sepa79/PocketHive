import { describe, expect, it, vi } from 'vitest'
import { renderToStaticMarkup } from 'react-dom/server'
import { PlannerPicker } from './PlannerPicker'

describe('Planner picker', () => {
  it('renders only master catalogue names, membership and the existing plan', () => {
    const plan = { id: 'plan', name: 'Master plan', testPlan: 'pp:qa:plan', streams: [{ id: 'stream', name: 'Stream A' }], activities: [
      { id: 'minimal', name: 'Minimal', streamId: 'stream', tag: 'tag-a' }, { id: 'other', name: 'Other stream task', streamId: 'other', tag: 'tag-b' },
    ] }
    const html = renderToStaticMarkup(<PlannerPicker testPlan="pp:qa:plan" state={{
      catalogue: { schemaVersion: 1, instanceId: 'qa', revision: 1, plans: [plan, { ...plan, id: 'another', name: 'Another plan', testPlan: 'pp:qa:another' }] },
      plan, planId: 'plan', streamId: 'stream', activityId: '', selection: null, loading: false, error: null,
      selectPlan: vi.fn(), selectStream: vi.fn(), selectActivity: vi.fn(), reload: vi.fn(),
    }} />)
    expect(html).toContain('Master plan')
    expect(html).toContain('Stream A')
    expect(html).toContain('Minimal')
    expect(html).not.toContain('Another plan')
    expect(html).not.toContain('Other stream task')
    expect(html).toContain('does not mark an Activity Started or Done')
  })
})
