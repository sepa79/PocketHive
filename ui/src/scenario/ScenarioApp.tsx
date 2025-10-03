import { Suspense, lazy } from 'react'
import { Route, Routes } from 'react-router-dom'

const ScenarioLayout = lazy(() => import('./routes/ScenarioLayout'))
const ScenarioListRoute = lazy(() =>
  import('./routes/ScenarioListRoute').then((mod) => ({ default: mod.Component })),
)
const ScenarioEditorRoute = lazy(() =>
  import('./routes/ScenarioEditorRoute').then((mod) => ({ default: mod.Component })),
)

export default function ScenarioApp() {
  return (
    <Suspense fallback={<div className="p-8 text-slate-200">Loading scenario module…</div>}>
      <Routes>
        <Route element={<ScenarioLayout />}>
          <Route index element={<ScenarioListRoute />} />
          <Route path="new" element={<ScenarioEditorRoute />} />
          <Route path="edit/:scenarioId" element={<ScenarioEditorRoute />} />
        </Route>
      </Routes>
    </Suspense>
  )
}
