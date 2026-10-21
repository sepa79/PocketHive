import { cleanup, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { SideNav } from './SideNav'

const access = vi.hoisted(() => ({ canAccessPocketHive: false, isAuthAdmin: false }))
vi.mock('../lib/authContext', () => ({ useAuth: () => access }))
afterEach(cleanup)

describe('PH menu selection', () => {
  it('does not expose PH domain links or admin links without the existing access projection', () => {
    access.canAccessPocketHive = false
    access.isAuthAdmin = false
    render(<MemoryRouter><SideNav expanded onExpandedChange={vi.fn()} /></MemoryRouter>)
    expect(screen.getAllByRole('link').map((link) => link.getAttribute('href'))).toEqual(['/other'])
  })

  it('preserves all PH routes and leaves the active route with React Router', () => {
    access.canAccessPocketHive = true
    access.isAuthAdmin = true
    render(<MemoryRouter initialEntries={['/journal']}><SideNav expanded={false} onExpandedChange={vi.fn()} /></MemoryRouter>)
    expect(screen.getAllByRole('link').map((link) => link.getAttribute('href'))).toEqual([
      '/hive', '/proxy', '/journal', '/buzz', '/scenarios', '/users', '/other',
    ])
    expect(screen.getByRole('link', { name: 'Journal' }).getAttribute('aria-current')).toBe('page')
    expect(screen.getByRole('link', { name: 'Hive' }).getAttribute('aria-current')).toBeNull()
  })
})
