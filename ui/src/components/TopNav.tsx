import { Menu, Moon, Sun } from 'lucide-react';
import { Link } from 'react-router-dom';
import { useUIStore } from '@lib/store';

export default function TopNav() {
  const toggleSidebar = useUIStore((s) => s.toggleSidebar);
  const theme = useUIStore((s) => s.theme);
  const toggleTheme = useUIStore((s) => s.toggleTheme);

  return (
    <header className="flex items-center justify-between bg-card border-b border-accent px-4 h-14">
      <div className="flex items-center gap-2">
        <button
          className="md:hidden"
          onClick={toggleSidebar}
          aria-label="Toggle sidebar"
        >
          <Menu size={24} />
        </button>
        <img src="/logo.svg" alt="PocketHive" className="h-6 w-6" />
        <Link to="/" className="font-bold text-lg">
          PocketHive
        </Link>
        <nav className="hidden md:flex gap-4 ml-4">
          <Link to="/">Home</Link>
          <Link to="/hive">Hive</Link>
          <Link to="/buzz">Buzz</Link>
          <Link to="/nectar">Nectar</Link>
        </nav>
      </div>
      <button onClick={toggleTheme} aria-label="Toggle theme">
        {theme === 'dark' ? <Sun size={20} /> : <Moon size={20} />}
      </button>
    </header>
  );
}
