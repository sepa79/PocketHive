import { Routes, Route, Navigate } from 'react-router-dom';
import Layout from './components/Layout';
import Home from './views/Home';
import Hive from './views/Hive';
import Buzz from './views/Buzz';
import Nectar from './views/Nectar';

export default function App() {
  return (
    <Routes>
      <Route path="/" element={<Layout />}>
        <Route index element={<Home />} />
        <Route path="hive" element={<Hive />} />
        <Route path="buzz" element={<Buzz />} />
        <Route path="nectar" element={<Nectar />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Route>
    </Routes>
  );
}
