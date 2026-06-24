import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { ConfirmModal } from '../components/ConfirmModal'
import { Icon } from '../components/Icon'
import { MonacoEditorHost } from '../components/MonacoEditorHost'
import { ScenarioWorkspaceTree } from '../components/scenarios/ScenarioWorkspaceTree'
import { useAuth } from '../lib/authContext'
import { monacoLanguageForBundleFile } from '../lib/bundleEditor'
import {
  type BundleValidationResult,
  type BundleFilePayload,
  type BundleTemplateEntry,
  type BundleTreeNode,
  createBundleFile,
  createBundleFolder,
  deleteBundle,
  deleteBundleEntry,
  downloadBundle,
  listBundleWorkspaces,
  readBundleFile,
  readBundleTree,
  reloadScenarioManager,
  renameBundleEntry,
  uploadScenarioBundle,
  validateExistingScenarioBundle,
  writeBundleFile,
} from '../lib/scenariosApi'

function folderLabel(summary: BundleTemplateEntry): string {
  return summary.folderPath && summary.folderPath.trim().length > 0 ? summary.folderPath.trim() : 'root'
}

function bundleLabel(entry: BundleTemplateEntry): string {
  return entry.id ?? entry.bundlePath
}

function parentPath(path: string | null): string {
  if (!path) return ''
  const index = path.lastIndexOf('/')
  return index > 0 ? path.slice(0, index) : ''
}

function basename(path: string): string {
  const index = path.lastIndexOf('/')
  return index >= 0 ? path.slice(index + 1) : path
}

function joinPath(prefix: string, leaf: string): string {
  const cleanLeaf = leaf.trim().replace(/^\/+/, '')
  return prefix ? `${prefix}/${cleanLeaf}` : cleanLeaf
}

type BundleValidationPanelState = {
  scope: 'all' | 'selected'
  checkedAt: string
  results: BundleValidationResult[]
}

