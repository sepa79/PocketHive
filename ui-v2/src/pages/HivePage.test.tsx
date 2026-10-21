import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ToolsBarProvider } from '../components/ToolsBarContext'
import { HivePage } from './HivePage'
import { observedStateFixture } from '../testFixtures/swarmState'
import { swarmTuningFixture } from '../testFixtures/swarmTuning'

vi.mock('../lib/authContext', () => ({
  useAuth: () => authenticatedCaller,
}))

const authenticatedCaller = {
  status: 'authenticated',
  accessStatus: 'ready',
  canAccessPocketHive: true,
  canRunPocketHive: true,
  user: {
    id: 'synthetic-hive-operator',
    username: 'synthetic-hive-operator',
    displayName: 'Synthetic Hive Operator',
    active: true,
    authProvider: 'DEV',
    grants: [],
  },
}

const swarmId = 'synthetic-detail-swarm'
const templateId = 'synthetic-detail-scenario'
const runId = 'synthetic-detail-run'
const observedAt = '2026-07-22T12:34:56Z'
const controllerImage = 'ghcr.io/pockethive/swarm-controller:synthetic'
const workerImage = 'ghcr.io/pockethive/generator:synthetic'
const scenarioName = 'Synthetic swarm detail regression'

function workerFixture(instance = `${swarmId}-generator-1`, enabled = false, tps = 0) {
  return {
    ...observedStateFixture().observation.workers[0],
    instance,
    enabled,
    tps,
    stale: false,
    lastSeenAt: observedAt,
    ioState: { work: { input: 'ok', output: 'ok' } },
    runtime: {
      templateId,
      runId,
      containerId: null,
      image: workerImage,
      stackName: null,
    },
    config: { message: { body: `Synthetic payload for ${instance}` } },
  }
}

function swarmFixture(workers = [workerFixture()], running = false) {
  return {
    ...observedStateFixture(),
    id: swarmId,
    runId,
    templateId,
    controllerImage,
    runtimeIntent: 'PRESENT',
    workloadIntent: running ? 'RUNNING' : 'STOPPED',
    controllerState: 'READY',
    workloadState: running ? 'RUNNING' : 'STOPPED',
    health: 'HEALTHY',
    runtimeResourceState: 'PRESENT',
    observedAt,
    observationStale: false,
    activeOperation: null,
    bees: workers.map(worker => ({
      role: worker.role,
      instance: worker.instance,
      image: worker.runtime.image,
    })),
    observation: {
      controllerState: 'READY',
      workloadState: running ? 'RUNNING' : 'STOPPED',
      health: 'HEALTHY',
      startupReady: true,
      startupArtifactSha256: 'a'.repeat(64),
      watermarkAt: observedAt,
      expectedWorkers: workers.map(worker => ({ role: worker.role, instance: worker.instance })),
      workers,
    },
  }
}

const scenario = {
  protocolVersion: '2.0.0',
  id: templateId,
  name: scenarioName,
  description: 'Authored synthetic scenario; not captured runtime data.',
  template: {
    image: controllerImage,
    bees: [{
      role: 'generator',
      image: workerImage,
      work: { out: { out: 'synthetic-output' } },
      ports: [{ id: 'out', direction: 'out' }],
    }],
  },
  topology: { version: 1, edges: [] },
}

const capabilities = [{
  schemaVersion: '1.0',
  capabilitiesVersion: 'synthetic',
  image: { name: 'ghcr.io/pockethive/generator', tag: 'synthetic' },
  role: 'generator',
  config: [{ name: 'message.body', type: 'string', liveMutable: true }],
  actions: [],
  panels: [],
}]

let unexpectedRequests: string[]

