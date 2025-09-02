import TopNav from './TopNav'
import SideNav from './SideNav'
import { Outlet } from 'react-router-dom'

export default function Layout() {
  return (
    <div className="min-h-screen">
      <TopNav />
      <div className="mx-auto flex max-w-[1400px]">
        <SideNav />
        <main className="flex-1 space-y-4 p-4 md:p-6 lg:p-8">
          <Outlet />
        </main>
      </div>
    </div>
  )
}
