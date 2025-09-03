import { Routes, Route } from 'react-router-dom'
import Layout from './layout/Layout'
import Home from './pages/Home'
import Hive from './pages/Hive'
import Buzz from './pages/Buzz'
import Nectar from './pages/Nectar'
import Readme from './pages/Readme'
import Bindings from './pages/Bindings'
import Changelog from './pages/Changelog'
import Docs from './pages/Docs'

export default function App() {
  return (
    <Routes>
      <Route path="/" element={<Layout />}>
        <Route index element={<Home />} />
        <Route path="hive" element={<Hive />} />
        <Route path="buzz" element={<Buzz />} />
        <Route path="nectar" element={<Nectar />} />
        <Route path="readme" element={<Readme />} />
        <Route path="bindings" element={<Bindings />} />
        <Route path="changelog" element={<Changelog />} />
        <Route path="docs" element={<Docs />} />
      </Route>
    </Routes>
  )
}
