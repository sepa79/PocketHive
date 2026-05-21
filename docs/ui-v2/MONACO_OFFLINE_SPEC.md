# UI V2 Monaco Offline Spec

> Status: **planned / spec draft**  
> Scope: `ui-v2` only  
> Related:
> - `docs/ui-v2/SCENARIO_WORKSPACE_PLAN.md`
> - `docs/architecture/tenancy-foundation-plan.md`

This spec defines how Monaco must be packaged and initialized in **UI v2** so
that it works in **isolated deployments with no internet access**.

---

## 1. Problem

UI v2 currently uses `@monaco-editor/react`, which in its default configuration
uses `@monaco-editor/loader`. That loader defaults to CDN-based Monaco asset
resolution.

For PocketHive this is not acceptable:

- UI v2 may run in isolated environments.
- UI v2 must not depend on outbound internet access.
- UI v2 must not silently fall back to remote Monaco assets.

---

## 2. Hard requirements

- Monaco must load with **zero external network access**.
- Monaco workers must be served from the **same UI deployment**.
- Monaco initialization must be **explicitly configured** in UI v2.
- Failure to load Monaco must be shown as an **explicit local error state**.
- UI v2 must not use CDN themes, CDN workers, CDN loaders, or runtime asset
  fetches outside the application origin.

---

## 3. Decision

UI v2 will use the locally installed `monaco-editor` package as the source of
editor code and workers.

Implementation direction:

- Keep Monaco sourced from local npm dependencies and packaged into the UI image.
- Configure `@monaco-editor/react` with a **local-only loader path**.
- Serve Monaco `vs/` assets from the same UI deployment.
- Centralize Monaco setup in one shared UI v2 module.

There will be **one Monaco bootstrap path** for UI v2. No alternate loader
chains, no “if that fails, use CDN”.

---

## 4. Required UI v2 structure

Add a shared Monaco bootstrap module in UI v2, for example:

- `src/lib/monaco/bootstrap.ts`

Responsibilities:

- configure Monaco exactly once,
- configure the local `vs/` path,
- expose shared editor defaults,
- expose Monaco init status for editor hosts.

All pages/components using Monaco must go through this shared bootstrap.

Direct ad-hoc use of `@monaco-editor/react` without bootstrap is out of spec.

---

## 5. Worker model

For MVP, Monaco workers are served from the local Monaco `vs/` asset tree
packaged with UI v2.

This keeps worker resolution local to the UI deployment without relying on a
remote CDN or a second runtime downloader.

---

## 6. Loader model

Preferred approach for UI v2:

- provide `loader.config({ paths: { vs: '<local-app-path>/monaco/vs' } })`

This replaces the default CDN-based configuration with a local app-served path.
The Monaco asset tree must be versioned and shipped together with the UI image.

---

## 7. Error handling

If Monaco fails to initialize:

- the page must render an explicit inline error panel,
- the error must say Monaco could not be loaded locally,
- the UI must not retry against any external source,
- the error should be logged once in browser console for diagnosis.

The UI may offer a local retry action, but it must retry the same local
bootstrap path only.

---

## 8. Theming and extensions

For MVP:

- use built-in Monaco themes only,
- no remote theme downloads,
- no runtime extension downloads,
- no language servers or assets fetched from the internet.

Custom language registration is allowed only if shipped inside UI v2.

---

## 9. Acceptance criteria

- Starting UI v2 in an environment with blocked outbound internet still loads
  Monaco.
- Browser network logs show Monaco assets/workers served from the local app
  origin only.
- `WireLogPage` and future Scenarios editors share the same Monaco bootstrap.
- There is no CDN URL in the effective Monaco initialization path.
- Failure mode is explicit and visible.

---

## 10. Out of scope

- Monaco in UI v1.
- Remote collaboration features.
- LSP-backed advanced language services.
- Non-text binary preview/editing.
