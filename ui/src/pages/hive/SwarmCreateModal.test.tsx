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
      <SwarmCreateModal onClose={() => {}} autoPullOnStart={false} onChangeAutoPull={() => {}} />
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
    // scenario preview fetch
    .mockResolvedValueOnce({
      ok: true,
      json: async () => ({ id: 'basic', name: 'Basic', template: {} }),
    } as unknown as Response)
    .mockResolvedValueOnce({ ok: true } as Response)
  render(
    <CapabilitiesProvider>
      <SwarmCreateModal onClose={() => {}} autoPullOnStart={false} onChangeAutoPull={() => {}} />
    </CapabilitiesProvider>,
  )

  await screen.findByText('Basic')
  fireEvent.change(screen.getByLabelText(/swarm id/i), { target: { value: 'sw1' } })
  fireEvent.click(screen.getByRole('button', { name: 'Basic' }))
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
    // scenario preview fetch
    .mockResolvedValueOnce({
      ok: true,
      json: async () => ({ id: 'basic', name: 'Basic', template: {} }),
    } as unknown as Response)
    .mockResolvedValueOnce({
      ok: false,
      status: 409,
      text: async () => "{\"message\": \"Swarm 'sw1' already exists\"}",
    } as unknown as Response)

  render(
    <CapabilitiesProvider>
      <SwarmCreateModal onClose={() => {}} autoPullOnStart={false} onChangeAutoPull={() => {}} />
    </CapabilitiesProvider>,
  )

  await screen.findByText('Basic')
  fireEvent.change(screen.getByLabelText(/swarm id/i), { target: { value: 'sw1' } })
  fireEvent.click(screen.getByRole('button', { name: 'Basic' }))
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
    // no create call
  render(
    <CapabilitiesProvider>
      <SwarmCreateModal onClose={() => {}} autoPullOnStart={false} onChangeAutoPull={() => {}} />
    </CapabilitiesProvider>,
  )

  await screen.findByText('Basic')
  fireEvent.change(screen.getByLabelText(/swarm id/i), { target: { value: 'sw1' } })
  fireEvent.click(screen.getByText('Create'))

  await waitFor(() => expect(apiFetchSpy.mock.calls.length).toBe(2))
  await screen.findByText(/swarm id and scenario required/i)
})

test('loads scenario preview when a template is selected', async () => {
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
      ok: true,
      json: async () => ({ id: 'basic', name: 'Basic', template: { image: 'img' } }),
    } as unknown as Response)

  render(
    <CapabilitiesProvider>
      <SwarmCreateModal onClose={() => {}} autoPullOnStart={false} onChangeAutoPull={() => {}} />
    </CapabilitiesProvider>,
  )

  await screen.findByText('Basic')
  fireEvent.click(screen.getByRole('button', { name: 'Basic' }))

  await screen.findByText(/Components/i)
  // Raw toggle should be present once preview has loaded
  await screen.findByRole('button', { name: /Show raw scenario definition/i })
  expect(apiFetchSpy).toHaveBeenCalledWith(
    '/scenario-manager/scenarios/basic',
    expect.objectContaining({
      headers: expect.objectContaining({ Accept: 'application/json' }),
    }),
  )
})