export function ScenariosPage() {
  const auth = useAuth()
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [items, setItems] = useState<BundleTemplateEntry[]>([])
  const [selectedKey, setSelectedKey] = useState<string | null>(null)
  const [bundleTreeNodes, setBundleTreeNodes] = useState<BundleTreeNode[]>([])
  const [bundleTreeLoading, setBundleTreeLoading] = useState(false)
  const [bundleTreeError, setBundleTreeError] = useState<string | null>(null)
  const [selectedFilePath, setSelectedFilePath] = useState<string | null>(null)
  const [selectedDirectoryPath, setSelectedDirectoryPath] = useState<string | null>(null)
  const [selectedFile, setSelectedFile] = useState<BundleFilePayload | null>(null)
  const [fileDraft, setFileDraft] = useState('')
  const [selectedFileLoading, setSelectedFileLoading] = useState(false)
  const [selectedFileError, setSelectedFileError] = useState<string | null>(null)

  const [busy, setBusy] = useState(false)
  const [validationBusy, setValidationBusy] = useState(false)
  const [validationState, setValidationState] = useState<BundleValidationPanelState | null>(null)
  const [validationError, setValidationError] = useState<string | null>(null)
  const [deleteTarget, setDeleteTarget] = useState<BundleTemplateEntry | null>(null)
  const uploadInputRef = useRef<HTMLInputElement | null>(null)

  const selected = useMemo(
    () => (selectedKey ? items.find((entry) => entry.bundleKey === selectedKey) ?? null : null),
    [items, selectedKey],
  )
  const selectedBundleKey = selected?.bundleKey ?? null

  const canManageSelected = selected ? auth.canManageBundle(selected.bundlePath, selected.folderPath) : false
  const visibleItems = useMemo(
    () => items.filter((entry) => auth.canViewBundle(entry.bundlePath, entry.folderPath)),
    [auth, items],
  )
  const validationTotals = useMemo(() => {
    const results = validationState?.results ?? []
    return {
      bundles: results.length,
      errors: results.reduce((sum, result) => sum + result.summary.errors, 0),
      warnings: results.reduce((sum, result) => sum + result.summary.warnings, 0),
      findings: results.flatMap((result) =>
        result.findings.map((finding) => ({
          result,
          finding,
        })),
      ),
    }
  }, [validationState])

  useEffect(() => {
    let cancelled = false
    setBundleTreeNodes([])
    setBundleTreeError(null)
    setSelectedFilePath(null)
    setSelectedDirectoryPath(null)
    setSelectedFile(null)
    setFileDraft('')
    setSelectedFileError(null)

    if (!selectedBundleKey) {
      setBundleTreeLoading(false)
      return
    }

    setBundleTreeLoading(true)
    readBundleTree(selectedBundleKey)
      .then((tree) => {
        if (cancelled) return
        setBundleTreeNodes(tree.nodes)
        const preferred = tree.nodes.find((node) => node.nodeType === 'file' && node.path === 'scenario.yaml')
          ?? tree.nodes.find((node) => node.nodeType === 'file' && node.editorKind !== 'unsupported')
          ?? tree.nodes.find((node) => node.nodeType === 'file')
        setSelectedFilePath(preferred?.path ?? null)
        setSelectedDirectoryPath(null)
      })
      .catch((err: unknown) => {
        if (!cancelled) setBundleTreeError(err instanceof Error ? err.message : 'Failed to load bundle files')
      })
      .finally(() => {
        if (!cancelled) setBundleTreeLoading(false)
      })

    return () => {
      cancelled = true
    }
  }, [selectedBundleKey])

  useEffect(() => {
    let cancelled = false
    setSelectedFile(null)
    setFileDraft('')
    setSelectedFileError(null)

    if (!selected || !selectedFilePath) {
      setSelectedFileLoading(false)
      return
    }

    setSelectedFileLoading(true)
    readBundleFile(selected.bundleKey, selectedFilePath)
      .then((file) => {
        if (!cancelled) {
          setSelectedFile(file)
          setFileDraft(file.content ?? '')
        }
      })
      .catch((err: unknown) => {
        if (!cancelled) setSelectedFileError(err instanceof Error ? err.message : 'Failed to load bundle file')
      })
      .finally(() => {
        if (!cancelled) setSelectedFileLoading(false)
      })

    return () => {
      cancelled = true
    }
  }, [selected, selectedFilePath])

  const refreshCurrentBundleTree = useCallback(async () => {
    if (!selected) return
    const tree = await readBundleTree(selected.bundleKey)
    setBundleTreeNodes(tree.nodes)
  }, [selected])

  const reload = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const list = await listBundleWorkspaces()
      setItems(list)
      setSelectedKey((current) => {
        if (list.length === 0) return null
        if (current && list.some((entry) => entry.bundleKey === current)) return current
        return list[0].bundleKey
      })
      return list
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to load scenarios')
      return []
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    void reload()
  }, [reload])

  const triggerUpload = useCallback(() => {
    uploadInputRef.current?.click()
  }, [])

  const selectedEntryLabel = selectedFilePath ?? selectedDirectoryPath ?? '(bundle root)'
  const selectedEntryParent = selectedDirectoryPath ?? parentPath(selectedFilePath)
  const fileDirty = selectedFile !== null && selectedFile.content !== null && fileDraft !== selectedFile.content
  const confirmDiscardChanges = useCallback(() => {
    if (!fileDirty) return true
    return window.confirm('Discard unsaved file changes?')
  }, [fileDirty])

  const handleUploadChange = useCallback(
    async (event: React.ChangeEvent<HTMLInputElement>) => {
      const file = event.target.files?.[0] ?? null
      event.target.value = ''
      if (!file) return
      if (!confirmDiscardChanges()) return
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
    [confirmDiscardChanges, reload],
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

  const handleReload = useCallback(async () => {
    if (!confirmDiscardChanges()) return
    await reload()
  }, [confirmDiscardChanges, reload])

  const runBundleValidation = useCallback(
    async (scope: 'all' | 'selected') => {
      if (!auth.canManagePocketHive) return
      if (!confirmDiscardChanges()) return
      setValidationBusy(true)
      setValidationError(null)
      try {
        await reloadScenarioManager()
        const refreshed = await reload()
        const targetBundleKey = selected?.bundleKey ?? selectedKey
        const targets = scope === 'selected'
          ? refreshed.filter((entry) => entry.bundleKey === targetBundleKey)
          : refreshed
        if (scope === 'selected' && targets.length === 0) {
          throw new Error('Selected scenario has no bundle validation target')
        }
        if (scope === 'all' && targets.length === 0) {
          throw new Error('No scenario bundle validation targets found')
        }
        const results: BundleValidationResult[] = []
        for (const target of targets) {
          results.push(await validateExistingScenarioBundle(target.bundleKey))
        }
        setValidationState({
          scope,
          checkedAt: new Date().toISOString(),
          results,
        })
      } catch (e) {
        setValidationError(e instanceof Error ? e.message : 'Bundle validation failed')
      } finally {
        setValidationBusy(false)
      }
    },
    [auth.canManagePocketHive, confirmDiscardChanges, reload, selected, selectedKey],
  )

  const handleSelectBundle = useCallback((bundleKey: string) => {
    if (bundleKey !== selectedKey && !confirmDiscardChanges()) return
    setSelectedKey(bundleKey)
  }, [confirmDiscardChanges, selectedKey])

  const handleSelectFile = useCallback((path: string) => {
    if (path !== selectedFilePath && !confirmDiscardChanges()) return
    setSelectedDirectoryPath(null)
    setSelectedFilePath(path)
  }, [confirmDiscardChanges, selectedFilePath])

  const handleSelectDirectory = useCallback((path: string) => {
    if (path !== selectedDirectoryPath && !confirmDiscardChanges()) return
    setSelectedFilePath(null)
    setSelectedDirectoryPath(path)
  }, [confirmDiscardChanges, selectedDirectoryPath])

  const handleCreateFile = useCallback(async () => {
    if (!selected) return
    if (!confirmDiscardChanges()) return
    const path = window.prompt('New file path', joinPath(selectedEntryParent, 'new-file.yaml'))
    if (!path) return
    setBusy(true)
    setError(null)
    try {
      const created = await createBundleFile(selected.bundleKey, path, '')
      await refreshCurrentBundleTree()
      await reload()
      setSelectedDirectoryPath(null)
      setSelectedFilePath(created.path)
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Create file failed')
    } finally {
      setBusy(false)
    }
  }, [confirmDiscardChanges, refreshCurrentBundleTree, reload, selected, selectedEntryParent])

  const handleCreateFolder = useCallback(async () => {
    if (!selected) return
    if (!confirmDiscardChanges()) return
    const path = window.prompt('New folder path', joinPath(selectedEntryParent, 'new-folder'))
    if (!path) return
    setBusy(true)
    setError(null)
    try {
      await createBundleFolder(selected.bundleKey, path)
      await refreshCurrentBundleTree()
      await reload()
      setSelectedFilePath(null)
      setSelectedDirectoryPath(path.trim())
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Create folder failed')
    } finally {
      setBusy(false)
    }
  }, [confirmDiscardChanges, refreshCurrentBundleTree, reload, selected, selectedEntryParent])

  const handleRenameEntry = useCallback(async () => {
    if (!selected) return
    const currentPath = selectedFilePath ?? selectedDirectoryPath
    if (!currentPath) return
    if (!confirmDiscardChanges()) return
    const nextName = window.prompt('Rename', basename(currentPath))
    if (!nextName) return
    const trimmedName = nextName.trim()
    if (!trimmedName || trimmedName.includes('/') || trimmedName.includes('\\')) {
      setError('Rename expects a name, not a path')
      return
    }
    if (trimmedName === basename(currentPath)) return
    setBusy(true)
    setError(null)
    try {
      await renameBundleEntry(selected.bundleKey, currentPath, trimmedName)
      const targetPath = joinPath(parentPath(currentPath), trimmedName)
      await refreshCurrentBundleTree()
      await reload()
      if (selectedFilePath) {
        setSelectedFilePath(targetPath)
        setSelectedDirectoryPath(null)
      } else {
        setSelectedFilePath(null)
        setSelectedDirectoryPath(targetPath)
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Rename failed')
    } finally {
      setBusy(false)
    }
  }, [confirmDiscardChanges, refreshCurrentBundleTree, reload, selected, selectedDirectoryPath, selectedFilePath])

  const handleDeleteEntry = useCallback(async () => {
    if (!selected) return
    const currentPath = selectedFilePath ?? selectedDirectoryPath
    if (!currentPath) return
    if (!confirmDiscardChanges()) return
    if (!window.confirm(`Delete '${currentPath}' from bundle '${bundleLabel(selected)}'?`)) return
    setBusy(true)
    setError(null)
    try {
      await deleteBundleEntry(selected.bundleKey, currentPath)
      await refreshCurrentBundleTree()
      await reload()
      setSelectedFilePath(null)
      setSelectedDirectoryPath(parentPath(currentPath) || null)
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Delete entry failed')
    } finally {
      setBusy(false)
    }
  }, [confirmDiscardChanges, refreshCurrentBundleTree, reload, selected, selectedDirectoryPath, selectedFilePath])

  const handleSaveFile = useCallback(async () => {
    if (!selected || !selectedFile || selectedFile.content === null) return
    setBusy(true)
    setError(null)
    try {
      const result = await writeBundleFile(selected.bundleKey, selectedFile.path, fileDraft, selectedFile.revision)
      await refreshCurrentBundleTree()
      await reload()
      const saved = await readBundleFile(selected.bundleKey, selectedFile.path)
      setSelectedFile({ ...saved, revision: result.revision })
      setFileDraft(saved.content ?? fileDraft)
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Save file failed')
    } finally {
      setBusy(false)
    }
  }, [fileDraft, refreshCurrentBundleTree, reload, selected, selectedFile])

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
            <>
              <button
                type="button"
                className="actionButton actionButtonGhost"
                onClick={() => void runBundleValidation('all')}
                disabled={busy || validationBusy}
              >
                Reload & validate all
              </button>
              <button type="button" className="actionButton" onClick={triggerUpload} disabled={busy || validationBusy}>
                Upload bundle
              </button>
            </>
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

      <div className="scenarioWorkspaceGrid" style={{ marginTop: 12 }}>
        <div className="card scenarioWorkspaceSidebar">
          <div className="row between">
            <div className="h2">Workspace</div>
            <div className="row" style={{ gap: 8 }}>
              {bundleTreeLoading ? <span className="pill pillInfo">FILES</span> : null}
              <div className={loading ? 'pill pillInfo' : 'pill pillOk'}>{loading ? 'LOADING' : `${visibleItems.length}`}</div>
              <button type="button" className="actionButton actionButtonGhost" onClick={() => void handleReload()} disabled={busy || loading}>
                Refresh
              </button>
            </div>
          </div>

          {bundleTreeError ? <div className="warningText" style={{ marginTop: 10 }}>{bundleTreeError}</div> : null}

          <div className="scenarioWorkspaceTreeActions">
            <button type="button" className="actionButton actionButtonGhost" onClick={() => void handleCreateFile()} disabled={busy || !canManageSelected}>
              New file
            </button>
            <button type="button" className="actionButton actionButtonGhost" onClick={() => void handleCreateFolder()} disabled={busy || !canManageSelected}>
              New folder
            </button>
          </div>
          <div className="scenarioWorkspaceSelectionRow">
            <div className="muted scenarioWorkspaceSelectionLabel">{selectedEntryLabel}</div>
            <div className="scenarioWorkspaceSelectionActions" aria-label="Selected entry actions">
              <button
                type="button"
                className="iconButton scenarioWorkspaceIconButton"
                onClick={() => void handleRenameEntry()}
                disabled={busy || !canManageSelected || (!selectedFilePath && !selectedDirectoryPath)}
                title="Rename selected file or folder"
                aria-label="Rename selected file or folder"
              >
                <Icon name="edit" />
              </button>
              <button
                type="button"
                className="iconButton scenarioWorkspaceIconButton scenarioWorkspaceIconButtonDanger"
                onClick={() => void handleDeleteEntry()}
                disabled={busy || !canManageSelected || (!selectedFilePath && !selectedDirectoryPath)}
                title="Delete selected file or folder"
                aria-label="Delete selected file or folder"
              >
                <Icon name="trash" />
              </button>
            </div>
          </div>

          <div className="scenarioWorkspaceTree">
            <ScenarioWorkspaceTree
              bundles={visibleItems}
              selectedBundleKey={selectedKey}
              selectedFilePath={selectedFilePath}
              selectedDirectoryPath={selectedDirectoryPath}
              bundleFiles={bundleTreeNodes}
              onSelectBundle={handleSelectBundle}
              onSelectFile={handleSelectFile}
              onSelectDirectory={handleSelectDirectory}
              height={680}
            />
          </div>
        </div>

        <div className="card scenarioWorkspaceDetails">
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

              <div className="scenarioValidationPanel">
                <div className="row between">
                  <div className="h2" style={{ fontSize: 13 }}>Bundle validation</div>
                  <div className="row" style={{ gap: 8 }}>
                    {validationBusy ? <span className="pill pillInfo">VALIDATING</span> : null}
                    {validationState ? (
                      <span className={validationTotals.errors > 0 ? 'pill pillBad' : 'pill pillOk'}>
                        {validationTotals.errors} errors
                      </span>
                    ) : (
                      <span className="pill pillWarn">NOT RUN</span>
                    )}
                  </div>
                </div>

                {validationError ? (
                  <div className="warningText scenarioValidationMessage">{validationError}</div>
                ) : validationState ? (
                  <>
                    <div className="scenarioValidationSummary">
                      <span>{validationState.scope === 'all' ? 'All bundles' : 'Selected bundle'}</span>
                      <span>{validationTotals.bundles} bundle{validationTotals.bundles === 1 ? '' : 's'}</span>
                      <span>{validationTotals.warnings} warnings</span>
                      <span>{new Date(validationState.checkedAt).toLocaleTimeString()}</span>
                    </div>
                    {validationTotals.findings.length > 0 ? (
                      <div className="scenarioValidationFindings">
                        {validationTotals.findings.slice(0, 10).map(({ result, finding }, index) => (
                          <div
                            key={`${result.bundleKey ?? result.scenarioId ?? 'bundle'}-${finding.code}-${finding.path}-${index}`}
                            className="scenarioValidationFinding"
                          >
                            <div className="scenarioValidationFindingHeader">
                              <span className={finding.severity === 'error' ? 'warningText' : 'scenarioValidationWarning'}>
                                {finding.severity}
                              </span>
                              <span className="mono">{finding.code}</span>
                              <span className="muted">{result.bundleKey ?? result.scenarioId ?? 'bundle'}</span>
                              <span className="mono muted">{finding.path}</span>
                            </div>
                            <div>{finding.message}</div>
                            <div className="muted">{finding.fix}</div>
                          </div>
                        ))}
                      </div>
                    ) : (
                      <div className="scenarioValidationMessage">No findings.</div>
                    )}
                  </>
                ) : (
                  <div className="muted scenarioValidationMessage">No validation run in this session.</div>
                )}
              </div>

              <div className="scenarioWorkspaceActions">
                {auth.canManagePocketHive ? (
                  <button
                    type="button"
                    className="actionButton actionButtonGhost"
                    onClick={() => void runBundleValidation('selected')}
                    disabled={busy || validationBusy || !selected}
                  >
                    Reload & validate this
                  </button>
                ) : null}
                <button type="button" className="actionButton" onClick={() => void handleSaveFile()} disabled={busy || !canManageSelected || !fileDirty}>
                  Save file
                </button>
                <button type="button" className="actionButton actionButtonGhost" onClick={() => void handleDownloadSelected()} disabled={busy}>
                  Download bundle
                </button>
                <button
                  type="button"
                  className="actionButton actionButtonDanger"
                  onClick={() => {
                    if (confirmDiscardChanges()) setDeleteTarget(selected)
                  }}
                  disabled={busy || !canManageSelected}
                >
                  Delete bundle
                </button>
              </div>

              <div className="scenarioWorkspaceEditor">
                <div className="scenarioFilePreview">
                  <div className="scenarioFilePreviewHeader">
                    <div className="scenarioTreeTitle">{selectedFilePath ?? 'No file selected'}</div>
                    {selectedFile ? <div className="pill pillInfo">{selectedFile.editorKind}</div> : null}
                  </div>
                  {selectedFileLoading ? (
                    <div className="scenarioFileUnsupported">Loading file…</div>
                  ) : selectedFileError ? (
                    <div className="scenarioFileUnsupported warningText">{selectedFileError}</div>
                  ) : selectedFile && selectedFile.content !== null ? (
                    <div className="scenarioFilePreviewBody">
                      <MonacoEditorHost
                        className="monacoSurface"
                        value={fileDraft}
                        language={monacoLanguageForBundleFile(selectedFile.editorKind)}
                        theme="vs-dark"
                        onChange={(value) => setFileDraft(value ?? '')}
                        options={{
                          readOnly: !canManageSelected,
                          automaticLayout: true,
                          minimap: { enabled: false },
                          scrollBeyondLastLine: false,
                          wordWrap: 'on',
                          fontSize: 12,
                        }}
                      />
                    </div>
                  ) : selectedFile ? (
                    <div className="scenarioFileUnsupported">This file is not previewable in Scenarios.</div>
                  ) : (
                    <div className="scenarioFileUnsupported">Select a file to preview it.</div>
                  )}
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
