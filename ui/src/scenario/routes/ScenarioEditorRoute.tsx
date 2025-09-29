import { useEffect, useState } from 'react'
import { useParams } from 'react-router-dom'

import { useScenario, type ScenarioDocument } from '../api/scenarioManager'
import ScenarioMetadataForm from '../ScenarioMetadataForm'
import ScenarioTopBar from '../ScenarioTopBar'
import ScenarioWorkspace from '../ScenarioWorkspace'
import { useAssetStore } from '../assets/assetStore'
import type { ScenarioMetadata } from '../types'

export function Component() {
  const { scenarioId } = useParams<{ scenarioId: string }>()
  const isEditing = Boolean(scenarioId)
  const hydrate = useAssetStore((state) => state.hydrate)
  const [metadata, setMetadata] = useState<ScenarioMetadata>({
    id: scenarioId ?? '',
    name: '',
    description: '',
  })

  const { data, isLoading, isError, error } = useScenario(scenarioId, {
    enabled: isEditing,
  })

  useEffect(() => {
    if (!scenarioId) {
      setMetadata({ id: '', name: '', description: '' })
    }
  }, [scenarioId])

  useEffect(() => {
    if (data) {
      hydrate({
        sutAssets: data.sutAssets,
        datasetAssets: data.datasetAssets,
        swarmTemplates: data.swarmTemplates,
      })
      setMetadata({
        id: data.id,
        name: data.name,
        description: data.description ?? '',
      })
    }
  }, [data, hydrate])

  const updateMetadata = (updates: Partial<ScenarioMetadata>) => {
    setMetadata((prev) => ({
      ...prev,
      ...updates,
    }))
  }

  const handleSaved = (scenario: ScenarioDocument) => {
    setMetadata({
      id: scenario.id,
      name: scenario.name,
      description: scenario.description ?? '',
    })
  }

  let body: JSX.Element
  if (isEditing && isLoading) {
    body = (
      <div className="flex items-center justify-center p-12 text-sm text-slate-300">
        Loading scenario…
      </div>
    )
  } else if (isEditing && isError) {
    body = (
      <div className="p-12">
        <div className="mx-auto max-w-4xl rounded border border-rose-500/50 bg-rose-950/40 p-6 text-sm text-rose-100" role="alert">
          {error?.message ?? 'Failed to load scenario'}
        </div>
      </div>
    )
  } else {
    body = <ScenarioWorkspace metadataPanel={<ScenarioMetadataForm draft={metadata} onChange={updateMetadata} />} />
  }

  return (
    <div className="flex min-h-screen flex-col">
      <ScenarioTopBar
        draft={metadata}
        allowIdEdit={!isEditing}
        onChange={updateMetadata}
        onSaved={handleSaved}
      />
      <div className="flex-1">{body}</div>
    </div>
  )
}

export default Component
