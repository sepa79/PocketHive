# Auth Service Follow-ups

> Status: future / design
> Delivered baselines:
> - `docs/archive/auth-service-foundation-plan.md`
> - `docs/archive/tenancy-foundation-plan.md`

PocketHive already has the standalone auth service, shared contracts/client, DEV provider, opaque user and service-principal sessions, grant resolution, PocketHive enforcement, UI capability projection, and service-token refresh.

Remaining independent work:

- [ ] Define and implement one explicit LDAP provider mode when required; no automatic provider fallback.
- [ ] Decide whether OIDC is needed in a separate proposal.
- [ ] Complete HiveWatch integration against the shared auth contract outside PocketHive-specific authorization logic.
- [ ] Decide whether shared user/grant administration APIs and UI need expansion beyond the current baseline.
- [ ] Consider finer-grained PocketHive permissions or roles/teams only as explicit contract changes.

The implemented HTTP contract remains in `docs/architecture/AUTH_SERVICE_API_SPEC.md`.
