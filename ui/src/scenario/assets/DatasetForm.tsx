import { useMemo, useState } from 'react'

import AssetForm from './AssetForm'
import type { DatasetAsset } from './assets'

interface DatasetFormProps {
  initialValue?: DatasetAsset
  onCancel: () => void
  onSubmit: (asset: DatasetAsset) => void
}

type DatasetFields = {
  id: string
  name: string
  description: string
  uri: string
  format: string
}

const sanitize = (value: string) => value.trim()

const buildErrors = (fields: DatasetFields) => {
  const next: Partial<Record<keyof DatasetFields, string>> = {}
  if (!sanitize(fields.id)) {
    next.id = 'Provide a unique identifier.'
  }
  if (!sanitize(fields.name)) {
    next.name = 'Provide a friendly name.'
  }
  if (!sanitize(fields.uri)) {
    next.uri = 'Provide a dataset source URI.'
  }
  if (!sanitize(fields.format)) {
    next.format = 'Select or describe a format.'
  }
  return next
}

export const DatasetForm = ({ initialValue, onCancel, onSubmit }: DatasetFormProps) => {
  const [fields, setFields] = useState<DatasetFields>({
    id: initialValue?.id ?? '',
    name: initialValue?.name ?? '',
    description: initialValue?.description ?? '',
    uri: initialValue?.uri ?? '',
    format: initialValue?.format ?? '',
  })

  const errors = useMemo(() => buildErrors(fields), [fields])

  const updateField = <K extends keyof DatasetFields>(key: K, value: DatasetFields[K]) => {
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
      uri: sanitize(fields.uri),
      format: sanitize(fields.format),
    })
  }

  return (
    <AssetForm
      title={initialValue ? 'Edit dataset' : 'Create dataset'}
      description="Register datasets that scenarios can mount at runtime."
      onCancel={onCancel}
      onSubmit={handleSubmit}
      submitLabel={initialValue ? 'Save changes' : 'Create dataset'}
      isSubmitDisabled={Object.keys(errors).length > 0}
    >
      <label className="flex flex-col gap-1 text-sm">
        <span className="font-semibold text-slate-200">Identifier</span>
        <input
          type="text"
          value={fields.id}
          onChange={(event) => updateField('id', event.target.value)}
          aria-invalid={Boolean(errors.id)}
          aria-describedby="dataset-id-error"
          className="rounded border border-slate-700 bg-slate-950 px-3 py-2 text-sm text-slate-100 focus:border-amber-400 focus:outline-none"
          disabled={Boolean(initialValue)}
        />
        {errors.id ? (
          <span id="dataset-id-error" role="alert" className="text-xs text-red-300">
            {errors.id}
          </span>
        ) : null}
      </label>

      <label className="flex flex-col gap-1 text-sm">
        <span className="font-semibold text-slate-200">Name</span>
        <input
          type="text"
          value={fields.name}
          onChange={(event) => updateField('name', event.target.value)}
          aria-invalid={Boolean(errors.name)}
          aria-describedby="dataset-name-error"
          className="rounded border border-slate-700 bg-slate-950 px-3 py-2 text-sm text-slate-100 focus:border-amber-400 focus:outline-none"
        />
        {errors.name ? (
          <span id="dataset-name-error" role="alert" className="text-xs text-red-300">
            {errors.name}
          </span>
        ) : null}
      </label>

      <label className="flex flex-col gap-1 text-sm">
        <span className="font-semibold text-slate-200">Source URI</span>
        <input
          type="text"
          value={fields.uri}
          onChange={(event) => updateField('uri', event.target.value)}
          aria-invalid={Boolean(errors.uri)}
          aria-describedby="dataset-uri-error"
          placeholder="s3://bucket/datasets/example.json"
          className="rounded border border-slate-700 bg-slate-950 px-3 py-2 text-sm text-slate-100 focus:border-amber-400 focus:outline-none"
        />
        {errors.uri ? (
          <span id="dataset-uri-error" role="alert" className="text-xs text-red-300">
            {errors.uri}
          </span>
        ) : null}
      </label>

      <label className="flex flex-col gap-1 text-sm">
        <span className="font-semibold text-slate-200">Format</span>
        <input
          type="text"
          value={fields.format}
          onChange={(event) => updateField('format', event.target.value)}
          aria-invalid={Boolean(errors.format)}
          aria-describedby="dataset-format-error"
          placeholder="json | csv | parquet"
          className="rounded border border-slate-700 bg-slate-950 px-3 py-2 text-sm text-slate-100 focus:border-amber-400 focus:outline-none"
        />
        {errors.format ? (
          <span id="dataset-format-error" role="alert" className="text-xs text-red-300">
            {errors.format}
          </span>
        ) : null}
      </label>

      <label className="flex flex-col gap-1 text-sm">
        <span className="font-semibold text-slate-200">Description</span>
        <textarea
          value={fields.description}
          onChange={(event) => updateField('description', event.target.value)}
          className="min-h-[96px] rounded border border-slate-700 bg-slate-950 px-3 py-2 text-sm text-slate-100 focus:border-amber-400 focus:outline-none"
        />
      </label>
    </AssetForm>
  )
}

export default DatasetForm
