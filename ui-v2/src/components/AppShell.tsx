import { AppShell as PocketAppShell } from '@pockethive/ui'
import { AuthAccessNotice } from './AuthAccessNotice'
import { Outlet } from 'react-router-dom'
import { useEffect, useState } from 'react'
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

/**
 * Responsibility: compose PH navigation preferences, access status and routed content.
 * Must not: own shared shell presentation or derive backend permissions.
 * Contract: RESP-POCKET-UI-SHELL — docs/architecture/runtime-responsibilities.md#resp-pocket-ui-shell; RESP-UI-GLOBAL-ACCESS (access presentation).
 */
export function AppShell() {
  const [nav, setNav] = useState<NavState>(() => getInitialNavState())
  const navExpanded = nav === 'expanded'

  useEffect(() => {
    try {
      window.sessionStorage.setItem(NAV_STATE_KEY, nav)
    } catch {
      // ignore
    }
  }, [nav])

  return (
    <ToolsBarProvider>
      <PocketAppShell
        navigationExpanded={navExpanded}
        header={<TopBar />}
        navigation={<SideNav expanded={navExpanded} onExpandedChange={(expanded) => setNav(expanded ? 'expanded' : 'collapsed')} />}
        toolbar={<PageToolsBar />}
      >
        <AuthAccessNotice />
        <Outlet />
      </PocketAppShell>
    </ToolsBarProvider>
  )
}
