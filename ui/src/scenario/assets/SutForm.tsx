import { useMemo, useState } from 'react'

import AssetForm from './AssetForm'
import type { SutAsset } from './assets'

interface SutFormProps {
  initialValue?: SutAsset
  onCancel: () => void
  onSubmit: (asset: SutAsset) => void
}

type SutFields = {
  id: string
  name: string
  description: string
  entrypoint: string
  version: string
}

const sanitize = (value: string) => value.trim()

const buildErrors = (fields: SutFields) => {
  const next: Partial<Record<keyof SutFields, string>> = {}
  if (!sanitize(fields.id)) {
    next.id = 'Provide a unique identifier.'
  }
  if (!sanitize(fields.name)) {
    next.name = 'Provide a friendly name.'
  }
  if (!sanitize(fields.entrypoint)) {
    next.entrypoint = 'Provide an executable entrypoint.'
  }
  if (!sanitize(fields.version)) {
    next.version = 'Specify a version tag.'
  }
  return next
}

export const SutForm = ({ initialValue, onCancel, onSubmit }: SutFormProps) => {
  const [fields, setFields] = useState<SutFields>({
    id: initialValue?.id ?? '',
    name: initialValue?.name ?? '',
    description: initialValue?.description ?? '',
    entrypoint: initialValue?.entrypoint ?? '',
    version: initialValue?.version ?? '',
  })

  const errors = useMemo(() => buildErrors(fields), [fields])

  const updateField = <K extends keyof SutFields>(key: K, value: SutFields[K]) => {
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
      entrypoint: sanitize(fields.entrypoint),
      version: sanitize(fields.version),
    })
  }

  return (
    <AssetForm
      title={initialValue ? 'Edit system under test' : 'Create system under test'}
      description="Define how PocketHive should launch and identify this system."
      onCancel={onCancel}
      onSubmit={handleSubmit}
      submitLabel={initialValue ? 'Save changes' : 'Create system'}
      isSubmitDisabled={Object.keys(errors).length > 0}
    >
      <label className="flex flex-col gap-1 text-sm">
        <span className="font-semibold text-slate-200">Identifier</span>
        <input
          type="text"
          value={fields.id}
          onChange={(event) => updateField('id', event.target.value)}
          aria-invalid={Boolean(errors.id)}
          aria-describedby="sut-id-error"
          className="rounded border border-slate-700 bg-slate-950 px-3 py-2 text-sm text-slate-100 focus:border-amber-400 focus:outline-none"
          disabled={Boolean(initialValue)}
        />
        {errors.id ? (
          <span id="sut-id-error" role="alert" className="text-xs text-red-300">
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
          aria-describedby="sut-name-error"
          className="rounded border border-slate-700 bg-slate-950 px-3 py-2 text-sm text-slate-100 focus:border-amber-400 focus:outline-none"
        />
        {errors.name ? (
          <span id="sut-name-error" role="alert" className="text-xs text-red-300">
            {errors.name}
          </span>
        ) : null}
      </label>

      <label className="flex flex-col gap-1 text-sm">
        <span className="font-semibold text-slate-200">Entry point</span>
        <input
          type="text"
          value={fields.entrypoint}
          onChange={(event) => updateField('entrypoint', event.target.value)}
          aria-invalid={Boolean(errors.entrypoint)}
          aria-describedby="sut-entrypoint-error"
          placeholder="docker.io/acme/sut:latest"
          className="rounded border border-slate-700 bg-slate-950 px-3 py-2 text-sm text-slate-100 focus:border-amber-400 focus:outline-none"
        />
        {errors.entrypoint ? (
          <span id="sut-entrypoint-error" role="alert" className="text-xs text-red-300">
            {errors.entrypoint}
          </span>
        ) : null}
      </label>

      <label className="flex flex-col gap-1 text-sm">
        <span className="font-semibold text-slate-200">Version</span>
        <input
          type="text"
          value={fields.version}
          onChange={(event) => updateField('version', event.target.value)}
          aria-invalid={Boolean(errors.version)}
          aria-describedby="sut-version-error"
          placeholder="v1.0.0"
          className="rounded border border-slate-700 bg-slate-950 px-3 py-2 text-sm text-slate-100 focus:border-amber-400 focus:outline-none"
        />
        {errors.version ? (
          <span id="sut-version-error" role="alert" className="text-xs text-red-300">
            {errors.version}
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

export default SutForm
