import { describe, it, expect } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import '@testing-library/jest-dom/vitest'
import SwarmDebugPanel from './SwarmDebugPanel'
import { logHandshake, resetLogs } from '../lib/logs'

/**
 * @vitest-environment jsdom
 */

describe('SwarmDebugPanel', () => {
  it('renders creation and readiness timeline for swarms', async () => {
    resetLogs()
    render(<SwarmDebugPanel />)
    logHandshake('/exchange/ph.control/ev.swarm-created.sw1', '{}', 'hive', 'stomp')
    await waitFor(() => screen.getByText('Swarm sw1'))
    expect(screen.getByText(/created:/).textContent).not.toContain('pending')
    expect(screen.getByText(/ready: pending/)).toBeTruthy()

    logHandshake('/exchange/ph.control/ev.swarm-ready.sw1', '{}', 'hive', 'stomp')
    await waitFor(() =>
      expect(screen.getByText(/ready:/).textContent).not.toContain('pending'),
    )
  })
})
