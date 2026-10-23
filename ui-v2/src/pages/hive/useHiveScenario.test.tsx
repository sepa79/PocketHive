import { act, cleanup, renderHook, waitFor } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import runtimeState from '../../../../common/swarm-model/src/test/resources/io/pockethive/swarm/model/lifecycle/swarm-state-initial.json'
import type { AuthenticatedUser } from '../../lib/auth'
import type { SwarmStateSnapshot } from '../../lib/SwarmStateSnapshot'
import { parseSwarmStateSnapshot } from '../../lib/parseSwarmStateSnapshot'
import { useSwarmSnapshot } from './useSwarmSnapshot'
import { useHiveScenario } from './useHiveScenario'

const caller: AuthenticatedUser = {
  id: 'synthetic-operator', username: 'synthetic-operator', displayName: 'Synthetic',
  active: true, authProvider: 'DEV', grants: [],
}
const snapshot = parseSwarmStateSnapshot(runtimeState, runtimeState.id)
const templateId = runtimeState.templateId
const scenario = {
  protocolVersion: '2.0.0', id: templateId, name: 'Synthetic authored scenario',
  description: 'Isolated scenario lifecycle fixture',
  template: {
    image: 'controller:synthetic',
    bees: [{
      role: 'generator', image: 'generator:synthetic',
      work: { in: { in: 'input' }, out: { out: 'output' } },
      ports: [{ id: 'in', direction: 'in' }, { id: 'out', direction: 'out' }],
      config: { message: { body: 'Authored value is not runtime config' } },
    }],
  },
  topology: { version: 1, edges: [{
    id: 'loop', from: { role: 'generator', port: 'out' }, to: { role: 'generator', port: 'in' },
  }] },
}
type Props = {
  swarmId: string | null
  snapshot: SwarmStateSnapshot | null
  caller: AuthenticatedUser | null
  token: string | null
}
const initialProps: Props = { swarmId: snapshot.id, snapshot, caller, token: 'synthetic-token' }

function renderScenario(props = initialProps) {
  return renderHook(current => useHiveScenario(current.swarmId, current.snapshot, current.caller, current.token), {
    initialProps: props,
  })
}

afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.useRealTimers() })

it('loads the authoring projection through ingress from a producer-owned runtime fixture', async () => {
  const fetch = vi.fn().mockImplementation(async () => Response.json(scenario))
  vi.stubGlobal('fetch', fetch)
  const { result, rerender } = renderScenario()
  await waitFor(() => expect(result.current.selectedScenario?.id).toBe(templateId))
  expect(result.current).toMatchObject({ scenarioLoading: false, scenarioError: null })
  expect(fetch.mock.calls[0][0]).toBe(`/scenario-manager/scenarios/${templateId}`)
  expect(result.current.selectedScenario).toEqual({
    id: templateId, name: scenario.name, description: scenario.description,
    template: { image: scenario.template.image, bees: [{
      role: 'generator', image: 'generator:synthetic', work: scenario.template.bees[0].work,
      ports: scenario.template.bees[0].ports,
    }] },
    topology: scenario.topology,
  })
  rerender({ ...initialProps, snapshot: { ...snapshot, observationStale: true } })
  expect(fetch).toHaveBeenCalledTimes(1)
  expect(result.current.selectedScenario?.name).toBe(scenario.name)
})

it('aborts A and discards its late scenario response after B completes', async () => {
  const pending: Array<(value: Response) => void> = []
  const fetch = vi.fn<(input: string, init?: RequestInit) => Promise<Response>>(
    () => new Promise<Response>(resolve => pending.push(resolve)),
  )
  vi.stubGlobal('fetch', fetch)
  const { result, rerender } = renderScenario()
  rerender({ ...initialProps, swarmId: 'swarm-B', snapshot: { ...snapshot, id: 'swarm-B', templateId: 'scenario-B' } })
  expect(fetch.mock.calls[0][1]?.signal?.aborted).toBe(true)
  await act(async () => pending[1](Response.json({ ...scenario, id: 'scenario-B', name: 'Scenario B' })))
  await waitFor(() => expect(result.current.selectedScenario?.name).toBe('Scenario B'))
  await act(async () => pending[0](Response.json(scenario)))
  expect(result.current.selectedScenario?.id).toBe('scenario-B')
  expect(result.current.scenarioError).toBeNull()
})

it.each(['swarm', 'run', 'template', 'caller', 'token'] as const)(
  'immediately hides the previous scenario when the %s changes', async changed => {
    const fetch = vi.fn().mockResolvedValueOnce(Response.json(scenario))
      .mockImplementation(() => new Promise<Response>(() => {}))
    vi.stubGlobal('fetch', fetch)
    const { result, rerender } = renderScenario()
    await waitFor(() => expect(result.current.selectedScenario).not.toBeNull())
    rerender({
      ...initialProps,
      swarmId: changed === 'swarm' ? 'other-swarm' : snapshot.id,
      snapshot: { ...snapshot,
        id: changed === 'swarm' ? 'other-swarm' : snapshot.id,
        runId: changed === 'run' ? 'other-run' : snapshot.runId,
        templateId: changed === 'template' ? 'other-template' : templateId,
      },
      caller: changed === 'caller' ? { ...caller, id: 'other-caller' } : caller,
      token: changed === 'token' ? 'other-token' : initialProps.token,
    })
    expect(result.current).toEqual({ selectedScenario: null, scenarioLoading: true, scenarioError: null })
    expect(fetch).toHaveBeenCalledTimes(2)
    expect(fetch.mock.calls[0][1].signal.aborted).toBe(true)
  },
)

