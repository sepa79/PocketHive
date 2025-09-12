/**
 * @vitest-environment jsdom
 */
import { render, screen, fireEvent, waitFor, cleanup } from '@testing-library/react'
import SwarmCreateModal from './SwarmCreateModal'
import { vi, test, expect, afterEach } from 'vitest'
import { createSwarm } from '../../lib/stompClient'

vi.mock('../../lib/stompClient', () => ({
  createSwarm: vi.fn(),
}))

afterEach(() => {
  vi.clearAllMocks()
  cleanup()
})

test('loads available scenarios on mount', async () => {
  const fetchMock = vi
    .fn()
    .mockResolvedValue({
      ok: true,
      headers: { get: () => 'application/json' },
      json: async () => [
        { id: 'basic', name: 'Basic' },
        { id: 'advanced', name: 'Advanced' },
      ],
    })
  global.fetch = fetchMock as unknown as typeof fetch

  render(<SwarmCreateModal onClose={() => {}} />)

  await screen.findByText('Basic')
  await screen.findByText('Advanced')

  expect(fetchMock).toHaveBeenCalled()
  const args = fetchMock.mock.calls[0]
  expect(args[0]).toBe('/scenario-manager/scenarios')
  expect(args[1]).toMatchObject({
    headers: expect.objectContaining({
      Accept: 'application/json',
      'x-correlation-id': expect.any(String),
    }),
  })
})

test('submits selected scenario', async () => {
  const fetchMock = vi
    .fn()
    .mockResolvedValue({
      ok: true,
      headers: { get: () => 'application/json' },
      json: async () => [{ id: 'basic', name: 'Basic' }],
    })
  global.fetch = fetchMock as unknown as typeof fetch

  render(<SwarmCreateModal onClose={() => {}} />)

  await screen.findByText('Basic')
  fireEvent.change(screen.getByLabelText(/swarm id/i), { target: { value: 'sw1' } })
  fireEvent.change(screen.getByLabelText(/scenario/i), { target: { value: 'basic' } })
  fireEvent.click(screen.getByText('Create'))
  await waitFor(() => expect(createSwarm).toHaveBeenCalledWith('sw1', 'basic'))
})

test('does not submit when scenario selection is cleared', async () => {
  const fetchMock = vi
    .fn()
    .mockResolvedValue({
      ok: true,
      headers: { get: () => 'application/json' },
      json: async () => [{ id: 'basic', name: 'Basic' }],
    })
  global.fetch = fetchMock as unknown as typeof fetch

  render(<SwarmCreateModal onClose={() => {}} />)

  await screen.findByText('Basic')
  fireEvent.change(screen.getByLabelText(/swarm id/i), { target: { value: 'sw1' } })
  fireEvent.change(screen.getByLabelText(/scenario/i), { target: { value: 'basic' } })
  fireEvent.change(screen.getByLabelText(/scenario/i), { target: { value: '' } })
  fireEvent.click(screen.getByText('Create'))

  await waitFor(() => expect(createSwarm).not.toHaveBeenCalled())
  await screen.findByText(/swarm id and scenario required/i)
})
