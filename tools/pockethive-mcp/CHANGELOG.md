# PocketHive MCP Server Changelog

## Unreleased

## [0.15.22] - 2026-06-03

### Added
- MCP App evidence widgets now include a local light/dark mode toggle for both
  swarm evidence and workflow evidence reports.

### Changed
- `workflow_result.proof.validation` now separates latest validation,
  structural validation, and Scenario Manager dry-run validation so agents can
  distinguish bundle issues from runtime/auth validation gaps.
- `workflow_result.nextAction` now prefers resumable deploy/verify lifecycle
  tools and includes poll timing for running operations, steering agents away
  from long blocking sleeps on slow PocketHive stacks.
- WireMock authoring now preserves response body aliases and generates
  result-rule-compatible default mock bodies when success codes are configured.

### Fixed
- Username-derived local dev auth now refreshes once after PocketHive-owned API
  calls return `401`; explicit bearer tokens remain caller-owned.
- Scenario Manager dry-run and deploy `401` failures are now classified as
  `WORKFLOW_ENV_AUTH_FAILED` with empty patch scope, so agents inspect
  environment/auth state instead of patching generated bundles.
- Scenario Manager dry-run validation failures no longer erase a prior local
  structural validation pass in workflow evidence.
- Generated WireMock stubs for mutating calls preserve request body assertions
  while honoring explicit response bodies.
- Wizard validation now rejects explicit WireMock response bodies that conflict
  with configured result-code success rules.

## [0.15.21] - 2026-05-28

### Added
- Added `tapFlow` evidence and the `tap.flow` report claim so strict runtime
  proof can compare internal debug tap step flow with the scenario plan and
  WireMock request journal.
- Added developer quickstart documentation in `tools/pockethive-mcp/README.md`
  covering setup, canonical agent workflow, strict proof, live acceptance, and
  teardown expectations.
- Added private SUT template context support for `authProfiles.yaml`, allowing
  auth profiles to reference selected SUT endpoint data such as
  `{{ sut.endpoints['default'].baseUrl }}` while keeping the resolved SUT under
  `privateConfig.authProfile.sut` instead of public config/status evidence.

### Changed
- Workflow verification now treats `tap.flow` as a strict proof blocker when
  tap samples are requested but internal step flow is missing or disagrees with
  WireMock.
- `workflow_result.proof.traffic` now exposes interpreted tap-flow evidence for
  the agent fast path.
- Live workflow acceptance now creates unique live bundle/swarm ids and removes
  both the live swarm and uploaded Scenario Manager bundle in a teardown block
  after verification.

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
