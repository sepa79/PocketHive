import { useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Icon } from './Icon'
import { getTheme, setTheme, type Theme } from '../lib/theme'

export function UserMenu() {
  const navigate = useNavigate()
  const [open, setOpen] = useState(false)
  const [theme, setThemeState] = useState<Theme>(() => getTheme())
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

  const nextTheme = useMemo<Theme>(() => (theme === 'dark' ? 'light' : 'dark'), [theme])

  const applyTheme = (t: Theme) => {
    setTheme(t)
    setThemeState(t)
  }

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
          <button type="button" className="menuItem" role="menuitem" onClick={() => applyTheme(nextTheme)}>
            Theme: <span className="menuItemValue">{theme}</span>
          </button>
          <div className="menuSep" role="separator" />
          <button
            type="button"
            className="menuItem"
            role="menuitem"
            onClick={() => {
              setOpen(false)
              navigate('/login')
            }}
          >
            Login / users…
          </button>
        </div>
      ) : null}
    </div>
  )
}
