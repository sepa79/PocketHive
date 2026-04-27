# Scenario bundle diagnostics gap

**Area:** Scenario Manager REST + Hive/UI template/scenario visibility  
**Status:** Confirmed bug / missing diagnostics  
**Impact:** Operators do not know why some scenario bundles are unavailable or missing; malformed bundles can also break reload instead of being isolated.

---

## Problem

Scenario bundles can become unavailable in two different ways:

1. The bundle loads, but the scenario is not usable.
   Example: `scenario.yaml` parses correctly, but the scenario references a controller/bee image
   that has no matching capability manifest. In that case the scenario becomes effectively
   **defunct**.

2. The bundle cannot be loaded at all.
   Example: malformed YAML in `scenario.yaml`.

Today the user-facing diagnostics are incomplete:

- unavailable/defunct scenarios are filtered out of normal template selection,
- but the UI does not get a structured reason explaining why they are unavailable,
- and there is no dedicated REST surface for bundles that failed to load entirely.

As a result, operators see an incomplete list of scenarios/templates without a clear explanation
of what is broken and where.

---

## Current behaviour on `main`

### 1) Defunct scenarios are hidden without explanation

Scenario Manager already distinguishes available vs defunct scenarios internally.
However:

- `GET /api/templates` returns only available templates,
- the response does not expose any `defunct` flag or `defunctReason`,
- so Hive/UI cannot show unavailable templates with a diagnostic reason.

User-visible result:

- a manually added bundle may exist on disk,
- but it does not appear in the template picker,
- and the operator has no explanation in the UI.

### 2) Malformed bundles are not isolated and reported

If a bundle contains malformed YAML/JSON, there is currently no dedicated REST endpoint that reports:

- which bundle failed,
- and why it failed.

Worse, malformed bundle content can currently fail the reload path instead of being isolated while
healthy bundles continue to load.

User-visible result:

- healthy scenarios may still be visible only if reload survives,
- broken bundles are not represented in any diagnostics list,
- and in the bad case reload fails before the UI can show anything useful.

---

## Expected behaviour

The product should distinguish three states clearly:

1. **Available**
   Scenario is valid and can be used to create a swarm.

2. **Defunct**
   Scenario bundle loaded, but it is not usable.
   The UI should receive and display a human-readable reason.

3. **Load failure**
   Bundle could not be loaded at all.
   The system should isolate the bad bundle, keep healthy bundles available, and expose the failure
   in a dedicated diagnostics list.

Concretely this means:

- template/scenario listings should provide structured diagnostics for defunct scenarios,
- malformed bundles should not take down the whole reload flow,
- there should be a REST endpoint for load failures so the UI can explain why a bundle is missing.

---

## Minimal reproduction

The clean repro lives on branch:

- `repro/bundle-diagnostics-clean`

It adds failing tests showing that on `main`:

- `/api/templates` does not expose defunct metadata,
- malformed bundles are not reported through a failures endpoint,
- and malformed bundle content still breaks reload instead of being isolated.

---

## Notes

- This is primarily a diagnostics/visibility bug, not a request to keep backward compatibility with
  the current opaque behaviour.
- The problem is most visible for bundles added manually on disk, because API-driven creation usually
  fails earlier and more explicitly.
