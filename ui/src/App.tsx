import { Routes, Route } from 'react-router-dom'
import Layout from './layout/Layout'
import Home from './pages/Home'
import Hive from './pages/Hive'
import Buzz from './pages/Buzz'
import Nectar from './pages/Nectar'

export default function App() {
  return (
    <Routes>
      <Route path="/" element={<Layout />}>
        <Route index element={<Home />} />
        <Route path="hive" element={<Hive />} />
        <Route path="buzz" element={<Buzz />} />
        <Route path="nectar" element={<Nectar />} />
      </Route>
    </Routes>
  )
}
