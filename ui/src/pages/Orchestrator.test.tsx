/**
 * @vitest-environment jsdom
 */
import { render, screen, fireEvent } from '@testing-library/react'
import Orchestrator from './Orchestrator'
import { vi, test, expect } from 'vitest'
import { startSwarm } from '../lib/stompClient'

vi.mock('../lib/stompClient', () => ({
  startSwarm: vi.fn(),
}))

test('starts swarm on submit', () => {
  render(<Orchestrator />)
  fireEvent.change(screen.getByLabelText(/swarm id/i), { target: { value: 'sw1' } })
  fireEvent.change(screen.getByLabelText(/image/i), { target: { value: 'img:latest' } })
  fireEvent.click(screen.getByText('Start'))
  expect(startSwarm).toHaveBeenCalledWith('sw1', 'img:latest')
})
