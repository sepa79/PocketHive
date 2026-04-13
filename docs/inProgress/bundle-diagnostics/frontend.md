# Frontend Changes — UI v2

All changes are in `ui-v2`. No changes to `ui` (v1).

---

## 1. `ui-v2/src/lib/scenariosApi.ts`

### Updated type: `ScenarioSummary`

```typescript
export type ScenarioSummary = {
  id: string
  name: string
  folderPath: string | null
  defunct: boolean        // new
  defunctReason: string | null  // new
}
```

### New type: `BundleLoadFailure`

```typescript
export type BundleLoadFailure = {
  bundlePath: string
  reason: string
}
```

### Updated `normalizeScenarioSummary()`

```typescript
function normalizeScenarioSummary(input: unknown): ScenarioSummary | null {
  if (!isRecord(input)) return null
  const id = asString(input['id'])
  if (!id) return null
  const name = asString(input['name']) ?? id
  const folderPath = asString(input['folderPath'])
  const defunct = input['defunct'] === true
  const defunctReason = asString(input['defunctReason'])
  return { id, name, folderPath, defunct, defunctReason }
}
```

### New function: `listBundleFailures()`

```typescript
export async function listBundleFailures(): Promise<BundleLoadFailure[]> {
  const response = await fetch('/scenario-manager/scenarios/failures', {
    headers: { Accept: 'application/json' },
  })
  await ensureOk(response, 'Failed to load bundle failures')
  try {
    const payload = (await response.json()) as unknown
    if (!Array.isArray(payload)) return []
    return payload
      .map((entry) => {
        if (!isRecord(entry)) return null
        const bundlePath = asString(entry['bundlePath'])
        const reason = asString(entry['reason'])
        if (!bundlePath || !reason) return null
        return { bundlePath, reason }
      })
      .filter((entry): entry is BundleLoadFailure => entry !== null)
  } catch {
    return []
  }
}
```

---

## 2. New component: `ui-v2/src/components/scenarios/BundleFailuresBanner.tsx`

Collapsible warning banner shown when `listBundleFailures()` returns a non-empty array.

```typescript
import { useState } from 'react'
import type { BundleLoadFailure } from '../../lib/scenariosApi'

export function BundleFailuresBanner({ failures }: { failures: BundleLoadFailure[] }) {
  const [expanded, setExpanded] = useState(false)
  if (failures.length === 0) return null

  return (
    <div className="card" style={{
      borderColor: 'rgba(255, 193, 7, 0.4)',
      background: 'rgba(255, 193, 7, 0.08)',
      marginBottom: 12
    }}>
      <div className="row between">
        <div className="row" style={{ gap: 8 }}>
          <span className="pill pillWarn">⚠ {failures.length} bundle{failures.length > 1 ? 's' : ''} failed to load</span>
          <span className="muted">These bundles are not available for use.</span>
        </div>
        <button
          type="button"
          className="actionButton actionButtonGhost"
          onClick={() => setExpanded((prev) => !prev)}
        >
          {expanded ? 'Hide details' : 'Show details'}
        </button>
      </div>

      {expanded && (
        <div style={{ marginTop: 12, display: 'grid', gap: 10 }}>
          {failures.map((failure) => (
            <div key={failure.bundlePath} style={{
              borderTop: '1px solid rgba(255,255,255,0.08)',
              paddingTop: 10
            }}>
              <div className="h2" style={{ fontSize: 12 }}>{failure.bundlePath}</div>
              <div className="muted" style={{ marginTop: 4 }}>{failure.reason}</div>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
```

---

## 3. `ui-v2/src/pages/ScenariosPage.tsx`

### Changes summary
- Fetch `listBundleFailures()` alongside `listScenarios()` in `reload()`
- Render `<BundleFailuresBanner>` at the top of the page
- Add defunct badge to `renderScenarioButton()`
- Add defunct reason card to the details panel

### Updated `reload()` callback

```typescript
const [failures, setFailures] = useState<BundleLoadFailure[]>([])

const reload = useCallback(async () => {
  setLoading(true)
  setError(null)
  try {
    const [list, folderList, failureList] = await Promise.all([
      listScenarios({ includeDefunct: true }),
      listScenarioFolders(),
      listBundleFailures(),   // new
    ])
    setItems(list)
    setFolders(folderList)
    setFailures(failureList)  // new
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
```

