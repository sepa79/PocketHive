# Auth Service API Spec

> Status: implemented baseline / MCP OAuth extension approved for implementation
> Scope: shared `auth-service` HTTP contract for PocketHive and HiveWatch  
> Related:
> - `docs/archive/auth-service-foundation-plan.md`
> - `docs/archive/tenancy-foundation-plan.md`
> - `docs/todo/auth-service-followups.md`

This spec defines the MVP HTTP contract for the standalone shared
`auth-service`.

The service must support:

- PocketHive without HiveWatch,
- HiveWatch without PocketHive,
- both systems together.
- internal service-to-service authentication without product-local auth bypasses.

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
- `auth-service` authenticates service principals separately from human users,
- `auth-service` issues an **opaque bearer token**,
- UIs send `Authorization: Bearer <token>` to PocketHive / HiveWatch APIs,
- product backends resolve the token through `auth-service`,
- product backends map returned grants into local authorization decisions.

Reason:

- this is simpler than making both products independently understand LDAP or
  token issuance,
- it allows changing the identity backend later without changing product API
  contracts,
- it keeps one authoritative user/grant source,
- it keeps service-to-service auth on the same foundation as UI auth.

Future:

- signed JWT may be introduced later if there is a proven need,
- but JWT is not required for MVP.

### 3.1 PocketHive MCP OAuth extension

The Java PocketHive MCP is a separate OAuth protected resource. Auth Service is
the only authorization server and grant authority for it. The first release
uses opaque, audience-bound MCP access tokens and the OAuth 2.1 authorization
code flow for public clients.

This extension does not reclassify a legacy `phauth_*` product session as an MCP
access token. An MCP token has its own `phmcp_*` namespace, exact resource,
client ID, scopes, principal, issue time, and expiry. PocketHive MCP accepts only
tokens resolved by the OAuth introspection contract below and never forwards an
inbound MCP token to Scenario Manager, Orchestrator, or another owner API.

First-release choices are explicit:

- one authorization-code grant plus refresh-token rotation for each VS Code
  companion environment session;
- public pre-registered clients only; no Dynamic Client Registration or Client
  ID Metadata Document fetching;
- PKCE `S256` is mandatory; `plain` and missing challenges fail;
- exact redirect URI matching; no wildcard, prefix, pattern, or alternate-port
  matching;
- the `resource` parameter is mandatory and identical in authorization and
  token requests;
- opaque access tokens expire after the configured short lifetime;
- the default access-token lifetime is 15 minutes and the default rotating
  companion refresh-token lifetime is 30 days; deployments may shorten either
  through the canonical Auth Service properties;
- the VS Code client requests its exact companion intent once:
  `pockethive:mcp:discover`, `pockethive:mcp:read`,
  `pockethive:mcp:operate`, `pockethive:mcp:author`, and
  `pockethive:mcp:publish`; Auth Service narrows that request to the
  authenticated principal's current PocketHive grants before consent and
  returns the exact granted scope set in the token response;
- a consented companion scope set containing discover and read receives one
  opaque rotating refresh token; the grant never includes
  `pockethive:mcp:cleanup`; refresh preserves the originally consented scope
  set, never widens it, and rejects a current-grant reduction;
- every successful refresh rotates the refresh token; a retired, expired,
  revoked, wrong-client, or wrong-resource refresh token fails explicitly and
  issues no token;
- the VS Code companion schedules renewal before access-token expiry and also
  checks on demand before every MCP action. Renewal is single-flight, retries
  no command by opening a browser, and keeps the last good workspace visible
  while renewal completes. A transient refresh or transport failure retains
  the refresh grant for an explicit bounded retry; a definitive token rejection
  clears it and requires one visible sign-in action;
- explicit sign-out revokes the current access and refresh tokens, deletes the
  local secret even if remote revocation cannot be confirmed, closes the MCP
  transport session, and never claims a remote logout that was not confirmed;
- one-time authorization codes expire after the configured short lifetime;
- consent decline and browser cancellation do not issue a code or token; and
- unsupported grants, response types, clients, resources, redirects, or scopes
  fail explicitly without fallback.

The required PocketHive VS Code public client ID is `pockethive-vscode`. Its
first-release callback is exactly
`http://127.0.0.1:57548/callback`. A deployment may register other clients only
as an explicit list of client IDs, display names, and exact redirect URIs. Their
support status is determined by the PocketHive MCP client conformance matrix;
registration alone is not a support claim.

Remote authorization and token endpoints require HTTPS. Loopback HTTP is
permitted only for an explicitly configured local development issuer and
resource whose resolved hosts remain loopback.

### 3.2 MCP OAuth scopes and grants

