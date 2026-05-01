import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { ConfirmModal } from '../components/ConfirmModal'
import { MonacoEditorHost } from '../components/MonacoEditorHost'
import { ScenarioWorkspaceTree } from '../components/scenarios/ScenarioWorkspaceTree'
import { useAuth } from '../lib/authContext'
import { monacoLanguageForBundleFile } from '../lib/bundleEditor'
import {
  type BundleFilePayload,
  type BundleTemplateEntry,
  type BundleTreeNode,
  deleteBundle,
  downloadBundle,
  listBundleWorkspaces,
  readBundleFile,
  readBundleTree,
  uploadScenarioBundle,
} from '../lib/scenariosApi'

function folderLabel(summary: BundleTemplateEntry): string {
  return summary.folderPath && summary.folderPath.trim().length > 0 ? summary.folderPath.trim() : 'root'
}

function bundleLabel(entry: BundleTemplateEntry): string {
  return entry.id ?? entry.bundlePath
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
  const [selectedFile, setSelectedFile] = useState<BundleFilePayload | null>(null)
  const [selectedFileLoading, setSelectedFileLoading] = useState(false)
  const [selectedFileError, setSelectedFileError] = useState<string | null>(null)

  const [busy, setBusy] = useState(false)
  const [deleteTarget, setDeleteTarget] = useState<BundleTemplateEntry | null>(null)
  const uploadInputRef = useRef<HTMLInputElement | null>(null)

  const selected = useMemo(
    () => (selectedKey ? items.find((entry) => entry.bundleKey === selectedKey) ?? null : null),
    [items, selectedKey],
  )

  const canManageSelected = selected ? auth.canManageBundle(selected.bundlePath, selected.folderPath) : false
  const visibleItems = useMemo(
    () => items.filter((entry) => auth.canViewBundle(entry.bundlePath, entry.folderPath)),
    [auth, items],
  )

  useEffect(() => {
    let cancelled = false
    setBundleTreeNodes([])
    setBundleTreeError(null)
    setSelectedFilePath(null)
    setSelectedFile(null)
    setSelectedFileError(null)

    if (!selected) {
      setBundleTreeLoading(false)
      return
    }

    setBundleTreeLoading(true)
    readBundleTree(selected.bundleKey)
      .then((tree) => {
        if (cancelled) return
        setBundleTreeNodes(tree.nodes)
        const preferred = tree.nodes.find((node) => node.nodeType === 'file' && node.path === 'scenario.yaml')
          ?? tree.nodes.find((node) => node.nodeType === 'file' && node.editorKind !== 'unsupported')
          ?? tree.nodes.find((node) => node.nodeType === 'file')
        setSelectedFilePath(preferred?.path ?? null)
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
  }, [selected])

  useEffect(() => {
    let cancelled = false
    setSelectedFile(null)
    setSelectedFileError(null)

    if (!selected || !selectedFilePath) {
      setSelectedFileLoading(false)
      return
    }

    setSelectedFileLoading(true)
    readBundleFile(selected.bundleKey, selectedFilePath)
      .then((file) => {
        if (!cancelled) setSelectedFile(file)
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
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to load scenarios')
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

      <div className="scenarioWorkspaceGrid" style={{ marginTop: 12 }}>
        <div className="card scenarioWorkspaceSidebar">
          <div className="row between">
            <div className="h2">Workspace</div>
            <div className="row" style={{ gap: 8 }}>
              {bundleTreeLoading ? <span className="pill pillInfo">FILES</span> : null}
              <div className={loading ? 'pill pillInfo' : 'pill pillOk'}>{loading ? 'LOADING' : `${visibleItems.length}`}</div>
              <button type="button" className="actionButton actionButtonGhost" onClick={() => void reload()} disabled={busy || loading}>
                Refresh
              </button>
            </div>
          </div>

          {bundleTreeError ? <div className="warningText" style={{ marginTop: 10 }}>{bundleTreeError}</div> : null}

          <div className="scenarioWorkspaceTree">
            <ScenarioWorkspaceTree
              bundles={visibleItems}
              selectedBundleKey={selectedKey}
              selectedFilePath={selectedFilePath}
              bundleFiles={bundleTreeNodes}
              onSelectBundle={(bundleKey) => setSelectedKey(bundleKey)}
              onSelectFile={setSelectedFilePath}
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

              <div className="scenarioWorkspaceActions">
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
                        value={selectedFile.content}
                        language={monacoLanguageForBundleFile(selectedFile.editorKind)}
                        theme="vs-dark"
                        options={{
                          readOnly: true,
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
