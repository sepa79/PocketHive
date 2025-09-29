import type { ScenarioMetadata } from './types'

interface ScenarioMetadataFormProps {
  draft: ScenarioMetadata
  onChange: (updates: Partial<ScenarioMetadata>) => void
}

export default function ScenarioMetadataForm({ draft, onChange }: ScenarioMetadataFormProps) {
  return (
    <section className="rounded-xl border border-slate-800 bg-slate-900/60 p-6">
      <header className="mb-4">
        <p className="text-xs uppercase tracking-[0.35em] text-amber-400">Scenario overview</p>
        <h2 className="text-xl font-semibold text-slate-100">Metadata</h2>
      </header>
      <div className="grid gap-4 md:grid-cols-2">
        <label className="flex flex-col gap-2 md:col-span-2">
          <span className="text-sm text-slate-300">Summary</span>
          <textarea
            value={draft.description}
            onChange={(event) => onChange({ description: event.target.value })}
            className="min-h-[120px] rounded border border-slate-700 bg-slate-950/60 px-3 py-2 text-sm text-slate-100 placeholder:text-slate-500 focus:border-amber-400 focus:outline-none"
            placeholder="Describe how this scenario should be executed, dependencies, and any notable constraints."
          />
        </label>
        <div className="rounded border border-dashed border-slate-700/70 bg-slate-900/40 p-4 text-xs text-slate-400 md:col-span-2">
          Provide context for operators launching this scenario. Include service dependencies, dataset rationale, and any manual
          preparation needed before execution.
        </div>
      </div>
    </section>
  )
}
