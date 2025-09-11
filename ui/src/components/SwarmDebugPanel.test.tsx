import { describe, it, expect } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import '@testing-library/jest-dom/vitest'
import SwarmDebugPanel from './SwarmDebugPanel'
import { logHandshake, resetLogs } from '../lib/logs'

/**
 * @vitest-environment jsdom
 */

describe('SwarmDebugPanel', () => {
  it('renders handshake timeline for swarms', async () => {
    resetLogs()
    render(<SwarmDebugPanel />)
    logHandshake('/exchange/ph.control/ev.swarm-created.sw1', '{}')
    await waitFor(() => screen.getByText('Swarm sw1'))
    expect(screen.getByText(/created:/).textContent).not.toContain('pending')
    expect(screen.getByText(/started: pending/)).toBeTruthy()
  })
})
