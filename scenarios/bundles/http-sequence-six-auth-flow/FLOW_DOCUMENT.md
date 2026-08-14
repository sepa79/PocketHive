# http-sequence-six-auth-flow Flow

Intent: Create an HTTP sequence proof with exactly six authenticated business requests using OAuth2 client credentials against local WireMock.

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
| 1 | start | POST | /api/six-auth/start | Start authenticated customer journey |
| 2 | profile | GET | /api/six-auth/profile | Read profile after auth |
| 3 | validate | POST | /api/six-auth/validate | Validate customer state |
| 4 | session-update | PUT | /api/six-auth/session | Update authenticated session |
| 5 | confirm | POST | /api/six-auth/confirm | Confirm authenticated operation |
| 6 | receipt | GET | /api/six-auth/receipt | Fetch final receipt |

## Data And Traffic

- Data source: SCHEDULER
- Default profile: 1 rps for 20s
- NFT profile: 1 rps for 20s
- Effective runtime: `inputs.scheduler.maxMessages: 1` caps the generator at
  one seed, which expands into the six calls above. Profile duration is metadata
  here and does not imply continued seed dispatch for 20 seconds.
- Traffic shape: smoke

## Evidence

- Requested ClickHouse plan metadata: `yes_for_nft_only`
- Requested Grafana dashboard metadata: `rtt_overview`
- Runtime transaction-outcome sink: `NONE`. The postprocessor therefore does
  not persist transaction outcomes to ClickHouse; the two requested values
  above are not runtime evidence or qualification.
- Objective: not set
