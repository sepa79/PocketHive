/**
 * @vitest-environment jsdom
 */
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import '@testing-library/jest-dom/vitest'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ComponentDetail from './ComponentDetail'
import type { CapabilityManifest } from '../../types/capabilities'
import type { Component } from '../../types/hive'
import type { WiremockComponentConfig } from '../../lib/wiremockClient'
import { fetchWiremockComponent } from '../../lib/wiremockClient'
import { upsertSyntheticComponent } from '../../lib/stompClient'
import { sendConfigUpdate } from '../../lib/orchestratorApi'

vi.mock('../../lib/orchestratorApi', () => ({
  sendConfigUpdate: vi.fn(),
}))

vi.mock('../../lib/stompClient', () => ({
  upsertSyntheticComponent: vi.fn(),
  removeSyntheticComponent: vi.fn(),
}))

vi.mock('../../lib/wiremockClient', () => ({
  fetchWiremockComponent: vi.fn(),
}))

const baseTimestamp = Date.now()

const wiremockConfig: WiremockComponentConfig = {
  healthStatus: 'OK',
  version: '3.0.0',
  requestCount: 42,
  stubCount: 5,
  unmatchedCount: 1,
  recentRequests: [
    { id: 'req-1', method: 'GET', url: '/api/foo', status: 200, loggedDate: baseTimestamp - 10_000 },
  ],
  unmatchedRequests: [
    { id: 'req-2', method: 'POST', url: '/api/bar', status: 404, loggedDate: baseTimestamp - 20_000 },
  ],
  scenarios: [
    { id: 'scenario-1', name: 'Happy path', state: 'Started' },
    { id: 'scenario-2', name: 'Edge case', state: 'Complete', completed: true },
  ],
  adminUrl: 'http://localhost:8080/__admin/',
  lastUpdatedTs: baseTimestamp - 30_000,
}

const wiremockComponent: Component = {
  id: 'wiremock',
  name: 'WireMock',
  role: 'wiremock',
  lastHeartbeat: baseTimestamp - 5000,
  queues: [],
  status: 'OK',
  config: wiremockConfig,
}

const generatorManifest: CapabilityManifest = {
  schemaVersion: '1.0',
  capabilitiesVersion: '1.0',
  image: { name: 'pockethive-generator', tag: 'latest', digest: null },
  role: 'generator',
  config: [
    { name: 'enabled', type: 'boolean', default: true, ui: { label: 'Enabled' } },
    {
      name: 'ratePerSec',
      type: 'number',
      default: 5,
      min: 0,
      max: 1000,
      ui: { label: 'Rate (msg/s)' },
    },
    {
      name: 'message.body',
      type: 'json',
      default: { sample: true },
      multiline: true,
      ui: { label: 'Body JSON' },
    },
  ],
  actions: [
    {
      id: 'single',
      label: 'Single fire',
      params: [
        { name: 'count', type: 'int', default: 1, required: true, ui: { label: 'Count to send' } },
      ],
    },
  ],
  panels: [],
  ui: { label: 'Generator' },
}

function createGeneratorComponent(): Component {
  return {
    id: 'generator-1',
    name: 'generator-1',
    role: 'generator',
    lastHeartbeat: baseTimestamp - 2000,
    uptimeSec: 300,
    version: '1.0.0',
    env: 'dev',
    queues: [],
    config: {
      enabled: true,
      ratePerSec: 5,
      message: { body: { sample: true } },
    },
    capabilities: generatorManifest,
  }
}

