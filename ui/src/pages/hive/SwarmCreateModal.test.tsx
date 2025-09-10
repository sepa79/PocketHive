/**
 * @vitest-environment jsdom
 */
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import SwarmCreateModal from './SwarmCreateModal'
import { vi, test, expect } from 'vitest'
import { createSwarm } from '../../lib/stompClient'

vi.mock('../../lib/stompClient', () => ({
  createSwarm: vi.fn(),
}))

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
  await waitFor(() =>
    expect(fetchMock).toHaveBeenCalledWith('/scenario-manager/scenarios/basic'),
  )
  fireEvent.click(screen.getByText('Create'))
  expect(createSwarm).toHaveBeenCalledWith('sw1', detail)
})