it.each(['caller', 'token', 'run'] as const)('discards an in-flight response from the previous %s', async changed => {
  const pending: Array<(value: Response) => void> = []
  const fetch = vi.fn(() => new Promise<Response>(resolve => pending.push(resolve)))
  vi.stubGlobal('fetch', fetch)
  const { result, rerender } = renderScenario()
  rerender({ ...initialProps,
    caller: changed === 'caller' ? { ...caller, id: 'other-caller' } : caller,
    token: changed === 'token' ? 'other-token' : initialProps.token,
    snapshot: changed === 'run' ? { ...snapshot, runId: 'other-run' } : snapshot,
  })
  await act(async () => pending[1](Response.json({ ...scenario, name: 'Current scenario' })))
  await waitFor(() => expect(result.current.selectedScenario?.name).toBe('Current scenario'))
  await act(async () => pending[0](new Response('Obsolete read failed', { status: 403 })))
  expect(result.current.selectedScenario?.name).toBe('Current scenario')
  expect(result.current.scenarioError).toBeNull()
})

it.each([null, '', '   '])('clears loaded details without a request for template %j', async missingTemplate => {
  const fetch = vi.fn().mockImplementation(async () => Response.json(scenario))
  vi.stubGlobal('fetch', fetch)
  const { result, rerender } = renderScenario()
  await waitFor(() => expect(result.current.selectedScenario).not.toBeNull())
  rerender({ ...initialProps, snapshot: { ...snapshot, templateId: missingTemplate } })
  expect(result.current).toEqual({ selectedScenario: null, scenarioLoading: false, scenarioError: null })
  expect(fetch).toHaveBeenCalledTimes(1)
})

it.each(['runtime', 'selection'] as const)('cancels a pending scenario when the %s disappears', async missing => {
  let finish: (value: Response) => void = () => {}
  const fetch = vi.fn<(input: string, init?: RequestInit) => Promise<Response>>(
    () => new Promise<Response>(resolve => { finish = resolve }),
  )
  vi.stubGlobal('fetch', fetch)
  const { result, rerender } = renderScenario()
  rerender({ ...initialProps, swarmId: missing === 'selection' ? null : snapshot.id,
    snapshot: missing === 'runtime' ? null : snapshot })
  expect(fetch.mock.calls[0][1]?.signal?.aborted).toBe(true)
  await act(async () => finish(Response.json(scenario)))
  expect(result.current).toEqual({ selectedScenario: null, scenarioLoading: false, scenarioError: null })
  expect(fetch).toHaveBeenCalledTimes(1)
})

it.each([
  { label: 'failed', response: () => new Response('Runtime unavailable', { status: 503 }) },
  { label: 'missing', response: () => new Response('', { status: 404 }) },
  { label: 'malformed', response: () => Response.json({ ...runtimeState, observation: [] }) },
])('clears the scenario after a $label runtime refresh', async ({ response }) => {
  let runtimeResponse = () => Response.json(runtimeState)
  const fetch = vi.fn(async (input: string) => input.startsWith('/orchestrator/')
    ? runtimeResponse() : Response.json(scenario))
  vi.stubGlobal('fetch', fetch)
  const { result } = renderHook(() => {
    const runtime = useSwarmSnapshot(snapshot.id, caller, initialProps.token)
    return { runtime, scenario: useHiveScenario(snapshot.id, runtime.snapshot, caller, initialProps.token) }
  })
  await waitFor(() => expect(result.current.scenario.selectedScenario?.id).toBe(templateId))
  runtimeResponse = response
  await act(() => result.current.runtime.reload())
  expect(result.current.runtime.error).not.toBeNull()
  expect(result.current.scenario).toEqual({ selectedScenario: null, scenarioLoading: false, scenarioError: null })
  expect(fetch.mock.calls.filter(([input]) => input.startsWith('/scenario-manager/'))).toHaveLength(1)
})

it.each([null, [], {}, { ...scenario, id: 'other-scenario' }, { ...scenario, id: null }])(
  'reports an invalid scenario reference explicitly: %j', async payload => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json(payload)))
    const { result } = renderScenario()
    await waitFor(() => expect(result.current.scenarioLoading).toBe(false))
    expect(result.current.selectedScenario).toBeNull()
    expect(result.current.scenarioError).toContain(templateId)
  },
)

it('reports an unsuccessful scenario read and aborts on unmount', async () => {
  const fetch = vi.fn().mockResolvedValue(new Response('Scenario is forbidden', { status: 403 }))
  vi.stubGlobal('fetch', fetch)
  const { result, unmount } = renderScenario()
  await waitFor(() => expect(result.current.scenarioError).toContain('Scenario is forbidden'))
  expect(result.current.selectedScenario).toBeNull()
  expect(result.current.scenarioLoading).toBe(false)
  unmount()
  expect(fetch.mock.calls[0][1].signal.aborted).toBe(true)
})

it('never loads an authored scenario without a current matching runtime selection', () => {
  const fetch = vi.fn()
  vi.stubGlobal('fetch', fetch)
  const { result, rerender } = renderScenario({ ...initialProps, snapshot: null })
  expect(result.current.selectedScenario).toBeNull()
  rerender({ ...initialProps, snapshot: { ...snapshot, id: 'another-swarm' } })
  expect(result.current.selectedScenario).toBeNull()
  expect(fetch).not.toHaveBeenCalled()
})
