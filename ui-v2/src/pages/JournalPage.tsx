import { Navigate, Route, Routes } from 'react-router-dom'
import { HiveJournalPage } from './journal/HiveJournalPage'
import { SwarmJournalPage } from './journal/SwarmJournalPage'

export function JournalPage() {
  return (
    <Routes>
      <Route index element={<Navigate to="hive" replace />} />
      <Route path="hive" element={<HiveJournalPage />} />
      <Route path="swarms/:swarmId" element={<SwarmJournalPage />} />
      <Route path="*" element={<Navigate to="hive" replace />} />
    </Routes>
  )
}
