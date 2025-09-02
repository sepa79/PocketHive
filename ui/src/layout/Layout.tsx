import { NavLink, Outlet } from 'react-router-dom'
import { Menu } from 'lucide-react'
import { useUIStore } from '../store'
import { useEffect } from 'react'

export default function Layout() {
  const { sidebarOpen, toggleSidebar, closeSidebar } = useUIStore()

  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'Escape') closeSidebar()
    }
    window.addEventListener('keydown', handler)
    return () => window.removeEventListener('keydown', handler)
  }, [closeSidebar])

  return (
    <div className="min-h-screen text-white">
      <header className="flex items-center gap-4 p-4 sticky top-0 z-10 backdrop-blur border-b border-white/10 bg-[#080a0e]/75">
        <img className="logo" src="/logo.svg" alt="PocketHive Logo" />
        <nav className="nav-tabs">
          <NavLink to="/hive" className="tab-btn">
            <img src="/hive.svg" alt="" className="tab-icon" />Hive
          </NavLink>
          <NavLink to="/buzz" className="tab-btn">
            <img src="/buzz.svg" alt="" className="tab-icon" />Buzz
          </NavLink>
          <NavLink to="/nectar" className="tab-btn">
            <img src="/nectar.svg" alt="" className="tab-icon" />Nectar
          </NavLink>
        </nav>
        <div className="header-right">
          <div className="service-links">
            <a id="link-rabbitmq" href="#" target="_blank" rel="noopener" aria-label="RabbitMQ"><img src="/icons/rabbitmq.svg" alt="RabbitMQ" /></a>
            <a id="link-prometheus" href="#" target="_blank" rel="noopener" aria-label="Prometheus"><img src="/icons/prometheus.svg" alt="Prometheus" /></a>
            <a id="link-grafana" href="#" target="_blank" rel="noopener" aria-label="Grafana"><img src="/icons/grafana.svg" alt="Grafana" /></a>
            <a id="link-wiremock" href="#" target="_blank" rel="noopener" aria-label="WireMock"><img src="/icons/wiremock.svg" alt="WireMock" /></a>
          </div>
          <div className="menu relative">
            <button id="menu-btn" className="bg-toggle" onClick={toggleSidebar}>
              <Menu className="inline mr-1 h-4 w-4" /> Menu
            </button>
            {sidebarOpen && (
              <div id="menu-dropdown" className="dropdown-panel absolute right-0 mt-2" onMouseLeave={closeSidebar}>
                <a href="/readme.html" className="dropdown-item">README</a>
                <a href="/bindings.html" className="dropdown-item">Buzz Bindings</a>
                <a href="/changelog.html" className="dropdown-item">Changelog</a>
                <a href="/docs.html" className="dropdown-item">API Docs</a>
              </div>
            )}
          </div>
        </div>
      </header>
      <main>
        <Outlet />
      </main>
    </div>
  )
}
