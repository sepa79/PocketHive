# UI V2 Scenario Workspace — Plan

> Status: **planned**  
> Scope: UI v2, Scenario Manager, tenancy-aware bundle/file editing, Monaco offline packaging  
> Supersedes for UI v2 scenario editing:
> - `docs/archive/scenario-editor-plans/scenario-sut-editor-plan.md`
> - `docs/archive/scenario-editor-plans/vscode-scenario-editor-ui-plan.md`
>
> Read together with:
> - `docs/scenarios/SCENARIO_EDITOR_STATUS.md`
> - `docs/ui-v2/UI_V2_FLOW.md`
> - `docs/architecture/tenancy-foundation-plan.md`

This plan defines the **new Scenarios page** in UI v2 as a **bundle workspace**
with a file explorer, built-in editor, bundle operations, and tenancy-aware
contracts.

It replaces earlier “scenario editor” plans that were either too VS Code
specific or too focused on one file type (`scenario.yaml`) instead of the whole
bundle.

---

## 1. Working decisions

- [ ] Treat `ScenariosPage` as a **workspace**, not just a bundle list/browser.
- [ ] Keep **raw file editing** available for every text file in a bundle.
- [ ] Treat UI-driven editors as **optional overlays** on top of raw editing,
      never as the only editing path.
- [ ] Make tenancy an explicit part of the page contract from day one.
- [ ] Keep URL-driven navigation and avoid panel remount flicker.

---

## 2. Goals

- [ ] Show a full tree view of bundle contents, not only scenario names.
- [ ] Allow moving bundles between bundle folders/subdirectories.
- [ ] Allow editing every supported text file inside the app.
- [ ] Support richer guided editors for selected file types.
- [ ] Work in isolated/offline environments without fetching UI/editor assets
      from the internet.
- [ ] Preserve YAML-as-SSOT and avoid data loss in guided editors.

---

## 3. Non-goals for the first implementation

- [ ] No dependency on UI v1 editor behaviour or compatibility layers.
- [ ] No silent fallback from Monaco to external CDNs or remote asset loaders.
- [ ] No requirement to build guided editors for every file type before raw
      editing ships.
- [ ] No hidden tenant inference in `MULTI` mode.
- [ ] No second source of truth outside bundle files.

---

## 4. Architectural principles

- [ ] **Bundle-first UX**: the user works inside a scenario bundle workspace.
- [ ] **Raw-first editing**: Monaco/raw editing is the baseline capability.
- [ ] **Guided edit as overlay**: structured editors read/write the same files.
- [ ] **Generic contracts first**: the primary backend contract is bundle tree +
      generic file CRUD, not one endpoint per file type.
- [ ] **Offline-safe editor**: Monaco assets and workers are bundled into the UI
      image and served locally.
- [ ] **Explicit tenancy**: all tenant-scoped data flows through explicit tenant
      context and fail-fast validation.

---

## 5. Track 1 — Monaco offline foundation

- [ ] Document the hard requirement: no CDN, no remote worker/theme/assets, no
      runtime internet dependency for Monaco in UI v2.
- [ ] Replace implicit default loader behaviour with explicit local Monaco
      configuration.
- [ ] Bundle Monaco workers/assets into the UI v2 build and serve them under the
      application base path.
- [ ] Ensure Monaco failure is explicit in the UI instead of silently degrading
      to a different source.
- [ ] Verify Monaco works in an isolated environment with no outbound internet.

---

## 6. Track 2 — Backend bundle explorer contract

- [ ] Define one canonical DTO for bundle tree nodes.
- [ ] Define one canonical DTO for bundle file descriptors:
      `path`, `mediaType`, `size`, `revision`, `editorKind`, `writable`,
      `tenantId`.
- [ ] Define generic endpoints for:
      tree, read, write, create file, create folder, rename, move, delete.
- [ ] Keep existing type-specific endpoints only as specialized helpers or
      validators, not as the primary explorer API.
- [ ] Make all tenant-scoped contracts explicit in response DTOs and request
      handling.
- [ ] Enforce path safety and no traversal outside bundle root.

---

## 7. Track 3 — Scenario workspace UI shell

- [ ] Left panel: file explorer tree with folders, bundles, directories, files.
- [ ] Center panel: tabbed editor area for opened files.
- [ ] Right panel: metadata, validation, preview, and file-specific actions.
- [ ] Top toolbar: save, reload, create, rename, move, delete, validate.
- [ ] Route model stays path-based and shareable.
- [ ] Selection state should survive data refresh without list/canvas flicker.

---

## 8. Track 4 — Editing workflow

- [ ] Open multiple files in tabs.
- [ ] Track dirty state per tab/file.
- [ ] Save single file and “save all”.
- [ ] Reload file from disk/server explicitly.
- [ ] Support create/rename/move/delete for files and folders.
- [ ] Show explicit unsupported state for binary or non-editable file types in
      MVP.
- [ ] Preserve unknown YAML fields and minimize formatting churn.

---

## 9. Track 5 — Guided editors

- [ ] `scenario.yaml`: guided editor + raw tab.
- [ ] `variables.yaml`: guided editor + raw tab.
- [ ] `sut/<id>/sut.yaml`: guided editor + raw tab.
- [ ] Consider template/schema guided editors only after raw workspace is stable.
- [ ] Keep guided editor writes deterministic and round-trip safe.
- [ ] Require tests for “unknown subtree preserved” before shipping guided save.

---

## 10. Track 6 — Tenancy

- [ ] Add central tenant context in UI v2.
- [ ] Inject `X-Tenant-Id` on tenant-scoped API calls in `MULTI` mode.
- [ ] Show active tenant clearly in Scenarios workspace chrome.
- [ ] Fail fast on missing tenant context in `MULTI` mode.
- [ ] Ensure bundle and file operations cannot cross tenant boundaries.

---

## 11. Track 7 — Verification

- [ ] Backend tests for path validation and tenant enforcement.
- [ ] UI tests for stable explorer/editor state during refresh and selection.
- [ ] Smoke test for Monaco in isolated/no-internet deployment.
- [ ] Regression test for dirty tabs surviving tree refreshes.
- [ ] Regression test for move/rename/create/delete flows.

---

## 12. Delivery order

- [ ] Phase 1: Monaco offline foundation.
- [ ] Phase 2: generic bundle explorer API.
- [ ] Phase 3: raw bundle workspace UI.
- [ ] Phase 4: bundle/file operations polish.
- [ ] Phase 5: guided editors for selected files.
- [ ] Phase 6: tenancy polish and hardening.

---

## 13. Planned follow-up docs

- [x] Component spec for offline Monaco integration in UI v2:
      `docs/ui-v2/MONACO_OFFLINE_SPEC.md`
- [x] API spec for generic bundle tree and file CRUD:
      `docs/scenarios/SCENARIO_BUNDLE_WORKSPACE_API_SPEC.md`
- [x] UI interaction spec for Scenario Workspace:
      `docs/ui-v2/SCENARIO_WORKSPACE_UI_SPEC.md`
- [ ] Acceptance checklist for MVP release.
