# UI V2 Scenario Workspace UI Spec

> Status: **planned / spec draft**  
> Scope: UI v2 Scenarios page MVP  
> Related:
> - `docs/ui-v2/SCENARIO_WORKSPACE_PLAN.md`
> - `docs/ui-v2/MONACO_OFFLINE_SPEC.md`
> - `docs/scenarios/SCENARIO_BUNDLE_WORKSPACE_API_SPEC.md`

This spec defines the MVP behaviour of the new **Scenarios workspace** in UI
v2.

The Scenarios page is no longer just a bundle list. It becomes a **bundle
explorer + editor workspace**.

---

## 1. Route model

MVP routes:

- `/v2/scenarios`
- `/v2/scenarios/bundles/:bundleKey`

Rules:

- route state is shareable,
- browser `Back` works naturally,
- selected bundle is URL-driven,
- open tabs and local panel state may remain client-local in MVP.

---

## 2. Page layout

Three-column layout for MVP:

- left: explorer
- center: editor tabs + active editor
- right: details/actions

### 2.1 Left column

Shows:

- bundle list at root level,
- selected bundle file tree,
- folders and files with clear explorer icons,
- defunct badge/state for bundles where relevant.

### 2.2 Center column

Shows:

- open file tabs,
- raw Monaco editor for editable files,
- explicit unsupported state for non-editable files,
- dirty marker on modified tabs.

### 2.3 Right column

Shows:

- selected bundle metadata,
- selected file metadata,
- validation/messages,
- actions for create/rename/move/delete/save/reload.

---

## 3. Primary interactions

### 3.1 Root view

At `/v2/scenarios`:

- show bundle explorer entry state,
- allow selecting a bundle,
- allow moving bundles between bundle folders,
- do not auto-open files until a bundle is selected.

### 3.2 Bundle view

At `/v2/scenarios/bundles/:bundleKey`:

- load one bundle tree,
- preserve expanded directories during refresh,
- auto-focus explorer selection based on the last opened or selected file only
  if the user is already inside the bundle view,
- do not remount the whole explorer on every selection change.

### 3.3 File open

Clicking a file:

- opens it in a center tab,
- activates the tab if already open,
- loads raw content from the workspace API,
- selects that file in the explorer.

### 3.4 File save

Saving a file:

- writes through the generic workspace API,
- updates local revision,
- clears the dirty flag,
- keeps tab order and selection stable.

---

## 4. Explorer behaviour

- Explorer is file-tree style, similar to an IDE explorer.
- Folders expand/collapse inline.
- Files and folders must be visually distinct.
- Unsupported files still appear in the tree.
- Explorer state must not flicker during refresh or save.

Hard no-flicker rules:

- no full remount on selection change,
- no clearing and re-adding the entire tree during refresh,
- stable node ids,
- expanded folder state preserved when the underlying tree is unchanged.

---

## 5. Tab behaviour

- Multiple files may be open at once.
- Tabs are keyed by `bundleKey + path`.
- Dirty tabs show a visible dirty marker.
- Closing a clean tab is immediate.
- Closing a dirty tab requires explicit user confirmation in MVP.
- “Save all” operates on dirty tabs in the active bundle workspace.

---

## 6. File states

### 6.1 Editable text file

Show:

- Monaco editor,
- save/reload controls,
- path and metadata in the side panel.

### 6.2 Unsupported or binary file

Show:

- file metadata,
- explicit “not editable in MVP” message,
- no fake editor and no silent fallback to text guessing.

### 6.3 Missing/stale file

If a file disappears after it was opened:

- keep the tab visible,
- show a stale/missing state in the editor area,
- do not silently close the tab.

---

## 7. Monaco integration rules

- All Monaco hosts use the shared UI v2 Monaco bootstrap.
- No component may configure its own remote loader path.
- The Scenarios workspace must not initialize Monaco differently from other UI
  v2 pages.

---

## 8. Tenancy UX

MVP workspace must show active tenant context clearly.

Minimum rules:

- active tenant visible in page chrome or toolbar,
- tenant-aware API errors surfaced explicitly,
- no hidden tenant switching when navigating bundles.

If tenant context is missing in `MULTI` mode:

- workspace view shows an explicit blocking error,
- it does not attempt “best guess” bundle loading.

---

## 9. Guided editors in MVP

Not enabled by default in the first raw-workspace implementation.

However the UI must reserve extension points for:

- `scenario.yaml`
- `variables.yaml`
- `sut/<id>/sut.yaml`

When guided editors arrive later:

- raw tab remains available,
- raw file content remains the source of truth,
- switching editor modes must not drop unknown fields.

---

## 10. Error handling

Workspace errors must be explicit and local:

- tree load error,
- file read error,
- save conflict,
- unsupported file type,
- missing tenant context,
- Monaco bootstrap failure.

The UI must not respond to these errors by:

- reloading the whole page automatically,
- clearing all tabs automatically,
- switching to another bundle automatically,
- retrying with a different endpoint or fallback chain.

---

## 11. MVP exclusions

- drag-and-drop reordering within the editor area,
- binary previews,
- diff editor,
- cross-bundle search,
- cross-bundle refactors,
- complex guided editors,
- collaborative editing.

---

## 12. Acceptance criteria

- A user can open a bundle and browse all files through the explorer.
- A user can open multiple text files in tabs and edit/save them.
- A user can see non-editable files without broken editor behaviour.
- Explorer state remains stable during selection, save, and refresh.
- Monaco works in isolated/no-internet deployment.
- Tenant context is explicit and fail-fast in `MULTI` mode.
