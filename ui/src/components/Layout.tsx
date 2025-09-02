import { Outlet } from 'react-router-dom';
import TopNav from '@components/TopNav';
import SideNav from '@components/SideNav';

export default function Layout() {
  return (
    <div className="min-h-screen flex flex-col bg-background text-text">
      <TopNav />
      <div className="flex flex-1">
        <SideNav />
        <main className="flex-1 p-4">
          <Outlet />
        </main>
      </div>
    </div>
  );
}
