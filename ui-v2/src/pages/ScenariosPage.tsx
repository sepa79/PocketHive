import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { ConfirmModal } from '../components/ConfirmModal'
import { useAuth } from '../lib/authContext'
import {
  type BundleTemplateEntry,
  createScenarioFolder,
  deleteBundle,
  deleteScenarioFolder,
  downloadBundle,
  listBundleTemplates,
  listScenarioFolders,
  moveBundleToFolder,
  uploadScenarioBundle,
} from '../lib/scenariosApi'

type FolderFilter = { kind: 'all' } | { kind: 'root' } | { kind: 'folder'; path: string }
const QUARANTINE_FOLDER = 'quarantine'
type ScenarioFolderNode = {
  name: string
  path: string
  children: ScenarioFolderNode[]
  scenarios: BundleTemplateEntry[]
}

function folderLabel(summary: BundleTemplateEntry): string {
  return summary.folderPath && summary.folderPath.trim().length > 0 ? summary.folderPath.trim() : 'root'
}

function bundleLabel(entry: BundleTemplateEntry): string {
  return entry.id ?? entry.bundlePath
}

function isQuarantined(entry: BundleTemplateEntry): boolean {
  return entry.bundlePath === QUARANTINE_FOLDER || entry.bundlePath.startsWith(`${QUARANTINE_FOLDER}/`)
}

function buildScenarioFolderTree(items: BundleTemplateEntry[]): { folders: ScenarioFolderNode[]; rootScenarios: BundleTemplateEntry[] } {
  const rootScenarios: BundleTemplateEntry[] = []
  type MutableNode = { name: string; path: string; children: Map<string, MutableNode>; scenarios: BundleTemplateEntry[] }
  type RootNode = { children: Map<string, MutableNode>; scenarios: BundleTemplateEntry[] }
  const root: RootNode = { children: new Map(), scenarios: [] }

  const ensureNode = (parent: RootNode | MutableNode, name: string, path: string): MutableNode => {
    const existing = parent.children.get(name)
    if (existing) return existing
    const created: MutableNode = { name, path, children: new Map<string, MutableNode>(), scenarios: [] }
    parent.children.set(name, created)
    return created
  }

  for (const item of items) {
    const folderPath = item.folderPath?.trim() ?? ''
    if (!folderPath) {
      rootScenarios.push(item)
      continue
    }
    const segments = folderPath.split('/').map((segment) => segment.trim()).filter((segment) => segment.length > 0)
    if (segments.length === 0) {
      rootScenarios.push(item)
      continue
    }
    let current: RootNode | MutableNode = root
    let currentPath = ''
    for (const segment of segments) {
      currentPath = currentPath ? `${currentPath}/${segment}` : segment
      current = ensureNode(current, segment, currentPath)
    }
    current.scenarios.push(item)
  }

  const finalize = (node: MutableNode): ScenarioFolderNode => ({
    name: node.name,
    path: node.path,
    children: Array.from(node.children.values())
      .map(finalize)
      .sort((a, b) => a.name.localeCompare(b.name)),
    scenarios: [...node.scenarios].sort((a, b) => a.name.localeCompare(b.name)),
  })

  return {
    folders: Array.from(root.children.values())
      .map(finalize)
      .sort((a, b) => a.name.localeCompare(b.name)),
    rootScenarios: [...rootScenarios].sort((a, b) => a.name.localeCompare(b.name)),
  }
}

function countScenarios(node: ScenarioFolderNode): number {
  let count = node.scenarios.length
  for (const child of node.children) {
    count += countScenarios(child)
  }
  return count
}

