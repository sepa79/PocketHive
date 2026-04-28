# Auth API Rollout Plan

> Status: **in progress**  
> Scope: incoming auth/authz coverage for PocketHive HTTP APIs, e2e rollout tracking, and final verification  
> Related:
> - `docs/architecture/AUTH_SERVICE_FOUNDATION_PLAN.md`
> - `docs/architecture/AUTH_SERVICE_API_SPEC.md`
> - `docs/architecture/tenancy-foundation-plan.md`
> - `docs/ci/control-plane-testing.md`

This plan is the working checklist for rolling shared `auth-service` integration
through every PocketHive API that should be protected.

It exists so implementation can proceed iteratively, one API surface at a time,
without losing track of:

1. whether incoming auth/authz is actually implemented,
2. whether ingress-based e2e coverage exists,
3. whether the surface has been explicitly re-tested after rollout changes.

## 1) Guardrails

- No fallback auth paths.
- No product-local bypass headers or allowlists for normal API traffic.
- Backend authz is mandatory; UI gating is secondary.
- Tests and e2e checks must use the official ingress/API paths, not direct
  container ports, unless the test explicitly verifies that service interface.
- `actuator` endpoints are outside this rollout unless explicitly listed.

## 2) Checkbox meaning

### `auth zrobiony`

Check only when all are true:

- incoming requests on the listed API surface pass through auth resolution,
- the endpoint group has an explicit authz policy (`VIEW`, `RUN`, `ALL`, admin,
  or documented public endpoint),
- no parallel unauthenticated path exists for the same business action.

### `e2e dodane`

Check only when an ingress-based scenario exists that covers:

- at least one allowed path,
- at least one denied or unauthenticated path when the surface is protected.

### `przetestowane`

Check only when the rollout item was re-verified after the latest related code
change with targeted tests and/or stack verification.

## 3) Explicit exclusions

These are intentionally not tracked as protected API rollout items:

- `actuator` health/info endpoints,
- static UI asset delivery,
- docs/static content under `/docs/`.

## 4) Rollout order

1. Close externally exposed gaps first.
2. Finish auth coverage for remaining mutating APIs.
3. Add or extend ingress-based e2e for each protected surface.
4. Re-test each item and only then tick `przetestowane`.

## 5) API rollout matrix

