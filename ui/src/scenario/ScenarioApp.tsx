import { useMemo, useState } from 'react'
import { useConfig, useUIStore } from '@ph/shell'

import LeftNav from './LeftNav'
import Inspector from './Inspector'
import DatasetForm from './assets/DatasetForm'
import SwarmTemplateForm from './assets/SwarmTemplateForm'
import SutForm from './assets/SutForm'
import type { AssetKind, DatasetAsset, SwarmTemplateAsset, SutAsset } from './assets/assets'
import { useAssetStore } from './assets/assetStore'

type ModalState =
  | { kind: 'sut'; mode: 'create' }
  | { kind: 'sut'; mode: 'edit'; asset: SutAsset }
  | { kind: 'dataset'; mode: 'create' }
  | { kind: 'dataset'; mode: 'edit'; asset: DatasetAsset }
  | { kind: 'template'; mode: 'create' }
  | { kind: 'template'; mode: 'edit'; asset: SwarmTemplateAsset }

const ScenarioApp = () => {
  const config = useConfig()
  const messageLimit = useUIStore((state) => state.messageLimit)
  const [activeSection, setActiveSection] = useState<AssetKind>('sut')
  const [modal, setModal] = useState<ModalState | null>(null)

  const {
    sutAssets,
    datasetAssets,
    swarmTemplates,
    upsertSut,
    upsertDataset,
    upsertSwarmTemplate,
    removeSut,
    removeDataset,
    removeSwarmTemplate,
  } = useAssetStore((state) => state)

  const sections = useMemo(
    () => [
      {
        id: 'sut' as const,
        label: 'Systems',
        description: 'Containers, binaries, or services PocketHive exercises.',
        count: sutAssets.length,
      },
      {
        id: 'dataset' as const,
        label: 'Datasets',
        description: 'Input corpora and fixtures for swarm members.',
        count: datasetAssets.length,
      },
      {
        id: 'template' as const,
        label: 'Swarm templates',
        description: 'Composed assets that scale a workload.',
        count: swarmTemplates.length,
      },
    ],
    [datasetAssets.length, sutAssets.length, swarmTemplates.length],
  )

  const closeModal = () => setModal(null)

  const handleCreate = (kind: AssetKind) => {
    if (kind === 'sut') {
      setModal({ kind: 'sut', mode: 'create' })
    } else if (kind === 'dataset') {
      setModal({ kind: 'dataset', mode: 'create' })
    } else {
      setModal({ kind: 'template', mode: 'create' })
    }
  }

  const handleEdit = (kind: AssetKind, asset: SutAsset | DatasetAsset | SwarmTemplateAsset) => {
    if (kind === 'sut') {
      setModal({ kind: 'sut', mode: 'edit', asset: asset as SutAsset })
    } else if (kind === 'dataset') {
      setModal({ kind: 'dataset', mode: 'edit', asset: asset as DatasetAsset })
    } else {
      setModal({ kind: 'template', mode: 'edit', asset: asset as SwarmTemplateAsset })
    }
  }

  const handleDelete = (kind: AssetKind, asset: SutAsset | DatasetAsset | SwarmTemplateAsset) => {
    if (kind === 'sut') {
      removeSut(asset.id)
    } else if (kind === 'dataset') {
      removeDataset(asset.id)
    } else {
      removeSwarmTemplate(asset.id)
    }
  }

  const renderModal = () => {
    if (!modal) {
      return null
    }

    const containerClass =
      'fixed inset-0 z-50 flex items-center justify-center bg-slate-950/80 backdrop-blur-sm p-6'
    const panelClass = 'w-full max-w-2xl rounded-xl border border-slate-700 bg-slate-900 p-6 shadow-xl'

    if (modal.kind === 'sut') {
      return (
        <div className={containerClass} role="presentation">
          <div className={panelClass} role="dialog" aria-modal="true">
            <SutForm
              initialValue={modal.mode === 'edit' ? modal.asset : undefined}
              onCancel={closeModal}
              onSubmit={(asset) => {
                upsertSut(asset)
                closeModal()
              }}
            />
          </div>
        </div>
      )
    }

    if (modal.kind === 'dataset') {
      return (
        <div className={containerClass} role="presentation">
          <div className={panelClass} role="dialog" aria-modal="true">
            <DatasetForm
              initialValue={modal.mode === 'edit' ? modal.asset : undefined}
              onCancel={closeModal}
              onSubmit={(asset) => {
                upsertDataset(asset)
                closeModal()
              }}
            />
          </div>
        </div>
      )
    }

    return (
      <div className={containerClass} role="presentation">
        <div className={panelClass} role="dialog" aria-modal="true">
          <SwarmTemplateForm
            initialValue={modal.mode === 'edit' ? modal.asset : undefined}
            sutOptions={sutAssets}
            datasetOptions={datasetAssets}
            onCancel={closeModal}
            onSubmit={(asset) => {
              upsertSwarmTemplate(asset)
              closeModal()
            }}
          />
        </div>
      </div>
    )
  }

  return (
    <div className="min-h-screen bg-gradient-to-br from-slate-950 via-slate-900 to-slate-950 p-8 text-slate-100">
      <div className="mx-auto flex w-full max-w-6xl gap-6">
        <LeftNav active={activeSection} sections={sections} onSelect={setActiveSection} />

        <main className="flex flex-1 flex-col gap-6">
          <section className="rounded-xl border border-slate-800 bg-slate-900/60 p-6">
            <header className="mb-4 flex flex-wrap items-center justify-between gap-4">
              <div>
                <p className="text-xs uppercase tracking-[0.35em] text-amber-400">Host configuration</p>
                <h2 className="text-xl font-semibold">Runtime context</h2>
              </div>
              <span
                data-testid="ui-message-limit"
                className="rounded bg-slate-800 px-3 py-1 text-xs text-slate-300"
              >
                Messages limited to {messageLimit}
              </span>
            </header>
            <dl className="grid gap-3 md:grid-cols-2">
              <div>
                <dt className="text-sm text-slate-300">RabbitMQ endpoint</dt>
                <dd data-testid="config-rabbitmq" className="font-mono text-xs text-amber-300">
                  {config.rabbitmq}
                </dd>
              </div>
              <div>
                <dt className="text-sm text-slate-300">Prometheus endpoint</dt>
                <dd data-testid="config-prometheus" className="font-mono text-xs text-amber-300">
                  {config.prometheus}
                </dd>
              </div>
            </dl>
          </section>

          <section className="flex-1 rounded-xl border border-slate-800 bg-slate-900/60 p-6">
            <Inspector
              active={activeSection}
              sutAssets={sutAssets}
              datasetAssets={datasetAssets}
              swarmTemplates={swarmTemplates}
              onCreate={handleCreate}
              onEdit={handleEdit}
              onDelete={handleDelete}
            />
          </section>
        </main>
      </div>

      {renderModal()}
    </div>
  )
}

export default ScenarioApp