Auth Service validates requested scopes against both the registered client's
allow-list and the authenticated user's PocketHive grants. The canonical scope
IDs are shared constants, not raw strings in handlers:

| Scope | Minimum PocketHive permission | Purpose |
|---|---|---|
| `pockethive:mcp:discover` | `VIEW` or `ALL` | Server information and authorised catalogue discovery |
| `pockethive:mcp:read` | `VIEW` or `ALL` | Read-only Scenario Manager, Orchestrator, resource, and evidence calls |
| `pockethive:mcp:operate` | `RUN` or `ALL` | Swarm lifecycle and supported live operations |
| `pockethive:mcp:author` | `RUN` or `ALL` | QA workflow state, generation, and validation coordination |
| `pockethive:mcp:publish` | `ALL` | Scenario Bundle create or replace preparation |
| `pockethive:mcp:cleanup` | `ALL` | Governed runtime cleanup execution |

The companion request is an explicit OAuth policy case, not a fallback or a
scope alias. A principal with `VIEW` receives discover/read; `RUN` additionally
receives operate/author; `ALL` additionally receives publish. Cleanup is never
part of the VS Code companion session. The OAuth response always reports the
actual granted scope when Auth Service narrows the request, as required by the
OAuth scope contract.

Scopes constrain discovery and invocation but do not replace resource-level
PocketHive grants or HiveGate policy. Auth Service returns the principal's full
grants to the MCP introspection client so the MCP can apply the canonical folder
and bundle selectors at invocation time. A scope never grants a broader
resource selector than the underlying grant.

### 3.3 OAuth records

An authorization request record is short-lived and single-use. It binds the
request ID, authenticated principal, client ID, exact redirect URI, exact MCP
resource, ordered scope set, PKCE challenge and method, creation time, expiry,
and consent outcome. Browser form submission is bound to a cryptographically
random same-site request cookie; query parameters alone cannot approve it.

An authorization code is cryptographically random, short-lived, single-use,
and bound to the same principal, client, redirect, resource, scope set, and PKCE
challenge. Code consumption is atomic. Reuse, expiry, redirect mismatch,
resource mismatch, client mismatch, or verifier mismatch invalidates the
exchange and issues no token.

An opaque MCP access-token record binds token digest, principal, client,
resource, scopes, issue time, and expiry. A companion-session refresh-token
record also binds its authorization family, principal, public client, exact
resource, exact consented scope set, issue time, and expiry. Rotation retires the presented
refresh token before returning its replacement. Raw access or refresh tokens,
authorization codes, PKCE verifiers, request cookies, and OAuth `state` values
must not enter logs, telemetry, error bodies, or persisted evidence.

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

### 6.4 Service principal login

`POST /api/auth/service/login`

Request:

```json
{
  "serviceName": "orchestrator-service",
  "serviceSecret": "orchestrator-local-secret"
}
```

Response:

- `200` with `Session response`

Rules:

- service principal login is provider-independent,
- service principals are configured explicitly in `auth-service`,
- unknown, inactive, or secret-mismatched service principal returns `401`,
- product services must use this endpoint for outbound service-to-service
  authentication instead of ad-hoc bypass headers or local shared secrets.

### 6.5 Admin list users

`GET /api/auth/admin/users`

Returns:

- `200` with `Authenticated user[]`

### 6.6 Admin create/update user

`PUT /api/auth/admin/users/{userId}`

Request:

```json
{
  "username": "local-operator",
  "displayName": "Local Operator",
  "active": true
}
```

### 6.7 Admin replace grants

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

### 6.8 OAuth authorization-server metadata

`GET /.well-known/oauth-authorization-server` → `application/json`

The response follows RFC 8414 and is generated only from configured canonical
values. It contains:

```json
{
  "issuer": "https://environment.example/auth-service",
  "authorization_endpoint": "https://environment.example/auth-service/oauth/authorize",
  "token_endpoint": "https://environment.example/auth-service/oauth/token",
  "introspection_endpoint": "https://environment.example/auth-service/oauth/introspect",
  "response_types_supported": ["code"],
  "grant_types_supported": ["authorization_code", "refresh_token"],
  "token_endpoint_auth_methods_supported": ["none"],
  "revocation_endpoint": "https://environment.example/auth-service/oauth/revoke",
  "revocation_endpoint_auth_methods_supported": ["none"],
  "code_challenge_methods_supported": ["S256"],
  "scopes_supported": [
    "pockethive:mcp:discover",
    "pockethive:mcp:read",
    "pockethive:mcp:operate",
    "pockethive:mcp:author",
    "pockethive:mcp:publish",
    "pockethive:mcp:cleanup"
  ]
}
```

The service must not derive the issuer or endpoints from `Host`, `Forwarded`, or
`X-Forwarded-*` request headers. Missing or inconsistent canonical issuer
configuration fails startup.

