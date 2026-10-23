import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { NavigationItem } from './NavigationItem'

afterEach(cleanup)

describe('NavigationItem', () => {
  it('preserves the accessible name while collapsed and leaves routing to its consumer', async () => {
    const user = userEvent.setup()
    const navigate = vi.fn()
    const { rerender } = render(
      <NavigationItem label="Runs" icon={<span aria-hidden="true">R</span>} expanded={false}>
        {(presentation) => <a {...presentation} href="/host/runs" aria-current="page" onClick={(event) => { event.preventDefault(); navigate() }} />}
      </NavigationItem>,
    )
    const link = screen.getByRole('link', { name: 'Runs' })
    expect(link.textContent).toBe('R')
    expect(link.getAttribute('href')).toBe('/host/runs')
    expect(link.getAttribute('aria-current')).toBe('page')
    await user.tab()
    await user.keyboard('{Enter}')
    expect(navigate).toHaveBeenCalledOnce()
    rerender(
      <NavigationItem label="Runs" icon={<span aria-hidden="true">R</span>} expanded>
        {(presentation) => <a {...presentation} href="/host/runs" />}
      </NavigationItem>,
    )
    expect(screen.getByRole('link', { name: 'Runs' }).textContent).toBe('RRuns')
  })
})
