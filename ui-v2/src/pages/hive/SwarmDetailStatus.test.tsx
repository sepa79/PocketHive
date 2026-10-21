import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, expect, it, vi } from 'vitest'
import { SwarmDetailStatus } from './SwarmDetailStatus'

afterEach(cleanup)

it('announces runtime loading and clears the announcement after completion', () => {
  const { rerender } = render(<SwarmDetailStatus loading error={null} onRetry={vi.fn()} />)
  expect(screen.getByRole('status').textContent).toBe('Loading runtime snapshot…')
  rerender(<SwarmDetailStatus loading={false} error={null} onRetry={vi.fn()} />)
  expect(screen.queryByRole('status')).toBeNull()
  expect(screen.queryByRole('alert')).toBeNull()
  expect(screen.queryByRole('button')).toBeNull()
})

it('announces a failed runtime read with an accessible keyboard retry', async () => {
  const retry = vi.fn()
  const user = userEvent.setup()
  render(<SwarmDetailStatus loading={false} error="Synthetic runtime unavailable" onRetry={retry} />)
  const alert = screen.getByRole('alert')
  expect(alert.textContent).toContain('Unable to load runtime snapshot: Synthetic runtime unavailable')
  const button = within(alert).getByRole('button', { name: 'Retry runtime snapshot' })
  expect(button).toHaveProperty('disabled', false)
  await user.tab()
  expect(document.activeElement).toBe(button)
  await user.keyboard('{Enter}')
  expect(retry).toHaveBeenCalledTimes(1)
})

it('keeps retry explicit and prevents duplicate retries during a pending read', () => {
  const retry = vi.fn()
  const { rerender } = render(<SwarmDetailStatus loading error="Synthetic error" onRetry={retry} />)
  const button = screen.getByRole('button', { name: 'Retry runtime snapshot' })
  expect(button).toHaveProperty('disabled', true)
  fireEvent.click(button)
  expect(retry).not.toHaveBeenCalled()
  rerender(<SwarmDetailStatus loading={false} error="Synthetic error" onRetry={retry} />)
  fireEvent.click(button)
  expect(retry).toHaveBeenCalledTimes(1)
})
