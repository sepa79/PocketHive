import { Link, NavLink } from 'react-router-dom'
import { Menu, Sun, MoonStar } from 'lucide-react'
import Logo from './Logo'
import { useUiStore } from '../lib/store'
import Button from './ui/Button'

const baseLink =
  'inline-flex items-center justify-center rounded-xl px-6 py-3 text-sm font-semibold uppercase tracking-wide transition no-underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-offset-2 focus-visible:ring-phl-accent2 dark:focus-visible:ring-ph-accent2 focus-visible:ring-offset-phl-bg dark:focus-visible:ring-offset-ph-bg active:translate-y-px'

const navLinkClass = ({ isActive }: { isActive: boolean }) =>
  `${baseLink} ${
    isActive
      ? 'bg-phl-accent2 dark:bg-ph-accent2 text-phl-bg dark:text-ph-bg border border-transparent shadow-[0_0_20px_rgba(51,225,255,0.35)]'
      : 'text-phl-accent2 dark:text-ph-accent2 border border-phl-accent2/35 dark:border-ph-accent2/35 bg-[radial-gradient(120%_120%_at_10%_10%,rgba(51,225,255,0.25),rgba(51,225,255,0.06)_40%,rgba(255,255,255,0.05)_70%,rgba(0,0,0,0)_100%)] shadow-[0_0_20px_rgba(51,225,255,0.15)_inset,0_0_16px_rgba(51,225,255,0.18)] hover:bg-[radial-gradient(120%_120%_at_10%_10%,rgba(51,225,255,0.35),rgba(51,225,255,0.1)_50%,rgba(255,255,255,0.07)_75%,rgba(0,0,0,0)_100%)] hover:shadow-[0_0_26px_rgba(51,225,255,0.22)_inset,0_0_20px_rgba(51,225,255,0.28)]'
  }`

export default function TopNav() {
  const toggleSidebar = useUiStore((s) => s.toggleSidebar)
  const theme = useUiStore((s) => s.theme)
  const toggleTheme = useUiStore((s) => s.toggleTheme)

  return (
    <header className="sticky top-0 z-40 border-b border-phl-border dark:border-ph-border bg-phl-bg/70 dark:bg-ph-bg/70 backdrop-blur">
      <div className="mx-auto flex max-w-[1400px] items-center gap-3 px-4 py-3">
        <Button
          aria-label="Toggle sidebar"
          variant="ghost"
          size="icon"
          onClick={toggleSidebar}
        >
          <Menu size={18} />
        </Button>

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

        <nav className="ml-auto flex items-center gap-2">
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

          <Button
            aria-label="Toggle theme"
            variant="ghost"
            size="icon"
            className="ml-2"
            onClick={toggleTheme}
            title={`Switch to ${theme === 'dark' ? 'light' : 'dark'} theme`}
          >
            {theme === 'dark' ? <Sun size={18} /> : <MoonStar size={18} />}
          </Button>
        </nav>
      </div>
    </header>
  )
}
