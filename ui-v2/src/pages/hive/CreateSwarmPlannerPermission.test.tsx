import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import deploymentAccess from '../../../../auth-service/src/test/resources/auth-access-deployment-manager.json'
import folderAccess from '../../../../auth-service/src/test/resources/auth-access-folder-manager.json'
import { CreateSwarmModal } from './CreateSwarmModal'

vi.mock('../../lib/authContext', () => ({ useAuth: () => caller }))
vi.mock('../../lib/useScenarioCatalogue', () => ({ useScenarioCatalogue: () => catalogue }))
vi.mock('../../components/scenarios/ScenarioTree', () => ({
  ScenarioTree: ({ onSelectBundle }: { onSelectBundle: (key: string) => void }) =>
    <button type="button" onClick={() => onSelectBundle('team/scenario')}>Synthetic runnable scenario</button>,
}))
vi.mock('./CreateSwarmScenarioDetail', () => ({ CreateSwarmScenarioDetail: () => <p>Synthetic scenario details</p> }))

const caller = { ...deploymentAccess, user: { id: 'synthetic-operator', grants: [] } }
const catalogue = { loading: false, error: null, entries: [{
  bundleKey: 'team/scenario', bundlePath: 'team/scenario', folderPath: 'team', id: 'scenario',
  name: 'Synthetic runnable scenario', description: null, controllerImage: null, bees: [], defunct: false, defunctReason: null,
}] }
const swarmId = 'planner-create-synthetic'
const selection = { instanceId: 'synthetic-planner', revision: 7, planId: 'plan', streamId: 'stream', activityId: 'activity' }
const accepted = {
  correlationId: 'synthetic-create', idempotencyKey: 'synthetic-idempotency',
  operationUrl: `/api/swarms/${swarmId}/operations/synthetic-create`, outcomeTopic: 'synthetic-outcome', timeoutMs: 3000,
}
const operation = { ...accepted, swarmId, type: 'CREATE', state: 'SUCCEEDED', runtime: { runId: 'exact-created-run' } }
const plannerCatalogue = { schemaVersion: 1, instanceId: selection.instanceId, revision: 7, plans: [{
  id: 'plan', name: 'Synthetic plan', testPlan: 'pp:synthetic-planner:plan',
  streams: [{ id: 'stream', name: 'Synthetic stream' }],
  activities: [{ id: 'activity', streamId: 'stream', name: 'Synthetic activity', tag: 'pp.activity:' + 'A'.repeat(43) }],
}] }
let requests: Array<{ path: string; method: string; body: unknown }>
let unexpected: string[]

function ingress(options: {
  validate?: () => Promise<Response>
  create?: () => Promise<Response>
  readOperation?: () => Promise<Response>
  link?: () => Promise<Response>
} = {}) {
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const request = input instanceof Request ? input : new Request(new URL(String(input), window.location.origin), init)
    const path = new URL(request.url).pathname
    requests.push({ path, method: request.method, body: request.method === 'POST' ? await request.json() : null })
    if (path === '/scenario-manager/network-profiles' || path === '/scenario-manager/scenarios/scenario/suts') return Response.json([])
    if (path === '/scenario-manager/scenarios/scenario/variables') return new Response(null, { status: 404 })
    if (path.endsWith('/tree')) return Response.json({ bundleKey: 'team/scenario', nodes: [] })
    if (path === '/orchestrator/api/planner/catalogue') return Response.json(plannerCatalogue)
    if (path === '/orchestrator/api/planner/validate') return options.validate ? options.validate() : caller.canManageDeployment
      ? Response.json({}) : Response.json({ message: 'Deployment ALL permission required' }, { status: 403 })
    if (path === `/orchestrator/api/swarms/${swarmId}/create`) return options.create ? options.create() : Response.json(accepted, { status: 202 })
    if (path === `/orchestrator${accepted.operationUrl}`) return options.readOperation ? options.readOperation() : Response.json(operation)
    if (path === `/orchestrator/api/planner/runs/${swarmId}/exact-created-run`) return options.link ? options.link() : caller.canManageDeployment
      ? Response.json({ swarmId, runId: 'exact-created-run', testPlan: plannerCatalogue.plans[0].testPlan, tags: [], description: null })
      : Response.json({ message: 'Deployment ALL permission required' }, { status: 403 })
    unexpected.push(path)
    throw new Error(`Unexpected request: ${request.method} ${path}`)
  }))
}

async function modal() {
  const onCreated = vi.fn()
  const onClose = vi.fn()
  const view = render(<CreateSwarmModal open onCreated={onCreated} onClose={onClose} />)
  fireEvent.click(screen.getByRole('button', { name: 'Synthetic runnable scenario' }))
  await screen.findByText('Synthetic scenario details')
  fireEvent.change(screen.getByRole('textbox', { name: 'Swarm ID' }), { target: { value: swarmId } })
  return { onCreated, onClose, rerender: () => view.rerender(<CreateSwarmModal open onCreated={onCreated} onClose={onClose} />) }
}

