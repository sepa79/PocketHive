/**
 * @vitest-environment jsdom
 */
import { render, screen, fireEvent } from '@testing-library/react'
import SwarmCreateModal from './SwarmCreateModal'
import { vi, test, expect, beforeEach } from 'vitest'
import { createSwarm } from '../../lib/stompClient'

vi.mock('../../lib/stompClient', () => ({
  createSwarm: vi.fn(),
}))

beforeEach(() => {
  ;(global as unknown as { fetch: typeof fetch }).fetch = vi
    .fn()
    .mockResolvedValue({
      json: () => Promise.resolve([{ id: 'sc1', name: 'Scenario', image: 'img:1' }]),
    })
})

test('submits new swarm with scenario', async () => {
  render(<SwarmCreateModal onClose={() => {}} />)
  fireEvent.change(screen.getByLabelText(/swarm id/i), { target: { value: 'sw1' } })
  // wait for scenarios to load
  await screen.findByText('Scenario')
  fireEvent.change(screen.getByLabelText(/scenario/i), { target: { value: 'sc1' } })
  fireEvent.click(screen.getByText('Create'))
  expect(createSwarm).toHaveBeenCalledWith('sw1', 'img:1', 'sc1')
})
