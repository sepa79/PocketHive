# Shared Auth Service Foundation

> Status: proposal  
> Scope: shared auth/authn foundation for PocketHive and HiveWatch  
> Consumers: PocketHive, HiveWatch, future internal services

This document defines the **shared auth foundation** that must sit outside any
single product.

The target is an **independent `auth-service` container** that may run:

- with PocketHive only,
- with HiveWatch only,
- with both systems together.

---

## 1) Working decisions

- [ ] `auth-service` is a standalone deployable container, not a PocketHive-only module.
- [ ] PocketHive and HiveWatch must be able to integrate with it independently.
- [ ] Authentication foundation is shared; product authorization policy remains local.
- [ ] `auth-service` owns user identity, provider integration, current-user resolution, and grant storage.
- [ ] `auth-service` owns service-principal identity for internal service-to-service auth.
- [ ] Product resource semantics stay outside `auth-service`.
- [ ] Initial local/dev operation must work without LDAP.
- [ ] LDAP integration must be possible later without redesigning PocketHive or HiveWatch integration.

---

## 2) Why this direction

- [ ] Avoid duplicating auth plumbing in PocketHive and HiveWatch.
- [ ] Avoid baking PocketHive-specific permissions into a system that HiveWatch must also use.
- [ ] Keep one source of truth for users and grants.
- [ ] Allow future enterprise identity integration without tying browser/runtime flows directly to LDAP assumptions.

---

## 3) Responsibilities of `auth-service`

`auth-service` is responsible for:

- [ ] authenticating the caller using one configured provider mode,
- [ ] resolving the current authenticated user,
- [ ] storing users and assigned grants,
- [ ] exposing stable auth APIs such as `/me`,
- [ ] providing product-neutral grant data for downstream products,
- [ ] supporting local/dev flows for isolated deployments,
- [ ] issuing service principal sessions for internal HTTP callers,
- [ ] later integrating with LDAP or another enterprise identity source.

`auth-service` is **not** responsible for:

- [ ] evaluating PocketHive bundle/folder path semantics,
- [ ] evaluating HiveWatch environment visibility semantics,
- [ ] embedding product-specific UI logic,
- [ ] changing PocketHive runtime AMQP/control-plane topology.

---

## 4) Shared building blocks

### 4.1 Deployable service

- [ ] Create `auth-service` as a separate container in local compose/runtime topology.
- [ ] It must be optional at composition level but authoritative when present.

### 4.2 Shared contracts

- [ ] Add a shared contracts module for auth DTOs/constants.
- [ ] Keep it transport-neutral and product-neutral.
- [ ] Use it from `auth-service`, PocketHive, and HiveWatch.

### 4.3 Shared integration client

- [ ] Add a small shared client/integration module for JVM services.
- [ ] It should encapsulate “resolve current user / validate caller / parse auth response”.
- [ ] Do not duplicate this integration separately in PocketHive and HiveWatch.

---

## 5) Provider model

Authentication provider is explicit and single-mode.

Planned provider modes:

- [ ] `DEV`
- [ ] `LDAP`
- [ ] later, if needed, `OIDC`

Rules:

- [ ] no hidden provider fallback chain,
- [ ] no automatic failover from one provider to another,
- [ ] each deployment configures one explicit provider mode.
- [ ] browser/UI integrations must not talk raw LDAP directly as their product contract.

### 5.1 DEV mode

- [ ] supports local/dev/test workflows without external identity infra,
- [ ] useful for PocketHive-only and HiveWatch-only standalone development,
- [ ] must still go through `auth-service`, not product-local fake auth.

### 5.2 LDAP mode

- [ ] planned as a future identity backend,
- [ ] used for authentication and user lookup,
- [ ] must not become the only place where product-specific grants live.

Reason:

- LDAP may authenticate users,
- but PocketHive/HiveWatch-specific permissions remain domain-specific.

---

## 6) Shared grant model

Shared grant contract should remain product-neutral.

Recommended shape:

- [ ] `product`
- [ ] `permission`
- [ ] `resourceType`
- [ ] `resourceSelector`

Contract rule:

- [ ] `product`, `permission`, and `resourceType` must come from shared auth
      contracts/constants, not ad-hoc magic strings spread across products.

Examples:

- [ ] `product=POCKETHIVE`, `permission=RUN`, `resourceType=PH_FOLDER`, `resourceSelector=demo`
- [ ] `product=POCKETHIVE`, `permission=ALL`, `resourceType=PH_DEPLOYMENT`, `resourceSelector=*`
- [ ] `product=HIVEWATCH`, `permission=VIEW`, `resourceType=HW_ENVIRONMENT`, `resourceSelector=env-123`

This keeps `auth-service` generic while allowing each product to interpret its
own resource model.

---

## 7) Product boundaries

### 7.1 PocketHive

PocketHive remains responsible for:

- [ ] mapping grants to bundle/folder/deployment access,
- [ ] enforcing `VIEW`, `RUN`, `ALL` in Scenario Manager / Orchestrator / UI,
- [ ] keeping bundles/SUTs free of embedded ACL logic.

### 7.2 HiveWatch

HiveWatch remains responsible for:

- [ ] mapping grants to environment/admin visibility,
- [ ] preserving its own domain-specific authorization rules,
- [ ] evolving from local role handling to shared-auth-backed resolution when ready.

---

## 8) Minimum external API

MVP auth-service API should include:

- [ ] `GET /api/auth/me`
- [ ] `POST /api/auth/resolve`
- [ ] local/dev login/bootstrap capability
- [ ] service principal login capability
- [ ] admin endpoints for user + grant management

Exact token/session contract can be specified separately, but `/me` must be the
stable minimum shared endpoint used by both UIs.

Normative API details live in:

- [ ] `docs/architecture/AUTH_SERVICE_API_SPEC.md`

---

## 9) Rollout order

- [ ] Phase 1: define shared auth contracts and provider model.
- [ ] Phase 2: implement standalone `auth-service` container with `DEV` mode.
- [ ] Phase 3: add JVM integration client and wire PocketHive to shared current-user flow.
- [ ] Phase 4: wire HiveWatch to the same auth-service instead of product-local auth plumbing.
- [ ] Phase 5: add local admin APIs/UI for users and grants.
- [ ] Phase 6: add LDAP integration.
- [ ] Later: consider OIDC/browser-native flows if needed.

---

## 10) Decisions

Decided:

- [x] auth foundation must be shared across PocketHive and HiveWatch,
- [x] auth must be a standalone service/container,
- [x] LDAP is a future provider, not the first implementation slice,
- [x] product authorization remains local even when authentication is shared.
