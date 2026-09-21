# Explicit remote HTTP — implementation handoff

Branch: `feat/explicit-remote-http`, based on `2103a93a` from
`refactor/control-plane-critical-restart`. Deferred Orchestrator fixes are excluded.
This is implementation evidence for review, not a new transport contract or deployment approval.

## Result and owners

The [public endpoint transport contract](../architecture/AUTH_SERVICE_API_SPEC.md#public-endpoint-transport-policy)
owns the explicit exception. `POCKETHIVE_ALLOW_REMOTE_HTTP` defaults to `false`.
Auth Service and MCP bind it and delegate public-URL decisions to
`common/auth-contracts/.../PublicEndpointTransportPolicy.java`; both former
`secureOrLoopback` implementations are removed. Existing loopback HTTP remains.

The companion's `endpointSecurityPolicy.ts` owns supported modes and validation.
Profile creation, stored-profile decoding, webview command decoding and resource
metadata validation consume it. UI labels display the modes; they do not admit
connections. `REMOTE_HTTPS` remains the default, while saved `REMOTE_HTTP` is an
explicit independent client decision. IPv6 loopback is normalized for Node DNS
lookup. OAuth and MCP requests reject redirects instead of changing endpoints.
The companion release is 1.0.6.

Compose and HiveForge project the deployment allowance into both services.
Compose now exposes its public ingress and allowed hosts without editing YAML.
HiveForge requires an explicit allowance value in the component requirements.
Its deploy/update actions share `ansible/vars/public-endpoint.yml`; this also
fixes the missing public identity and secret inputs on the update action.
Use `true` for HTTP and `false` for HTTPS. The server default remains `false`.
Public issuer/resource URLs retain exact matching. Callback restrictions, PKCE,
scopes and token renewal/revocation remain unchanged. No dependencies were added.

## Verification performed

- Full Auth Service/MCP Maven reactor tests: **373 passed**, zero failures/errors/skips
  across swarm-model, auth-contracts, auth-client, auth-service and MCP.
- After the final MCP validation method rename, focused configuration tests and
  `RepositoryImportBoundaryTest` passed; no policy behavior changed after the full run.
- Remote HTTP MockMvc workflow: configured metadata, authorization/consent, PKCE
  token exchange, rotation and revocation passed. Wrong resource, missing PKCE
  and remote callback are rejected. These are in-process HTTP boundary checks.
- Companion **193 tests passed**; package command also passed assets, cutover and
  VSIX-content gates and produced `vscode-pockethive/pockethive-vscode-1.0.6.vsix`.
- A real in-process HTTP listener returned a redirect; the production MCP client
  rejected it and issued no request to the redirected endpoint.
- Headless Chromium loaded the production webview scripts with network blocked:
  HTTPS remained selected after entering an HTTP URL; explicit HTTP selection
  produced the exact `REMOTE_HTTP` connection command.
- Actual `docker compose config --format json` renders passed for default loopback,
  explicitly enabled remote HTTP and remote HTTPS. Selected environment fields
  were inspected without printing credentials.
- Jinja/YAML rendering and the actual HiveForge assertion expressions passed for
  both swarm profiles: enabled HTTP/HTTPS accepted, disabled HTTP and invalid
  allowance rejected. Ansible/HiveForge execution was not performed.
- `git diff --check` and local Markdown file-link checks passed. The remote base
  remained `2103a93a`; no Orchestrator or intake files changed.
- Repository-wide searches covered public endpoint checks, endpoint mode consumers,
  issuer/resource settings and deployment guards. No second Java transport policy
  remains. Runtime ownership is documented under `RESP-PUBLIC-ENDPOINT-TRANSPORT`.
- HiveForge follow-up: `python3 -B -m unittest discover -s deploy/hiveforge/tests -v`
  passed two tests covering 32 action/profile/transport cases; deploy and update
  render the same declared public identity, and missing/invalid allowances or
  disabled HTTP fail before rendering. `bash tools/hiveforge-contract-check.sh`
  passed. These are local render checks, not Ansible execution.

Commands/logs (local proof, not deployment evidence):

```text
./mvnw -pl auth-service,pockethive-mcp-service -am test
/tmp/pockethive-http-java-full.log
./mvnw -pl common/control-plane-core,pockethive-mcp-service -am -Dtest=RepositoryImportBoundaryTest,PocketHiveMcpPropertiesTest -Dsurefire.failIfNoSpecifiedTests=false test
/tmp/pockethive-http-boundary.log
npm --prefix vscode-pockethive run package
/tmp/pockethive-http-final-package.log
```

## Limits and remaining work

The root `npm test` run has **10 passing tests and one existing failure** in
`scripts/bootstrap-ssot.test.mjs`: HTTP Sequence still owns a local
`JacksonConfiguration.java`. Both the failing test and that class are unchanged
from `2103a93a`; this delivery does not repair the unrelated ownership debt.

No live remote deployment, native installed-extension sign-in or real-provider
OAuth qualification was performed. HTTP exposes credentials and tokens in transit;
other MCP clients may enforce HTTPS independently. Cancellation during metadata
discovery remains the separate pre-existing extension finding.

The changes require a separate review before integration. The user subsequently
requested a local commit; publication and live deployment remain outstanding.
No pushes were made.