export function ScenariosPage() {
  const auth = useAuth()
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [folders, setFolders] = useState<string[]>([])
  const [items, setItems] = useState<BundleTemplateEntry[]>([])
  const [filter, setFilter] = useState<FolderFilter>({ kind: 'all' })
  const [selectedKey, setSelectedKey] = useState<string | null>(null)

  const [newFolderPath, setNewFolderPath] = useState('')
  const [busy, setBusy] = useState(false)
  const [deleteTarget, setDeleteTarget] = useState<BundleTemplateEntry | null>(null)
  const uploadInputRef = useRef<HTMLInputElement | null>(null)

  const selected = useMemo(
    () => (selectedKey ? items.find((entry) => entry.bundleKey === selectedKey) ?? null : null),
    [items, selectedKey],
  )

  const canManageSelected = selected ? auth.canManageBundle(selected.bundlePath, selected.folderPath) : false

  const [movePath, setMovePath] = useState('')
  useEffect(() => {
    const current = selected?.folderPath ? selected.folderPath.trim() : ''
    setMovePath(current)
  }, [selected?.bundleKey, selected?.folderPath])

  const reload = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const [list, folderList] = await Promise.all([
        listBundleTemplates(),
        auth.canManagePocketHive ? listScenarioFolders() : Promise.resolve<string[]>([]),
      ])
      setItems(list)
      setFolders(folderList)
      setSelectedKey((current) => {
        if (list.length === 0) return null
        if (current && list.some((entry) => entry.bundleKey === current)) return current
        return list[0].bundleKey
      })
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to load scenarios')
    } finally {
      setLoading(false)
    }
  }, [auth.canManagePocketHive])

  useEffect(() => {
    void reload()
  }, [reload])

  const visibleItems = useMemo(() => {
    const readableItems = items.filter((entry) => auth.canViewBundle(entry.bundlePath, entry.folderPath))
    if (filter.kind === 'all') return readableItems
    if (filter.kind === 'root') return readableItems.filter((entry) => !entry.folderPath || entry.folderPath.trim().length === 0)
    const target = filter.path.trim()
    return readableItems.filter((entry) => (entry.folderPath ?? '').trim() === target)
  }, [auth, filter, items])

  const tree = useMemo(() => buildScenarioFolderTree(visibleItems), [visibleItems])

  const folderOptions = useMemo(() => {
    const paths = new Set<string>(folders)
    for (const entry of visibleItems) {
      const path = entry.folderPath?.trim() ?? ''
      if (path) {
        paths.add(path)
      }
    }
    return Array.from(paths).sort((left, right) => left.localeCompare(right))
  }, [folders, visibleItems])

  const openFolderPaths = useMemo(() => {
    if (filter.kind === 'folder') {
      return new Set<string>([filter.path.trim()])
    }
    if (filter.kind === 'root') {
      return new Set<string>()
    }
    if (!selected?.folderPath) {
      return new Set<string>()
    }
    const segments = selected.folderPath
      .split('/')
      .map((segment) => segment.trim())
      .filter((segment) => segment.length > 0)
    const result = new Set<string>()
    let currentPath = ''
    for (const segment of segments) {
      currentPath = currentPath ? `${currentPath}/${segment}` : segment
      result.add(currentPath)
    }
    return result
  }, [filter, selected?.folderPath])

  const handleCreateFolder = useCallback(async () => {
    const path = newFolderPath.trim()
    if (!path) return
    setBusy(true)
    try {
      await createScenarioFolder(path)
      setNewFolderPath('')
      await reload()
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Create folder failed')
    } finally {
      setBusy(false)
    }
  }, [newFolderPath, reload])

  const handleDeleteFolder = useCallback(async () => {
    if (filter.kind !== 'folder') return
    const path = filter.path.trim()
    if (!path) return
    setBusy(true)
    try {
      await deleteScenarioFolder(path)
      setFilter({ kind: 'all' })
      await reload()
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Delete folder failed')
    } finally {
      setBusy(false)
    }
  }, [filter, reload])

  const handleMoveSelected = useCallback(async () => {
    if (!selected) return
    const target = movePath.trim()
    const current = selected.folderPath ? selected.folderPath.trim() : ''
    if (target === current) return
    setBusy(true)
    try {
      await moveBundleToFolder(selected.bundleKey, target.length > 0 ? target : null)
      await reload()
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Move failed')
    } finally {
      setBusy(false)
    }
  }, [movePath, reload, selected])

  const handleMoveToQuarantine = useCallback(async () => {
    if (!selected || isQuarantined(selected)) return
    setBusy(true)
    setError(null)
    try {
      await moveBundleToFolder(selected.bundleKey, QUARANTINE_FOLDER)
      await reload()
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Move to quarantine failed')
    } finally {
      setBusy(false)
    }
  }, [reload, selected])

  const triggerUpload = useCallback(() => {
    uploadInputRef.current?.click()
  }, [])

  const handleUploadChange = useCallback(
    async (event: React.ChangeEvent<HTMLInputElement>) => {
      const file = event.target.files?.[0] ?? null
      event.target.value = ''
      if (!file) return
      setBusy(true)
      setError(null)
      try {
        await uploadScenarioBundle(file)
        await reload()
      } catch (e) {
        setError(e instanceof Error ? e.message : 'Upload failed')
      } finally {
        setBusy(false)
      }
    },
    [reload],
  )

  const handleDownloadSelected = useCallback(async () => {
    if (!selected) return
    setBusy(true)
    setError(null)
    try {
      const downloaded = await downloadBundle(selected.bundleKey)
      const url = URL.createObjectURL(downloaded.blob)
      const link = document.createElement('a')
      link.href = url
      link.download = downloaded.fileName
      document.body.appendChild(link)
      link.click()
      link.remove()
      window.setTimeout(() => URL.revokeObjectURL(url), 1000)
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Download failed')
    } finally {
      setBusy(false)
    }
  }, [selected])

  const handleConfirmDelete = useCallback(async () => {
    if (!deleteTarget) return
    setBusy(true)
    setError(null)
    try {
      await deleteBundle(deleteTarget.bundleKey)
      setDeleteTarget(null)
      await reload()
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Delete failed')
    } finally {
      setBusy(false)
    }
  }, [deleteTarget, reload])

  const renderScenarioButton = useCallback(
    (entry: BundleTemplateEntry) => {
      const active = entry.bundleKey === selectedKey
      return (
        <button
          key={entry.bundleKey}
          type="button"
          className={active ? 'swarmCard swarmCardSelected' : 'swarmCard'}
          onClick={() => setSelectedKey(entry.bundleKey)}
          style={{ textAlign: 'left', opacity: entry.defunct ? 0.7 : 1 }}
        >
          <div className="row between">
            <div className="h2">{entry.name}</div>
            <div className="row" style={{ gap: 6 }}>
              {entry.defunct ? <div className="pill pillBad">DEFUNCT</div> : null}
              <div className="pill pillInfo">{folderLabel(entry)}</div>
            </div>
          </div>
          <div className="muted" style={{ marginTop: 6 }}>
            {bundleLabel(entry)}
          </div>
        </button>
      )
    },
    [selectedKey],
  )

  const renderFolderNode = useCallback(
    (node: ScenarioFolderNode) => (
      <details key={node.path} open={filter.kind === 'all' ? openFolderPaths.has(node.path) : true}>
        <summary className="muted" style={{ cursor: 'pointer', padding: '6px 8px' }}>
          {node.name} <span className="muted">({countScenarios(node)})</span>
        </summary>
        <div style={{ marginLeft: 12 }}>
          {node.children.map((child) => renderFolderNode(child))}
          {node.scenarios.map((entry) => renderScenarioButton(entry))}
        </div>
      </details>
    ),
    [filter.kind, openFolderPaths, renderScenarioButton],
  )

  if (!auth.canAccessPocketHive) {
    return (
      <div className="page">
        <h1 className="h1">Scenarios</h1>
        <div className="card" style={{ marginTop: 12 }}>
          <div className="warningText">PocketHive access required.</div>
          <div className="muted" style={{ marginTop: 8 }}>
            This page requires a PocketHive VIEW, RUN, or ALL grant.
          </div>
        </div>
      </div>
    )
  }

  return (
    <div className="page">
      <ConfirmModal
        open={deleteTarget !== null}
        title="Delete bundle"
        message={deleteTarget ? `Delete scenario bundle '${bundleLabel(deleteTarget)}'? This removes the bundle from Scenario Manager.` : ''}
        confirmLabel="Delete"
        danger
        busy={busy}
        onConfirm={handleConfirmDelete}
        onClose={() => {
          if (!busy) setDeleteTarget(null)
        }}
      />

      <div className="row between">
        <h1 className="h1">Scenarios</h1>
        <div className="row" style={{ gap: 10 }}>
          <input
            ref={uploadInputRef}
            type="file"
            accept=".zip,application/zip"
            style={{ display: 'none' }}
            onChange={(event) => void handleUploadChange(event)}
          />
          {auth.canManagePocketHive ? (
            <button type="button" className="actionButton" onClick={triggerUpload} disabled={busy}>
              Upload bundle
            </button>
          ) : null}
          <div className="muted">Bundles can live anywhere under `scenarios/**`.</div>
        </div>
      </div>

      {error ? (
        <div className="card" style={{ marginTop: 12 }}>
          <div className="pill pillBad">ERROR</div>
          <div className="muted" style={{ marginTop: 8 }}>
            {error}
          </div>
        </div>
      ) : null}

      <div className="swarmViewGrid" style={{ marginTop: 12 }}>
        <div className="swarmViewCards">
          <div className="card">
            <div className="row between">
              <div className="h2">Folders</div>
              <button type="button" className="actionButton actionButtonGhost" onClick={() => void reload()} disabled={busy || loading}>
                Refresh
              </button>
            </div>

            <div className="formGrid" style={{ marginTop: 12 }}>
              <label className="field">
                <span className="fieldLabel">Filter</span>
                <select
                  className="textInput"
                  value={filter.kind === 'all' ? '__all__' : filter.kind === 'root' ? '__root__' : filter.path}
                  onChange={(event) => {
                    const value = event.target.value
                    if (value === '__all__') setFilter({ kind: 'all' })
                    else if (value === '__root__') setFilter({ kind: 'root' })
                    else setFilter({ kind: 'folder', path: value })
                  }}
                  disabled={busy}
                >
                  <option value="__all__">All folders</option>
                  <option value="__root__">Root</option>
                  {folderOptions.map((path) => (
                    <option key={path} value={path}>
                      {path}
                    </option>
                  ))}
                </select>
              </label>

              {auth.canManagePocketHive ? (
                <label className="field">
                  <span className="fieldLabel">New folder</span>
                  <input
                    className="textInput"
                    value={newFolderPath}
                    onChange={(event) => setNewFolderPath(event.target.value)}
                    placeholder="tcp/perf"
                    disabled={busy}
                  />
                  <div className="row" style={{ marginTop: 8 }}>
                    <button type="button" className="actionButton" onClick={() => void handleCreateFolder()} disabled={busy || newFolderPath.trim().length === 0}>
                      Add
                    </button>
                    <button
                      type="button"
                      className="actionButton actionButtonDanger"
                      onClick={() => void handleDeleteFolder()}
                      disabled={busy || filter.kind !== 'folder' || !auth.canManageFolder(filter.kind === 'folder' ? filter.path : null)}
                    >
                      Delete (empty only)
                    </button>
                  </div>
                </label>
              ) : (
                <div className="field">
                  <span className="fieldLabel">Write access</span>
                  <div className="muted">PocketHive ALL permission is required to create, move, upload, or delete bundles.</div>
                </div>
              )}
            </div>
          </div>

          <div className="card" style={{ marginTop: 12 }}>
            <div className="row between">
              <div className="h2">Scenarios</div>
              <div className={loading ? 'pill pillInfo' : 'pill pillOk'}>{loading ? 'LOADING' : `${visibleItems.length}`}</div>
            </div>

            <div className="swarmCardList" style={{ maxHeight: 'min(70vh, 820px)' }}>
              {tree.folders.map((folder) => renderFolderNode(folder))}
              {tree.rootScenarios.length > 0 ? (
                <details open={filter.kind !== 'folder'}>
                  <summary className="muted" style={{ cursor: 'pointer', padding: '6px 8px' }}>
                    (root) <span className="muted">({tree.rootScenarios.length})</span>
                  </summary>
                  <div style={{ marginLeft: 12 }}>{tree.rootScenarios.map((entry) => renderScenarioButton(entry))}</div>
                </details>
              ) : null}
              {!loading && visibleItems.length === 0 ? <div className="muted">No scenarios.</div> : null}
            </div>
          </div>
        </div>

        <div className="card">
          <div className="row between">
            <div className="h2">Details</div>
            {selected ? <div className="pill pillOk">SELECTED</div> : <div className="pill pillWarn">NONE</div>}
          </div>

          {selected ? (
            <>
              <div className="kvGrid" style={{ marginTop: 12 }}>
                <div className="kv">
                  <div className="k">ID</div>
                  <div className="v">{selected.id ?? '—'}</div>
                </div>
                <div className="kv">
                  <div className="k">Folder</div>
                  <div className="v">{folderLabel(selected)}</div>
                </div>
                <div className="kv">
                  <div className="k">Bundle</div>
                  <div className="v">{selected.bundlePath}</div>
                </div>
              </div>

              {selected.defunct ? (
                <div
                  className="card"
                  style={{
                    borderColor: 'rgba(255, 95, 95, 0.45)',
                    background: 'rgba(255, 95, 95, 0.08)',
                    marginTop: 12,
                  }}
                >
                  <div className="row" style={{ gap: 8, marginBottom: 8 }}>
                    <span className="pill pillBad">DEFUNCT</span>
                    <span className="h2" style={{ fontSize: 13 }}>This bundle cannot be used to create a swarm</span>
                  </div>
                  <div className="muted">{selected.defunctReason ?? 'Reason unavailable.'}</div>
                </div>
              ) : null}

              <div className="formGrid" style={{ marginTop: 14 }}>
                <label className="field">
                  <span className="fieldLabel">Move to folder</span>
                  <select
                    className="textInput"
                    value={movePath.trim().length === 0 ? '__root__' : movePath}
                    onChange={(event) => setMovePath(event.target.value === '__root__' ? '' : event.target.value)}
                    disabled={busy || !canManageSelected}
                  >
                    <option value="__root__">root</option>
                    {!folders.includes(QUARANTINE_FOLDER) ? (
                      <option value={QUARANTINE_FOLDER}>{QUARANTINE_FOLDER}</option>
                    ) : null}
                    {folderOptions.map((path) => (
                      <option key={path} value={path}>
                        {path}
                      </option>
                    ))}
                  </select>
                </label>
                <div className="field">
                  <span className="fieldLabel">Action</span>
                  <div className="row" style={{ gap: 10, flexWrap: 'wrap' }}>
                    <button
                      type="button"
                      className="actionButton"
                      onClick={() => void handleMoveSelected()}
                      disabled={
                        busy ||
                        !canManageSelected ||
                        selected.defunct ||
                        movePath.trim() === (selected.folderPath ? selected.folderPath.trim() : '')
                      }
                    >
                      Move
                    </button>
                    {selected.defunct && !isQuarantined(selected) ? (
                      <button
                        type="button"
                        className="actionButton"
                        onClick={() => void handleMoveToQuarantine()}
                        disabled={busy || !canManageSelected}
                      >
                        Move to quarantine
                      </button>
                    ) : null}
                    <button type="button" className="actionButton actionButtonGhost" onClick={() => void handleDownloadSelected()} disabled={busy}>
                      Download bundle
                    </button>
                    <button
                      type="button"
                      className="actionButton actionButtonDanger"
                      onClick={() => setDeleteTarget(selected)}
                      disabled={busy || !canManageSelected}
                    >
                      Delete bundle
                    </button>
                  </div>
                  <div className="muted" style={{ marginTop: 6 }}>
                    {selected.defunct
                      ? isQuarantined(selected)
                        ? 'Quarantined bundles stay visible for diagnosis. Download or remove them when no longer needed.'
                        : 'Defunct bundles can be quarantined, downloaded, or removed. Repair editing stays out of scope for broken ids.'
                      : 'Healthy bundles can be moved between folders, downloaded, or removed.'}
                  </div>
                </div>
              </div>
            </>
          ) : (
            <div className="muted" style={{ marginTop: 12 }}>
              Select a scenario on the left.
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
