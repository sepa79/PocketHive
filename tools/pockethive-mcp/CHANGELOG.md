# PocketHive MCP Server Changelog

## [0.15.21] - 2026-05-28

### Added
- Added `tapFlow` evidence and the `tap.flow` report claim so strict runtime
  proof can compare internal debug tap step flow with the scenario plan and
  WireMock request journal.
- Added developer quickstart documentation in `tools/pockethive-mcp/README.md`
  covering setup, canonical agent workflow, strict proof, live acceptance, and
  teardown expectations.

### Changed
- Workflow verification now treats `tap.flow` as a strict proof blocker when
  tap samples are requested but internal step flow is missing or disagrees with
  WireMock.
- `workflow_result.proof.traffic` now exposes interpreted tap-flow evidence for
  the agent fast path.
- Live workflow acceptance now creates unique live bundle/swarm ids and removes
  the live swarm in a teardown block after verification.

### Fixed
- Fixed MCP tap-flow interpretation for real postprocessor tap payloads where
  HTTP request/result data is nested inside each step's JSON `payload` string.
- Fixed repeat live acceptance failures caused by reusing
  `accept-workflow-live-swarm`.

### Verified
- `npm test`
- `npm run acceptance:agentic`
- `npm run acceptance:workflow`
- `PH_WORKFLOW_ACCEPTANCE_LIVE=1 POCKETHIVE_BASE_URL=http://localhost:8088 POCKETHIVE_AUTH_USERNAME=local-admin npm run acceptance:workflow:live`