### 6.9 OAuth authorization endpoint

`GET /oauth/authorize`

Required query parameters:

- `response_type=code`;
- registered `client_id`;
- exact registered `redirect_uri`;
- non-empty `state`;
- `code_challenge` and `code_challenge_method=S256`;
- exact configured MCP `resource`; and
- space-separated registered `scope` values.

The endpoint validates all parameters before presenting the DEV authentication
and consent page. The page shows client display name, redirect host, resource,
and requested scopes. It posts only to Auth Service using the one-time bound
authorization-request handle. User approval issues one authorization code and
redirects with the unchanged client `state`. Decline redirects with
`error=access_denied` and the unchanged `state`. Invalid requests return the
OAuth error directly and do not redirect to an untrusted URI.

The DEV provider asks for one configured active username. A future LDAP or OIDC
provider changes only the principal-authentication adapter; it does not change
the OAuth request, consent, code, token, or client contracts.

### 6.10 OAuth token endpoint

`POST /oauth/token` → request
`application/x-www-form-urlencoded`, response `application/json`

Authorization-code fields:

- `grant_type=authorization_code`;
- single-use `code`;
- registered public `client_id`;
- exact original `redirect_uri`;
- exact original `resource`; and
- `code_verifier` whose SHA-256 challenge matches the original request.

Success returns:

```json
{
  "access_token": "phmcp_opaque_value",
  "refresh_token": "phrfr_opaque_value",
  "token_type": "Bearer",
  "expires_in": 900,
  "scope": "pockethive:mcp:discover pockethive:mcp:read pockethive:mcp:operate pockethive:mcp:author"
}
```

The `refresh_token` field is returned only for a consented VS Code companion
scope set that contains discover/read, is a subset of the declared companion
intent, and excludes cleanup. The response `scope` is mandatory and is the
exact granted set. A non-companion or cleanup scope set receives the same
response without `refresh_token`.

Refresh fields are exact and explicit:

- `grant_type=refresh_token`;
- the registered public `client_id`;
- the current single-use `refresh_token`; and
- the exact configured MCP `resource`.

A successful refresh returns a new access token, the unchanged originally
consented scope set, and a different refresh token. It must not add a scope.
Auth Service rechecks the principal and current grants before issuing every
access token. An inactive principal or any reduction that no longer permits
the complete consented scope set fails with `invalid_grant`; the user must then
authorize the newly permitted profile once. A grant reduction therefore takes
effect at the next access-token refresh without waiting for the refresh-token
lifetime to elapse.
The previous refresh token is retired and cannot be replayed. OAuth errors use
the standard `error` field and a bounded non-sensitive `error_description`. A
failed authorization-code exchange consumes no valid code except where OAuth
replay protection requires invalidating the attempted code family.

### 6.11 OAuth token revocation

`POST /oauth/revoke` uses `application/x-www-form-urlencoded` with the exact
registered public `client_id`, one `token`, and one `token_type_hint` of either
`access_token` or `refresh_token`. The endpoint follows RFC 7009 response
semantics and never reveals whether an opaque token existed. The companion
revokes both current token types on explicit sign-out. Unknown clients,
credentials, duplicate fields, and unsupported authentication methods fail
explicitly.

### 6.12 OAuth token introspection

`POST /oauth/introspect` → request
`application/x-www-form-urlencoded`, response `application/json`

This endpoint is available only to the explicitly configured confidential
`pockethive-mcp` resource-server client using HTTP Basic authentication. The
request contains one `token` field. Invalid client authentication returns
`401`; a syntactically valid request for an unknown, expired, or revoked token
returns `{ "active": false }`.

An active response contains:

```json
{
  "active": true,
  "client_id": "pockethive-vscode",
  "username": "local-admin",
  "sub": "11111111-1111-1111-1111-111111111111",
  "aud": "https://environment.example/mcp",
  "scope": "pockethive:mcp:discover pockethive:mcp:read",
  "iat": 1787068800,
  "exp": 1787069700,
  "principal": {
    "id": "11111111-1111-1111-1111-111111111111",
    "username": "local-admin",
    "displayName": "Local Admin",
    "active": true,
    "authProvider": "DEV",
    "grants": []
  }
}
```

The MCP validates `active`, exact configured audience/resource, expiry, client,
and required scopes for every protected request. Introspection is token
validation, not token passthrough to a PocketHive owner.

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

Service-to-service callers:

- must obtain bearer tokens through `POST /api/auth/service/login`,
- must not bypass product auth filters with product-local headers or
  unauthenticated internal allowlists,
- may cache bearer tokens until `expiresAt`, but token renewal still goes
  through `auth-service`.

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
- `/api/auth/service/login`,
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
