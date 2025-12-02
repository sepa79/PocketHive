/**
 * @vitest-environment jsdom
 */
import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import '@testing-library/jest-dom/vitest'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ComponentDetail from './ComponentDetail'
import type { Component } from '../../types/hive'
import type { WiremockComponentConfig } from '../../lib/wiremockClient'
import { fetchWiremockComponent } from '../../lib/wiremockClient'
import { CapabilitiesContext, type CapabilitiesContextValue } from '../../contexts/CapabilitiesContext'
import { buildManifestIndex } from '../../lib/capabilities'
import type { CapabilityManifest } from '../../types/capabilities'
import { sendConfigUpdate } from '../../lib/orchestratorApi'
import {
  SwarmMetadataContext,
  type SwarmMetadataContextValue,
} from '../../contexts/SwarmMetadataContext'

vi.mock('../../lib/orchestratorApi', () => ({
  sendConfigUpdate: vi.fn(),
}))

vi.mock('../../lib/stompClient', () => ({
  setSwarmMetadataRefreshHandler: vi.fn(),
}))

vi.mock('../../lib/wiremockClient', () => ({
  fetchWiremockComponent: vi.fn(),
}))

const baseTimestamp = Date.now()

const wiremockConfig: WiremockComponentConfig = {
  healthStatus: 'OK',
  version: '3.0.0',
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

const sendConfigUpdateMock = vi.mocked(sendConfigUpdate)

describe('ComponentDetail wiremock panel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
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
  })
})

