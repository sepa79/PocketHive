# Bundle Diagnostics — Implementation Spec

## Status
`IMPLEMENTED`

## User Story
As a PocketHive operator, I want to see which scenario bundles failed to load and why, directly in the UI, so that I can quickly identify and fix broken bundles without needing access to server logs.

## Contents

| File | Description |
|---|---|
| [failure-catalogue.md](./failure-catalogue.md) | All 6 failure categories with causes and user-facing messages |
| [backend.md](./backend.md) | Scenario Manager changes — data model, reload hardening, new endpoint |
| [api-contract.md](./api-contract.md) | Full API contract for new and changed endpoints |
| [frontend.md](./frontend.md) | UI v2 changes — ScenariosPage, CreateSwarmModal, new component |
| [wireframes.md](./wireframes.md) | ASCII wireframes for all affected surfaces |
| [flows.md](./flows.md) | End-to-end flow diagrams |
| [docs-changes.md](./docs-changes.md) | Documentation files to update |

## Scope

**In scope:**
- `scenario-manager-service` — reload hardening, defunct reason, new failures endpoint
- `ui-v2` — ScenariosPage, CreateSwarmModal, new BundleFailuresBanner component
- API docs and Scenario Manager README

**Out of scope:**
- `ui` (v1) — no changes
- Orchestrator, Swarm Controller, worker services — no changes
- Scenario authoring, variables, SUT, template editing workflows — no changes
- New infrastructure, queues, or databases

## Key files touched

### Backend
| File | Change |
|---|---|
| `scenario-manager-service/src/main/java/io/pockethive/scenarios/ScenarioService.java` | Reload hardening, defunct reason, load failures map |
| `scenario-manager-service/src/main/java/io/pockethive/scenarios/ScenarioSummary.java` | Add `defunct`, `defunctReason` fields |
| `scenario-manager-service/src/main/java/io/pockethive/scenarios/ScenarioController.java` | New `GET /scenarios/failures` endpoint |
| `scenario-manager-service/src/main/java/io/pockethive/capabilities/api/CapabilityCatalogueController.java` | Include defunct + reason in `GET /api/templates` |

### Frontend
| File | Change |
|---|---|
| `ui-v2/src/lib/scenariosApi.ts` | Updated types, new `listBundleFailures()` function |
| `ui-v2/src/pages/ScenariosPage.tsx` | Defunct badge, reason panel, failures banner |
| `ui-v2/src/pages/hive/CreateSwarmModal.tsx` | Defunct templates greyed out with tooltip |
| `ui-v2/src/components/scenarios/BundleFailuresBanner.tsx` | New component |
| `ui/src/pages/hive/SwarmCreateModal.tsx` | One-line fix — filter defunct entries from ui-v1 template list |

### Docs
| File | Change |
|---|---|
| `docs/scenarios/SCENARIO_MANAGER_BUNDLE_REST.md` | New endpoint, updated response shapes |
| `scenario-manager-service/README.md` | Defunct causes and resolution guide |
