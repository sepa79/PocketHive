import type { FC, ReactNode } from 'react'

interface AssetFormProps {
  title: string
  description?: ReactNode
  submitLabel?: string
  onSubmit: () => void
  onCancel: () => void
  isSubmitDisabled?: boolean
  children: ReactNode
}

export const AssetForm: FC<AssetFormProps> = ({
  title,
  description,
  submitLabel = 'Save',
  onSubmit,
  onCancel,
  isSubmitDisabled,
  children,
}) => (
  <form
    aria-label={title}
    className="flex w-full flex-col gap-6"
    onSubmit={(event) => {
      event.preventDefault()
      onSubmit()
    }}
  >
    <header className="space-y-2">
      <h2 className="text-2xl font-semibold text-slate-100">{title}</h2>
      {description ? <p className="text-sm text-slate-300">{description}</p> : null}
    </header>

    <div className="flex flex-col gap-4">{children}</div>

    <footer className="flex justify-end gap-3">
      <button
        type="button"
        onClick={onCancel}
        className="rounded-md border border-slate-600 px-4 py-2 text-sm font-medium text-slate-200 transition hover:bg-slate-800"
      >
        Cancel
      </button>
      <button
        type="submit"
        disabled={isSubmitDisabled}
        className="rounded-md bg-amber-500 px-4 py-2 text-sm font-semibold text-slate-950 transition hover:bg-amber-400 disabled:cursor-not-allowed disabled:opacity-60"
      >
        {submitLabel}
      </button>
    </footer>
  </form>
)

export default AssetForm
