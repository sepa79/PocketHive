# Deployment Boundary and User Permissions Foundation

> Status: proposal  
> Scope: PocketHive deployment boundary, UI V2, Scenario Manager, Orchestrator, scoped user permissions  
> Note: legacy filename kept for now to avoid doc path churn.

## 1) Purpose

Replace the earlier runtime multi-tenancy direction with a simpler and more achievable model:

1. **one PocketHive deployment = one tenant / one environment boundary**
2. **users inside that deployment have different permissions**
3. **access is controlled by scoped grants**

This document defines the minimum authorization model we need now, without introducing teams, roles, or external IAM complexity yet.

---

## 2) Why this direction

The current system shape does not justify hard runtime multi-tenancy:

1. AMQP/control-plane topology is currently deployment-global.
2. Orchestrator and worker runtime assume one shared topology namespace.
3. Forcing tenant-scoped runtime now would create disproportionate code and ops complexity.

The real business need is different:

1. some users should only **view**
2. some users should be able to **do everything**
3. some scenarios should be safe for guest/demo/test usage
4. some scenarios should remain restricted for privileged users only

That is an **authentication + authorization** problem, not a runtime tenancy problem.

---

## 3) Deployment boundary

PocketHive v1 authorization model:

1. one deployment is one hard environment boundary
2. there is no per-request tenant routing inside one deployment
3. there is no tenant-scoped AMQP topology inside one deployment
4. there is no `X-Tenant-Id` requirement for normal product APIs

Implications:

1. `swarmId` remains deployment-scoped
2. control-plane topology remains deployment-scoped
3. runtime artifact layout remains deployment-scoped
4. user authorization is enforced inside that single deployment boundary

---

## 4) MVP authorization model

### 4.1 Users

Introduce users as first-class principals.

Minimum user shape:

1. `id`
2. `username`
3. `displayName`
4. `active`
5. `authSource`

### 4.2 Permissions

MVP permissions:

1. `VIEW`
2. `RUN`
3. `ALL`

Meaning:

1. `VIEW`
   - may browse UI and read state
   - may not launch or mutate system state
2. `RUN`
   - may launch scenarios within granted scope
   - may not edit bundles or perform privileged admin actions
3. `ALL`
   - full administrative and operational access

### 4.3 Grant scopes

Permissions may be granted at different scopes.

Supported scopes:

1. `GLOBAL`
2. `FOLDER`
3. `BUNDLE`

Meaning:

1. `GLOBAL`
   - applies everywhere in the deployment
2. `FOLDER`
   - applies to one folder subtree by path prefix
3. `BUNDLE`
   - applies to one exact bundle

This is simpler than keeping a separate scenario policy system.

### 4.4 Status tracking

- [ ] Define user identity contract for PocketHive HTTP APIs.
- [ ] Add MVP permissions: `VIEW`, `RUN`, `ALL`.
- [ ] Add grant scope model: `GLOBAL`, `FOLDER`, `BUNDLE`.
- [ ] Enforce authorization in Orchestrator create/start/stop/remove flows.
- [ ] Enforce authorization in Scenario Manager edit/delete flows.
- [ ] Surface current user and effective capabilities in UI v2.
- [ ] Add user management API/UI for local administration.
- [ ] Later: split `ALL` into finer-grained permissions.
- [ ] Later: add roles and teams on top of grants, not instead of grants.

---

## 5) Authorization rules

### 5.1 Permission + scope model

Access is decided by both:

1. user permission
2. granted scope

MVP rule table:

1. `VIEW + GLOBAL`
   - allow browse everywhere
2. `RUN + FOLDER=demo`
   - allow launch only inside `demo/...`
3. `RUN + BUNDLE=tcp/tcp-echo-demo`
   - allow launch only for that bundle
4. `ALL + GLOBAL`
   - allow all actions everywhere

### 5.2 Mutation rule

In MVP:

1. `VIEW` is pure read-only
2. `RUN` may launch scenarios only inside granted scope
3. `ALL` is required for:
   - scenario editing
   - bundle move/delete/upload
   - proxy management
   - user administration
   - launching outside narrower `RUN` scope

### 5.3 Future evolution

Later we may replace coarse permissions with finer ones, for example:

1. `SCENARIOS_VIEW`
2. `SCENARIOS_EDIT`
3. `SCENARIOS_MOVE`
4. `SCENARIOS_DELETE`
5. `SWARMS_START`
6. `SWARMS_STOP`
7. `SWARMS_REMOVE`
8. `PROXY_MANAGE`
9. `ADMIN_USERS`

If we later need bundle-specific permissions, add **scope to grants**, not a separate ACL system.

Recommended future grant shape:

1. `permission`
2. `scopeType = GLOBAL | FOLDER | BUNDLE`
3. `scopeValue`

Examples:

1. `RUN` on `FOLDER=demo`
2. `SCENARIOS_EDIT` on `FOLDER=tcp`
3. `SWARMS_START` on `BUNDLE=payments/prod-smoke`

Roles and teams should be built as aggregation layers over these permissions, not as the primary source of truth.

---

## 6) Where authorization is enforced

## 6.1 Scenario Manager

Authorization applies to:

1. bundle upload/delete/move/edit
2. file workspace write operations
3. SUT/dataset/simulation registry changes
4. future bundle classification/metadata changes if such metadata is introduced

Read operations require at least `VIEW`.
Write operations require `ALL` in MVP.

## 6.2 Orchestrator

Authorization applies to:

1. create swarm
2. start/stop/remove swarm
3. launch access to scenario bundles
4. future simulation lifecycle operations

Runtime actions depend on permission:

1. `RUN` may create/start only within granted folder/bundle scope
2. `ALL` may create/start any scenario and perform stop/remove/admin operations

## 6.3 UI V2

UI V2 owns:

1. showing current user
2. showing effective capability state
3. hiding/disabling actions for insufficient permissions
4. showing explicit denial reasons when a bundle is out of scope

The backend remains the enforcement boundary.
UI capability checks are advisory UX only.

---

## 7) Data and metadata model

## 7.1 Scenario metadata

Scenario/bundle metadata does not need its own ACL system in MVP.

Bundle access is derived from the user's grants and the bundle path.

Optional later metadata:

1. `classification = TEST | STAGING | PRODUCTION`
2. tags or labels used by policy engines

## 7.2 User grants

MVP grant model:

1. a user has one or more grants
2. each grant is:
   - `permission`
   - `scopeType`
   - `scopeValue`
3. a local admin assigns grants directly

We do not need teams/roles yet.
Keep one evaluator model: `isAllowed(user, permission, resource)`.

---

## 8) API contract (minimum)

1. APIs must resolve the current authenticated user explicitly.
2. Read-only product APIs require at least `VIEW`.
3. Launching a scenario requires `RUN` or `ALL` within matching scope.
4. Mutating product APIs require `ALL` in MVP unless explicitly carved out by a future finer permission.
5. Authorization failures return `403`.
6. Authentication failures return `401`.

No per-request tenant header is required in this model.

---

## 9) Implementation map

Current anchor points:

1. `scenario-manager-service`
   - scenario/bundle metadata
   - bundle workspace endpoints
   - edit/delete/upload authorization
2. `orchestrator-service`
   - swarm create/start/stop/remove
   - scope checks before launch
3. `ui-v2`
   - user/capability display
   - action gating in Hive / Scenarios / Proxy
4. shared docs/contracts
   - permission enum
   - grant scope enum

---

## 10) Rollout order

1. **PR 1**
   - define user identity contract
   - define MVP permission enum
   - define grant scope enum
2. **PR 2**
   - enforce authz in Orchestrator and Scenario Manager
   - add grant-aware evaluation to launch and workspace flows
3. **PR 3**
   - expose current user and capabilities in UI v2
   - disable/hide unauthorized actions
4. **PR 4**
   - local user administration UI/API
5. **Later**
   - split `ALL` into granular permissions
   - add roles/teams if needed

---

## 11) Decisions

Decided:

1. one deployment is one hard environment boundary
2. runtime multi-tenancy is not the target for this phase
3. `VIEW`, `RUN`, and `ALL` are sufficient as the first user permission set
4. folder/bundle scope is sufficient to allow guest/test launch without a separate scenario policy system
5. backend authorization is mandatory; UI-only checks are insufficient