function interceptIngress({
  state = swarmFixture(),
  canRun = true,
  canManage = true,
  detailResponse,
  authoredScenario = scenario,
  capabilityCatalogue = capabilities,
  configResponse,
}: {
  state?: Record<string, unknown>
  canRun?: boolean
  canManage?: boolean
  detailResponse?: () => Response
  authoredScenario?: unknown
  capabilityCatalogue?: unknown
  configResponse?: (request: Request) => Promise<Response>
} = {}) {
  const responses = new Map<string, unknown>([
    ['/orchestrator/api/swarms', [state]],
    [`/orchestrator/api/swarms/${swarmId}`, state],
    ['/orchestrator/api/access/swarms', { swarms: [{ swarmId, canRun, canManage }] }],
    [`/orchestrator/api/swarms/${swarmId}/journal/page?limit=50&severity=ERROR`, {
      items: [], nextCursor: null, hasMore: false,
    }],
    ['/orchestrator/api/planner/catalogue', {
      schemaVersion: 1, instanceId: 'synthetic-planner', revision: 0, plans: [],
    }],
    [`/orchestrator/api/planner/swarms/${swarmId}`, {
      swarmId, runId, testPlan: null, tags: [], description: null,
    }],
    [`/scenario-manager/scenarios/${templateId}`, authoredScenario],
    ['/scenario-manager/api/capabilities?all=true', capabilityCatalogue],
    ['/scenario-manager/network-profiles', []],
  ])

  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const request = input instanceof Request
      ? input
      : new Request(new URL(String(input), window.location.origin), init)
    const url = new URL(request.url)
    const path = url.pathname + url.search
    if (request.method === 'POST' && configResponse &&
      path.startsWith('/orchestrator/api/components/') && path.endsWith('/config')) return configResponse(request)
    if (request.method === 'GET' && path === `/network-proxy-manager/api/network/bindings/${swarmId}`) {
      return new Response(null, { status: 404 })
    }
    if (request.method !== 'GET' || url.origin !== window.location.origin || !responses.has(path)) {
      const unexpected = `${request.method} ${request.url}`
      unexpectedRequests.push(unexpected)
      throw new Error(`Unexpected Hive test request: ${unexpected}`)
    }
    if (path === `/orchestrator/api/swarms/${swarmId}` && detailResponse) return detailResponse()
    return Response.json(responses.get(path))
  }))
}

async function renderHive(waitForSnapshot = true) {
  render(
    <MemoryRouter initialEntries={[`/hive/${swarmId}`]}>
      <ToolsBarProvider>
        <Routes>
          <Route path="/hive/:swarmId" element={<HivePage />} />
          <Route path="/journal/swarms/:swarmId" element={<JournalLocation />} />
        </Routes>
      </ToolsBarProvider>
    </MemoryRouter>,
  )
  if (waitForSnapshot) await screen.findByText('Runtime snapshot')
}

function snapshotField(label: string) {
  const field = screen.getByText(label).parentElement
  if (!field) throw new Error(`Missing snapshot field: ${label}`)
  return within(field)
}

function JournalLocation() {
  const location = useLocation()
  return <p>{location.pathname + location.search}</p>
}

async function openTuningEditor(role: 'generator' | 'processor') {
  fireEvent.click(screen.getByRole('tab', { name: 'Scenario' }))
  await screen.findByText('Synthetic runtime tuning')
  fireEvent.click(screen.getByRole('button', { name: new RegExp(`^${role} `) }))
  fireEvent.click(await screen.findByRole('button', { name: 'Edit config' }))
  return screen.getByRole('dialog')
}

function tuningField(dialog: HTMLElement, path: string, role: 'spinbutton' | 'combobox' = 'spinbutton') {
  fireEvent.change(within(dialog).getByPlaceholderText('Search config fields'), { target: { value: path } })
  return within(dialog).getByRole(role)
}

function acceptedConfigResponse(idempotencyKey: string) {
  return Response.json({
    correlationId: 'synthetic-config-operation', idempotencyKey,
    operationUrl: `/api/swarms/${swarmId}/operations/synthetic-config-operation`,
    outcomeTopic: `event.outcome.config-update.${swarmId}.orchestrator.synthetic`, timeoutMs: 60000,
  }, { status: 202 })
}