### Updated `renderScenarioButton()` — defunct badge

```typescript
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
        <div className="muted" style={{ marginTop: 6 }}>{entry.id}</div>
      </button>
    )
  },
  [selectedId],
)
```

### Updated details panel — defunct reason card

Add this block above the action buttons in the details panel, when `selected.defunct` is true:

```tsx
{selected.defunct && (
  <div className="card" style={{
    borderColor: 'rgba(255, 95, 95, 0.35)',
    background: 'rgba(255, 95, 95, 0.08)',
    marginTop: 12
  }}>
    <div className="row" style={{ gap: 8, marginBottom: 8 }}>
      <span className="pill pillBad">DEFUNCT</span>
      <span className="h2" style={{ fontSize: 13 }}>This bundle cannot be used to create a swarm</span>
    </div>
    <div className="muted">
      {selected.defunctReason ?? 'Reason unknown — check server logs or reload.'}
    </div>
  </div>
)}
```

### Updated JSX — add banner at top of page

```tsx
<BundleFailuresBanner failures={failures} />
```

Place this immediately after the error card and before the `swarmViewGrid`.

---

## 4. `ui-v2/src/pages/hive/CreateSwarmModal.tsx`

### Updated `normalizeTemplates()`

Add `defunct` and `defunctReason` to `ScenarioTemplate` type and normalization:

```typescript
type ScenarioTemplate = {
  id: string
  name: string
  folderPath: string | null
  description: string | null
  controllerImage: string | null
  bees: BeeSummary[]
  defunct: boolean          // new
  defunctReason: string | null  // new
}
```

In `normalizeTemplates()`:
```typescript
const defunct = value.defunct === true
const defunctReason =
  typeof value.defunctReason === 'string' && value.defunctReason.trim().length > 0
    ? value.defunctReason.trim()
    : null
return { id, name, folderPath, description, controllerImage, bees, defunct, defunctReason }
```

### Updated `renderTemplateButton()` — defunct entries

```tsx
const renderTemplateButton = (template: ScenarioTemplate) => (
  <button
    key={template.id}
    type="button"
    className={template.id === templateId ? 'swarmTemplateItem swarmTemplateItemSelected' : 'swarmTemplateItem'}
    onClick={() => {
      if (template.defunct) return  // not selectable
      setTemplateId(template.id)
    }}
    aria-label={template.name}
    title={template.defunct ? (template.defunctReason ?? 'This template is unavailable') : undefined}
    style={template.defunct ? { opacity: 0.45, cursor: 'not-allowed' } : undefined}
  >
    <div className="row between">
      <div className="swarmTemplateTitle">{template.name}</div>
      {template.defunct && <span className="pill pillBad" style={{ fontSize: 10 }}>DEFUNCT</span>}
    </div>
    <div className="swarmTemplateId">
      {template.folderPath ? `${template.folderPath}/${template.id}` : template.id}
    </div>
    <div className="swarmTemplateDesc">
      {template.defunct
        ? (template.defunctReason ?? 'This template is unavailable')
        : (template.description ?? 'No description')}
    </div>
  </button>
)
```

### Defunct count notice

Add above the template list body when any defunct templates exist:

```tsx
{templates.some((t) => t.defunct) && (
  <div className="muted" style={{ fontSize: 11, padding: '4px 0' }}>
    {templates.filter((t) => t.defunct).length} template(s) unavailable — hover for details
  </div>
)}
```

### Guard: prevent submitting a defunct template

In `handleCreate()`, add after the existing required-field check:

```typescript
const selectedTpl = templates.find((t) => t.id === trimmedTemplateId)
if (selectedTpl?.defunct) {
  setError(`Template '${trimmedTemplateId}' is unavailable: ${selectedTpl.defunctReason ?? 'unknown reason'}`)
  return
}
```

---

## 5. `ui/src/pages/hive/SwarmCreateModal.tsx` (ui-v1)

One-line fix to prevent defunct templates appearing as selectable in ui-v1. Must ship in the same PR as the backend change.

```typescript
function normalizeTemplate(entry: unknown): ScenarioTemplate | null {
  if (!entry || typeof entry !== 'object') return null
  const value = entry as Record<string, unknown>
  if (value.defunct === true) return null   // ← add this line
  // ... rest unchanged
}
```
