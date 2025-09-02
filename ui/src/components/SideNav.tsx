import { Home, Hexagon, Bell, Droplet } from 'lucide-react';
import { NavLink } from 'react-router-dom';
import { useUIStore } from '@lib/store';

const links = [
  { to: '/', label: 'Home', icon: Home },
  { to: '/hive', label: 'Hive', icon: Hexagon },
  { to: '/buzz', label: 'Buzz', icon: Bell },
  { to: '/nectar', label: 'Nectar', icon: Droplet },
];

export default function SideNav() {
  const open = useUIStore((s) => s.sidebarOpen);
  return (
    <aside
      className={`bg-card border-r border-accent transition-all overflow-hidden ${open ? 'w-48' : 'w-0'}`}
    >
      <nav className="flex flex-col p-2 space-y-2">
        {links.map(({ to, label, icon: Icon }) => (
          <NavLink
            key={to}
            to={to}
            className={({ isActive }) =>
              `flex items-center gap-2 p-2 rounded hover:bg-accent/20 ${
                isActive ? 'font-semibold' : ''
              }`
            }
          >
            <Icon size={20} />
            <span>{label}</span>
          </NavLink>
        ))}
      </nav>
    </aside>
  );
}
