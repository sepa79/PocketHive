import { NavLink, Outlet } from 'react-router-dom'
import { Hexagon, Radio, Droplet } from 'lucide-react'
import MonolithIcon from '../icons/Monolith'
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

  const host = typeof window !== 'undefined' ? window.location.hostname : 'localhost'
  const services = {
    rabbitmq: `http://${host}:15672/`,
    prometheus: `http://${host}:9090/`,
    grafana: `http://${host}:3000/`,
    wiremock: `http://${host}:8080/__admin/`,
  }

  return (
    <div className="min-h-screen text-white">
      <header className="flex items-center gap-4 p-4 sticky top-0 z-10 backdrop-blur border-b border-white/10 bg-[#080a0e]/75">
        <img className="logo" src="/logo.svg" alt="PocketHive Logo" />
        <nav className="nav-tabs">
          <NavLink to="/hive" className="tab-btn">
            <Hexagon strokeWidth={1.5} className="tab-icon mr-1 text-white/80" />Hive
          </NavLink>
          <NavLink to="/buzz" className="tab-btn">
            <Radio strokeWidth={1.5} className="tab-icon mr-1 text-white/80" />Buzz
          </NavLink>
          <NavLink to="/nectar" className="tab-btn">
            <Droplet strokeWidth={1.5} className="tab-icon mr-1 text-white/80" />Nectar
          </NavLink>
        </nav>
        <div className="header-right">
          <div className="service-links">
            <a id="link-rabbitmq" href={services.rabbitmq} target="_blank" rel="noopener" aria-label="RabbitMQ"><img src="/icons/rabbitmq.svg" alt="RabbitMQ" /></a>
            <a id="link-prometheus" href={services.prometheus} target="_blank" rel="noopener" aria-label="Prometheus"><img src="/icons/prometheus.svg" alt="Prometheus" /></a>
            <a id="link-grafana" href={services.grafana} target="_blank" rel="noopener" aria-label="Grafana"><img src="/icons/grafana.svg" alt="Grafana" /></a>
            <a id="link-wiremock" href={services.wiremock} target="_blank" rel="noopener" aria-label="WireMock"><img src="/icons/wiremock.svg" alt="WireMock" /></a>
          </div>
          <div className="menu relative">
            <button
              id="menu-btn"
              className="bg-toggle"
              onClick={toggleSidebar}
              aria-label="Menu"
            >
              <MonolithIcon className="h-4 w-4" />
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
      <main>
        <Outlet />
      </main>
    </div>
  )
}
