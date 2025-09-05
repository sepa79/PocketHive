/**
 * @vitest-environment jsdom
 */
import { render, screen, fireEvent } from '@testing-library/react'
import Queen from './Queen'
import { vi, test, expect } from 'vitest'
import { startSwarm } from '../lib/stompClient'

vi.mock('../lib/stompClient', () => ({
  startSwarm: vi.fn(),
}))

test('starts swarm on submit', () => {
  render(<Queen />)
  fireEvent.change(screen.getByLabelText(/swarm id/i), { target: { value: 'sw1' } })
  fireEvent.change(screen.getByLabelText(/template/i), { target: { value: 'rest' } })
  fireEvent.click(screen.getByText('Start'))
  expect(startSwarm).toHaveBeenCalledWith('sw1', 'generator-service:latest')
})
