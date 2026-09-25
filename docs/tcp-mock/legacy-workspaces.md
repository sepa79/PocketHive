# Workspace catalogue contract

Workspaces are a durable presentation catalogue. Selection and shared metadata
do not isolate mappings, TCP traffic or users. No scenario, swarm or SUT binding
exists. Entries survive server restart; browser selection lasts for its session.
Future ownership will follow SUTs; this release adds no team/auth tenancy.

WorkspaceService owns policy. Responses contain immutable `id`, `name`, `owner`,
`shared`, `defaultWorkspace` and `deletable` fields. Exactly one initial entry is
the default and cannot be deleted. IDs are server-generated UUIDs prefixed `ws-`
for created entries. Owner and policy flags cannot be edited. Names must contain
1–128 characters after stripping surrounding whitespace. `shared` is metadata.

| Operation | Success | Failure |
|---|---|---|
| GET `/api/workspaces` | 200, catalogue in creation order | Request failure does not fabricate entries |
| POST `/api/workspaces`, `{name,shared}` | 201, created workspace | 400 invalid name |
| PUT `/api/workspaces/{id}`, `{name,shared}` | 200, updated workspace retaining identity/owner/policy | 404 missing ID; 400 invalid name |
| DELETE `/api/workspaces/{id}` | 204 after removal | 404 missing ID; 409 protected default |

The browser updates entries and selection only after a successful response. It
uses the server-designated default when a saved selection no longer exists or
when the selected workspace is deleted. Failed create/rename/delete/load leaves
its previous catalogue and selection intact and reports the failure. Initial load
failure leaves no selection. A malformed catalogue with no unique default is an
error, not permission to invent or select an arbitrary entry.

The dropdown shows the catalogue limitations and an explicit Reload catalogue action.
Rename/delete controls use server policy flags. Failed or uncertain operations keep
visible state and direct the user to reload before retrying; no automatic retry.

## Durable catalogue

`/app/data/workspace-catalogue.json` stores a version-1 document with `version` and
`workspaces` (the canonical workspace response records in creation order). The
existing instance data volume must be retained, with one process per data directory.
No new database service or in-memory runtime mode is introduced.

WorkspaceService owns validation and mutation ordering. It builds a complete candidate,
saves it through WorkspacePersistence, then publishes it to readers. WorkspaceFileStore
owns JSON encoding; AtomicSnapshotFile owns file IO shared with mapping persistence.
Create, rename and delete acknowledge only after atomic replacement. Initial startup
persists the default only when the snapshot is absent. An unreadable/corrupt snapshot,
unsupported version, duplicate ID or invalid default/deletion policy fails startup;
it never resets silently. A write failure leaves the prior in-memory catalogue intact
and fails the request. Restore storage and restart before further mutations after a
storage failure, because the replacement may have completed before failure was observed.
This is single-process restart durability, not replication or multi-instance coordination.

`tcp-mock.data-directory` is required and set to `/app/data` by the supplied
application configuration. TcpMockStoragePaths resolves both catalogue paths once.
Changing it selects a different explicit data directory; it does not migrate files.

Browser requests resolve through HttpClient.endpoint relative to the served page
directory. This preserves `/tcp-mock/` under the supported ingress and `/` for the
standalone UI. Authentication checks and catalogue requests use that same resolver.
Ingress canonicalizes `/tcp-mock` to `/tcp-mock/` with a relative redirect so the
browser retains its public host and port, including alternate local test ports.

## Authentication provider and ownership

`tcp-mock.auth.provider` explicitly selects `NATIVE` or `POCKETHIVE`. Unknown or
missing modes fail startup. There is no provider fallback, automatic switching,
credential copying or ownership migration. The supplied standalone configuration
selects NATIVE; PocketHive Compose explicitly selects POCKETHIVE. Neither provider
participates in TCP matching, delays or response delivery.

NATIVE uses the existing configured local username/password and Basic authentication.
It requires no auth-service URL, connection or browser asset. POCKETHIVE resolves
bearers through the existing AuthServiceClient and auth-service; the browser reuses
the existing PocketHive session module published at `/auth-session.js` and links to
PocketHive sign-in. Native credentials are not accepted in POCKETHIVE mode.

GET `/api/auth/config` is public and returns `{provider}` from server configuration.
GET `/api/auth/me` is protected and returns `{provider,subject,displayName}` from the
selected authenticated principal. This projection is not a user directory. In
POCKETHIVE mode missing/expired credentials return 401; inactive or insufficiently
authorized users return 403; auth-service failure returns 503. Reads require global
PocketHive VIEW/RUN/ALL, writes global ALL, evaluated by PocketHiveGrantChecks.
Static UI assets and health remain public. Resolution failure never permits mutation.

Workspace creation takes its `owner` from this trusted identity, encoded once as
`PROVIDER:subject`: auth-service UUID for POCKETHIVE, configured username for NATIVE.
Client input cannot override ownership; rename preserves it. The built-in default
remains system-owned. Provider changes preserve existing owner attribution. Ownership
does not add team filtering, mapping/traffic isolation, scenario/swarm binding or SUT
lifecycle. No MCP workflow persistence or authentication change is included here.

POCKETHIVE requires explicit `pockethive.auth.service-url`, `connect-timeout` and
`read-timeout`. NATIVE requires explicit native username/password settings. A selected
provider failure blocks administration; existing mock TCP traffic continues.
