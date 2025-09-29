import { Suspense, lazy } from 'react'
import { Routes, Route } from 'react-router-dom'
import Layout from './layout/Layout'
import Home from './pages/Home'
import HivePage from './pages/hive/HivePage'
import Nectar from './pages/Nectar'
import ScenarioHost from './pages/scenario/ScenarioHost'

const scenarioRemoteId = '@ph/scenario/ScenarioApp'

const ScenarioApp = lazy(async () =>
  (await import(/* @vite-ignore */ scenarioRemoteId)) as typeof import('@ph/scenario/ScenarioApp'),
)

function ScenarioFallback() {
  return (
    <div className="flex h-full min-h-[360px] items-center justify-center bg-slate-950 text-sm uppercase tracking-[0.3em] text-amber-300">
      Loading Scenario…
    </div>
  )
}

export default function App() {
  return (
    <Routes>
      <Route path="/" element={<Layout />}>
        <Route index element={<Home />} />
        <Route path="hive" element={<HivePage />} />
        <Route path="nectar" element={<Nectar />} />
        <Route
          path="scenario/*"
          element={(
            <Suspense fallback={<ScenarioFallback />}>
              <ScenarioHost>
                <ScenarioApp />
              </ScenarioHost>
            </Suspense>
          )}
        />
      </Route>
    </Routes>
  )
}
