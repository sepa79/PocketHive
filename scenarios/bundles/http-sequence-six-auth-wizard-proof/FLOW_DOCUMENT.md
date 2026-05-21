# http-sequence-six-auth-wizard-proof Flow

Intent: Create an HTTP sequence scenario proof with exactly six authenticated business requests using OAuth2 client credentials against local WireMock. The flow should perform start, profile, validate, session update, confirm, and receipt calls in order. It should include docs and mock endpoint definitions so the scenario can be proved locally through MCP.

## Runtime Contract Source

- Scenario shape follows `docs/scenarios/SCENARIO_CONTRACT.md` and `io.pockethive.scenarios.Scenario`.
- Worker fields follow Scenario Manager capability manifests from `/api/capabilities`.
- Runtime validation should use `bundle.validate` with `validator: scenario-manager-dry-run` when Scenario Manager is available.

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
- Traffic shape: smoke

## Evidence

- ClickHouse mode: no
- Grafana dashboard: none
- Objective: not set