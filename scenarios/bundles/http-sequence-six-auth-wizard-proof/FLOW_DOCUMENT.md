# http-sequence-six-auth-wizard-proof Flow

Intent: Create an HTTP sequence scenario proof with exactly six authenticated business requests using OAuth2 client credentials against local WireMock. The flow should perform start, profile, validate, session update, confirm, and receipt calls in order. It should include docs and mock endpoint definitions so the scenario can be proved locally through MCP.

## Runtime Contract Source

- In a complete source checkout, scenario shape follows
  `docs/scenarios/SCENARIO_CONTRACT.md` and `io.pockethive.scenarios.Scenario`.
  The current deployment archive omits that contract and remains blocked by its
  artifact audit; do not treat this raw path as a package-local reference.
- Worker fields follow Scenario Manager capability manifests through the
  official ingress route `/scenario-manager/api/capabilities?all=true`; do not
  replace it with the backend-only `/api/capabilities` path.
- When Scenario Manager is available, submit runtime validation with
  `bundle_validate` and `validator: scenario-manager-dry-run`, then poll
  `bundle_validate_result` with the returned `jobId` until terminal `done` or
  `error`; submission alone is not a validation result. Dotted tool names
  require the non-default legacy MCP name mode.
- Direct `scenario_deploy` and UI upload/Create do not load the seven files in
  `mock-config/wiremock`. A future candidate that passes Connectivity must use
  `workflow_deploy_start`, then `workflow_deploy_resume` through terminal
  completion, and require `evidence.mockConfig.wiremock.loaded == 7` before
  Start. Explicitly loading all seven supported mappings is the only
  equivalent; direct deploy/Create is insufficient.

## Target

- SUT: wiremock-local
- Endpoint key: default
- Base URL: http://wiremock:8080

## Endpoints

| # | callId | Method | Path | Notes |
|---|---|---|---|---|
| 1 | start | POST | /api/wizard-proof/start | Start authenticated journey |
| 2 | profile | GET | /api/wizard-proof/profile | Read authenticated profile |
| 3 | validate | POST | /api/wizard-proof/validate | Validate authenticated state |
| 4 | session-update | PUT | /api/wizard-proof/session | Update authenticated session |
| 5 | confirm | POST | /api/wizard-proof/confirm | Confirm authenticated operation |
| 6 | receipt | GET | /api/wizard-proof/receipt | Fetch authenticated receipt |

## Data And Traffic

- Data source: SCHEDULER
- Default profile: 1 rps for 10s
- NFT profile: 1 rps for 10s
- Effective runtime: `inputs.scheduler.maxMessages: 1` caps the generator at
  one seed, which expands into the six calls above. Profile duration is metadata
  here and does not imply continued seed dispatch for 10 seconds.
- Traffic shape: smoke

## Evidence

- ClickHouse mode: no
- Grafana dashboard: none
- Runtime transaction-outcome sink: `NONE`; this bundle does not persist
  transaction outcomes to ClickHouse. These values are not runtime evidence or
  qualification.
- Objective: not set
