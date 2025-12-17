import { NavLink, Route, Routes } from 'react-router-dom'

function Home() {
  return (
    <div className="card">
      <h1 style={{ margin: '0 0 8px' }}>PocketHive UI v2</h1>
      <div style={{ color: 'rgba(255,255,255,0.65)', fontSize: 14 }}>
        New editor work-in-progress. Served under <code>/v2</code>.
      </div>
    </div>
  )
}

function ScenarioEditorV2() {
  return (
    <div className="card">
      <h2 style={{ margin: '0 0 8px' }}>Scenario Editor v2 (beta)</h2>
      <div style={{ color: 'rgba(255,255,255,0.65)', fontSize: 14 }}>
        Placeholder. Next step: implement new scenario editor here.
      </div>
    </div>
  )
}

export default function App() {
  return (
    <div className="shell">
      <div className="topbar">
        <NavLink to="/" end>
          Home
        </NavLink>
        <NavLink to="/scenarios">Scenarios</NavLink>
      </div>
      <div className="content">
        <Routes>
          <Route path="/" element={<Home />} />
          <Route path="/scenarios" element={<ScenarioEditorV2 />} />
        </Routes>
      </div>
    </div>
  )
}
