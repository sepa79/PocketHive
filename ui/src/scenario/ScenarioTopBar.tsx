import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useUIStore } from '@ph/shell'

import { useAssetStore } from './assets/assetStore'
import { useUpsertScenario, type ScenarioDocument } from './api/scenarioManager'
import type { ScenarioMetadata } from './types'

interface ScenarioTopBarProps {
  draft: ScenarioMetadata
  allowIdEdit: boolean
  onChange: (updates: Partial<ScenarioMetadata>) => void
  onSaved?: (scenario: ScenarioDocument) => void
}

const labelStyles = 'text-xs uppercase tracking-[0.35em] text-amber-300'
const inputStyles =
  'w-full rounded border border-slate-700 bg-slate-900/60 px-3 py-2 text-sm text-slate-100 placeholder:text-slate-500 focus:border-amber-400 focus:outline-none'

const ScenarioTopBar = ({ draft, allowIdEdit, onChange, onSaved }: ScenarioTopBarProps) => {
  const navigate = useNavigate()
  const setToast = useUIStore((state) => state.setToast)
  const sutAssets = useAssetStore((state) => state.sutAssets)
  const datasetAssets = useAssetStore((state) => state.datasetAssets)
  const swarmTemplates = useAssetStore((state) => state.swarmTemplates)
  const hydrate = useAssetStore((state) => state.hydrate)
  const { mutateAsync, isPending } = useUpsertScenario()
  const [error, setError] = useState<string | null>(null)

  const handleSave = async () => {
    const id = draft.id?.trim()
    const name = draft.name.trim()
    const description = draft.description.trim()

    if (!id) {
      const message = 'Scenario identifier is required'
      setError(message)
      setToast(message)
      return
    }

    if (!/^[a-zA-Z0-9-_]+$/.test(id)) {
      const message = 'Scenario identifier may only include letters, numbers, hyphen, or underscore'
      setError(message)
      setToast(message)
      return
    }

    if (!name) {
      const message = 'Scenario name is required'
      setError(message)
      setToast(message)
      return
    }

    setError(null)
    setToast('Saving scenario…')

    try {
      const saved = await mutateAsync({
        id: allowIdEdit ? undefined : id,
        requestedId: allowIdEdit ? id : undefined,
        name,
        description,
        sutAssets,
        datasetAssets,
        swarmTemplates,
      })
      hydrate({
        sutAssets: saved.sutAssets,
        datasetAssets: saved.datasetAssets,
        swarmTemplates: saved.swarmTemplates,
      })
      onChange({ id: saved.id, name: saved.name, description: saved.description ?? '' })
      onSaved?.(saved)
      setToast('Scenario saved')
      navigate(`/scenario/edit/${saved.id}`, { replace: saved.id === draft.id })
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Failed to save scenario'
      setError(message)
      setToast(message)
    }
  }

  return (
    <header className="border-b border-slate-800/80 bg-slate-950/80 backdrop-blur px-8 py-6">
      <div className="mx-auto flex w-full max-w-6xl flex-col gap-4 lg:flex-row lg:items-end lg:justify-between">
        <div className="grid flex-1 gap-4 sm:grid-cols-2">
          <label className="flex flex-col gap-2">
            <span className={labelStyles}>Scenario ID</span>
            <input
              value={draft.id ?? ''}
              onChange={(event) => onChange({ id: event.target.value })}
              className={inputStyles}
              placeholder="scenario-id"
              disabled={!allowIdEdit}
              autoComplete="off"
            />
          </label>
          <label className="flex flex-col gap-2">
            <span className={labelStyles}>Scenario Name</span>
            <input
              value={draft.name}
              onChange={(event) => onChange({ name: event.target.value })}
              className={inputStyles}
              placeholder="Production benchmark"
            />
          </label>
        </div>
        <div className="flex flex-col items-stretch gap-2 text-sm text-slate-300 sm:flex-row sm:items-center">
          {error ? <span className="text-rose-300" role="alert">{error}</span> : null}
          <button
            type="button"
            className="rounded bg-amber-500 px-5 py-2 text-sm font-semibold text-slate-950 shadow transition hover:bg-amber-400 disabled:cursor-not-allowed disabled:bg-slate-700 disabled:text-slate-400"
            onClick={handleSave}
            disabled={isPending}
          >
            {isPending ? 'Saving…' : 'Save Scenario'}
          </button>
        </div>
      </div>
    </header>
  )
}

export default ScenarioTopBar
