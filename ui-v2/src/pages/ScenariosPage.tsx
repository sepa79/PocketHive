import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  createScenarioFolder,
  deleteScenarioFolder,
  listScenarioFolders,
  listScenarios,
  moveScenarioToFolder,
  type ScenarioSummary,
} from '../lib/scenariosApi'

type FolderFilter = { kind: 'all' } | { kind: 'root' } | { kind: 'folder'; path: string }

function folderLabel(summary: ScenarioSummary): string {
  return summary.folderPath && summary.folderPath.trim().length > 0 ? summary.folderPath.trim() : 'root'
}

export function ScenariosPage() {
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [folders, setFolders] = useState<string[]>([])
  const [items, setItems] = useState<ScenarioSummary[]>([])
  const [filter, setFilter] = useState<FolderFilter>({ kind: 'all' })
  const [selectedId, setSelectedId] = useState<string | null>(null)

  const [newFolderPath, setNewFolderPath] = useState('')
  const [busy, setBusy] = useState(false)

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
      const [list, folderList] = await Promise.all([listScenarios({ includeDefunct: true }), listScenarioFolders()])
      setItems(list)
      setFolders(folderList)
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

  return (
    <div className="page">
      <div className="row between">
        <h1 className="h1">Scenarios</h1>
        <div className="muted">Bundles can live anywhere under `scenarios/bundles/**`.</div>
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
              {visibleItems.map((entry) => {
                const active = entry.id === selectedId
                return (
                  <button
                    key={entry.id}
                    type="button"
                    className={active ? 'swarmCard swarmCardSelected' : 'swarmCard'}
                    onClick={() => setSelectedId(entry.id)}
                    style={{ textAlign: 'left' }}
                  >
                    <div className="row between">
                      <div className="h2">{entry.name}</div>
                      <div className="pill pillInfo">{folderLabel(entry)}</div>
                    </div>
                    <div className="muted" style={{ marginTop: 6 }}>
                      {entry.id}
                    </div>
                  </button>
                )
              })}
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
                  <button
                    type="button"
                    className="actionButton"
                    onClick={() => void handleMoveSelected()}
                    disabled={busy || movePath.trim() === (selected.folderPath ? selected.folderPath.trim() : '')}
                  >
                    Move
                  </button>
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
