import { Link, NavLink } from 'react-router-dom'
import { Menu, Sun, MoonStar } from 'lucide-react'
import Logo from './Logo'
import { useUiStore } from '../lib/store'

const navLinkClass = ({ isActive }: { isActive: boolean }) =>
  `px-3 py-2 rounded-xl text-sm transition-colors
   ${isActive ? 'bg-phl-surface dark:bg-ph-surface text-phl-text dark:text-white' : 'text-phl-muted dark:text-ph-muted hover:bg-phl-surface/70 dark:hover:bg-ph-surface/70 hover:text-phl-text dark:hover:text-white'}`

export default function TopNav() {
  const toggleSidebar = useUiStore((s) => s.toggleSidebar)
  const theme = useUiStore((s) => s.theme)
  const toggleTheme = useUiStore((s) => s.toggleTheme)

  return (
    <header className="sticky top-0 z-40 border-b border-phl-border dark:border-ph-border bg-phl-bg/70 dark:bg-ph-bg/70 backdrop-blur">
      <div className="mx-auto flex max-w-[1400px] items-center gap-3 px-4 py-3">
        <button
          aria-label="Toggle sidebar"
          className="rounded-xl p-2 hover:bg-phl-surface/70 dark:hover:bg-ph-surface/70"
          onClick={toggleSidebar}
        >
          <Menu size={18} />
        </button>

        <Link to="/" className="flex items-center gap-3">
          <Logo className="h-9 w-9" />
          <div className="leading-tight">
            <span className="block text-lg font-semibold">
              <span className="bg-clip-text text-transparent bg-gradient-to-r from-cyan-300 to-amber-300">
                PocketHive
              </span>
            </span>
            <span className="text-[11px] tracking-wide text-phl-muted dark:text-ph-muted">portable transaction swarm</span>
          </div>
        </Link>

        <nav className="ml-auto flex items-center gap-1">
          <NavLink to="/" className={navLinkClass} end>
            Home
          </NavLink>
          <NavLink to="/hive" className={navLinkClass}>
            Hive
          </NavLink>
          <NavLink to="/buzz" className={navLinkClass}>
            Buzz
          </NavLink>
          <NavLink to="/nectar" className={navLinkClass}>
            Nectar
          </NavLink>

          <button
            aria-label="Toggle theme"
            className="ml-2 rounded-xl p-2 hover:bg-phl-surface/70 dark:hover:bg-ph-surface/70"
            onClick={toggleTheme}
            title={`Switch to ${theme === 'dark' ? 'light' : 'dark'} theme`}
          >
            {theme === 'dark' ? <Sun size={18} /> : <MoonStar size={18} />}
          </button>
        </nav>
      </div>
    </header>
  )
}
