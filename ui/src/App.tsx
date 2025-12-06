import { Routes, Route } from 'react-router-dom'
import Layout from './layout/Layout'
import Home from './pages/Home'
import HivePage from './pages/hive/HivePage'
import SwarmJournalPage from './pages/hive/SwarmJournalPage'
import Nectar from './pages/Nectar'
import { CapabilitiesProvider } from './contexts/CapabilitiesContext'
import { SwarmMetadataProvider } from './contexts/SwarmMetadataContext'
import SutEnvironmentsPage from './pages/sut/SutEnvironmentsPage'
import SwarmListPage from './pages/SwarmListPage'
import ScenariosPage from './pages/ScenariosPage'
import PerfModelerPage from './pages/perf/PerfModelerPage'

export default function App() {
  return (
    <Routes>
      <Route path="/" element={<Layout />}>
        <Route index element={<Home />} />
        <Route
          path="hive"
          element={
            <SwarmMetadataProvider>
              <CapabilitiesProvider>
                <HivePage />
              </CapabilitiesProvider>
            </SwarmMetadataProvider>
          }
        />
        <Route
          path="hive/journal/:swarmId"
          element={
            <SwarmMetadataProvider>
              <CapabilitiesProvider>
                <SwarmJournalPage />
              </CapabilitiesProvider>
            </SwarmMetadataProvider>
          }
        />
        <Route
          path="swarms"
          element={
            <SwarmMetadataProvider>
              <SwarmListPage />
            </SwarmMetadataProvider>
          }
        />
        <Route
          path="scenarios"
          element={
            <CapabilitiesProvider>
              <ScenariosPage />
            </CapabilitiesProvider>
          }
        />
        <Route path="sut" element={<SutEnvironmentsPage />} />
        <Route path="perf" element={<PerfModelerPage />} />
        <Route path="nectar" element={<Nectar />} />
      </Route>
    </Routes>
  )
}
