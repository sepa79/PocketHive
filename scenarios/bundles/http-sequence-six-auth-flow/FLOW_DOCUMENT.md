# http-sequence-six-auth-flow Flow

Intent: Create an HTTP sequence proof with exactly six authenticated business requests using OAuth2 client credentials against local WireMock.

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
- Traffic shape: smoke

## Evidence

- ClickHouse mode: yes_for_nft_only
- Grafana dashboard: rtt_overview
- Objective: not set