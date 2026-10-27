import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Icon } from './Icon'
import { setTheme, type Theme } from '../lib/theme'
import { useTheme } from '../lib/useTheme'
import { useAuth } from '../lib/authContext'

/**
 * Responsibility: present account actions and the applied application theme.
 * Must not: own authentication or an independent theme preference.
 * Contract: RESP-POCKET-UI-SHELL — docs/architecture/runtime-responsibilities.md#resp-pocket-ui-shell.
 */
export function UserMenu() {
  const navigate = useNavigate()
  const auth = useAuth()
  const [open, setOpen] = useState(false)
  const theme = useTheme()
  const rootRef = useRef<HTMLDivElement | null>(null)

  useEffect(() => {
    const onDocMouseDown = (e: MouseEvent) => {
      const root = rootRef.current
      if (!root) return
      if (!(e.target instanceof Node)) return
      if (root.contains(e.target)) return
      setOpen(false)
    }
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false)
    }
    document.addEventListener('mousedown', onDocMouseDown)
    document.addEventListener('keydown', onKeyDown)
    return () => {
      document.removeEventListener('mousedown', onDocMouseDown)
      document.removeEventListener('keydown', onKeyDown)
    }
  }, [])

  const nextTheme: Theme = theme === 'dark' ? 'light' : 'dark'

  return (
    <div className="menuRoot" ref={rootRef}>
      <button
        type="button"
        className="iconButton"
        title="User"
        aria-label="User menu"
        aria-haspopup="menu"
        aria-expanded={open}
        onClick={() => setOpen((v) => !v)}
      >
        <Icon name="user" />
      </button>

      {open ? (
        <div className="menu" role="menu">
          <div className="menuIdentity">
            <div className="menuIdentityTitle">
              {auth.status === 'authenticated' ? auth.user?.displayName ?? auth.user?.username : 'Not signed in'}
            </div>
            <div className="menuIdentityMeta">
              {auth.status === 'authenticated'
                ? `${auth.user?.authProvider ?? 'UNKNOWN'} · ${auth.user?.username ?? ''}`
                : auth.status === 'loading'
                  ? 'Resolving session...'
                  : 'DEV login available'}
            </div>
          </div>
          <div className="menuSep" role="separator" />
          <button type="button" className="menuItem" role="menuitem" onClick={() => setTheme(nextTheme)}>
            Theme: <span className="menuItemValue">{theme}</span>
          </button>
          <div className="menuSep" role="separator" />
          <button
            type="button"
            className="menuItem"
            role="menuitem"
            onClick={() => {
              setOpen(false)
              navigate(auth.isAuthAdmin ? '/users' : '/login')
            }}
          >
            {auth.status === 'authenticated'
              ? auth.isAuthAdmin
                ? 'Users / account'
                : 'Account / switch user'
              : 'Login / users…'}
          </button>
          {auth.status === 'authenticated' ? (
            <button
              type="button"
              className="menuItem"
              role="menuitem"
              onClick={() => {
                void auth.logout()
                setOpen(false)
              }}
            >
              Sign out
            </button>
          ) : null}
        </div>
      ) : null}
    </div>
  )
}
