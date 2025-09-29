import { NavLink, Outlet } from 'react-router-dom'
import { Hexagon, Radio, Droplet, Workflow } from 'lucide-react'
import MonolithIcon from '../icons/Monolith'
import Health from '../components/Health'
import Connectivity from '../components/Connectivity'
import { useUIStore } from '../store'
import { useEffect } from 'react'
import { useConfig } from '../lib/config'
import BuzzPanel from '../components/BuzzPanel'
import { PanelGroup, Panel, PanelResizeHandle } from 'react-resizable-panels'

export default function Layout() {
  const {
    sidebarOpen,
    toggleSidebar,
    closeSidebar,
    toast,
    clearToast,
    buzzVisible,
    toggleBuzz,
    buzzDock,
    buzzSize,
    setBuzzSize,
  } = useUIStore()

  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'Escape') closeSidebar()
    }
    window.addEventListener('keydown', handler)
    return () => window.removeEventListener('keydown', handler)
  }, [closeSidebar])

  useEffect(() => {
    if (toast) {
      const t = setTimeout(() => clearToast(), 3000)
      return () => clearTimeout(t)
    }
  }, [toast, clearToast])

  const { rabbitmq, prometheus, grafana, wiremock } = useConfig()
  const services = { rabbitmq, prometheus, grafana, wiremock }

  return (
    <div className="flex h-screen flex-col overflow-hidden text-white">
      <header className="flex items-center gap-4 p-4 sticky top-0 z-10 backdrop-blur border-b border-white/10 bg-[#080a0e]/75">
        <img className="logo" src="/logo.svg" alt="PocketHive Logo" />
        <nav className="nav-tabs">
          <NavLink
            to="/hive"
            className={({ isActive }) =>
              `tab-btn flex items-center${isActive ? ' tab-active' : ''}`
            }
          >
            <Hexagon strokeWidth={1.5} className="tab-icon text-white/80" />
            Hive
          </NavLink>
          <NavLink
            to="/scenario"
            className={({ isActive }) =>
              `tab-btn flex items-center${isActive ? ' tab-active' : ''}`
            }
          >
            <Workflow strokeWidth={1.5} className="tab-icon text-white/80" />
            Scenario
          </NavLink>
          <button
            className={`tab-btn flex items-center${buzzVisible ? ' tab-active' : ''}`}
            onClick={toggleBuzz}
          >
            <Radio strokeWidth={1.5} className="tab-icon text-white/80" />
            Buzz
          </button>
          <NavLink
            to="/nectar"
            className={({ isActive }) =>
              `tab-btn flex items-center${isActive ? ' tab-active' : ''}`
            }
          >
            <Droplet strokeWidth={1.5} className="tab-icon text-white/80" />
            Nectar
          </NavLink>
        </nav>
        <div className="header-right">
          <div className="service-links">
            <a id="link-rabbitmq" href={services.rabbitmq} target="_blank" rel="noopener" aria-label="RabbitMQ"><img src="/icons/rabbitmq.svg" alt="RabbitMQ" /></a>
            <a id="link-prometheus" href={services.prometheus} target="_blank" rel="noopener" aria-label="Prometheus"><img src="/icons/prometheus.svg" alt="Prometheus" /></a>
            <a id="link-grafana" href={services.grafana} target="_blank" rel="noopener" aria-label="Grafana"><img src="/icons/grafana.svg" alt="Grafana" /></a>
            <a id="link-wiremock" href={services.wiremock} target="_blank" rel="noopener" aria-label="WireMock"><img src="/icons/wiremock.svg" alt="WireMock" /></a>
          </div>
          <Connectivity />
          <Health />
          <div className="h-6 w-px bg-white/20 mx-2" />
          <div className="menu relative">
            <button
              className="inline-flex items-center justify-center p-0 bg-transparent border-0 cursor-pointer"
              onClick={toggleSidebar}
              aria-label="Menu"
            >
              <MonolithIcon className="h-6 w-6" />
            </button>
            {sidebarOpen && (
              <div id="menu-dropdown" className="dropdown-panel absolute right-0 mt-2" onMouseLeave={closeSidebar}>
                <a href="/docs/readme.html" className="dropdown-item" onClick={closeSidebar}>README</a>
                <a href="/docs/bindings.html" className="dropdown-item" onClick={closeSidebar}>Buzz Bindings</a>
                <a href="/docs/changelog.html" className="dropdown-item" onClick={closeSidebar}>Changelog</a>
                <a href="/docs/docs.html" className="dropdown-item" onClick={closeSidebar}>API Docs</a>
              </div>
            )}
          </div>
        </div>
      </header>
      <main className="flex flex-1 min-h-0 overflow-hidden">
        {buzzVisible ? (
          buzzDock === 'bottom' ? (
            <PanelGroup
              direction="vertical"
              onLayout={(sizes) => setBuzzSize(sizes[1])}
              className="flex-1 h-full min-h-0"
            >
              <Panel minSize={10}>
                <Outlet />
              </Panel>
              <PanelResizeHandle className="h-1 bg-white/20" />
              <Panel minSize={10} defaultSize={buzzSize}>
                <BuzzPanel />
              </Panel>
            </PanelGroup>
          ) : (
            <PanelGroup
              direction="horizontal"
              onLayout={(sizes) =>
                setBuzzSize(sizes[buzzDock === 'left' ? 0 : 1])
              }
              className="flex-1 h-full min-h-0"
            >
              {buzzDock === 'left' ? (
                <>
                  <Panel minSize={10} defaultSize={buzzSize}>
                    <BuzzPanel />
                  </Panel>
                  <PanelResizeHandle className="w-1 bg-white/20" />
                  <Panel minSize={10}>
                    <Outlet />
                  </Panel>
                </>
              ) : (
                <>
                  <Panel minSize={10}>
                    <Outlet />
                  </Panel>
                  <PanelResizeHandle className="w-1 bg-white/20" />
                  <Panel minSize={10} defaultSize={buzzSize}>
                    <BuzzPanel />
                  </Panel>
                </>
              )}
            </PanelGroup>
          )
        ) : (
          <div className="flex-1 min-h-0 overflow-auto">
            <Outlet />
          </div>
        )}
      </main>
      {toast && (
        <div className="fixed bottom-4 right-4 bg-black/80 text-white px-4 py-2 rounded">
          {toast}
        </div>
      )}
    </div>
  )
}
