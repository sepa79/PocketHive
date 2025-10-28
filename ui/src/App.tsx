import { Routes, Route } from 'react-router-dom'
import Layout from './layout/Layout'
import Home from './pages/Home'
import HivePage from './pages/hive/HivePage'
import Nectar from './pages/Nectar'
import { CapabilitiesProvider } from './contexts/CapabilitiesContext'

export default function App() {
  return (
    <Routes>
      <Route path="/" element={<Layout />}>
        <Route index element={<Home />} />
        <Route
          path="hive"
          element={
            <CapabilitiesProvider>
              <HivePage />
            </CapabilitiesProvider>
          }
        />
        <Route path="nectar" element={<Nectar />} />
      </Route>
    </Routes>
  )
}
