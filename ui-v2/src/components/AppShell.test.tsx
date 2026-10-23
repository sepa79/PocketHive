import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AppShell } from './AppShell'

vi.mock('./TopBar', async () => {
  const { AppHeader } = await import('@pockethive/ui')
  return { TopBar: () => <AppHeader leading="PH brand" center={null} trailing={null} /> }
})
vi.mock('./AuthAccessNotice', () => ({ AuthAccessNotice: () => <p>PH access notice</p> }))
vi.mock('./PageToolsBar', () => ({ PageToolsBar: () => <div role="toolbar">PH tools</div> }))
vi.mock('../lib/authContext', () => ({ useAuth: () => ({ canAccessPocketHive: true, isAuthAdmin: false }) }))

beforeEach(() => {
  window.sessionStorage.clear()
  vi.stubGlobal('matchMedia', vi.fn().mockReturnValue({ matches: true }))
})
afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

function renderShell() {
  return render(<MemoryRouter initialEntries={['/hive']}><Routes>
    <Route element={<AppShell />}>
      <Route path="/hive" element={<h1>PH Hive</h1>} />
      <Route path="/journal" element={<h1>PH Journal</h1>} />
    </Route>
  </Routes></MemoryRouter>)
}

describe('PH shell integration', () => {
  it('retains routed content and PH-owned navigation preferences across remounts', async () => {
    const user = userEvent.setup()
    const rendered = renderShell()
    expect(screen.getByRole('heading', { name: 'PH Hive' })).toBeDefined()
    expect(screen.getByText('PH access notice')).toBeDefined()
    await user.click(screen.getByRole('button', { name: 'Collapse navigation' }))
    expect(window.sessionStorage.getItem('PH_UI_NAV')).toBe('collapsed')
    await user.click(screen.getByRole('link', { name: 'Journal' }))
    expect(screen.getByRole('heading', { name: 'PH Journal' })).toBeDefined()
    rendered.unmount()
    renderShell()
    expect(screen.getByRole('button', { name: 'Expand navigation' }).getAttribute('aria-expanded')).toBe('false')
    expect(screen.getByRole('link', { name: 'Hive' }).getAttribute('aria-current')).toBe('page')
  })

  it('retains the collapsed initial view below the PH viewport breakpoint', () => {
    vi.mocked(window.matchMedia).mockReturnValue({ matches: false } as MediaQueryList)
    renderShell()
    expect(screen.getByRole('button', { name: 'Expand navigation' })).toBeDefined()
    expect(window.sessionStorage.getItem('PH_UI_NAV')).toBe('collapsed')
  })
})
