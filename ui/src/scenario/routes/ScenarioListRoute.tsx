import { Link } from 'react-router-dom'

import { useScenarioList } from '../api/scenarioManager'

const cardStyles = 'rounded-xl border border-slate-800 bg-slate-900/70 p-6 shadow-sm shadow-slate-950/40'

export function Component() {
  const { data, isLoading, isError, error } = useScenarioList()
  const scenarios = data ?? []

  return (
    <div className="mx-auto flex w-full max-w-5xl flex-col gap-6 px-8 py-12">
      <header className="flex flex-col gap-4 sm:flex-row sm:items-end sm:justify-between">
        <div>
          <p className="text-xs uppercase tracking-[0.35em] text-amber-300">Scenario builder</p>
          <h1 className="text-3xl font-semibold text-slate-100">Manage scenarios</h1>
          <p className="mt-2 max-w-2xl text-sm text-slate-400">
            Load existing scenarios or spin up new experiments. Assets and configuration are preserved per scenario so you can
            iterate with confidence.
          </p>
        </div>
        <Link
          to="new"
          className="inline-flex items-center justify-center rounded bg-amber-500 px-5 py-2 text-sm font-semibold text-slate-950 shadow transition hover:bg-amber-400"
        >
          New scenario
        </Link>
      </header>

      {isLoading ? (
        <div className={`${cardStyles} animate-pulse`}>
          <div className="h-4 w-36 rounded bg-slate-800" />
          <div className="mt-4 h-3 w-full rounded bg-slate-800" />
          <div className="mt-2 h-3 w-2/3 rounded bg-slate-800" />
        </div>
      ) : null}

      {isError ? (
        <div className={`${cardStyles} border-rose-500/50 bg-rose-950/40 text-sm text-rose-200`} role="alert">
          {error?.message ?? 'Failed to load scenarios'}
        </div>
      ) : null}

      {!isLoading && !isError && scenarios.length === 0 ? (
        <div className={`${cardStyles} text-sm text-slate-300`}>
          No scenarios found. Create a new scenario to start building workload assets.
        </div>
      ) : null}

      <ul className="grid gap-4 md:grid-cols-2">
        {scenarios.map((scenario) => (
          <li key={scenario.id} className={cardStyles}>
            <div className="flex items-start justify-between gap-3">
              <div>
                <h2 className="text-lg font-semibold text-slate-100">{scenario.name}</h2>
                <p className="mt-1 text-xs uppercase tracking-[0.3em] text-slate-400">{scenario.id}</p>
              </div>
              <Link
                to={`edit/${encodeURIComponent(scenario.id)}`}
                className="rounded border border-amber-400/50 px-3 py-1 text-xs font-semibold text-amber-200 transition hover:bg-amber-400/10"
              >
                Open
              </Link>
            </div>
            {scenario.description ? (
              <p className="mt-3 text-sm text-slate-300">
                {scenario.description.length > 180
                  ? `${scenario.description.slice(0, 177)}…`
                  : scenario.description}
              </p>
            ) : null}
          </li>
        ))}
      </ul>
    </div>
  )
}

export default Component
