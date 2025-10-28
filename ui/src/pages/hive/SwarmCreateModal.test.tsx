/**
 * @vitest-environment jsdom
 */
import { render, screen, fireEvent, waitFor, cleanup } from '@testing-library/react'
import SwarmCreateModal from './SwarmCreateModal'
import { vi, test, expect, afterEach, beforeEach, type MockInstance } from 'vitest'
import * as apiModule from '../../lib/api'
import { CapabilitiesProvider } from '../../contexts/CapabilitiesContext'

let apiFetchSpy: MockInstance<typeof apiModule.apiFetch>

beforeEach(() => {
  apiFetchSpy = vi.spyOn(apiModule, 'apiFetch')
})

afterEach(() => {
  apiFetchSpy.mockRestore()
  vi.clearAllMocks()
  cleanup()
})

test('loads available scenarios on mount', async () => {
  apiFetchSpy
    .mockResolvedValueOnce({
      ok: true,
      json: async () => [
        { id: 'basic', name: 'Basic', bees: [] },
        { id: 'advanced', name: 'Advanced', bees: [] },
      ],
    } as unknown as Response)
    .mockResolvedValueOnce({
      ok: true,
      json: async () => [],
    } as unknown as Response)

  render(
    <CapabilitiesProvider>
      <SwarmCreateModal onClose={() => {}} />
    </CapabilitiesProvider>,
  )

  await screen.findByText('Basic')
  await screen.findByText('Advanced')

  expect(apiFetchSpy).toHaveBeenCalledWith(
    '/scenario-manager/api/templates',
    expect.objectContaining({
      headers: expect.objectContaining({ Accept: 'application/json' }),
    }),
  )
  expect(apiFetchSpy).toHaveBeenCalledWith(
    '/scenario-manager/api/capabilities?all=true',
    expect.objectContaining({
      headers: expect.objectContaining({ Accept: 'application/json' }),
    }),
  )
})

test('submits selected scenario', async () => {
  apiFetchSpy
    .mockResolvedValueOnce({
      ok: true,
      json: async () => [{ id: 'basic', name: 'Basic', bees: [] }],
    } as unknown as Response)
    .mockResolvedValueOnce({
      ok: true,
      json: async () => [],
    } as unknown as Response)
    .mockResolvedValueOnce({ ok: true } as Response)
  render(
    <CapabilitiesProvider>
      <SwarmCreateModal onClose={() => {}} />
    </CapabilitiesProvider>,
  )

  await screen.findByText('Basic')
  fireEvent.change(screen.getByLabelText(/swarm id/i), { target: { value: 'sw1' } })
  fireEvent.change(screen.getByLabelText(/scenario/i), { target: { value: 'basic' } })
  fireEvent.click(screen.getByText('Create'))
  await waitFor(() =>
    expect(apiFetchSpy.mock.calls.some((call) => call[0] === '/orchestrator/swarms/sw1/create')).toBe(true),
  )
  const createCall = apiFetchSpy.mock.calls.find((call) => call[0] === '/orchestrator/swarms/sw1/create')
  expect(createCall?.[0]).toBe('/orchestrator/swarms/sw1/create')
  expect(createCall?.[1]).toMatchObject({ method: 'POST' })
  const body = createCall?.[1]?.body
  expect(typeof body).toBe('string')
  const parsed = JSON.parse(body as string)
  expect(parsed).toMatchObject({ templateId: 'basic' })
  expect(parsed.notes).toBeUndefined()
  expect(await screen.findByText('Swarm created')).toBeTruthy()
})

test('shows conflict message when swarm already exists', async () => {
  apiFetchSpy
    .mockResolvedValueOnce({
      ok: true,
      json: async () => [{ id: 'basic', name: 'Basic', bees: [] }],
    } as unknown as Response)
    .mockResolvedValueOnce({
      ok: true,
      json: async () => [],
    } as unknown as Response)
    .mockResolvedValueOnce({
      ok: false,
      status: 409,
      text: async () => "{\"message\": \"Swarm 'sw1' already exists\"}",
    } as unknown as Response)

  render(
    <CapabilitiesProvider>
      <SwarmCreateModal onClose={() => {}} />
    </CapabilitiesProvider>,
  )

  await screen.findByText('Basic')
  fireEvent.change(screen.getByLabelText(/swarm id/i), { target: { value: 'sw1' } })
  fireEvent.change(screen.getByLabelText(/scenario/i), { target: { value: 'basic' } })
  fireEvent.click(screen.getByText('Create'))

  expect(await screen.findByText("Swarm 'sw1' already exists")).toBeTruthy()
})

test('does not submit when scenario selection is cleared', async () => {
  apiFetchSpy
    .mockResolvedValueOnce({
      ok: true,
      json: async () => [{ id: 'basic', name: 'Basic', bees: [] }],
    } as unknown as Response)
    .mockResolvedValueOnce({
      ok: true,
      json: async () => [],
    } as unknown as Response)
  render(
    <CapabilitiesProvider>
      <SwarmCreateModal onClose={() => {}} />
    </CapabilitiesProvider>,
  )

  await screen.findByText('Basic')
  fireEvent.change(screen.getByLabelText(/swarm id/i), { target: { value: 'sw1' } })
  fireEvent.change(screen.getByLabelText(/scenario/i), { target: { value: 'basic' } })
  fireEvent.change(screen.getByLabelText(/scenario/i), { target: { value: '' } })
  fireEvent.click(screen.getByText('Create'))

  await waitFor(() => expect(apiFetchSpy.mock.calls.length).toBe(2))
  await screen.findByText(/swarm id and scenario required/i)
})

test('renders manifest details when available', async () => {
  apiFetchSpy
    .mockResolvedValueOnce({
      ok: true,
      json: async () => [
        {
          id: 'basic',
          name: 'Basic',
          bees: [{ role: 'generator', image: 'ghcr.io/pockethive/generator:1.0.0' }],
        },
      ],
    } as unknown as Response)
    .mockResolvedValueOnce({
      ok: true,
      json: async () => [
        {
          schemaVersion: '1.0',
          capabilitiesVersion: '1',
          role: 'generator',
          image: {
            name: 'ghcr.io/pockethive/generator',
            tag: '1.0.0',
            digest: null,
          },
          config: [
            {
              name: 'rate',
              type: 'int',
              default: 100,
            },
          ],
          actions: [
            {
              id: 'warmup',
              label: 'Warm Up',
              params: [],
            },
          ],
          panels: [
            {
              id: 'metrics',
            },
          ],
        },
      ],
    } as unknown as Response)

  render(
    <CapabilitiesProvider>
      <SwarmCreateModal onClose={() => {}} />
    </CapabilitiesProvider>,
  )

  fireEvent.change(await screen.findByLabelText(/scenario/i), { target: { value: 'basic' } })

  expect(await screen.findByDisplayValue('100')).toBeTruthy()
  expect(screen.getByText('Warm Up')).toBeTruthy()
  expect(screen.getByText('metrics')).toBeTruthy()
})
