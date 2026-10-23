import { useId, useState, type KeyboardEvent } from 'react'
import { BundleFileTree } from '../../components/scenarios/BundleFileTree'
import { MonacoEditorHost } from '../../components/MonacoEditorHost'
import { monacoLanguageForBundleFile } from '../../lib/bundleEditor'
import type { BundleFilePayload, BundleTemplateEntry, BundleTreeNode } from '../../lib/scenariosApi'
import styles from './CreateSwarmDialog.module.css'

type DetailTab = 'overview' | 'files'

/**
 * Responsibility: present scenario metadata and a read-only bundle preview in selectable tabs.
 * Must not: fetch bundles, mutate creation inputs or authorize swarm creation.
 * Contract: RESP-SCENARIO-CATALOGUE-ACCESS — docs/architecture/runtime-responsibilities.md#resp-scenario-catalogue-access (presentation only).
 */
export function CreateSwarmScenarioDetail({
  template,
  treeNodes,
  treeLoading,
  treeError,
  selectedFilePath,
  selectedFile,
  fileLoading,
  fileError,
  onSelectFile,
}: {
  template: BundleTemplateEntry
  treeNodes: BundleTreeNode[]
  treeLoading: boolean
  treeError: string | null
  selectedFilePath: string | null
  selectedFile: BundleFilePayload | null
  fileLoading: boolean
  fileError: string | null
  onSelectFile: (path: string) => void
}) {
  const [tab, setTab] = useState<DetailTab>('files')
  const id = useId()

  function navigateTab(event: KeyboardEvent<HTMLButtonElement>) {
    let next: DetailTab
    switch (event.key) {
      case 'ArrowLeft':
      case 'ArrowRight':
        next = tab === 'overview' ? 'files' : 'overview'
        break
      case 'Home':
        next = 'overview'
        break
      case 'End':
        next = 'files'
        break
      default:
        return
    }
    event.preventDefault()
    setTab(next)
    document.getElementById(`${id}-${next}-tab`)?.focus()
  }

  return (
    <section className={styles.detail} aria-label="Scenario details">
      <div className={styles.detailHeader}>
        <h3 className={styles.scenarioName}>{template.name}</h3>
        <div className={styles.scenarioPath}>{template.bundlePath}</div>
        {template.defunct ? (
          <div className={styles.defunctMessage}>
            <span className="pill pillBad">DEFUNCT</span>
            <span>{template.defunctReason ?? 'This bundle is unavailable.'}</span>
          </div>
        ) : null}
        <div className={styles.tabs} role="tablist" aria-label="Scenario details">
          {(['overview', 'files'] as const).map((value) => (
            <button
              key={value}
              type="button"
              className={styles.tab}
              id={`${id}-${value}-tab`}
              role="tab"
              aria-selected={tab === value}
              aria-controls={`${id}-${value}-panel`}
              tabIndex={tab === value ? 0 : -1}
              onClick={() => setTab(value)}
              onKeyDown={navigateTab}
            >
              {value === 'overview' ? 'Overview' : 'Files'}
            </button>
          ))}
        </div>
      </div>

      <div
        className={styles.overview}
        id={`${id}-overview-panel`}
        role="tabpanel"
        aria-labelledby={`${id}-overview-tab`}
        hidden={tab !== 'overview'}
      >
        <h4>Description</h4>
        <p>{template.description ?? 'No description provided.'}</p>
        <dl className={styles.metadata}>
          <div>
            <dt>Controller image</dt>
            <dd>{template.controllerImage ?? '—'}</dd>
          </div>
          <div>
            <dt>Bees</dt>
            <dd>{template.bees.length === 0 ? '—' : template.bees.map((bee) => bee.role).join(', ')}</dd>
          </div>
        </dl>
      </div>

      <div
        className={styles.filesPanel}
        id={`${id}-files-panel`}
        role="tabpanel"
        aria-labelledby={`${id}-files-tab`}
        hidden={tab !== 'files'}
      >
        <div className={styles.fileTreePane}>
          <div className={styles.fileTreeLabel}>Bundle files</div>
          {treeLoading ? <div className={styles.emptyState}>Loading files…</div> :
            treeError ? <div className="warningText">{treeError}</div> : (
              <BundleFileTree nodes={treeNodes} selectedPath={selectedFilePath} onSelectFile={onSelectFile} height="container" />
            )}
        </div>
        <div className={styles.filePreview}>
          <div className={styles.filePreviewHeader}>
            <span className={styles.filePath} title={selectedFilePath ?? undefined}>
              {selectedFilePath ?? 'No file selected'}
            </span>
            <span className={styles.readOnly}>Read only{selectedFile ? ` · ${selectedFile.editorKind}` : ''}</span>
          </div>
          {fileLoading ? <div className={styles.emptyState}>Loading file…</div> :
            fileError ? <div className={`${styles.emptyState} warningText`}>{fileError}</div> :
              selectedFile && selectedFile.content !== null ? (
                <div className={styles.filePreviewBody}>
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
                      fontSize: 14,
                    }}
                  />
                </div>
              ) : (
                <div className={styles.emptyState}>
                  {selectedFile ? 'This file is not previewable in New Swarm.' : 'Select a file to preview it.'}
                </div>
              )}
        </div>
      </div>
    </section>
  )
}
