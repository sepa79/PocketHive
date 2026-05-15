# PocketHive MCP POC Runbook

## Status
`POC READY`

This runbook proves the current POC path end to end:

1. Start the MCP server through a real MCP client.
2. Create a bundle through `wizard.start` -> `wizard.summary` -> `wizard.complete`.
3. Validate the generated bundle with `bundle.check`.
4. Start the same server in Streamable HTTP mode.
5. Confirm the read-only evidence widget resource is discoverable and uses
   `text/html;profile=mcp-app`.
6. Optionally deploy/run against a live PocketHive stack and call
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
✓ bundle.check ok
✓ widget resource listed ui://pockethive/evidence-summary-v1.html
✓ HTTP/App resource smoke ui://pockethive/evidence-summary-v1.html
POC runner completed.
```

This proves the local authoring loop and App-resource packaging without needing
RabbitMQ, Scenario Manager, Orchestrator, Prometheus, or mock servers.

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
6. `evidence.summary`

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
  -> bundle.check
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
# Local structural validation only
npm run poc

# Against a running Scenario Manager, use the MCP tool:
bundle.validate { "bundle": "<bundleId>", "validator": "scenario-manager-upload" }
```

`scenario-manager-upload` has Scenario Manager write side effects because the
available Scenario Manager validation path is the bundle upload/replace API.
