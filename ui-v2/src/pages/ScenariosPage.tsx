import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { ConfirmModal } from '../components/ConfirmModal'
import {
  createScenarioFolder,
  deleteScenarioBundle,
  deleteScenarioFolder,
  downloadScenarioBundle,
  listBundleFailures,
  listScenarioFolders,
  listScenarios,
  moveScenarioToFolder,
  type BundleLoadFailure,
  type ScenarioSummary,
  uploadScenarioBundle,
} from '../lib/scenariosApi'
import { BundleFailuresBanner } from '../components/scenarios/BundleFailuresBanner'

type FolderFilter = { kind: 'all' } | { kind: 'root' } | { kind: 'folder'; path: string }
type ScenarioFolderNode = {
  name: string
  path: string
  children: ScenarioFolderNode[]
  scenarios: ScenarioSummary[]
}

function folderLabel(summary: ScenarioSummary): string {
  return summary.folderPath && summary.folderPath.trim().length > 0 ? summary.folderPath.trim() : 'root'
}

function buildScenarioFolderTree(items: ScenarioSummary[]): { folders: ScenarioFolderNode[]; rootScenarios: ScenarioSummary[] } {
  const rootScenarios: ScenarioSummary[] = []
  type MutableNode = { name: string; path: string; children: Map<string, MutableNode>; scenarios: ScenarioSummary[] }
  type RootNode = { children: Map<string, MutableNode>; scenarios: ScenarioSummary[] }
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
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [folders, setFolders] = useState<string[]>([])
  const [items, setItems] = useState<ScenarioSummary[]>([])
  const [failures, setFailures] = useState<BundleLoadFailure[]>([])
  const [filter, setFilter] = useState<FolderFilter>({ kind: 'all' })
  const [selectedId, setSelectedId] = useState<string | null>(null)

  const [newFolderPath, setNewFolderPath] = useState('')
  const [busy, setBusy] = useState(false)
  const [deleteTarget, setDeleteTarget] = useState<ScenarioSummary | null>(null)
  const uploadInputRef = useRef<HTMLInputElement | null>(null)

  const selected = useMemo(
    () => (selectedId ? items.find((entry) => entry.id === selectedId) ?? null : null),
    [items, selectedId],
  )

  const [movePath, setMovePath] = useState('')
  useEffect(() => {
    const current = selected?.folderPath ? selected.folderPath.trim() : ''
    setMovePath(current)
  }, [selected?.id, selected?.folderPath])

  const reload = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const [list, folderList, failureList] = await Promise.all([
        listScenarios({ includeDefunct: true }),
        listScenarioFolders(),
        listBundleFailures(),
      ])
      setItems(list)
      setFolders(folderList)
      setFailures(failureList)
      setSelectedId((current) => {
        if (list.length === 0) return null
        if (current && list.some((entry) => entry.id === current)) return current
        return list[0].id
      })
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to load scenarios')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    void reload()
  }, [reload])

  const visibleItems = useMemo(() => {
    if (filter.kind === 'all') return items
    if (filter.kind === 'root') return items.filter((entry) => !entry.folderPath || entry.folderPath.trim().length === 0)
    const target = filter.path.trim()
    return items.filter((entry) => (entry.folderPath ?? '').trim() === target)
  }, [filter, items])

  const tree = useMemo(() => buildScenarioFolderTree(visibleItems), [visibleItems])

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
      await moveScenarioToFolder(selected.id, target.length > 0 ? target : null)
      await reload()
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Move failed')
    } finally {
      setBusy(false)
    }
  }, [movePath, reload, selected])

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
        const created = await uploadScenarioBundle(file)
        await reload()
        if (created?.id) {
          setSelectedId(created.id)
        }
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
      const downloaded = await downloadScenarioBundle(selected.id)
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
      await deleteScenarioBundle(deleteTarget.id)
      setDeleteTarget(null)
      await reload()
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Delete failed')
    } finally {
      setBusy(false)
    }
  }, [deleteTarget, reload])

  const renderScenarioButton = useCallback(
    (entry: ScenarioSummary) => {
      const active = entry.id === selectedId
      return (
        <button
          key={entry.id}
          type="button"
          className={active ? 'swarmCard swarmCardSelected' : 'swarmCard'}
          onClick={() => setSelectedId(entry.id)}
          style={{ textAlign: 'left', opacity: entry.defunct ? 0.75 : 1 }}
        >
          <div className="row between">
            <div className="h2">{entry.name}</div>
            <div className="row" style={{ gap: 6 }}>
              {entry.defunct && <div className="pill pillBad">DEFUNCT</div>}
              <div className="pill pillInfo">{folderLabel(entry)}</div>
            </div>
          </div>
          <div className="muted" style={{ marginTop: 6 }}>
            {entry.id}
          </div>
        </button>
      )
    },
    [selectedId],
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

  return (
    <div className="page">
      <ConfirmModal
        open={deleteTarget !== null}
        title="Delete bundle"
        message={deleteTarget ? `Delete scenario bundle '${deleteTarget.id}'? This removes the bundle from Scenario Manager.` : ''}
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
          <button type="button" className="actionButton" onClick={triggerUpload} disabled={busy}>
            Upload bundle
          </button>
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

      <BundleFailuresBanner failures={failures} />

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
                  {folders.map((path) => (
                    <option key={path} value={path}>
                      {path}
                    </option>
                  ))}
                </select>
              </label>

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
                  <button type="button" className="actionButton actionButtonDanger" onClick={() => void handleDeleteFolder()} disabled={busy || filter.kind !== 'folder'}>
                    Delete (empty only)
                  </button>
                </div>
              </label>
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
                  <div className="v">{selected.id}</div>
                </div>
                <div className="kv">
                  <div className="k">Folder</div>
                  <div className="v">{folderLabel(selected)}</div>
                </div>
              </div>

              {selected.defunct && (
                <div
                  className="card"
                  style={{
                    borderColor: 'rgba(255, 95, 95, 0.35)',
                    background: 'rgba(255, 95, 95, 0.08)',
                    marginTop: 12,
                  }}
                >
                  <div className="row" style={{ gap: 8, marginBottom: 8 }}>
                    <span className="pill pillBad">DEFUNCT</span>
                    <span className="h2" style={{ fontSize: 13 }}>This bundle cannot be used to create a swarm</span>
                  </div>
                  <div className="muted">
                    {selected.defunctReason ?? 'Reason unknown — check server logs or reload.'}
                  </div>
                </div>
              )}

              <div className="formGrid" style={{ marginTop: 14 }}>
                <label className="field">
                  <span className="fieldLabel">Move to folder</span>
                  <select
                    className="textInput"
                    value={movePath.trim().length === 0 ? '__root__' : movePath}
                    onChange={(event) => setMovePath(event.target.value === '__root__' ? '' : event.target.value)}
                    disabled={busy}
                  >
                    <option value="__root__">root</option>
                    {folders.map((path) => (
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
                      disabled={busy || movePath.trim() === (selected.folderPath ? selected.folderPath.trim() : '')}
                    >
                      Move
                    </button>
                    <button type="button" className="actionButton actionButtonGhost" onClick={() => void handleDownloadSelected()} disabled={busy}>
                      Download bundle
                    </button>
                    <button type="button" className="actionButton actionButtonDanger" onClick={() => setDeleteTarget(selected)} disabled={busy}>
                      Delete bundle
                    </button>
                  </div>
                  <div className="muted" style={{ marginTop: 6 }}>
                    Folder delete requires the folder to be empty.
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
