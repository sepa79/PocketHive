import { Navigate, Route, Routes } from 'react-router-dom'
import { AppShell } from './components/AppShell'
import { HelpPage } from './pages/HelpPage'
import { HealthPage } from './pages/HealthPage'
import { HivePage } from './pages/HivePage'
import { HomePage } from './pages/HomePage'
import { JournalPage } from './pages/JournalPage'
import { LoginPage } from './pages/LoginPage'
import { OtherPage } from './pages/OtherPage'
import { ScenariosPage } from './pages/ScenariosPage'
import { WireLogPage } from './pages/WireLogPage'

export default function App() {
  return (
    <Routes>
      <Route element={<AppShell />}>
        <Route path="/" element={<HomePage />} />
        <Route path="/home" element={<Navigate to="/" replace />} />

        <Route path="/scenarios/*" element={<ScenariosPage />} />
        <Route path="/hive" element={<HivePage />} />
        <Route path="/hive/:swarmId" element={<HivePage />} />
        <Route path="/journal/*" element={<JournalPage />} />
        <Route path="/other/*" element={<OtherPage />} />
        <Route path="/buzz" element={<WireLogPage />} />

        <Route path="/health" element={<HealthPage />} />
        <Route path="/help" element={<HelpPage />} />
        <Route path="/login" element={<LoginPage />} />

        <Route path="*" element={<Navigate to="/" replace />} />
      </Route>
    </Routes>
  )
}