async function openScenario() {
  fireEvent.click(screen.getByRole('tab', { name: 'Scenario' }))
  await screen.findByText(scenarioName)
}

beforeEach(() => {
  unexpectedRequests = []
  window.sessionStorage.clear()
  vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
})

afterEach(() => {
  cleanup()
  vi.useRealTimers()
  vi.unstubAllGlobals()
  expect(unexpectedRequests).toEqual([])
})

describe('Hive swarm detail through the canonical REST projection', () => {
  it('reports an obsolete detail payload explicitly and recovers through retry', async () => {
    let detail = () => Response.json({ receivedAt: observedAt, staleAfterSec: 15, envelope: {} })
    interceptIngress({ detailResponse: () => detail() })
    await renderHive(false)
    const alert = await screen.findByRole('alert')
    expect(alert.textContent).toContain('Invalid swarm state response')
    expect(screen.queryByRole('region', { name: 'Runtime snapshot' })).toBeNull()
    fireEvent.click(screen.getByRole('tab', { name: 'Scenario' }))
    expect(screen.getByRole('alert').textContent).toContain('Invalid swarm state response')
    expect(screen.queryByRole('button', { name: 'Edit config' })).toBeNull()
    detail = () => Response.json(swarmFixture())
    fireEvent.click(screen.getByRole('button', { name: 'Retry runtime snapshot' }))
    await screen.findByText(scenarioName)
    expect(screen.queryByRole('alert')).toBeNull()
    expect(await screen.findByRole('button', { name: 'Edit config' })).toHaveProperty('disabled', false)
    fireEvent.click(screen.getByRole('tab', { name: 'Snapshot' }))
    expect(snapshotField('Run ID').getByText(runId)).toBeDefined()
  })

  it('clears runtime config controls and reports refresh failure while Scenario stays selected', async () => {
    let detail = () => Response.json(swarmFixture())
    interceptIngress({ detailResponse: () => detail() })
    await renderHive()
    await openScenario()
    expect(await screen.findByRole('button', { name: 'Edit config' })).toHaveProperty('disabled', false)
    detail = () => new Response('Synthetic runtime unavailable', { status: 503 })
    await act(async () => { vi.advanceTimersByTime(5000) })
    expect((await screen.findByRole('alert')).textContent).toContain('Synthetic runtime unavailable')
    expect(screen.getByRole('tab', { name: 'Scenario' }).getAttribute('aria-selected')).toBe('true')
    expect(screen.queryByText(scenarioName)).toBeNull()
    expect(screen.queryByRole('button', { name: 'Edit config' })).toBeNull()
    expect(screen.queryByText('runtime live')).toBeNull()
    detail = () => Response.json(swarmFixture())
    fireEvent.click(screen.getByRole('button', { name: 'Retry runtime snapshot' }))
    await screen.findByText(scenarioName)
    expect(await screen.findByRole('button', { name: 'Edit config' })).toHaveProperty('disabled', false)
  })

  it('opens the journal for the run owned by the detail response', async () => {
    interceptIngress()
    await renderHive()
    fireEvent.click(screen.getByRole('button', { name: 'Open journal' }))
    expect(await screen.findByText(`/journal/swarms/${swarmId}?runId=${runId}`)).toBeDefined()
  })

  it('presents stale observation explicitly without computing its own freshness', async () => {
    interceptIngress({ state: { ...swarmFixture(), observationStale: true } })
    await renderHive()
    expect(screen.getByText('Observation: Stale')).toBeDefined()
    expect(snapshotField('Controller state').getByText('READY')).toBeDefined()
  })

  it.each([
    { enabled: false, tps: 0, workloadState: 'STOPPED', enabledLabel: 'disabled' },
    { enabled: true, tps: 37, workloadState: 'RUNNING', enabledLabel: 'enabled' },
  ])('renders $workloadState root status and the observed worker with operator controls', async ({
    enabled, tps, workloadState, enabledLabel,
  }) => {
    const worker = workerFixture(`${swarmId}-generator-1`, enabled, tps)
    interceptIngress({ state: swarmFixture([worker], enabled) })
    await renderHive()

    expect(snapshotField('Controller state').getByText('READY')).toBeDefined()
    expect(snapshotField('Workload state').getByText(workloadState)).toBeDefined()
    expect(snapshotField('Health').getByText('HEALTHY')).toBeDefined()
    expect(snapshotField('Template ID').getByText(templateId)).toBeDefined()
    expect(snapshotField('Run ID').getByText(runId)).toBeDefined()
    expect(snapshotField('Controller image').getByText(controllerImage)).toBeDefined()
    expect(snapshotField('Observed at').getByText(observedAt)).toBeDefined()
    expect(screen.getByText('Observation: Fresh')).toBeDefined()
    expect(screen.queryByText('Envelope timestamp')).toBeNull()
    expect(screen.queryByText(/receivedAt:|staleAfterSec:/)).toBeNull()
    expect(screen.getByRole('button', { name: 'Start', exact: true })).toHaveProperty('disabled', false)
    expect(screen.getByRole('button', { name: 'Stop', exact: true })).toHaveProperty('disabled', false)

    await openScenario()
    expect(screen.getByTitle(`instance: ${worker.instance}`)).toBeDefined()
    expect(screen.getByText(enabledLabel, { exact: true })).toBeDefined()
    expect(screen.getByText(`tps ${tps}`, { exact: true })).toBeDefined()
    expect(screen.getByText('runtime live')).toBeDefined()
    const editConfig = await screen.findByRole('button', { name: 'Edit config' })
    expect(editConfig).toHaveProperty('disabled', false)
    fireEvent.click(editConfig)
    const dialog = screen.getByRole('dialog')
    expect(within(dialog).getByDisplayValue(worker.config.message.body)).toBeDefined()
    fireEvent.click(within(dialog).getByRole('button', { name: 'Close' }))
  })

  it.each([
    { access: 'read-only', canRun: false },
    { access: 'run-only', canRun: true },
  ])('uses backend $access permissions, not global run access, for runtime controls', async ({ canRun }) => {
    interceptIngress({ canRun, canManage: false })
    await renderHive()
    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Start', exact: true })).toHaveProperty('disabled', !canRun)
    })
    expect(screen.getByRole('button', { name: 'Stop', exact: true })).toHaveProperty('disabled', true)
    expect(screen.getByRole('button', { name: 'Remove', exact: true })).toHaveProperty('disabled', true)

    await openScenario()
    expect(screen.getByTitle(`instance: ${swarmId}-generator-1`)).toBeDefined()
    expect(screen.getByText('disabled', { exact: true })).toBeDefined()
    expect(screen.getByText('tps 0', { exact: true })).toBeDefined()
    const editConfig = await screen.findByRole('button', { name: 'Edit config' })
    expect(editConfig).toHaveProperty('disabled', true)
    expect(editConfig.getAttribute('title')).toMatch(/ALL permission/)
    fireEvent.click(editConfig)
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it('keeps pre-status root identity and unavailable observation explicit when observation is empty', async () => {
    interceptIngress({ state: {
      ...swarmFixture([]),
      controllerState: 'PROVISIONING',
      workloadState: 'UNAVAILABLE',
      health: 'UNKNOWN',
      observedAt: null,
      observationStale: true,
      observation: {},
    } })
    await renderHive()

    expect(snapshotField('Controller state').getByText('PROVISIONING')).toBeDefined()
    expect(snapshotField('Workload state').getByText('UNAVAILABLE')).toBeDefined()
    expect(snapshotField('Health').getByText('UNKNOWN')).toBeDefined()
    expect(snapshotField('Template ID').getByText(templateId)).toBeDefined()
    expect(snapshotField('Run ID').getByText(runId)).toBeDefined()
    expect(snapshotField('Observed at').getByText('Not yet observed')).toBeDefined()
    expect(screen.getByText('Observation: Not yet observed')).toBeDefined()

    await openScenario()
    await screen.findByText('capabilities: runtime worker missing')
    expect(screen.getByText('runtime: no matching status-full worker')).toBeDefined()
    expect(screen.queryByText('runtime live')).toBeNull()
    expect(screen.queryByRole('button', { name: 'Edit config' })).toBeNull()
  })

  it('preserves repeated runtime roles without silently selecting one instance for config editing', async () => {
    const workers = [workerFixture(`${swarmId}-generator-1`), workerFixture(`${swarmId}-generator-2`)]
    interceptIngress({ state: swarmFixture(workers) })
    await renderHive()
    expect(screen.getByText('generator, generator', { exact: true })).toBeDefined()

    await openScenario()
    await screen.findByText('capabilities: runtime worker missing')
    expect(screen.getByText('runtime: no matching status-full worker')).toBeDefined()
    expect(screen.queryByRole('button', { name: 'Edit config' })).toBeNull()
    for (const worker of workers) {
      expect(screen.queryByTitle(`instance: ${worker.instance}`)).toBeNull()
    }
  })
})

