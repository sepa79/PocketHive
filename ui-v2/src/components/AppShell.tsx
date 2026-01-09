import { Outlet } from 'react-router-dom'
import { useEffect, useMemo, useState } from 'react'
import { SideNav } from './SideNav'
import { TopBar } from './TopBar'
import { ToolsBarProvider } from './ToolsBarContext'
import { PageToolsBar } from './PageToolsBar'

const NAV_STATE_KEY = 'PH_UI_NAV'

type NavState = 'collapsed' | 'expanded'

function getInitialNavState(): NavState {
  try {
    const raw = window.sessionStorage.getItem(NAV_STATE_KEY)
    if (raw === 'collapsed' || raw === 'expanded') return raw
  } catch {
    // ignore
  }
  try {
    return window.matchMedia('(min-width: 1101px)').matches ? 'expanded' : 'collapsed'
  } catch {
    return 'collapsed'
  }
}

export function AppShell() {
  const [nav, setNav] = useState<NavState>(() => getInitialNavState())
  const navExpanded = nav === 'expanded'
  const shellClass = useMemo(() => (navExpanded ? 'appShell appShellNavExpanded' : 'appShell'), [navExpanded])

  useEffect(() => {
    try {
      window.sessionStorage.setItem(NAV_STATE_KEY, nav)
    } catch {
      // ignore
    }
  }, [nav])

  return (
    <ToolsBarProvider>
      <div className={shellClass}>
        <header className="topBar">
          <TopBar />
        </header>
        <aside className="sideNav">
          <SideNav expanded={navExpanded} onToggle={() => setNav((v) => (v === 'expanded' ? 'collapsed' : 'expanded'))} />
        </aside>
        <main className="appContent">
          <PageToolsBar />
          <div className="pageContent">
            <Outlet />
          </div>
        </main>
      </div>
    </ToolsBarProvider>
  )
}
