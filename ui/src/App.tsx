import { Routes, Route } from 'react-router-dom'
import Layout from './layout/Layout'
import Home from './pages/Home'
import HivePage from './pages/hive/HivePage'
import Buzz from './pages/Buzz'
import Nectar from './pages/Nectar'
import Queen from './pages/Queen'

export default function App() {
  return (
    <Routes>
      <Route path="/" element={<Layout />}>
        <Route index element={<Home />} />
        <Route path="queen" element={<Queen />} />
        <Route path="hive" element={<HivePage />} />
        <Route path="buzz" element={<Buzz />} />
        <Route path="nectar" element={<Nectar />} />
      </Route>
    </Routes>
  )
}
