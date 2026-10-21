import { afterEach, expect, it, vi } from 'vitest'
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { PlannerRunPanel } from './PlannerRunPanel'

const swarmId = 'reused-synthetic-swarm'
const catalogue = {
  schemaVersion: 1, instanceId: 'synthetic-planner', revision: 1,
  plans: [{ id: 'plan', name: 'Master plan', testPlan: 'pp:synthetic-planner:plan',
    streams: [{ id: 'stream', name: 'Stream A' }],
    activities: [{ id: 'activity', streamId: 'stream', name: 'Minimal load', tag: 'pp.activity:' + 'A'.repeat(43) }],
  }],
}

function runFixture(runId: string) {
  return { swarmId, runId, testPlan: null, tags: [], description: null }
}

function chooseActivity() {
  fireEvent.change(screen.getByRole('combobox', { name: 'Plan' }), { target: { value: 'plan' } })
  fireEvent.change(screen.getByRole('combobox', { name: 'Stream' }), { target: { value: 'stream' } })
  fireEvent.change(screen.getByRole('combobox', { name: 'Activity' }), { target: { value: 'activity' } })
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

it('resets choices on a new run and links only the displayed exact run', async () => {
  const writes: string[] = []
  let currentRunId = 'old-run'
  vi.stubGlobal('fetch', vi.fn(async (input: string, init?: RequestInit) => {
    if (input === '/orchestrator/api/planner/catalogue') return Response.json(catalogue)
    if (input === `/orchestrator/api/planner/swarms/${swarmId}`) return Response.json(runFixture(currentRunId))
    for (const runId of ['old-run', 'new-run']) {
      if (input === `/orchestrator/api/planner/runs/${swarmId}/${runId}` && init?.method === 'POST') {
        writes.push(runId)
        return Response.json(runFixture(runId))
      }
    }
    throw new Error(`Unexpected request: ${input}`)
  }))
  const view = render(<PlannerRunPanel key="old-run" swarmId={swarmId} runId="old-run" canManage />)
  await screen.findByText('Run old-run')
  await screen.findByRole('option', { name: 'Master plan' })
  chooseActivity()
  expect((screen.getByRole('button', { name: 'Link Activity' }) as HTMLButtonElement).disabled).toBe(false)

  currentRunId = 'new-run'
  view.rerender(<PlannerRunPanel key="new-run" swarmId={swarmId} runId="new-run" canManage />)
  await screen.findByText('Run new-run')
  await screen.findByRole('option', { name: 'Master plan' })
  expect((screen.getByRole('combobox', { name: 'Plan' }) as HTMLSelectElement).value).toBe('')
  expect((screen.getByRole('button', { name: 'Link Activity' }) as HTMLButtonElement).disabled).toBe(true)
  chooseActivity()
  fireEvent.click(screen.getByRole('button', { name: 'Link Activity' }))
  await waitFor(() => expect(writes).toEqual(['new-run']))
})

it('ignores a superseded exact-run read even when the transport completes after abort', async () => {
  let finishOldRead!: (response: Response) => void
  let oldSignal: AbortSignal | undefined
  let reads = 0
  vi.stubGlobal('fetch', vi.fn(async (input: string, init?: RequestInit) => {
    if (input === '/orchestrator/api/planner/catalogue') return Response.json(catalogue)
    if (input === `/orchestrator/api/planner/swarms/${swarmId}`) {
      if (reads++ === 0) {
        oldSignal = init?.signal as AbortSignal
        return new Promise<Response>(resolve => { finishOldRead = resolve })
      }
      return Response.json(runFixture('new-run'))
    }
    throw new Error(`Unexpected request: ${input}`)
  }))
  const view = render(<PlannerRunPanel swarmId={swarmId} runId="old-run" canManage />)
  await waitFor(() => expect(finishOldRead).toBeTypeOf('function'))
  view.rerender(<PlannerRunPanel swarmId={swarmId} runId="new-run" canManage />)
  await screen.findByText('Run new-run')
  expect(oldSignal?.aborted).toBe(true)
  await act(async () => { finishOldRead(Response.json(runFixture('old-run'))) })
  await waitFor(() => expect(screen.queryByText('Run old-run')).toBeNull())
  expect(screen.getByText('Run new-run')).toBeTruthy()
})

it('refuses a current-run response that does not match the displayed snapshot', async () => {
  const writes: string[] = []
  vi.stubGlobal('fetch', vi.fn(async (input: string, init?: RequestInit) => {
    if (init?.method === 'POST') writes.push(input)
    if (input === '/orchestrator/api/planner/catalogue') return Response.json(catalogue)
    if (input === `/orchestrator/api/planner/swarms/${swarmId}`) return Response.json(runFixture('replacement-run'))
    throw new Error(`Unexpected request: ${input}`)
  }))
  render(<PlannerRunPanel swarmId={swarmId} runId="displayed-run" canManage />)
  expect((await screen.findByRole('alert')).textContent).toContain('Swarm run changed')
  expect(screen.queryByText('Run replacement-run')).toBeNull()
  expect((screen.getByRole('button', { name: 'Link Activity' }) as HTMLButtonElement).disabled).toBe(true)
  expect(writes).toEqual([])
})
