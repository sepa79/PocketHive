import { cleanup, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { NavigationRail } from './NavigationRail'

afterEach(cleanup)

describe('NavigationRail', () => {
  it('requests expansion with the keyboard but keeps the host state authoritative', async () => {
    const user = userEvent.setup()
    const onExpandedChange = vi.fn()
    const { rerender } = render(
      <NavigationRail expanded={false} onExpandedChange={onExpandedChange} label="Views" expandLabel="Expand views" collapseLabel="Collapse views">
        <a href="/plan">Plan</a>
      </NavigationRail>,
    )
    const toggle = screen.getByRole('button', { name: 'Expand views' })
    const items = document.getElementById(toggle.getAttribute('aria-controls')!)!
    expect(within(items).getByRole('link', { name: 'Plan' })).toBeDefined()
    await user.tab()
    await user.keyboard('{Enter}')
    expect(onExpandedChange).toHaveBeenCalledExactlyOnceWith(true)
    expect(toggle.getAttribute('aria-expanded')).toBe('false')
    rerender(
      <NavigationRail expanded onExpandedChange={onExpandedChange} label="Views" expandLabel="Expand views" collapseLabel="Collapse views">
        <a href="/plan">Plan</a>
      </NavigationRail>,
    )
    const collapse = screen.getByRole('button', { name: 'Collapse views' })
    expect(collapse.getAttribute('aria-expanded')).toBe('true')
    await user.click(collapse)
    expect(onExpandedChange).toHaveBeenLastCalledWith(false)
  })

  it('keeps two consumers independent with unique accessible controls', async () => {
    const user = userEvent.setup()
    const firstChange = vi.fn()
    const secondChange = vi.fn()
    render(<>
      <NavigationRail expanded onExpandedChange={firstChange} label="First" expandLabel="Expand first" collapseLabel="Collapse first">First links</NavigationRail>
      <NavigationRail expanded={false} onExpandedChange={secondChange} label="Second" expandLabel="Expand second" collapseLabel="Collapse second">Second links</NavigationRail>
    </>)
    const first = screen.getByRole('button', { name: 'Collapse first' })
    const second = screen.getByRole('button', { name: 'Expand second' })
    expect(first.getAttribute('aria-controls')).not.toBe(second.getAttribute('aria-controls'))
    await user.click(first)
    expect(firstChange).toHaveBeenCalledExactlyOnceWith(false)
    expect(secondChange).not.toHaveBeenCalled()
    expect(second.getAttribute('aria-expanded')).toBe('false')
  })
})
