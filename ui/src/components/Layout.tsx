import { NavLink, Outlet } from 'react-router-dom';
import { useUIState } from '../state/ui';
import logo from '@legacy/assets/logo.svg';

export default function Layout() {
  const { sidebarOpen, toggleSidebar } = useUIState();
  return (
    <div className="min-h-screen text-white">
      <header>
        <img src={logo} className="logo" alt="PocketHive Logo" />
        <div className="header-right">
          <button id="menu-btn" className="bg-toggle" onClick={toggleSidebar}>
            ☰ Menu
          </button>
        </div>
      </header>
      <div className="flex">
        <nav
          className={`bg-gray-900 p-4 transition-transform duration-300 md:translate-x-0 md:static fixed top-0 left-0 h-full z-10 transform ${
            sidebarOpen ? 'translate-x-0' : '-translate-x-full'
          }`}
          style={{ width: '200px' }}
        >
          <ul className="flex flex-col gap-2 nav-tabs">
            <li>
              <NavLink to="/" end className="tab-btn">
                Home
              </NavLink>
            </li>
            <li>
              <NavLink to="/hive" className="tab-btn">
                Hive
              </NavLink>
            </li>
            <li>
              <NavLink to="/buzz" className="tab-btn">
                Buzz
              </NavLink>
            </li>
            <li>
              <NavLink to="/nectar" className="tab-btn">
                Nectar
              </NavLink>
            </li>
          </ul>
        </nav>
        <main className="flex-1 p-6">
          <Outlet />
        </main>
      </div>
    </div>
  );
}
