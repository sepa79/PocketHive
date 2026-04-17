# Auth Service API Spec

> Status: planned / spec draft  
> Scope: shared `auth-service` HTTP contract for PocketHive and HiveWatch  
> Related:
> - `docs/architecture/AUTH_SERVICE_FOUNDATION_PLAN.md`
> - `docs/architecture/tenancy-foundation-plan.md`

This spec defines the MVP HTTP contract for the standalone shared
`auth-service`.

The service must support:

- PocketHive without HiveWatch,
- HiveWatch without PocketHive,
- both systems together.

---

## 1. Goals

- provide one shared current-user and grant source for both products,
- keep products out of raw LDAP integration details,
- work in local/dev environments before LDAP exists,
- keep product authorization logic outside `auth-service`.

---

## 2. Non-goals

- no PocketHive-specific bundle/folder evaluation in `auth-service`,
- no HiveWatch-specific environment visibility evaluation in `auth-service`,
- no direct UI-to-LDAP contract,
- no hidden fallback between auth providers,
- no requirement to use JWT in MVP.

---

## 3. Authentication model

MVP model:

- `auth-service` authenticates the user,
- `auth-service` issues an **opaque bearer token**,
- UIs send `Authorization: Bearer <token>` to PocketHive / HiveWatch APIs,
- product backends resolve the token through `auth-service`,
- product backends map returned grants into local authorization decisions.

Reason:

- this is simpler than making both products independently understand LDAP or
  token issuance,
- it allows changing the identity backend later without changing product API
  contracts,
- it keeps one authoritative user/grant source.

Future:

- signed JWT may be introduced later if there is a proven need,
- but JWT is not required for MVP.

---

## 4. Provider modes

Supported modes:

- `DEV`
- later: `LDAP`
- later if needed: `OIDC`

Rules:

- one deployment configures one explicit provider mode,
- unsupported mode is a startup failure,
- endpoints that do not apply to the configured mode must fail explicitly.

---

## 5. Core DTOs

### 5.1 Authenticated user

```json
{
  "id": "0d7bb04a-967d-4df8-b0d8-2e3b8a3f6c62",
  "username": "local-admin",
  "displayName": "Local Admin",
  "active": true,
  "authProvider": "DEV",
  "grants": [
    {
      "product": "POCKETHIVE",
      "permission": "ALL",
      "resourceType": "PH_DEPLOYMENT",
      "resourceSelector": "*"
    }
  ]
}
```

Rules:

- `product`, `permission`, and `resourceType` must come from shared contracts,
- `resourceSelector` is opaque to `auth-service`,
- product backends own interpretation of `resourceType + resourceSelector`.

### 5.2 Grant

```json
{
  "product": "POCKETHIVE",
  "permission": "RUN",
  "resourceType": "PH_FOLDER",
  "resourceSelector": "demo"
}
```

### 5.3 Session response

```json
{
  "accessToken": "phauth_opaque_token_value",
  "tokenType": "Bearer",
  "expiresAt": "2026-04-17T16:10:00Z",
  "user": {
    "id": "0d7bb04a-967d-4df8-b0d8-2e3b8a3f6c62",
    "username": "local-admin",
    "displayName": "Local Admin",
    "active": true,
    "authProvider": "DEV",
    "grants": []
  }
}
```

MVP token is opaque.

---

## 6. Endpoints

All paths below are relative to the `auth-service` base URL.

### 6.1 Resolve current user for UI

`GET /api/auth/me`

Headers:

- `Authorization: Bearer <token>`

Returns:

- `200` with `Authenticated user`
- `401` when token is missing/invalid/expired

Purpose:

- UIs use this to build current-user state and capability displays.

### 6.2 Resolve token for product backend

`POST /api/auth/resolve`

Headers:

- `Authorization: Bearer <token>`

Returns:

- `200` with `Authenticated user`
- `401` when token is missing/invalid/expired

Purpose:

- PocketHive and HiveWatch backends use this as the canonical token resolution
  endpoint.

Reason for a dedicated endpoint:

- `/me` is UI-facing current-user API,
- `/resolve` is the stable backend integration contract.

### 6.3 DEV login

`POST /api/auth/dev/login`

Request:

```json
{
  "username": "local-admin"
}
```

Response:

- `200` with `Session response`

Rules:

- available only in `DEV` mode,
- in other modes returns `405` or `400` with explicit error,
- unknown or inactive user returns `401`.

### 6.4 Admin list users

`GET /api/auth/admin/users`

Returns:

- `200` with `Authenticated user[]`

### 6.5 Admin create/update user

`PUT /api/auth/admin/users/{userId}`

Request:

```json
{
  "username": "local-operator",
  "displayName": "Local Operator",
  "active": true
}
```

### 6.6 Admin replace grants

`PUT /api/auth/admin/users/{userId}/grants`

Request:

```json
{
  "grants": [
    {
      "product": "POCKETHIVE",
      "permission": "RUN",
      "resourceType": "PH_FOLDER",
      "resourceSelector": "demo"
    }
  ]
}
```

Rules:

- grant replacement is explicit and full-state,
- no hidden merge/fallback behavior,
- unknown enum-like values fail validation explicitly.

---

## 7. Error model

Minimum status codes:

- `400` invalid request body or unsupported provider-specific operation,
- `401` missing/invalid/expired authentication,
- `403` authenticated caller lacks admin permission for auth admin API,
- `404` user not found,
- `409` conflicting username or conflicting admin mutation.

Response shape:

```json
{
  "message": "Human-readable error"
}
```

---

## 8. Product integration rules

### 8.1 UI integration

PocketHive UI and HiveWatch UI:

- authenticate through `auth-service`,
- store only the returned bearer token,
- call `/api/auth/me` to hydrate current-user state,
- send the same bearer token to their product APIs.

### 8.2 Backend integration

PocketHive and HiveWatch backends:

- must not parse provider-specific auth directly,
- must not talk raw LDAP directly,
- must resolve bearer tokens through `POST /api/auth/resolve`,
- must map returned grants into local product authorization.

### 8.3 Failure behavior

- if `auth-service` is unavailable, product APIs fail explicitly,
- no silent anonymous fallback,
- no product-local emergency auth bypass in normal mode.

---

## 9. Initial rollout scope

MVP implementation must include:

- `DEV` provider mode,
- opaque bearer tokens,
- `/api/auth/me`,
- `/api/auth/resolve`,
- `/api/auth/dev/login`,
- minimal admin user/grant API,
- integration path for PocketHive and HiveWatch.

Deferred:

- LDAP provider,
- OIDC provider,
- external group sync,
- product-specific admin UX polish.

---

## 10. Notes on LDAP

LDAP is a future identity backend for `auth-service`.

When LDAP is added:

- browser/UI still talks to `auth-service`, not LDAP,
- product APIs still resolve tokens through `auth-service`,
- local/shared grant model remains the same.

This keeps LDAP from leaking into product contracts.
