# PocketHive MCP POC Runbook

## Status
`POC COMPLETED / ARCHIVED`

This runbook proves the current POC path end to end:

1. Start the MCP server through a real MCP client.
2. Create a bundle through `wizard.start` -> `wizard.summary` -> `wizard.complete`.
3. Run generated artifact checks from `wizard.complete`.
4. Start the same server in Streamable HTTP mode.
5. Confirm the read-only evidence widget resource is discoverable and uses
   `text/html;profile=mcp-app`.
6. Confirm real-time control tools are exposed:
   `component.config-preview` and `component.config-update`.
7. Optionally deploy/run against a live PocketHive stack and call
   `evidence.summary`.

## Offline POC

The offline POC does not require a running PocketHive stack. It writes a
generated demo bundle under a temporary bundles root.

```bash
cd tools/pockethive-mcp
npm ci
npm run poc
```

Expected result:

```text
PocketHive MCP POC runner
✓ MCP context ...
✓ wizard.start ...
✓ wizard.summary rest-rbuilder
✓ wizard.complete ...
✓ bundle files scenario.yaml + templates/http/default/onboarding.yaml + docs/mock artifacts
✓ bundle generation sanity ok
✓ widget resource listed ui://pockethive/evidence-summary-v1.html
✓ real-time control tools listed component.config-preview + component.config-update
✓ HTTP/App resource smoke ui://pockethive/evidence-summary-v1.html
POC runner completed.
```

This proves the local authoring loop and App-resource packaging without needing
RabbitMQ, Scenario Manager, Orchestrator, Grafana/ClickHouse metrics, or mock
servers.

## Wizard Acceptance Suite

The wizard acceptance suite is the maturity gate for bundle authoring. It runs
through multiple novice intents and proves that the generated bundles are
structurally valid without requiring a live PocketHive stack.

```bash
cd tools/pockethive-mcp
npm run acceptance:wizard
```

Covered flows:

- REST + WireMock + result rules
- HTTP sequence + external SUT + OAuth client credentials
- TCP + TCP mock + mTLS auth reference
- Redis dataset backed REST loop
- Invalid input guard for incompatible protocol/target choices

Each accepted bundle must pass `wizard.start`, `wizard.summary`,
`wizard.complete`, generated artifact checks, and Scenario Manager validation
through `bundle.validate`.

## Live Stack POC

Use this only when the local or remote PocketHive stack is already running. MCP
does not start Docker or rebuild services.

```bash
cd tools/pockethive-mcp
POCKETHIVE_BASE_URL=http://localhost:8088 npm run poc:live
```

The live path continues after the offline checks:

1. `health.check`
2. `scenario.deploy`
3. `swarm.create`
4. `swarm.wait-ready`
5. `swarm.start`
6. `component.config-preview`
7. `component.config-update`
8. `evidence.summary`

Use `component.config-preview` before `component.config-update` for live tuning
changes such as rate updates. The preview reads the current component config
from Orchestrator journal/status evidence or the live control-plane status
stream, deep-merges the requested patch, and shows the planned merged config
shape without publishing a control-plane update.

When the local stack has auth enabled, run with either `POCKETHIVE_AUTH_TOKEN`
or `POCKETHIVE_AUTH_USERNAME` available to the spawned MCP process. For example:

```bash
POCKETHIVE_AUTH_USERNAME=local-admin npm run poc:live
```

The runner intentionally does not remove the swarm afterwards. Use the normal
PocketHive lifecycle tools or UI to stop/remove it when the demo is finished.

## Useful Overrides

| Variable | Default | Purpose |
|---|---|---|
| `PH_POC_BUNDLES_ROOT` | temporary directory | Where the generated bundle is written |
| `PH_POC_BUNDLE_ID` | timestamped `poc-onboarding-*` | Demo bundle id |
| `PH_POC_HTTP_PORT` | random free port | HTTP MCP smoke-test port |
| `POCKETHIVE_BASE_URL` | `http://localhost:8088` | PocketHive reverse proxy base URL |
| `PH_POC_LIVE` | unset | Set to `1` to run live stack steps |
| `PH_POC_SWARM_ID` | `<bundleId>-swarm` | Live-stack swarm id |
| `PH_POC_READY_TIMEOUT_SEC` | `45` | Ready wait timeout |

## POC Completeness

This is enough for a POC because it proves the architecture is joined up:

```text
wizard.* tools
  -> generated bundle files
  -> generation sanity checks
  -> Streamable HTTP MCP endpoint
  -> App-capable evidence widget resource
  -> optional live evidence.summary
```

The wizard POC now generates the main authoring artifacts expected by the
current Scenario Manager contract: `scenario.yaml`, protocol-scoped templates,
`variables.yaml`, bundle-local `sut.yaml`, optional auth profiles, dataset/mock
artifacts, and generated README/FLOW/CHANGELOG files.

Runtime contract validation is explicit:

```bash
# Local generation sanity only
npm run poc

# Wizard maturity acceptance suite
npm run acceptance:wizard

# Pure regression tests for real-time config merge behavior
npm test --prefix tools/pockethive-mcp

# Against a running Scenario Manager, use the MCP tool:
bundle.validate { "bundle": "<bundleId>", "validator": "scenario-manager-upload" }
```

`scenario-manager-upload` has Scenario Manager write side effects because the
available Scenario Manager validation path is the bundle upload/replace API.
