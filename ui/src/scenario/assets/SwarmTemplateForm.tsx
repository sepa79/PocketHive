import { useEffect, useMemo, useState } from 'react'
import type { ChangeEvent } from 'react'

import AssetForm from './AssetForm'
import type { DatasetAsset, SwarmTemplateAsset, SutAsset } from './assets'

interface SwarmTemplateFormProps {
  initialValue?: SwarmTemplateAsset
  sutOptions: SutAsset[]
  datasetOptions: DatasetAsset[]
  onCancel: () => void
  onSubmit: (asset: SwarmTemplateAsset) => void
}

type SwarmTemplateFields = {
  id: string
  name: string
  description: string
  sutId: string
  datasetId: string
  swarmSize: number
}

const sanitize = (value: string) => value.trim()

const buildErrors = (
  fields: SwarmTemplateFields,
  sutOptions: SutAsset[],
  datasetOptions: DatasetAsset[],
) => {
  const next: Partial<Record<keyof SwarmTemplateFields, string>> = {}
  if (!sanitize(fields.id)) {
    next.id = 'Provide a unique identifier.'
  }
  if (!sanitize(fields.name)) {
    next.name = 'Provide a friendly name.'
  }
  if (!sutOptions.some((option) => option.id === fields.sutId)) {
    next.sutId = 'Select a registered system under test.'
  }
  if (!datasetOptions.some((option) => option.id === fields.datasetId)) {
    next.datasetId = 'Select an available dataset.'
  }
  if (!Number.isFinite(fields.swarmSize) || fields.swarmSize < 1) {
    next.swarmSize = 'Specify a swarm size of at least 1.'
  }
  return next
}