describe('ComponentDetail wiremock panel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(sendConfigUpdate).mockResolvedValue(undefined)
    vi.mocked(fetchWiremockComponent).mockResolvedValue({
      ...wiremockComponent,
      config: { ...wiremockConfig, lastUpdatedTs: baseTimestamp },
    })
  })

  afterEach(() => {
    cleanup()
  })

  it('renders wiremock metrics and tables', async () => {
    render(<ComponentDetail component={wiremockComponent} onClose={() => {}} />)

    const panel = screen.getByTestId('wiremock-panel')
    expect(panel).toBeInTheDocument()

    expect(screen.queryByRole('heading', { name: 'Queues' })).not.toBeInTheDocument()

    expect(within(panel).getByText('Health')).toBeInTheDocument()
    expect(within(panel).getAllByText('OK').length).toBeGreaterThan(0)
    expect(within(panel).getByText('Stub count')).toBeInTheDocument()
    expect(within(panel).getByText('5')).toBeInTheDocument()
    expect(within(panel).getByText('Total requests')).toBeInTheDocument()
    expect(within(panel).getByText('42')).toBeInTheDocument()
    expect(within(panel).getByText('Unmatched total')).toBeInTheDocument()
    expect(within(panel).getByText('1')).toBeInTheDocument()
    expect(within(panel).getByText('Last heartbeat')).toBeInTheDocument()

    expect(within(panel).getByText(/Updated .*ago/i)).toBeInTheDocument()

    const adminLink = within(panel).getByRole('link', { name: 'Open admin' })
    expect(adminLink).toHaveAttribute('href', 'http://localhost:8080/__admin/')

    const recentSection = within(panel).getByText('Recent requests').parentElement!
    expect(within(recentSection).getByText('GET')).toBeInTheDocument()
    expect(within(recentSection).getByText('/api/foo')).toBeInTheDocument()
    const recentRow = within(recentSection).getByText('/api/foo').closest('tr')!
    const recentCells = within(recentRow).getAllByRole('cell')
    expect(recentCells[3]).not.toHaveTextContent('—')

    const unmatchedSection = within(panel).getByText('Unmatched requests').parentElement!
    expect(within(unmatchedSection).getByText('POST')).toBeInTheDocument()

    const scenarios = within(panel).getByText('Scenario states').parentElement!
    await waitFor(() => {
      expect(within(scenarios).getByText('Happy path')).toBeInTheDocument()
    })
    expect(within(scenarios).getByText('Edge case')).toBeInTheDocument()
    expect(within(scenarios).getByText('Complete')).toBeInTheDocument()
  })

  it('allows triggering a refresh of wiremock metrics', async () => {
    const updatedConfig: WiremockComponentConfig = {
      ...wiremockConfig,
      requestCount: 100,
      lastUpdatedTs: baseTimestamp,
    }
    vi.mocked(fetchWiremockComponent).mockResolvedValueOnce({
      ...wiremockComponent,
      config: updatedConfig,
    })

    const user = userEvent.setup()
    render(<ComponentDetail component={wiremockComponent} onClose={() => {}} />)

    const panel = screen.getByTestId('wiremock-panel')
    const retryButton = within(panel).getByRole('button', { name: 'Retry' })
    await user.click(retryButton)

    await waitFor(() => {
      expect(fetchWiremockComponent).toHaveBeenCalled()
    })
    await waitFor(() => {
      expect(upsertSyntheticComponent).toHaveBeenCalledWith(
        expect.objectContaining({
          id: 'wiremock',
          config: expect.objectContaining({ requestCount: 100 }),
        }),
      )
    })
  })
})

describe('ComponentDetail manifest-driven panel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(sendConfigUpdate).mockResolvedValue(undefined)
  })

  afterEach(() => {
    cleanup()
  })

  it('renders manifest-defined config fields and submits payload', async () => {
    const user = userEvent.setup()
    const component = createGeneratorComponent()
    render(<ComponentDetail component={component} onClose={() => {}} />)

    const enabledToggle = screen.getByLabelText(/Enabled/) as HTMLInputElement
    expect(enabledToggle).toBeChecked()
    await user.click(enabledToggle)

    const rateInput = screen.getByLabelText(/Rate \(msg\/s\)/) as HTMLInputElement
    expect(rateInput).toHaveValue(5)
    await user.clear(rateInput)
    await user.type(rateInput, '12')

    const bodyInput = screen.getByLabelText(/Body JSON/) as HTMLTextAreaElement
    fireEvent.change(bodyInput, { target: { value: '{"hello":"world"}' } })

    const confirmButton = screen.getByRole('button', { name: 'Confirm' })
    await user.click(confirmButton)

    await waitFor(() => {
      expect(sendConfigUpdate).toHaveBeenCalledTimes(1)
    })

    const [, payload] = vi.mocked(sendConfigUpdate).mock.calls[0]
    expect(payload).toEqual({
      enabled: false,
      ratePerSec: 12,
      message: { body: { hello: 'world' } },
    })
  })

  it('sends manifest-defined actions with parameters', async () => {
    const user = userEvent.setup()
    const component = createGeneratorComponent()
    render(<ComponentDetail component={component} onClose={() => {}} />)

    const countInput = screen.getByLabelText(/Count to send/) as HTMLInputElement
    await user.clear(countInput)
    await user.type(countInput, '3')

    const actionButton = screen.getByRole('button', { name: 'Run Single fire' })
    await user.click(actionButton)

    await waitFor(() => {
      expect(sendConfigUpdate).toHaveBeenCalledTimes(1)
    })

    const [, payload] = vi.mocked(sendConfigUpdate).mock.calls[0]
    expect(payload).toEqual({ action: { id: 'single', params: { count: 3 } } })
  })
})
