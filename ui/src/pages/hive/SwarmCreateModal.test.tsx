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

test('submits new swarm', () => {
  render(<SwarmCreateModal onClose={() => {}} />)
  fireEvent.change(screen.getByLabelText(/swarm id/i), { target: { value: 'sw1' } })
  fireEvent.change(screen.getByLabelText(/template/i), { target: { value: 'rest' } })
  fireEvent.click(screen.getByText('Create'))
  expect(createSwarm).toHaveBeenCalledWith('sw1', 'generator-service:latest')
})
