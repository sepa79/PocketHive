import { Routes, Route } from 'react-router-dom';
import Layout from '@components/Layout';
import Home from '@routes/Home';
import Hive from '@routes/Hive';
import Buzz from '@routes/Buzz';
import Nectar from '@routes/Nectar';

export default function App() {
  return (
    <Routes>
      <Route element={<Layout />}>
        <Route path="/" element={<Home />} />
        <Route path="/hive" element={<Hive />} />
        <Route path="/buzz" element={<Buzz />} />
        <Route path="/nectar" element={<Nectar />} />
      </Route>
    </Routes>
  );
}
