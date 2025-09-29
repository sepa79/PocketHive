import type { FC } from 'react'

import type { AssetKind } from './assets/assets'

interface SectionSummary {
  id: AssetKind
  label: string
  description: string
  count: number
}

interface LeftNavProps {
  active: AssetKind
  sections: SectionSummary[]
  onSelect: (id: AssetKind) => void
}

const LeftNav: FC<LeftNavProps> = ({ active, sections, onSelect }) => (
  <nav className="flex w-72 flex-col gap-4 rounded-xl border border-slate-800 bg-slate-950/70 p-4">
    <header className="space-y-1">
      <p className="text-xs uppercase tracking-[0.35em] text-amber-400">Scenario builder</p>
      <h1 className="text-lg font-semibold text-slate-50">Assets</h1>
      <p className="text-xs leading-relaxed text-slate-400">
        Prepare systems, datasets, and swarm templates before assembling a scenario.
      </p>
    </header>

    <ul className="flex flex-col gap-2">
      {sections.map((section) => {
        const isActive = section.id === active
        return (
          <li key={section.id}>
            <button
              type="button"
              onClick={() => onSelect(section.id)}
              className={`flex w-full flex-col gap-1 rounded-lg border px-3 py-2 text-left text-sm transition ${
                isActive
                  ? 'border-amber-400 bg-amber-400/10 text-amber-200'
                  : 'border-slate-800 bg-slate-900/70 text-slate-300 hover:border-amber-400/70 hover:text-amber-200'
              }`}
            >
              <span className="flex items-center justify-between">
                <span className="font-semibold">{section.label}</span>
                <span className="rounded bg-slate-800 px-2 py-0.5 text-xs font-mono">{section.count}</span>
              </span>
              <span className="text-xs text-slate-400">{section.description}</span>
            </button>
          </li>
        )
      })}
    </ul>
  </nav>
)

export default LeftNav
