# Upgrading PocketHive

This is the canonical index of required actions when upgrading PocketHive.
Release summaries remain in `CHANGELOG.md`; detailed scenario rewrite rules
and commands live in `docs/ai/SCENARIO_CONFIG_MIGRATION_GUIDE.md`.

PocketHive does not implicitly accept legacy contract shapes. Complete the
documented migration before deploying a release that removes an old shape.

## 0.15.38 (from 0.15.37)

### Deployment and sign-in

There are no newly mandatory HiveForge component inputs in this version. Retain
the explicit WORK selection and public Auth/MCP identity required by 0.15.36;
use matching source and image versions when the release images are published.

PH's UI now uses [Pocket device-confirmation sign-in](architecture/BROWSER_DEVICE_LOGIN.md).
Start sign-in in PH and confirm in the PH Auth tab; the original page completes
sign-in without redirecting to a preview port. Device clients do not require
per-preview callback registrations. Exact-redirect clients keep their explicit
callback/PKCE requirements; do not relax redirect validation or origins.

The OAuth identity repair requires restarting Auth Service. Its in-memory
OAuth authorizations, consent and browser security contexts must be cleared;
users sign in again after the restart. Do not hot-migrate existing username-based
principals into the UUID namespace: a former username can itself look like another
user's UUID. New authorizations bind identity once at sign-in and never resolve a
retained principal through a reassigned username.

### Shared Java device-auth clients

Rebuild Java consumers of the shared auth client against matching `auth-client`
and `auth-contracts` versions. `DeviceAuthClient` now requires the fifth
constructor argument, `boolean allowRemoteHttp`; update every call site,
including PocketPlanner and PocketData integrations.

Pass `false` for HTTPS public endpoints and loopback-only HTTP. Pass `true` only
when the deployment explicitly permits remote public HTTP, using the consumer's
`POCKETHIVE_ALLOW_REMOTE_HTTP` setting. The client applies this policy to returned
verification and logout endpoints through the shared transport contract. An
internal HTTP Auth Service base URL does not enable public remote HTTP.
The response DTOs validate HTTP(S) URI structure without selecting a consumer's
transport policy; do not replace the client-side check with a DTO-only check.

### Optional PocketPlanner integration

