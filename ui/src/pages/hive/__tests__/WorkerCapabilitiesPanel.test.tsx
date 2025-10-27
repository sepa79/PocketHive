/**
 * @vitest-environment jsdom
 */
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import '@testing-library/jest-dom/vitest'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import WorkerCapabilitiesPanel from '../WorkerCapabilitiesPanel'
import type { Component } from '../../../types/hive'
import { useRuntimeCapabilitiesStore } from '../../../store'
import { sendConfigUpdate } from '../../../lib/orchestratorApi'

vi.mock('../../../lib/orchestratorApi', () => ({
  sendConfigUpdate: vi.fn(),
}))

describe('WorkerCapabilitiesPanel', () => {
  const baseComponent: Component = {
    id: 'generator.alpha',
    name: 'Generator',
    role: 'generator',
    swarmId: 'swarm-alpha',
    version: '1.0.0',
    lastHeartbeat: Date.now(),
    queues: [],
    config: {
      enabled: true,
      ratePerSec: 10,
      mode: 'steady',
      metadata: { foo: 'bar' },
    },
  }

  beforeEach(() => {
    useRuntimeCapabilitiesStore.setState({ catalogue: {} })
    vi.mocked(sendConfigUpdate).mockReset()
    vi.mocked(sendConfigUpdate).mockResolvedValue()
  })

  afterEach(() => {
    cleanup()
  })

  it('renders manifest-driven fields and issues config updates', async () => {
    useRuntimeCapabilitiesStore.setState((state) => ({
      ...state,
      catalogue: {
        'swarm-alpha': {
          generator: {
            '1.0.0': {
              manifest: {
                capabilitiesVersion: '1.0.0',
                role: 'generator',
                displayName: 'Generator Worker',
                summary: 'Emits traffic based on the configured payload.',
                config: [
                  { name: 'enabled', label: 'Enabled', type: 'boolean' },
                  {
                    name: 'ratePerSec',
                    label: 'Requests per second',
                    type: 'number',
                    validation: { minimum: 0 },
                  },
                  {
                    name: 'mode',
                    label: 'Mode',
                    type: 'select',
                    options: [
                      { value: 'steady', label: 'Steady' },
                      { value: 'burst', label: 'Burst' },
                    ],
                  },
                  {
                    name: 'metadata',
                    label: 'Metadata',
                    type: 'json',
                  },
                ],
                actions: [
                  {
                    id: 'trigger-sample',
                    label: 'Trigger sample',
                    description: 'Emit a single sample payload.',
                    method: 'POST',
                    endpoint: '/actions/trigger',
                  },
                ],
                panels: [
                  {
                    type: 'customPanel',
                    label: 'Custom Panel',
                    description: 'Specialised dashboard',
                  },
                ],
              },
              instances: ['generator.alpha'],
            },
          },
        },
      },
    }))

    render(<WorkerCapabilitiesPanel component={baseComponent} />)

    expect(await screen.findByText('Generator Worker')).toBeInTheDocument()
    expect(screen.getByLabelText('Enabled')).toBeChecked()

    const rateInput = screen.getByLabelText('Requests per second') as HTMLInputElement
    expect(rateInput.value).toBe('10')

    const modeSelect = screen.getByLabelText('Mode') as HTMLSelectElement
    expect(modeSelect.value).not.toBe('')
    const metadataInput = screen.getByLabelText('Metadata') as HTMLTextAreaElement
    expect(metadataInput.value).toContain('"foo"')

    const actionButton = screen.getByRole('button', { name: /POST\s+\/actions\/trigger/i })
    expect(actionButton).toBeDisabled()
    expect(screen.getByText('Trigger sample')).toBeInTheDocument()

    expect(screen.getByText('Custom Panel')).toBeInTheDocument()

    const user = userEvent.setup()
    await user.clear(rateInput)
    await user.type(rateInput, '20')

    const burstOption = within(modeSelect).getByRole('option', { name: 'Burst' }) as HTMLOptionElement
    await user.selectOptions(modeSelect, burstOption)

    fireEvent.change(metadataInput, { target: { value: '{"foo":"baz"}' } })

    const saveButton = screen.getByRole('button', { name: 'Save changes' })
    await waitFor(() => expect(saveButton).toBeEnabled())

    await user.click(saveButton)

    await waitFor(() => {
      expect(sendConfigUpdate).toHaveBeenCalledTimes(1)
    })

    expect(sendConfigUpdate).toHaveBeenCalledWith(
      baseComponent,
      expect.objectContaining({ ratePerSec: 20, mode: 'burst', metadata: { foo: 'baz' } }),
    )

    await waitFor(() => expect(saveButton).toBeDisabled())
  })

  it('falls back to manifest placeholder when catalogue entry missing', () => {
    const component: Component = {
      ...baseComponent,
      id: 'processor.alpha',
      role: 'processor',
      swarmId: 'swarm-beta',
      config: { foo: 'bar', enabled: true },
    }

    render(<WorkerCapabilitiesPanel component={component} />)

    expect(screen.getByText(/manifest not available/i)).toBeInTheDocument()
    expect(screen.getByText(/"foo": "bar"/)).toBeInTheDocument()
  })
})
