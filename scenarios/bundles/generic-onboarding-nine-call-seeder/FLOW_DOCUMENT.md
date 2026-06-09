# generic-onboarding-nine-call-seeder Flow

Intent: Create a generic onboarding data seeder HTTP sequence bundle that proves a tenant batch flow and per-user onboarding flow with conditional validation polling. The proof should execute 9 outbound HTTP calls in total for one user: open batch, create profile/start validation, poll validation pending, poll validation approved, create account, initial funding/setup, provision resource, enable optional capability, and close batch. Activation is deferred as a follow-on process. This is a data seeding capability, not a performance test.

## Runtime Contract Source

- Scenario shape follows `docs/scenarios/SCENARIO_CONTRACT.md` and `io.pockethive.scenarios.Scenario`.
- Worker fields follow Scenario Manager capability manifests from `/api/capabilities`.
- Runtime validation should use `bundle.validate` with `validator: scenario-manager-dry-run` when Scenario Manager is available.

## Target

- SUT: wiremock-local
- Endpoint key: default
- Base URL: http://wiremock:8080

## Endpoints

The configured sequence has 8 business steps. The validation step is expected
to make 2 HTTP attempts in the proof run because it retries while JSON
`validationStatus` is `PENDING`, giving 9 outbound HTTP calls total.

| # | callId | Method | Path | Notes |
|---|---|---|---|---|
| 1 | open-batch | POST | /api/onboarding/batches/open | Open tenant-level onboarding batch/order. |
| 2 | create-profile-validation | POST | /api/onboarding/users/profile-validation | Create user profile and start validation/screening. |
| 3 | poll-validation-result | POST | /api/onboarding/users/validation-result | Poll validation result; retry while JSON validationStatus is PENDING, continue on APPROVED, stop on REJECTED/TIMEOUT or terminal non-2xx. |
| 4 | create-account | POST | /api/onboarding/accounts/create | Create the core account after validation approval. |
| 5 | initial-funding-setup | POST | /api/onboarding/accounts/setup | Perform funding/setup for the account. |
| 6 | provision-resource | POST | /api/onboarding/resources/provision | Provision the associated resource for the onboarded user. |
| 7 | enable-additional-capability | POST | /api/onboarding/features/enable | Enable optional additional capability/integration. |
| 8 | close-batch | POST | /api/onboarding/batches/close | Close tenant batch and persist reusable seeding artefact references; activation remains deferred. |

## Data And Traffic

- Data source: SCHEDULER
- Default profile: finite tenant run
- Scheduler rate: 500 user journeys per second
- Scheduler max messages: `{{ vars.userCount }}`
- HTTP sequence concurrency: 64 in-flight journeys per sequence bee
- Tenant profiles: tenant-001 through tenant-010
- Users per tenant: 10000
- Output lists: `ph:onboarding:approved-active:tenant-001` through
  `ph:onboarding:approved-active:tenant-010`
- Traffic shape: data seeding

Each tenant profile provides the tenant id, batch reference, user count, country,
optional capability flag, seeder run id, and Redis output list. The generator
uses the scheduler sequence header to create deterministic user references such
as `tenant-001-user-1`.

## Evidence

- ClickHouse mode: no
- Grafana dashboard: pipeline_observability
- Objective: Data seeding proof, not a performance test: generate reusable onboarding dataset rows through the tenant batch and user onboarding lifecycle, proving ordered calls, validation polling via JSON validationStatus retry to approval, extracted identifier propagation, Redis persistence, mock matching, queue drain, and runtime evidence.

The scenario persists only successful terminal journey results. The expected
scale proof result is a Redis list length increase of 10000 for each tenant
output list when the ten profiles are run.
