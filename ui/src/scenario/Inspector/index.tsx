import type { FC } from 'react'

import AssetList from '../assets/AssetList'
import type {
  AssetKind,
  DatasetAsset,
  SwarmTemplateAsset,
  SutAsset,
} from '../assets/assets'

interface InspectorProps {
  active: AssetKind
  sutAssets: SutAsset[]
  datasetAssets: DatasetAsset[]
  swarmTemplates: SwarmTemplateAsset[]
  onCreate: (kind: AssetKind) => void
  onEdit: (kind: AssetKind, asset: SutAsset | DatasetAsset | SwarmTemplateAsset) => void
  onDelete: (kind: AssetKind, asset: SutAsset | DatasetAsset | SwarmTemplateAsset) => void
}

const Inspector: FC<InspectorProps> = ({
  active,
  sutAssets,
  datasetAssets,
  swarmTemplates,
  onCreate,
  onEdit,
  onDelete,
}) => {
  if (active === 'sut') {
    return (
      <AssetList
        title="Systems under test"
        assets={sutAssets}
        createLabel="New system"
        emptyLabel="No systems registered yet. Add a system under test to reuse across scenarios."
        onCreate={() => onCreate('sut')}
        onEdit={(asset) => onEdit('sut', asset)}
        onDelete={(asset) => onDelete('sut', asset)}
        renderMetadata={(asset) => (
          <dl className="grid gap-1 md:grid-cols-2">
            <div>
              <dt className="text-slate-400">Entry point</dt>
              <dd className="font-mono text-[11px] text-amber-300">{asset.entrypoint}</dd>
            </div>
            <div>
              <dt className="text-slate-400">Version</dt>
              <dd className="font-mono text-[11px] text-amber-300">{asset.version}</dd>
            </div>
          </dl>
        )}
      />
    )
  }

  if (active === 'dataset') {
    return (
      <AssetList
        title="Datasets"
        assets={datasetAssets}
        createLabel="New dataset"
        emptyLabel="No datasets registered yet. Bring datasets into PocketHive before linking them to templates."
        onCreate={() => onCreate('dataset')}
        onEdit={(asset) => onEdit('dataset', asset)}
        onDelete={(asset) => onDelete('dataset', asset)}
        renderMetadata={(asset) => (
          <dl className="grid gap-1 md:grid-cols-2">
            <div>
              <dt className="text-slate-400">Source</dt>
              <dd className="font-mono text-[11px] text-amber-300">{asset.uri}</dd>
            </div>
            <div>
              <dt className="text-slate-400">Format</dt>
              <dd className="font-mono text-[11px] text-amber-300">{asset.format}</dd>
            </div>
          </dl>
        )}
      />
    )
  }

  return (
    <AssetList
      title="Swarm templates"
      assets={swarmTemplates}
      createLabel="New template"
      emptyLabel="No templates exist yet. Combine a system and dataset to describe a swarm."
      onCreate={() => onCreate('template')}
      onEdit={(asset) => onEdit('template', asset)}
      onDelete={(asset) => onDelete('template', asset)}
      renderMetadata={(asset) => {
        const sut = sutAssets.find((candidate) => candidate.id === asset.sutId)
        const dataset = datasetAssets.find((candidate) => candidate.id === asset.datasetId)
        return (
          <dl className="grid gap-1 md:grid-cols-3">
            <div>
              <dt className="text-slate-400">System</dt>
              <dd className="font-mono text-[11px] text-amber-300">{sut?.name ?? asset.sutId}</dd>
            </div>
            <div>
              <dt className="text-slate-400">Dataset</dt>
              <dd className="font-mono text-[11px] text-amber-300">{dataset?.name ?? asset.datasetId}</dd>
            </div>
            <div>
              <dt className="text-slate-400">Swarm size</dt>
              <dd className="font-mono text-[11px] text-amber-300">{asset.swarmSize}</dd>
            </div>
          </dl>
        )
      }}
    />
  )
}

export default Inspector
