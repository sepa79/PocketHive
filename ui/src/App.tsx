import { Routes, Route } from 'react-router-dom'
import Layout from './layout/Layout'
import Home from './pages/Home'
import HivePage from './pages/hive/HivePage'
import Nectar from './pages/Nectar'
import DocPage from './pages/docs/DocPage'

export default function App() {
  return (
    <Routes>
      <Route path="/" element={<Layout />}>
        <Route index element={<Home />} />
        <Route path="hive" element={<HivePage />} />
        <Route path="nectar" element={<Nectar />} />
        <Route path="docs">
          <Route index element={<DocPage />} />
          <Route path=":docId" element={<DocPage />} />
        </Route>
      </Route>
    </Routes>
  )
}
