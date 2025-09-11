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
      json: async () => [
        { id: 'basic', name: 'Basic' },
        { id: 'advanced', name: 'Advanced' },
      ],
    })
  global.fetch = fetchMock as unknown as typeof fetch

  render(<SwarmCreateModal onClose={() => {}} />)

  await screen.findByText('Basic')
  await screen.findByText('Advanced')

  expect(fetchMock).toHaveBeenCalledWith('/scenario-manager/scenarios', {
    headers: { Accept: 'application/json' },
  })
})

test('loads scenarios from wrapped response', async () => {
  const fetchMock = vi
    .fn()
    .mockResolvedValue({
      ok: true,
      json: async () => ({
        scenarios: [
          { id: 'basic', name: 'Basic' },
          { id: 'advanced', name: 'Advanced' },
        ],
      }),
    })
  global.fetch = fetchMock as unknown as typeof fetch

  render(<SwarmCreateModal onClose={() => {}} />)

  await screen.findByText('Basic')
  await screen.findByText('Advanced')

  expect(fetchMock).toHaveBeenCalledWith('/scenario-manager/scenarios', {
    headers: { Accept: 'application/json' },
  })
})

test('submits selected scenario', async () => {
  const detail = { template: { image: 'img:1', bees: [] } }
  const fetchMock = vi
    .fn()
    .mockResolvedValueOnce({
      ok: true,
      json: async () => [{ id: 'basic', name: 'Basic' }],
    })
    .mockResolvedValueOnce({ ok: true, json: async () => detail })
  global.fetch = fetchMock as unknown as typeof fetch

  render(<SwarmCreateModal onClose={() => {}} />)

  await screen.findByText('Basic')
  fireEvent.change(screen.getByLabelText(/swarm id/i), { target: { value: 'sw1' } })
  fireEvent.change(screen.getByLabelText(/scenario/i), { target: { value: 'basic' } })
  await waitFor(() =>
    expect(fetchMock).toHaveBeenCalledWith(
      '/scenario-manager/scenarios/basic',
      { headers: { Accept: 'application/json' } },
    ),
  )
  await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2))
  fireEvent.click(screen.getByText('Create'))
  await waitFor(() =>
    expect(createSwarm).toHaveBeenCalledWith('sw1', detail),
  )
})

test('does not submit when scenario selection is cleared', async () => {
  const detail = { template: { image: 'img:1', bees: [] } }
  const fetchMock = vi
    .fn()
    .mockResolvedValueOnce({
      ok: true,
      json: async () => [{ id: 'basic', name: 'Basic' }],
    })
    .mockResolvedValueOnce({ ok: true, json: async () => detail })
  global.fetch = fetchMock as unknown as typeof fetch

  render(<SwarmCreateModal onClose={() => {}} />)

  await screen.findByText('Basic')
  fireEvent.change(screen.getByLabelText(/swarm id/i), { target: { value: 'sw1' } })
  fireEvent.change(screen.getByLabelText(/scenario/i), { target: { value: 'basic' } })
  await waitFor(() =>
    expect(fetchMock).toHaveBeenCalledWith(
      '/scenario-manager/scenarios/basic',
      { headers: { Accept: 'application/json' } },
    ),
  )
  await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2))

  fireEvent.change(screen.getByLabelText(/scenario/i), { target: { value: '' } })
  fireEvent.click(screen.getByText('Create'))

  await waitFor(() => expect(createSwarm).not.toHaveBeenCalled())
  await screen.findByText(/swarm id and scenario required/i)
})
