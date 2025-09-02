import { NavLink } from 'react-router-dom'
import { useUiStore } from '../lib/store'
import { Home as HomeIcon, Hexagon, Radio, Droplets } from 'lucide-react'

const item = ({ isActive }: { isActive: boolean }) =>
  `group flex items-center gap-2 rounded-xl px-3 py-2 text-sm
   ${isActive ? 'bg-phl-surface dark:bg-ph-surface text-phl-text dark:text-white' : 'text-phl-muted dark:text-ph-muted hover:bg-phl-surface/70 dark:hover:bg-ph-surface/70 hover:text-phl-text dark:hover:text-white'}`

export default function SideNav() {
  const open = useUiStore((s) => s.sidebarOpen)

  return (
    <aside
      className={`transition-[width] duration-200 border-r border-phl-border dark:border-ph-border bg-phl-bg/60 dark:bg-ph-bg/60 ${open ? 'w-60' : 'w-16'}`}
      aria-label="Sidebar"
    >
      <div className="p-3">
        <NavLink to="/" className={item} end>
          <HomeIcon size={16} />
          <span className={`${open ? 'block' : 'hidden'}`}>Home</span>
        </NavLink>
        <NavLink to="/hive" className={item}>
          <Hexagon size={16} />
          <span className={`${open ? 'block' : 'hidden'}`}>Hive</span>
        </NavLink>
        <NavLink to="/buzz" className={item}>
          <Radio size={16} />
          <span className={`${open ? 'block' : 'hidden'}`}>Buzz</span>
        </NavLink>
        <NavLink to="/nectar" className={item}>
          <Droplets size={16} />
          <span className={`${open ? 'block' : 'hidden'}`}>Nectar</span>
        </NavLink>
      </div>
    </aside>
  )
}