describe('Hive runtime tuning through the rendered UI and official config ingress', () => {
  it.each([
    { inputType: 'SCHEDULER', path: 'inputs.scheduler.ratePerSec', patch: { inputs: { scheduler: { ratePerSec: 12.5 } } } },
    { inputType: 'REDIS_DATASET', path: 'inputs.redis.ratePerSec', patch: { inputs: { redis: { ratePerSec: 12.5 } } } },
  ] as const)('edits generator rate for $inputType and reads back only confirmed runtime config', async ({ inputType, path, patch }) => {
    const fixture = swarmTuningFixture({ inputType })
    let observed = fixture.state
    const submissions: { path: string; body: { swarmId: string; patch: unknown; idempotencyKey: string } }[] = []
    interceptIngress({
      state: fixture.state, authoredScenario: fixture.scenario, capabilityCatalogue: fixture.capabilities,
      detailResponse: () => Response.json(observed),
      configResponse: async request => {
        const body = await request.json()
        submissions.push({ path: new URL(request.url).pathname, body })
        return acceptedConfigResponse(body.idempotencyKey)
      },
    })
    await renderHive()
    let dialog = await openTuningEditor('generator')
    const rate = tuningField(dialog, path)
    expect(rate).toHaveProperty('value', '1')
    fireEvent.change(rate, { target: { value: '12.5' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Send config update' }))
    await screen.findByText(`Config update accepted for generator/${fixture.generator.instance}.`)
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(submissions).toEqual([{
      path: `/orchestrator/api/components/generator/${fixture.generator.instance}/config`,
      body: { swarmId, patch, idempotencyKey: expect.any(String) },
    }])
    expect(submissions[0].body.idempotencyKey.length).toBeGreaterThan(0)

    dialog = await openTuningEditor('generator')
    expect(tuningField(dialog, path)).toHaveProperty('value', '1')
    fireEvent.click(within(dialog).getByRole('button', { name: 'Close' }))
    observed = swarmTuningFixture({ inputType, generatorRate: 12.5 }).state
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'Refresh details' })) })
    dialog = await openTuningEditor('generator')
    expect(tuningField(dialog, path)).toHaveProperty('value', '12.5')
    expect(submissions).toHaveLength(1)
  })

  it.each([
    { rateMode: false, patch: { threadCount: 6 } },
    { rateMode: true, patch: { threadCount: 6, mode: 'RATE_PER_SEC', ratePerSec: 15.5 } },
  ])('edits processor concurrency with rate mode=$rateMode and reads back runtime confirmation', async ({ rateMode, patch }) => {
    const fixture = swarmTuningFixture()
    let observed = fixture.state
    const submissions: { path: string; body: { swarmId: string; patch: unknown; idempotencyKey: string } }[] = []
    interceptIngress({
      state: fixture.state, authoredScenario: fixture.scenario, capabilityCatalogue: fixture.capabilities,
      detailResponse: () => Response.json(observed),
      configResponse: async request => {
        const body = await request.json()
        submissions.push({ path: new URL(request.url).pathname, body })
        return acceptedConfigResponse(body.idempotencyKey)
      },
    })
    await renderHive()
    let dialog = await openTuningEditor('processor')
    fireEvent.change(within(dialog).getByPlaceholderText('Search config fields'), { target: { value: 'ratePerSec' } })
    expect(within(dialog).queryByRole('spinbutton')).toBeNull()
    const threads = tuningField(dialog, 'threadCount')
    expect(threads).toHaveProperty('value', '2')
    fireEvent.change(threads, { target: { value: '6' } })
    if (rateMode) {
      fireEvent.change(tuningField(dialog, 'mode', 'combobox'), { target: { value: 'RATE_PER_SEC' } })
      const rate = tuningField(dialog, 'ratePerSec')
      expect(rate).toHaveProperty('value', '2.5')
      fireEvent.change(rate, { target: { value: '15.5' } })
    }
    fireEvent.click(within(dialog).getByRole('button', { name: 'Send config update' }))
    await screen.findByText(`Config update accepted for processor/${fixture.processor.instance}.`)
    expect(submissions).toEqual([{
      path: `/orchestrator/api/components/processor/${fixture.processor.instance}/config`,
      body: { swarmId, patch, idempotencyKey: expect.any(String) },
    }])
    observed = swarmTuningFixture({ processorThreads: 6,
      processorMode: rateMode ? 'RATE_PER_SEC' : 'THREAD_COUNT', processorRate: rateMode ? 15.5 : 2.5 }).state
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'Refresh details' })) })
    dialog = await openTuningEditor('processor')
    expect(tuningField(dialog, 'threadCount')).toHaveProperty('value', '6')
    expect(tuningField(dialog, 'mode', 'combobox')).toHaveProperty('value', rateMode ? 'RATE_PER_SEC' : 'THREAD_COUNT')
    if (rateMode) expect(tuningField(dialog, 'ratePerSec')).toHaveProperty('value', '15.5')
    expect(submissions).toHaveLength(1)
  })

  it('rejects fractional processor thread counts without sending a request', async () => {
    const fixture = swarmTuningFixture()
    const submit = vi.fn(async () => acceptedConfigResponse('unexpected'))
    interceptIngress({ state: fixture.state, authoredScenario: fixture.scenario,
      capabilityCatalogue: fixture.capabilities, configResponse: submit })
    await renderHive()
    const dialog = await openTuningEditor('processor')
    fireEvent.change(tuningField(dialog, 'threadCount'), { target: { value: '1.5' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Send config update' }))
    expect(within(dialog).getByText('threadCount must be an integer.')).toBeDefined()
    expect(submit).not.toHaveBeenCalled()
  })

  it('keeps generator and processor tuning read-only without backend manage permission', async () => {
    const fixture = swarmTuningFixture()
    const submit = vi.fn(async () => acceptedConfigResponse('unexpected'))
    interceptIngress({ state: fixture.state, authoredScenario: fixture.scenario,
      capabilityCatalogue: fixture.capabilities, canManage: false, configResponse: submit })
    await renderHive()
    fireEvent.click(screen.getByRole('tab', { name: 'Scenario' }))
    await screen.findByText('Synthetic runtime tuning')
    for (const role of ['generator', 'processor']) {
      fireEvent.click(screen.getByRole('button', { name: new RegExp(`^${role} `) }))
      const edit = await screen.findByRole('button', { name: 'Edit config' })
      expect(edit).toHaveProperty('disabled', true)
      fireEvent.click(edit)
      expect(screen.queryByRole('dialog')).toBeNull()
    }
    expect(submit).not.toHaveBeenCalled()
  })
})
