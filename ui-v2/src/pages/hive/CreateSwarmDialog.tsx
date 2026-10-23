import { useEffect, useId, useRef, type FormEvent, type ReactNode } from 'react'
import { createPortal } from 'react-dom'
import { ScenarioTree } from '../../components/scenarios/ScenarioTree'
import type { NetworkMode } from '../../lib/NetworkMode'
import type { BundleFilePayload, BundleTemplateEntry, BundleTreeNode } from '../../lib/scenariosApi'
import { CreateSwarmScenarioDetail } from './CreateSwarmScenarioDetail'
import type { SwarmNetworkProfileOption } from './SwarmNetworkProfileOption'
import type { SwarmVariablesMeta } from './SwarmVariablesMeta'
import styles from './CreateSwarmDialog.module.css'

/**
 * Responsibility: render the swarm-creation form and the supplied runnable scenario catalogue.
 * Must not: load bundles, interpret grants, dispatch API calls or settle creation outcomes.
 * Contract: RESP-SCENARIO-CATALOGUE-ACCESS — docs/architecture/runtime-responsibilities.md#resp-scenario-catalogue-access (presentation only).
 */
export function CreateSwarmDialog({
  swarmId, networkMode, sutIds, sutId, networkProfiles, networkProfileId,
  variablesMeta, variablesProfileId, requiresSut, requiresProfile,
  templates, templatesLoaded, templateFilter, selectedBundleKey, selectedTemplate, openFolderPaths,
  bundleTreeNodes, bundleTreeLoading, bundleTreeError, selectedFilePath, selectedFile,
  selectedFileLoading, selectedFileError, autoPullImages, busy, catalogueError, error, message,
  onClose, onSubmit, onSwarmIdChange, onNetworkModeChange, onSutIdChange, onNetworkProfileIdChange,
  onVariablesProfileIdChange, onTemplateFilterChange, onSelectBundle, onSelectFile, onAutoPullImagesChange,
  plannerControls, creationPending, associationBlocked,
}: {
  swarmId: string
  plannerControls: ReactNode
  creationPending: boolean
  associationBlocked: boolean
  networkMode: NetworkMode
  sutIds: string[]
  sutId: string
  networkProfiles: SwarmNetworkProfileOption[]
  networkProfileId: string
  variablesMeta: SwarmVariablesMeta
  variablesProfileId: string
  requiresSut: boolean
  requiresProfile: boolean
  templates: BundleTemplateEntry[]
  templatesLoaded: boolean
  templateFilter: string
  selectedBundleKey: string
  selectedTemplate: BundleTemplateEntry | null
  openFolderPaths: Set<string> | null
  bundleTreeNodes: BundleTreeNode[]
  bundleTreeLoading: boolean
  bundleTreeError: string | null
  selectedFilePath: string | null
  selectedFile: BundleFilePayload | null
  selectedFileLoading: boolean
  selectedFileError: string | null
  autoPullImages: boolean
  busy: boolean
  catalogueError: string | null
  error: string | null
  message: string | null
  onClose: () => void
  onSubmit: (event: FormEvent) => void
  onSwarmIdChange: (value: string) => void
  onNetworkModeChange: (value: NetworkMode) => void
  onSutIdChange: (value: string) => void
  onNetworkProfileIdChange: (value: string) => void
  onVariablesProfileIdChange: (value: string) => void
  onTemplateFilterChange: (value: string) => void
  onSelectBundle: (bundleKey: string) => void
  onSelectFile: (path: string) => void
  onAutoPullImagesChange: (value: boolean) => void
}) {
  const id = useId()
  const dialogRef = useRef<HTMLDivElement>(null)
  const hasSuts = sutIds.length > 0

  useEffect(() => {
    const previousFocus = document.activeElement
    dialogRef.current?.querySelector<HTMLInputElement>('input')?.focus()
    return () => {
      if (previousFocus instanceof HTMLElement) previousFocus.focus()
    }
  }, [])

  return createPortal(
    <div className={styles.backdrop} role="presentation" onClick={() => { if (!busy) onClose() }}>
      <div
        className={styles.dialog}
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={`${id}-title`}
        onClick={(event) => event.stopPropagation()}
        onKeyDown={(event) => {
          if (event.key === 'Escape') { event.stopPropagation(); if (!busy) onClose() }
          if (event.key !== 'Tab') return
          const elements = Array.from(event.currentTarget.querySelectorAll<HTMLElement>(
            'button:not(:disabled), input:not(:disabled), select:not(:disabled), [tabindex="0"]',
          )).filter((element) => element.getClientRects().length > 0)
          const first = elements[0]
          const last = elements[elements.length - 1]
          if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus() }
          else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus() }
        }}
      >
        <div className={styles.header}>
          <div><h2 id={`${id}-title`}>Create swarm</h2><div className={styles.subtitle}>Choose a scenario and configure your swarm.</div></div>
          <button type="button" className="actionButton actionButtonGhost" onClick={onClose} disabled={busy}>Close</button>
        </div>
        <div className={styles.messages} aria-live="polite">
          {catalogueError ? <div className="warningText">{catalogueError}</div> : null}
          {error ? <div className="warningText" role="alert">{error}</div> : null}
          {message ? <div>{message}</div> : null}
        </div>
        <form className={styles.form} onSubmit={onSubmit}>
          <fieldset className={styles.config} disabled={busy || creationPending}>
            <label className={styles.field}><span>Swarm ID</span><input className="textInput" value={swarmId} onChange={(event) => onSwarmIdChange(event.target.value)} placeholder="demo" /></label>
            <label className={styles.field}>
              <span>Network mode</span>
              <select className="textInput" value={networkMode} onChange={(event) => onNetworkModeChange(event.target.value as NetworkMode)}>
                <option value="DIRECT">Direct</option><option value="PROXIED">Proxied</option>
              </select>
            </label>
            <label className={styles.field}>
              <span>Bundle SUT{requiresSut ? ' (required)' : ''}</span>
              <select className="textInput" value={sutId} onChange={(event) => onSutIdChange(event.target.value)} disabled={!hasSuts}>
                <option value="">{hasSuts ? '(none)' : '(none available)'}</option>
                {sutIds.map((value) => <option key={value} value={value}>{value}</option>)}
              </select>
            </label>
            {variablesMeta.exists ? (
              <label className={styles.field}>
                <span>Variables profile{requiresProfile ? ' (required)' : ''}</span>
                <select className="textInput" value={variablesProfileId} onChange={(event) => onVariablesProfileIdChange(event.target.value)}>
                  <option value="">(select)</option>
                  {variablesMeta.profiles.map((profile) => <option key={profile.id} value={profile.id}>{profile.name ? `${profile.name} (${profile.id})` : profile.id}</option>)}
                </select>
              </label>
            ) : null}
            {networkMode === 'PROXIED' ? (
              <label className={styles.field}>
                <span>Network profile (required)</span>
                <select className="textInput" value={networkProfileId} onChange={(event) => onNetworkProfileIdChange(event.target.value)} disabled={networkProfiles.length === 0}>
                  <option value="">{networkProfiles.length === 0 ? '(no profiles available)' : '(select)'}</option>
                  {networkProfiles.map((profile) => <option key={profile.id} value={profile.id}>{profile.name ? `${profile.name} (${profile.id})` : profile.id}</option>)}
                </select>
              </label>
            ) : null}
          </fieldset>
          {plannerControls}
          <fieldset className={styles.workspace} disabled={busy || creationPending}>
            <section className={styles.scenarioPicker} aria-label="Scenario picker">
              <div className={styles.pickerHeader}>
                <h3>Scenarios</h3>
                <input className="textInput" value={templateFilter} onChange={(event) => onTemplateFilterChange(event.target.value)} placeholder="Filter scenarios" aria-label="Scenario filter" />
              </div>
              <div className={styles.scenarioList}>
                {!templatesLoaded ? <div className={styles.emptyState}>Loading scenarios…</div> : templates.length === 0 ? (
                  <div className={styles.emptyState}>No runnable scenarios found.</div>
                ) : (
                  <ScenarioTree items={templates} selectedBundleKey={selectedBundleKey} onSelectBundle={onSelectBundle} searchTerm={templateFilter} openPaths={openFolderPaths} rowHeight={48} emptyMessage="No scenarios found." />
                )}
              </div>
            </section>
            {selectedTemplate ? (
              <CreateSwarmScenarioDetail
                template={selectedTemplate} treeNodes={bundleTreeNodes} treeLoading={bundleTreeLoading} treeError={bundleTreeError}
                selectedFilePath={selectedFilePath} selectedFile={selectedFile} fileLoading={selectedFileLoading} fileError={selectedFileError} onSelectFile={onSelectFile}
              />
            ) : <div className={`${styles.detail} ${styles.emptyState}`}>Select a scenario to see details.</div>}
          </fieldset>
          <div className={styles.footer}>
            <label className={styles.pullImages}>
              <input type="checkbox" checked={autoPullImages} onChange={(event) => onAutoPullImagesChange(event.target.checked)} disabled={busy || !selectedTemplate || selectedTemplate.defunct || !selectedTemplate.id} />
              <span>Pull images</span>
            </label>
            {variablesMeta.exists ? <span className={styles.variablesHint}>Variables: {variablesMeta.hasGlobalVars ? 'global' : '—'} · {variablesMeta.hasSutVars ? 'sut' : '—'}</span> : null}
            <div className={styles.actions}>
              <button type="button" className="actionButton actionButtonGhost" onClick={onClose} disabled={busy}>{creationPending ? 'Continue in swarm details' : 'Cancel'}</button>
              <button type="submit" className="btnPrimary" disabled={busy || associationBlocked || (!creationPending && (!selectedTemplate || selectedTemplate.defunct))}>{busy ? 'Working…' : creationPending ? 'Retry association only' : 'Create swarm'}</button>
            </div>
          </div>
        </form>
      </div>
    </div>,
    document.body,
  )
}
