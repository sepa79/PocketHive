import { cleanup, render, screen, within } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { AppHeader } from './AppHeader'
import { AppShell } from './AppShell'

afterEach(cleanup)

describe('AppShell', () => {
  it('keeps supplied header, navigation, toolbar and page content in their own regions', () => {
    render(
      <AppShell
        navigationExpanded
        header={<AppHeader leading={<a href="/">Host logo</a>} center="Host notices" trailing={<button>Host sign in</button>} />}
        navigation={<nav aria-label="Host navigation"><a href="/runs">Host runs</a></nav>}
        toolbar={<div role="toolbar" aria-label="Host page tools">Host tools</div>}
      >
        <h1>Host page</h1>
      </AppShell>,
    )

    const header = screen.getByRole('banner')
    expect(within(header).getByRole('link', { name: 'Host logo' }).getAttribute('href')).toBe('/')
    expect(within(header).getByRole('button', { name: 'Host sign in' })).toBeDefined()
    expect(within(header).getByText('Host notices')).toBeDefined()
    expect(within(screen.getByRole('navigation')).getByRole('link', { name: 'Host runs' })).toBeDefined()
    const main = screen.getByRole('main')
    expect(within(main).getByRole('heading', { name: 'Host page' })).toBeDefined()
    expect(within(main).getByRole('toolbar', { name: 'Host page tools' })).toBeDefined()
    expect(within(main).queryByRole('button', { name: 'Host sign in' })).toBeNull()
  })

  it('changes the supplied expansion projection without adding menus or account controls', () => {
    const { container, rerender } = render(
      <AppShell navigationExpanded={false} header={null} navigation={null} toolbar={null}>Page</AppShell>,
    )
    expect(container.firstElementChild?.getAttribute('data-navigation')).toBe('collapsed')
    rerender(<AppShell navigationExpanded header={null} navigation={null} toolbar={null}>Page</AppShell>)
    expect(container.firstElementChild?.getAttribute('data-navigation')).toBe('expanded')
    expect(screen.queryByRole('button')).toBeNull()
    expect(screen.queryByRole('link')).toBeNull()
  })
})
