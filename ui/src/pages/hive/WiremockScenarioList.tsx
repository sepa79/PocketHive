import type { WiremockScenarioSummary } from '../../lib/wiremockClient'

interface Props {
  scenarios: WiremockScenarioSummary[]
  error?: boolean
}

export default function WiremockScenarioList({ scenarios, error }: Props) {
  return (
    <div className="overflow-hidden rounded border border-white/10">
      <div className="border-b border-white/10 bg-white/10 px-3 py-2 text-sm font-medium text-white/80">
        Scenario states
      </div>
      {error ? (
        <div className="px-3 py-4 text-xs text-red-400">Unable to load scenarios.</div>
      ) : scenarios.length === 0 ? (
        <div className="px-3 py-4 text-xs text-white/60">No scenarios reported.</div>
      ) : (
        <ul className="divide-y divide-white/10 text-xs">
          {scenarios.map((scenario) => (
            <li key={scenario.id ?? scenario.name} className="px-3 py-2">
              <div className="flex items-center justify-between gap-4 text-white/80">
                <span className="font-medium">{scenario.name}</span>
                <span className="rounded bg-white/10 px-2 py-1 text-[0.7rem] uppercase tracking-wide text-white/70">
                  {scenario.state}
                </span>
              </div>
              {scenario.completed ? (
                <div className="mt-1 text-[0.65rem] uppercase tracking-wide text-emerald-300">
                  Completed
                </div>
              ) : null}
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
