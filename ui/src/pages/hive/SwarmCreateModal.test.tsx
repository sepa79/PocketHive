/**
 * @vitest-environment jsdom
 */
import { render, screen, fireEvent, waitFor, cleanup } from '@testing-library/react'
import SwarmCreateModal from './SwarmCreateModal'
import { vi, test, expect, afterEach, beforeEach, type MockInstance } from 'vitest'
import * as apiModule from '../../lib/api'

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
  apiFetchSpy.mockResolvedValueOnce({
    json: async () => [
      { id: 'basic', name: 'Basic' },
      { id: 'advanced', name: 'Advanced' },
    ],
  } as unknown as Response)
  render(<SwarmCreateModal onClose={() => {}} />)

  await screen.findByText('Basic')
  await screen.findByText('Advanced')

  expect(apiFetchSpy).toHaveBeenCalledWith(
    '/scenario-manager/scenarios',
    expect.objectContaining({
      headers: expect.objectContaining({ Accept: 'application/json' }),
    }),
  )
})

test('submits selected scenario', async () => {
  apiFetchSpy
    .mockResolvedValueOnce({
      json: async () => [{ id: 'basic', name: 'Basic' }],
    } as unknown as Response)
    .mockResolvedValueOnce({} as Response)
  render(<SwarmCreateModal onClose={() => {}} />)

  await screen.findByText('Basic')
  fireEvent.change(screen.getByLabelText(/swarm id/i), { target: { value: 'sw1' } })
  fireEvent.change(screen.getByLabelText(/scenario/i), { target: { value: 'basic' } })
  fireEvent.click(screen.getByText('Create'))
  await waitFor(() => expect(apiFetchSpy.mock.calls.length).toBeGreaterThanOrEqual(2))
  const createCall = apiFetchSpy.mock.calls[1]
  expect(createCall?.[0]).toBe('/orchestrator/swarms/sw1/create')
  expect(createCall?.[1]).toMatchObject({ method: 'POST' })
  const body = createCall?.[1]?.body
  expect(typeof body).toBe('string')
  const parsed = JSON.parse(body as string)
  expect(parsed).toMatchObject({ templateId: 'basic' })
  expect(parsed.notes).toBeUndefined()
  expect(await screen.findByText('Swarm created')).toBeTruthy()
})

test('does not submit when scenario selection is cleared', async () => {
  apiFetchSpy.mockResolvedValueOnce({
    json: async () => [{ id: 'basic', name: 'Basic' }],
  } as unknown as Response)
  render(<SwarmCreateModal onClose={() => {}} />)

  await screen.findByText('Basic')
  fireEvent.change(screen.getByLabelText(/swarm id/i), { target: { value: 'sw1' } })
  fireEvent.change(screen.getByLabelText(/scenario/i), { target: { value: 'basic' } })
  fireEvent.change(screen.getByLabelText(/scenario/i), { target: { value: '' } })
  fireEvent.click(screen.getByText('Create'))

  await waitFor(() => expect(apiFetchSpy.mock.calls.length).toBe(1))
  await screen.findByText(/swarm id and scenario required/i)
})
