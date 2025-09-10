/**
 * @vitest-environment jsdom
 */
import { render, screen, fireEvent } from '@testing-library/react'
import SwarmCreateModal from './SwarmCreateModal'
import { vi, test, expect } from 'vitest'
import { createSwarm } from '../../lib/stompClient'

vi.mock('../../lib/stompClient', () => ({
  createSwarm: vi.fn(),
}))

test('submits new swarm with scenario payload', async () => {
  render(<SwarmCreateModal onClose={() => {}} />)
  fireEvent.change(screen.getByLabelText(/swarm id/i), { target: { value: 'sw1' } })
  fireEvent.change(screen.getByLabelText(/image/i), { target: { value: 'img:1' } })
  fireEvent.click(screen.getByText('Create'))
  expect(createSwarm).toHaveBeenCalledWith('sw1', {
    template: { image: 'img:1', bees: [] },
  })
})