| Service | API surface | Expected policy | auth zrobiony | e2e dodane | przetestowane | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| `auth-service` | `/api/auth/dev/login`, `/api/auth/service/login`, `/api/auth/me`, `/api/auth/resolve` | public login endpoints + authenticated resolve/current-user | [x] | [x] | [x] | Controller tests cover dev/service login plus `me`/`resolve`, and the ingress auth pack re-verifies the shared auth flow against the live stack. |
| `auth-service` | `/api/auth/admin/users`, `/api/auth/admin/users/{userId}`, `/api/auth/admin/users/{userId}/grants` | `AUTH_SERVICE ADMIN` only | [x] | [x] | [x] | Admin provisioning flow is covered by both `AuthControllerTest` and the ingress auth pack. |
| `scenario-manager-service` | `/api/templates`, `/api/templates/{id}` | runnable-template feed for create flow | [x] | [x] | [x] | `CapabilityCatalogueControllerTest`, `ScenarioManagerAuthFilterTest`, and the live auth pack all re-verify the runnable-template feed separately from workspace reads. |
| `scenario-manager-service` | `GET /scenarios/bundles/workspaces` | PocketHive `VIEW` within scope, including non-runnable/defunct bundle metadata | [x] | [x] | [x] | Scenarios page now uses this read feed instead of inheriting `RUN` semantics from `/api/templates`; auth pack now hits it through ingress. |
| `scenario-manager-service` | `/api/capabilities` | PocketHive read access | [x] | [x] | [x] | Capability catalogue now enforces explicit PocketHive read authz instead of relying only on coarse GET gating. |
| `scenario-manager-service` | `GET /scenarios`, `GET /scenarios/defunct`, `GET /scenarios/{id}`, raw/schema/template/bundle read endpoints | PocketHive `VIEW` within scope | [x] | [x] | [x] | Core read path exists; workspace-specific bundle metadata now flows through `/scenarios/bundles/workspaces`. |
| `scenario-manager-service` | `POST /scenarios` | deployment-global PocketHive `ALL` | [x] | [x] | [x] | New scenario creation writes at deployment root, so folder-scoped `ALL` is not sufficient. |
| `scenario-manager-service` | workspace mutation endpoints (`PUT/DELETE /scenarios*`, folder ops, bundle move/delete/upload/replace, variable/template/schema writes) | PocketHive `ALL` within explicit scope | [x] | [x] | [x] | Scenario-scoped writes, folder-targeted ops, and default upload folder now all honor explicit scope; `POST /scenarios/reload` remains deployment-global admin. |
| `scenario-manager-service` | `POST /scenarios/{id}/runtime` | PocketHive `RUN` within scope + reject defunct/unlaunchable bundles | [x] | [x] | [x] | Backend now rejects defunct bundles instead of relying on UI-only admission. |
| `scenario-manager-service` | `/sut-environments*` | deployment-scoped read/write policy | [x] | [x] | [x] | Reads require PocketHive read access; raw writes require deployment-level `ALL`. |
| `scenario-manager-service` | `/network-profiles*` | deployment-scoped read/write policy | [x] | [x] | [x] | Reads require PocketHive read access; raw writes require deployment-level `ALL`. |
| `orchestrator-service` | `GET /api/swarms`, `GET /api/swarms/{swarmId}` | PocketHive `VIEW` within scope | [x] | [x] | [x] | `OrchestratorAuthFilterTest` covers the read gate and the ingress auth pack re-verifies swarm reads against a live scoped swarm. |
| `orchestrator-service` | `POST /api/swarms/{swarmId}/create`, `/start` | PocketHive `RUN` within scope + reject defunct templates | [x] | [x] | [x] | Launch admission now hard-fails defunct templates before orchestration side effects start. |
| `orchestrator-service` | `POST /api/swarms/{swarmId}/stop`, `/remove` | PocketHive `ALL` within scope | [x] | [x] | [x] | Covered by the existing folder-admin auth scenario and re-verified in `SwarmControllerTest`. |
| `orchestrator-service` | `POST /api/swarms/{swarmId}/network` | explicit policy for network binding changes | [x] | [x] | [x] | Endpoint keeps scope-aware `ALL` authz and the auth pack now exercises the allowed path through ingress, even when business validation returns `409`. |
| `orchestrator-service` | `/api/swarms/{swarmId}/journal*` | PocketHive `VIEW` within scope; pin/write actions require stronger policy | [x] | [x] | [x] | Swarm journal reads/pin are now gated per swarm scope and covered by targeted controller + Postgres journal tests. |
| `orchestrator-service` | `/api/journal/hive/page`, `/api/journal/swarm/runs`, `/api/journal/swarm/runs/{runId}/meta` | read vs run-metadata mutation policy must be explicit | [x] | [x] | [x] | Global journal read now requires deployment read; run metadata updates require deployment admin and are covered in the auth pack. |
| `orchestrator-service` | `/api/control-plane/refresh`, `/api/control-plane/reset` | PocketHive `ALL` | [x] | [x] | [x] | Control-plane sync endpoints now require deployment-global `ALL`; unit auth coverage exists and ingress scenarios exercise the boundary. |
| `orchestrator-service` | `/api/control-plane/schema/control-events` | PocketHive `VIEW` | [x] | [x] | [x] | Schema endpoint is now explicitly protected by PocketHive read authz instead of relying only on the coarse GET filter. |
| `orchestrator-service` | `/api/components/{role}/{instance}/config` | PocketHive `ALL` | [x] | [x] | [x] | Requests without `swarmId` require deployment admin; swarm-targeted requests require matching-scope `ALL`. |
| `orchestrator-service` | `/api/debug/taps*` | explicit read/write policy | [x] | [x] | [x] | Create/close now require manage scope while reads stay scoped read-only; both controller tests and ingress scenarios cover the behavior. |
| `orchestrator-service` | `/api/swarm-managers*` | PocketHive `ALL` | [x] | [x] | [x] | Deployment-wide fan-out now requires deployment admin, while single-swarm toggles honor matching-scope `ALL`. |
| `network-proxy-manager-service` | `/api/network/bindings*` | GET requires PocketHive read access; mutations require PocketHive `ALL` | [x] | [x] | [x] | Incoming auth filter is now active for the service; auth pack covers both unauthenticated rejection and allowed read paths. |
| `network-proxy-manager-service` | `/api/network/proxies` | explicit read policy | [x] | [x] | [x] | Read path now sits behind the same incoming auth filter as the rest of the service and is covered by ingress auth scenarios. |
| `network-proxy-manager-service` | `/api/network/manual-override*` | privileged admin/proxy-manage only | [x] | [x] | [x] | Current rollout keeps `ALL` on mutations, and the auth pack now captures the denied mutation plus allowed read status path. |

## 6) Immediate implementation queue

- [x] Add incoming auth/authz to `network-proxy-manager-service`.
- [x] Finish Scenario Manager mutation authz so create/workspace writes honor the
      intended scope model instead of relying on partial coverage.
- [x] Make Orchestrator launch flows reject defunct or otherwise unlaunchable
      templates at the backend boundary.
- [x] Separate Scenarios read APIs from runnable-template APIs in UI v2 so
      `VIEW` users keep read access without inheriting `RUN` semantics.
- [x] Add missing ingress-based e2e for the remaining unchecked Scenario Manager surfaces (`/scenarios*`, `/sut-environments*`, `/network-profiles*`) and re-run the full auth pack against a live stack.
- [x] Re-test items and tick `przetestowane` only after explicit verification.

## 7) Update rule

When working an auth item:

1. implement or adjust auth/authz,
2. add/update ingress-based e2e,
3. run targeted verification,
4. tick the matching row,
5. note any policy decision in the relevant architecture/spec doc if the change
   affects product rules rather than just implementation.