export const SwarmTemplateForm = ({
  initialValue,
  sutOptions,
  datasetOptions,
  onCancel,
  onSubmit,
}: SwarmTemplateFormProps) => {
  const [fields, setFields] = useState<SwarmTemplateFields>({
    id: initialValue?.id ?? '',
    name: initialValue?.name ?? '',
    description: initialValue?.description ?? '',
    sutId: initialValue?.sutId ?? sutOptions[0]?.id ?? '',
    datasetId: initialValue?.datasetId ?? datasetOptions[0]?.id ?? '',
    swarmSize: initialValue?.swarmSize ?? 1,
  })

  useEffect(() => {
    setFields((current) => {
      let next = current
      if (sutOptions.length > 0 && !sutOptions.some((sut) => sut.id === current.sutId)) {
        next = { ...next, sutId: sutOptions[0]!.id }
      }
      if (datasetOptions.length > 0 && !datasetOptions.some((dataset) => dataset.id === current.datasetId)) {
        next = { ...next, datasetId: datasetOptions[0]!.id }
      }
      return next
    })
  }, [sutOptions, datasetOptions])

  const errors = useMemo(() => buildErrors(fields, sutOptions, datasetOptions), [
    fields,
    sutOptions,
    datasetOptions,
  ])

  const updateField = <K extends keyof SwarmTemplateFields>(key: K, value: SwarmTemplateFields[K]) => {
    setFields((current) => ({
      ...current,
      [key]: value,
    }))
  }

  const handleSubmit = () => {
    if (Object.keys(errors).length > 0) {
      return
    }

    onSubmit({
      id: sanitize(fields.id),
      name: sanitize(fields.name),
      description: sanitize(fields.description) || undefined,
      sutId: fields.sutId,
      datasetId: fields.datasetId,
      swarmSize: Math.max(1, Math.floor(fields.swarmSize)),
    })
  }

  return (
    <AssetForm
      title={initialValue ? 'Edit swarm template' : 'Create swarm template'}
      description="Templates combine a system under test with datasets and execution scale."
      onCancel={onCancel}
      onSubmit={handleSubmit}
      submitLabel={initialValue ? 'Save changes' : 'Create template'}
      isSubmitDisabled={Object.keys(errors).length > 0}
    >
      <label className="flex flex-col gap-1 text-sm">
        <span className="font-semibold text-slate-200">Identifier</span>
        <input
          type="text"
          value={fields.id}
          onChange={(event: ChangeEvent<HTMLInputElement>) => updateField('id', event.target.value)}
          aria-invalid={Boolean(errors.id)}
          aria-describedby="template-id-error"
          className="rounded border border-slate-700 bg-slate-950 px-3 py-2 text-sm text-slate-100 focus:border-amber-400 focus:outline-none"
          disabled={Boolean(initialValue)}
        />
        {errors.id ? (
          <span id="template-id-error" role="alert" className="text-xs text-red-300">
            {errors.id}
          </span>
        ) : null}
      </label>

      <label className="flex flex-col gap-1 text-sm">
        <span className="font-semibold text-slate-200">Name</span>
        <input
          type="text"
          value={fields.name}
          onChange={(event: ChangeEvent<HTMLInputElement>) => updateField('name', event.target.value)}
          aria-invalid={Boolean(errors.name)}
          aria-describedby="template-name-error"
          className="rounded border border-slate-700 bg-slate-950 px-3 py-2 text-sm text-slate-100 focus:border-amber-400 focus:outline-none"
        />
        {errors.name ? (
          <span id="template-name-error" role="alert" className="text-xs text-red-300">
            {errors.name}
          </span>
        ) : null}
      </label>

      <label className="flex flex-col gap-1 text-sm">
        <span className="font-semibold text-slate-200">System under test</span>
        <select
          value={fields.sutId}
          onChange={(event: ChangeEvent<HTMLSelectElement>) => updateField('sutId', event.target.value)}
          aria-invalid={Boolean(errors.sutId)}
          aria-describedby="template-sut-error"
          className="rounded border border-slate-700 bg-slate-950 px-3 py-2 text-sm text-slate-100 focus:border-amber-400 focus:outline-none"
        >
          <option value="" disabled>
            Select a system under test
          </option>
          {sutOptions.map((sut) => (
            <option key={sut.id} value={sut.id}>
              {sut.name}
            </option>
          ))}
        </select>
        {errors.sutId ? (
          <span id="template-sut-error" role="alert" className="text-xs text-red-300">
            {errors.sutId}
          </span>
        ) : null}
      </label>

      <label className="flex flex-col gap-1 text-sm">
        <span className="font-semibold text-slate-200">Dataset</span>
        <select
          value={fields.datasetId}
          onChange={(event: ChangeEvent<HTMLSelectElement>) => updateField('datasetId', event.target.value)}
          aria-invalid={Boolean(errors.datasetId)}
          aria-describedby="template-dataset-error"
          className="rounded border border-slate-700 bg-slate-950 px-3 py-2 text-sm text-slate-100 focus:border-amber-400 focus:outline-none"
        >
          <option value="" disabled>
            Select a dataset
          </option>
          {datasetOptions.map((dataset) => (
            <option key={dataset.id} value={dataset.id}>
              {dataset.name}
            </option>
          ))}
        </select>
        {errors.datasetId ? (
          <span id="template-dataset-error" role="alert" className="text-xs text-red-300">
            {errors.datasetId}
          </span>
        ) : null}
      </label>

      <label className="flex flex-col gap-1 text-sm">
        <span className="font-semibold text-slate-200">Swarm size</span>
        <input
          type="number"
          min={1}
          value={fields.swarmSize}
          onChange={(event: ChangeEvent<HTMLInputElement>) =>
            updateField('swarmSize', Number.parseInt(event.target.value, 10) || 0)
          }
          aria-invalid={Boolean(errors.swarmSize)}
          aria-describedby="template-swarm-error"
          className="rounded border border-slate-700 bg-slate-950 px-3 py-2 text-sm text-slate-100 focus:border-amber-400 focus:outline-none"
        />
        {errors.swarmSize ? (
          <span id="template-swarm-error" role="alert" className="text-xs text-red-300">
            {errors.swarmSize}
          </span>
        ) : null}
      </label>

      <label className="flex flex-col gap-1 text-sm">
        <span className="font-semibold text-slate-200">Description</span>
        <textarea
          value={fields.description}
          onChange={(event: ChangeEvent<HTMLTextAreaElement>) => updateField('description', event.target.value)}
          className="min-h-[96px] rounded border border-slate-700 bg-slate-950 px-3 py-2 text-sm text-slate-100 focus:border-amber-400 focus:outline-none"
        />
      </label>
    </AssetForm>
  )
}

export default SwarmTemplateForm