async function chooseActivity() {
  await screen.findByRole('option', { name: 'Synthetic plan' })
  fireEvent.change(screen.getByRole('combobox', { name: 'Plan' }), { target: { value: 'plan' } })
  fireEvent.change(screen.getByRole('combobox', { name: 'Stream' }), { target: { value: 'stream' } })
  fireEvent.change(screen.getByRole('combobox', { name: 'Activity' }), { target: { value: 'activity' } })
}

async function selectPlanner() {
  fireEvent.click(screen.getByRole('checkbox', { name: 'Link to PocketPlanner' }))
  await chooseActivity()
}

function posts(suffix: string) { return requests.filter(request => request.method === 'POST' && request.path.endsWith(suffix)) }
function revoke() { Object.assign(caller, folderAccess) }
function restore() { Object.assign(caller, deploymentAccess) }

beforeEach(() => { requests = []; unexpected = []; restore() })
afterEach(() => { cleanup(); vi.unstubAllGlobals(); expect(unexpected).toEqual([]) })

describe('Create Swarm Planner deployment permission', () => {
  it('disables optional Planner linking for a folder manager and still creates their runnable swarm', async () => {
    revoke(); ingress()
    const view = await modal()
    expect(screen.getByRole('checkbox', { name: 'Link to PocketPlanner' })).toHaveProperty('disabled', true)
    fireEvent.click(screen.getByRole('button', { name: 'Create swarm', exact: true }))
    await waitFor(() => expect(view.onCreated).toHaveBeenCalledOnce())
    expect(posts('/create')).toHaveLength(1)
    expect(posts('/validate')).toEqual([])
    expect(requests.some(request => request.path.includes('/planner/'))).toBe(false)
  })

  it('discards a retained optional selection on permission loss without blocking CREATE', async () => {
    ingress(); const view = await modal(); await selectPlanner()
    revoke(); view.rerender()
    fireEvent.click(screen.getByRole('button', { name: 'Create swarm', exact: true }))
    await waitFor(() => expect(view.onCreated).toHaveBeenCalledOnce())
    expect(posts('/create')).toHaveLength(1)
    expect(posts('/validate')).toEqual([])
    expect(posts('/exact-created-run')).toEqual([])
  })

  it('validates and links the exact accepted run for a deployment manager', async () => {
    ingress(); const view = await modal(); await selectPlanner()
    fireEvent.click(screen.getByRole('button', { name: 'Create swarm', exact: true }))
    await waitFor(() => expect(view.onCreated).toHaveBeenCalledOnce())
    expect(posts('/create')).toHaveLength(1)
    expect(posts('/validate').map(request => request.body)).toEqual([selection])
    expect(posts('/exact-created-run').map(request => request.body)).toEqual([selection])
  })

  it('retains an accepted CREATE across lost permission and retries only its association after access returns', async () => {
    let links = 0
    ingress({ link: async () => ++links === 1 ? Response.json({ message: 'Planner changed' }, { status: 409 }) : Response.json({ runId: 'exact-created-run' }) })
    const view = await modal(); await selectPlanner()
    fireEvent.click(screen.getByRole('button', { name: 'Create swarm', exact: true }))
    await screen.findByText('Planner changed')
    revoke(); view.rerender()
    expect(screen.getByRole('button', { name: 'Retry association only' })).toHaveProperty('disabled', true)
    fireEvent.submit(screen.getByRole('button', { name: 'Retry association only' }).closest('form')!)
    await waitFor(() => expect(posts('/exact-created-run')).toHaveLength(1))
    expect(posts('/create')).toHaveLength(1)
    restore(); view.rerender(); await chooseActivity()
    fireEvent.click(screen.getByRole('button', { name: 'Retry association only' }))
    await waitFor(() => expect(view.onCreated).toHaveBeenCalledOnce())
    expect(posts('/create')).toHaveLength(1)
    expect(posts('/exact-created-run')).toHaveLength(2)
  })

  it('continues allowed CREATE without Planner if deployment permission disappears during validation', async () => {
    let finish!: (response: Response) => void
    ingress({ validate: () => new Promise(resolve => { finish = resolve }) })
    const view = await modal(); await selectPlanner()
    fireEvent.click(screen.getByRole('button', { name: 'Create swarm', exact: true }))
    await waitFor(() => expect(finish).toBeTypeOf('function'))
    revoke(); view.rerender()
    await act(async () => { finish(Response.json({ message: 'Deployment ALL permission required' }, { status: 403 })) })
    await waitFor(() => expect(view.onCreated).toHaveBeenCalledOnce())
    expect(posts('/create')).toHaveLength(1)
    expect(posts('/exact-created-run')).toEqual([])
  })

  it('preserves CREATE acceptance when permission disappears before the acceptance response arrives', async () => {
    let finish!: (response: Response) => void
    ingress({ create: () => new Promise(resolve => { finish = resolve }) })
    const view = await modal(); await selectPlanner()
    fireEvent.click(screen.getByRole('button', { name: 'Create swarm', exact: true }))
    await waitFor(() => expect(finish).toBeTypeOf('function'))
    revoke(); view.rerender()
    await act(async () => { finish(Response.json(accepted, { status: 202 })) })
    await waitFor(() => expect(screen.getByRole('button', { name: 'Retry association only' })).toHaveProperty('disabled', true))
    expect(posts('/exact-created-run')).toEqual([])
    restore(); view.rerender(); await chooseActivity()
    fireEvent.click(screen.getByRole('button', { name: 'Retry association only' }))
    await waitFor(() => expect(view.onCreated).toHaveBeenCalledOnce())
    expect(posts('/create')).toHaveLength(1)
    expect(posts('/exact-created-run')).toHaveLength(1)
  })

  it('requires a fresh explicit selection after access was lost and restored before CREATE acceptance arrived', async () => {
    let finish!: (response: Response) => void
    ingress({ create: () => new Promise(resolve => { finish = resolve }) })
    const view = await modal(); await selectPlanner()
    fireEvent.click(screen.getByRole('button', { name: 'Create swarm', exact: true }))
    await waitFor(() => expect(finish).toBeTypeOf('function'))
    revoke(); view.rerender()
    restore(); view.rerender()
    await act(async () => { finish(Response.json(accepted, { status: 202 })) })
    expect(posts('/exact-created-run')).toEqual([])
    expect(view.onCreated).not.toHaveBeenCalled()
    expect(await screen.findByRole('button', { name: 'Retry association only' })).toHaveProperty('disabled', true)
    await chooseActivity()
    fireEvent.click(screen.getByRole('button', { name: 'Retry association only' }))
    await waitFor(() => expect(view.onCreated).toHaveBeenCalledOnce())
    expect(posts('/create')).toHaveLength(1)
    expect(posts('/exact-created-run')).toHaveLength(1)
  })

  it('requires a fresh explicit selection after access was lost and restored before a pending operation read completed', async () => {
    let finish!: (response: Response) => void
    let reads = 0
    ingress({ readOperation: () => ++reads === 1 ? new Promise(resolve => { finish = resolve }) : Promise.resolve(Response.json(operation)) })
    const view = await modal(); await selectPlanner()
    fireEvent.click(screen.getByRole('button', { name: 'Create swarm', exact: true }))
    await waitFor(() => expect(finish).toBeTypeOf('function'))
    revoke(); view.rerender()
    restore(); view.rerender()
    await act(async () => { finish(Response.json(operation)) })
    expect(posts('/exact-created-run')).toEqual([])
    expect(view.onCreated).not.toHaveBeenCalled()
    expect(screen.getByRole('button', { name: 'Retry association only' })).toHaveProperty('disabled', true)
    await chooseActivity()
    fireEvent.click(screen.getByRole('button', { name: 'Retry association only' }))
    await waitFor(() => expect(view.onCreated).toHaveBeenCalledOnce())
    expect(posts('/create')).toHaveLength(1)
    expect(posts('/exact-created-run')).toHaveLength(1)
  })

  it('does not submit an association when permission is lost while the operation read is in flight', async () => {
    let finish!: (response: Response) => void
    let reads = 0
    ingress({ readOperation: () => ++reads === 1 ? new Promise(resolve => { finish = resolve }) : Promise.resolve(Response.json(operation)) })
    const view = await modal(); await selectPlanner()
    fireEvent.click(screen.getByRole('button', { name: 'Create swarm', exact: true }))
    await waitFor(() => expect(finish).toBeTypeOf('function'))
    revoke(); view.rerender()
    await act(async () => { finish(Response.json(operation)) })
    await waitFor(() => expect(screen.getByRole('button', { name: 'Retry association only' })).toHaveProperty('disabled', true))
    expect(posts('/exact-created-run')).toEqual([])
    restore(); view.rerender(); await chooseActivity()
    fireEvent.click(screen.getByRole('button', { name: 'Retry association only' }))
    await waitFor(() => expect(view.onCreated).toHaveBeenCalledOnce())
    expect(posts('/create')).toHaveLength(1)
    expect(posts('/exact-created-run')).toHaveLength(1)
  })
})
