/**
 * @vitest-environment jsdom
 */
import { render, screen, fireEvent } from '@testing-library/react'
import Queen from './Queen'
import { vi, test, expect } from 'vitest'
import { createSwarm } from '../lib/stompClient'

vi.mock('../lib/stompClient', () => ({
  createSwarm: vi.fn(),
}))

test('creates swarm on submit', () => {
  render(<Queen />)
  fireEvent.change(screen.getByLabelText(/swarm id/i), { target: { value: 'sw1' } })
  fireEvent.change(screen.getByLabelText(/template/i), { target: { value: 'rest' } })
  fireEvent.click(screen.getByText('Create'))
  expect(createSwarm).toHaveBeenCalledWith('sw1', 'generator-service:latest')
})
