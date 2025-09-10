/**
 * @vitest-environment jsdom
 */
import { render, screen, fireEvent, waitFor, cleanup } from '@testing-library/react'
import SwarmCreateModal from './SwarmCreateModal'
import { vi, test, expect, afterEach } from 'vitest'
import { createSwarm } from '../../lib/stompClient'
import { defaultBees } from '../../lib/defaultBees'

vi.mock('../../lib/stompClient', () => ({
  createSwarm: vi.fn(),
}))

afterEach(() => {
  vi.clearAllMocks()
  cleanup()
})

test('submits selected scenario', async () => {
  const detail = { template: { image: 'img:1', bees: [] } }
  const fetchMock = vi
    .fn()
    .mockResolvedValueOnce({ ok: true, json: async () => ['basic'] })
    .mockResolvedValueOnce({ ok: true, json: async () => detail })
  global.fetch = fetchMock as unknown as typeof fetch

  render(<SwarmCreateModal onClose={() => {}} />)

  await screen.findByText('basic')
  fireEvent.change(screen.getByLabelText(/swarm id/i), { target: { value: 'sw1' } })
  fireEvent.change(screen.getByLabelText(/scenario/i), { target: { value: 'basic' } })
  await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/scenario-manager/scenarios/basic'))
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
    .mockResolvedValueOnce({ ok: true, json: async () => ['basic'] })
    .mockResolvedValueOnce({ ok: true, json: async () => detail })
  global.fetch = fetchMock as unknown as typeof fetch

  render(<SwarmCreateModal onClose={() => {}} />)

  await screen.findByText('basic')
  fireEvent.change(screen.getByLabelText(/swarm id/i), { target: { value: 'sw1' } })
  fireEvent.change(screen.getByLabelText(/scenario/i), { target: { value: 'basic' } })
  await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/scenario-manager/scenarios/basic'))
  await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2))

  fireEvent.change(screen.getByLabelText(/scenario/i), { target: { value: '' } })
  fireEvent.click(screen.getByText('Create'))

  await waitFor(() => expect(createSwarm).not.toHaveBeenCalled())
  await screen.findByText(/swarm id and scenario required/i)
})