Configure `POCKETHIVE_PLANNER_BASE_URL` on Orchestrator only when enabling the
[Planner catalogue integration](ORCHESTRATOR-REST.md#pocketplanner-integration).
The URL must reach the intended Planner backend; an unconfigured integration
reports its configuration error rather than selecting another instance. The
source Compose file exposes the optional setting. Current HiveForge manifests
do not expose a Planner URL input, so upgrading that deployment alone does not
enable the integration. No automatic Plan/Activity creation, progress or
Started/Done synchronization is introduced.

### Scheduler input and worker template contexts

HTTP Sequence with SCHEDULER input now receives an initial `{}` JSON object
instead of empty text. The scheduler rate/enablement configuration is unchanged;
HTTP Sequence's normal strict input parser is still used.

Custom integrations that directly construct `SchedulerWorkInputFactory` must
now pass its fifth argument, a `UnaryOperator<WorkItem>` seed customizer.
Use `UnaryOperator.identity()` to retain the standard SDK seed. Normal Spring
autoconfiguration supplies that identity customizer; HTTP Sequence supplies its
JSON seed through the same extension point.

`PayloadWrapper` and its `getCol0()` through `getCol9()` methods are removed.
Java callers should use ordinary map access. Worker template object payloads
are maps; actual keys such as `customerId` and `col0` remain available without
manufacturing or renaming fields. Text and non-object payload views retain their
existing behavior.

### Strict template data functions

Review templates using `json_path`: selectors are RFC6901 JSON Pointers, not
JSONPath expressions, and must select exactly one non-null scalar. Missing/null
or non-scalar values fail explicitly instead of becoming empty strings. Use
`json_string`, `xml_text` and `xml_attr` for explicit escaping when constructing
requests.

The shared document reader rejects duplicate JSON keys, trailing documents,
invalid UTF-8, non-finite supplied numbers and unpaired JSON string surrogates.
Validate input documents before deploying affected templates. Named optional
native views retain their documented null/original-text behavior; this does
not make WorkItem transport interpret its opaque payload as JSON.

Native PH template contexts remain distinct from
[portable-v1 record/params/endpoint bindings](contracts/portable-http-templates.md).
Portable calls require an explicit profile, capabilities, response extraction
and one attempt, without native retry/extract policies. Existing native
entrypoints are not automatically converted to that profile. Extra record
fields and their JSON types are preserved.

### Deferred SUT and validator work

Keep authoring the current variable syntax. The proposed explicit configuration
reference format and the separation from per-message rendering are deferred to
the upcoming SUT work in [#533](https://github.com/sepa79/PocketHive/issues/533).
The proposed `$var` nodes are not accepted by this release.

That issue also records unresolved validator edge cases: reference/variable
inference over ordinary data, numeric call IDs skipping portable-step checks,
and trailing request-template JSON accepted by authoring but rejected by the
runtime loader. Do not treat successful bundle authoring validation as proof
that these cases were repaired or that every dynamic message reference exists.

## 0.15.36 (from 0.15.35)

### HiveForge deployment inputs (from 0.15.34 or 0.15.35)

The `v0.15.36` component manifest requires four additional explicit inputs:
`POCKETHIVE_WORK_TYPE`, `POCKETHIVE_PUBLIC_INGRESS`, `POCKETHIVE_PUBLIC_HOST`
and `POCKETHIVE_ALLOW_REMOTE_HTTP`. The checked `v0.15.35` manifest does not
declare them; this is the 0.15.36 migration boundary, not a claim that all
0.15.35 installations already used this contract.

Use the [current installation requirements](HIVEFORGE.md#declared-runtime-requirements)
and [complete HTTPS/HTTP profile maps](HIVEFORGE.md#complete-runtime-configuration-examples)
to choose accepted values. WORK is explicitly RabbitMQ or Artemis; CONTROL
remains RabbitMQ. The public origin and Host define the shared Auth/MCP identity,
and remote HTTP requires an explicit allowance. Both actions need the same
complete profile configuration; `swarm-full` additionally needs its four
dedicated roots, while `swarm-reduced` does not.

If this upgrade is already complete, use the installation guide's read-only
verification steps. Do not redeploy merely to satisfy this historical note.

### SUT registry contract SSOT

Scenario Manager now reads and returns the shared `SutEnvironment` contract
directly. Remove `ui` blocks from `scenario-manager-service/sut/sut-environments.yaml`;
presentation hints are not part of the runtime SUT contract. Environment
`id`, `name`, optional `type`, endpoint map keys and endpoint values must be
non-blank. The `endpoints` object is required.

This is an intentional strict contract change. There is no parser fallback for
the former Scenario Manager DTO.

## 0.15.35 (from 0.15.34 and earlier)

### SUT endpoint identity

The key in a SUT environment's `endpoints` map is now the only endpoint
identifier. Nested endpoint `id` fields have been removed from the contract and
are rejected by canonical Scenario Manager bundle validation.

Before:

```yaml
id: wiremock-local
name: WireMock local
endpoints:
  default:
    id: default
    kind: HTTP
    baseUrl: http://wiremock:8080
```

After:

```yaml
id: wiremock-local
name: WireMock local
endpoints:
  default:
    kind: HTTP
    baseUrl: http://wiremock:8080
```

Run the repository migrator against one bundle or a directory of bundles:

```bash
npm install --prefix tools/scenario-config-migrate
node tools/scenario-config-migrate/cli.mjs check scenarios
node tools/scenario-config-migrate/cli.mjs migrate --dry-run scenarios
node tools/scenario-config-migrate/cli.mjs migrate scenarios
node tools/scenario-config-migrate/cli.mjs check scenarios
```

The migrator removes a nested endpoint `id` only when it matches the map key or
is null. If the nested value differs from the key, it reports
`SUT_ENDPOINT_ID_CONFLICT` and leaves the file unchanged. Resolve that conflict
by choosing the intended map key and deleting the nested field.

After migration, validate bundles through the official Scenario Manager
validation ingress before deployment. Do not add a parser fallback or retain
both endpoint identity shapes.
