import type { ReactNode } from 'react'

import type { AssetBase } from './assets'

interface AssetListProps<T extends AssetBase> {
  title: string
  assets: T[]
  createLabel: string
  emptyLabel: string
  onCreate: () => void
  onEdit: (asset: T) => void
  onDelete: (asset: T) => void
  renderMetadata?: (asset: T) => ReactNode
}

export const AssetList = <T extends AssetBase>({
  title,
  assets,
  createLabel,
  emptyLabel,
  onCreate,
  onEdit,
  onDelete,
  renderMetadata,
}: AssetListProps<T>) => (
  <div className="flex h-full flex-col gap-4">
    <header className="flex items-center justify-between">
      <div>
        <h2 className="text-xl font-semibold text-slate-100">{title}</h2>
        <p className="text-sm text-slate-400">{assets.length} saved</p>
      </div>
      <button
        type="button"
        onClick={onCreate}
        className="rounded-md bg-amber-500 px-3 py-2 text-sm font-semibold text-slate-950 transition hover:bg-amber-400"
      >
        {createLabel}
      </button>
    </header>

    {assets.length === 0 ? (
      <p className="rounded-md border border-dashed border-slate-700 bg-slate-900/40 p-6 text-sm text-slate-400">
        {emptyLabel}
      </p>
    ) : (
      <ul className="flex flex-col gap-3">
        {assets.map((asset) => (
          <li
            key={asset.id}
            className="flex flex-col gap-3 rounded-md border border-slate-800 bg-slate-900/60 p-4 text-sm text-slate-200"
          >
            <div className="flex flex-wrap items-start justify-between gap-2">
              <div>
                <p className="text-base font-semibold text-slate-50">{asset.name}</p>
                <p className="font-mono text-xs uppercase tracking-widest text-amber-300">{asset.id}</p>
              </div>
              <div className="flex items-center gap-2">
                <button
                  type="button"
                  className="rounded border border-slate-600 px-3 py-1 text-xs font-semibold text-slate-200 transition hover:bg-slate-800"
                  onClick={() => onEdit(asset)}
                >
                  Edit
                </button>
                <button
                  type="button"
                  className="rounded border border-red-500/60 px-3 py-1 text-xs font-semibold text-red-300 transition hover:bg-red-500/20"
                  onClick={() => onDelete(asset)}
                >
                  Delete
                </button>
              </div>
            </div>
            {asset.description ? <p className="text-slate-400">{asset.description}</p> : null}
            {renderMetadata ? <div className="text-xs text-slate-300">{renderMetadata(asset)}</div> : null}
          </li>
        ))}
      </ul>
    )}
  </div>
)

export default AssetList