describe('ComponentDetail dynamic config', () => {
  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
  })

  it('renders capability-driven config inputs and submits typed payloads', async () => {
    const manifest: CapabilityManifest = {
      schemaVersion: '1.0',
      capabilitiesVersion: '1.0',
      role: 'generator',
      image: { name: 'gen', tag: 'latest', digest: null },
      config: [
        { name: 'enabled', type: 'boolean', default: false },
        { name: 'ratePerSec', type: 'number', default: 1 },
        { name: 'message.path', type: 'string', default: '/' },
        { name: 'message.method', type: 'string', default: 'GET' },
      ],
      actions: [],
      panels: [],
    }

    const providerValue: CapabilitiesContextValue = {
      manifests: [manifest],
      manifestIndex: buildManifestIndex([manifest]),
      ensureCapabilities: vi.fn().mockResolvedValue([manifest]),
      refreshCapabilities: vi.fn().mockResolvedValue([manifest]),
      getManifestForImage: vi.fn().mockReturnValue(manifest),
    }

    const component: Component = {
      id: 'gen-1',
      name: 'gen-1',
      role: 'generator',
      image: 'gen:latest',
      lastHeartbeat: baseTimestamp,
      queues: [],
      config: {
        enabled: true,
        ratePerSec: 5,
        message: { path: '/foo', method: 'GET' },
      },
    }

    const user = userEvent.setup()
    sendConfigUpdateMock.mockResolvedValue()

    const swarmValue: SwarmMetadataContextValue = {
      swarms: [],
      ensureSwarms: vi.fn().mockResolvedValue([]),
      refreshSwarms: vi.fn().mockResolvedValue([]),
      getBeeImage: vi.fn().mockReturnValue(null),
      getControllerImage: vi.fn().mockReturnValue(null),
      findSwarm: vi.fn().mockReturnValue(null),
    }

    render(
      <SwarmMetadataContext.Provider value={swarmValue}>
        <CapabilitiesContext.Provider value={providerValue}>
          <ComponentDetail component={component} onClose={() => {}} />
        </CapabilitiesContext.Provider>
      </SwarmMetadataContext.Provider>,
    )

    await waitFor(() => expect(providerValue.ensureCapabilities).toHaveBeenCalled())
    await waitFor(() => expect(swarmValue.ensureSwarms).toHaveBeenCalled())

    const editToggle = screen.getByRole('checkbox', { name: /Enable editing/i })
    await user.click(editToggle)

    const rateInput = (await screen.findByDisplayValue('5')) as HTMLInputElement
    await user.click(rateInput)
    await user.keyboard('{Control>}a{/Control}{Backspace}')
    await user.type(rateInput, '10')

    const pathInput = screen.getByDisplayValue('/foo') as HTMLInputElement
    await user.click(pathInput)
    await user.keyboard('{Control>}a{/Control}{Backspace}')
    await user.type(pathInput, '/new')

    await user.click(screen.getByRole('button', { name: 'Confirm' }))

    await waitFor(() => {
      expect(sendConfigUpdateMock).toHaveBeenCalledWith(
        expect.objectContaining({ id: 'gen-1' }),
        expect.objectContaining({
          enabled: true,
          ratePerSec: 10,
          message: expect.objectContaining({ path: '/new', method: 'GET' }),
        }),
      )
    })
  })

  it('skips swarm metadata lookup when swarm id is absent', async () => {
    const providerValue: CapabilitiesContextValue = {
      manifests: [],
      manifestIndex: buildManifestIndex([]),
      ensureCapabilities: vi.fn().mockResolvedValue([]),
      refreshCapabilities: vi.fn().mockResolvedValue([]),
      getManifestForImage: vi.fn().mockReturnValue(null),
    }

    const component: Component = {
      id: 'proc-1',
      name: 'proc-1',
      role: 'processor',
      lastHeartbeat: baseTimestamp,
      queues: [],
      config: {},
    }

    const swarmValue: SwarmMetadataContextValue = {
      swarms: [],
      ensureSwarms: vi.fn().mockResolvedValue([]),
      refreshSwarms: vi.fn().mockResolvedValue([]),
      getBeeImage: vi.fn().mockReturnValue('image-from-swarm'),
      getControllerImage: vi.fn().mockReturnValue(null),
      findSwarm: vi.fn().mockReturnValue(null),
    }

    render(
      <SwarmMetadataContext.Provider value={swarmValue}>
        <CapabilitiesContext.Provider value={providerValue}>
          <ComponentDetail component={component} onClose={() => {}} />
        </CapabilitiesContext.Provider>
      </SwarmMetadataContext.Provider>,
    )

    await waitFor(() => expect(providerValue.ensureCapabilities).toHaveBeenCalled())
    await waitFor(() => expect(swarmValue.ensureSwarms).toHaveBeenCalled())
    expect(swarmValue.getBeeImage).not.toHaveBeenCalled()
  })

  it('uses controller image metadata to resolve manifests when worker image is missing', async () => {
    const manifest: CapabilityManifest = {
      schemaVersion: '1.0',
      capabilitiesVersion: '1.0',
      role: 'swarm-controller',
      image: { name: 'controller', tag: 'latest', digest: null },
      config: [],
      actions: [],
      panels: [],
    }

    const providerValue: CapabilitiesContextValue = {
      manifests: [manifest],
      manifestIndex: buildManifestIndex([manifest]),
      ensureCapabilities: vi.fn().mockResolvedValue([manifest]),
      refreshCapabilities: vi.fn().mockResolvedValue([manifest]),
      getManifestForImage: vi.fn().mockImplementation((image) =>
        image === 'controller-image' ? manifest : null,
      ),
    }

    const component: Component = {
      id: 'controller-1',
      name: 'controller-1',
      role: 'swarm-controller',
      swarmId: 'swarm-a',
      lastHeartbeat: baseTimestamp,
      queues: [],
      config: {},
    }

    const swarmValue: SwarmMetadataContextValue = {
      swarms: [],
      ensureSwarms: vi.fn().mockResolvedValue([]),
      refreshSwarms: vi.fn().mockResolvedValue([]),
      getBeeImage: vi.fn().mockReturnValue('bee-image'),
      getControllerImage: vi.fn().mockReturnValue('controller-image'),
      findSwarm: vi.fn().mockReturnValue(null),
    }

    render(
      <SwarmMetadataContext.Provider value={swarmValue}>
        <CapabilitiesContext.Provider value={providerValue}>
          <ComponentDetail component={component} onClose={() => {}} />
        </CapabilitiesContext.Provider>
      </SwarmMetadataContext.Provider>,
    )

    await waitFor(() => expect(providerValue.ensureCapabilities).toHaveBeenCalled())
    await waitFor(() => expect(swarmValue.ensureSwarms).toHaveBeenCalled())

    expect(providerValue.getManifestForImage).toHaveBeenCalledWith('controller-image')
    expect(swarmValue.getBeeImage).not.toHaveBeenCalled()
    expect(
      screen.getByText('No configurable options'),
    ).toBeInTheDocument()
  })
})
