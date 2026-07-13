# Configuration Model

## Status
`LIVING REFERENCE`

## Principles

1. Config lives in IDE settings — never in .env files
2. Auth is scoped to the environment. Each environment may declare its own
   optional PocketHive auth token so local and remote hives can differ cleanly.
3. Config is injected into the MCP server as environment variables at spawn time
4. Switching environment or bundles folder respawns the MCP server (~1s, transparent)
5. All config is structured and typed — no free-form key=value strings
6. Scenario bundles should live in a separate scenario-bundles repo. In-repo
   `scenarios/bundles` paths are legacy/example fallbacks, not the normal
   authoring location.
7. Checked-in MCP config must use relative paths so a clean checkout works on
   another developer machine. Resolve `.` from the PocketHive repo root and
   keep the normal bundles repo as `../pockethive-scenario-bundles`.

## VS Code settings schema

Stored in `settings.json` (global or workspace scope).

```jsonc
{
  // Named environment profiles
  "pockethive.environments": [
    {
      "name": "local",
      "baseUrl": "http://localhost:8088",
      "authToken": "",
      "rabbitUser": "guest",
      "tcpMockUrl": "",       // blank = auto-derive from baseUrl host
      "wiremockUrl": ""       // blank = auto-derive from baseUrl host
    },
    {
      "name": "nft-remote",
      "baseUrl": "http://nft-server:8088",
      "authToken": "${TOKEN_FOR_NFT_REMOTE}",
      "rabbitUser": "admin",
      "tcpMockUrl": "http://nft-server:8083",
      "wiremockUrl": "http://nft-server:8080"
    }
  ],

  // Which environment is active
  "pockethive.activeEnvironment": "local",

  // Registered bundle folder paths
  "pockethive.bundlesFolders": [
    "../pockethive-scenario-bundles",
    "../payment-scenarios"
  ],

  // Which bundles folder is active
  "pockethive.activeBundlesFolder": "../pockethive-scenario-bundles",

  // Path to PocketHive repo checkout (for offline validation)
  "pockethive.pockethiveRoot": ".",

  // MCP server transport
  "pockethive.mcpTransport": "stdio",   // "stdio" | "http"
  "pockethive.mcpHttpUrl": "",          // used when transport = "http"
  "pockethive.mcpServerPath": ""        // override path to server.mjs, blank = npm package
}
```

## IntelliJ settings schema

Stored in `pockethive.xml` via `PersistentStateComponent`.
Equivalent structure to VS Code settings.

```xml
<component name="PocketHiveSettings">
  <option name="environments">
    <list>
      <Environment name="local" baseUrl="http://localhost:8088" rabbitUser="guest" />
      <Environment name="nft-remote" baseUrl="http://nft-server:8088" rabbitUser="admin"
                   tcpMockUrl="http://nft-server:8083" wiremockUrl="http://nft-server:8080" />
    </list>
  </option>
  <option name="activeEnvironment" value="local" />
  <option name="bundlesFolders">
    <list>
      <option value="../pockethive-scenario-bundles" />
    </list>
  </option>
  <option name="activeBundlesFolder" value="../pockethive-scenario-bundles" />
  <option name="pockethiveRoot" value="." />
  <option name="mcpTransport" value="stdio" />
</component>
```

## Secrets and auth storage

RabbitMQ passwords use OS keychain APIs. PocketHive API auth is represented as
an optional per-environment `authToken` property for now, because PocketHive auth
is future functionality and the extension needs a stable active-environment
pass-through for MCP and direct API calls.

| Secret | Key pattern | VS Code API | IntelliJ API |
|---|---|---|---|
| RabbitMQ password | `ph.env.<name>.rabbitPass` | `context.secrets` | `PasswordSafe` |

### VS Code RabbitMQ secret

```typescript
// Store
await context.secrets.store(`ph.env.${envName}.rabbitPass`, password);

// Read
const password = await context.secrets.get(`ph.env.${envName}.rabbitPass`);

// Delete
await context.secrets.delete(`ph.env.${envName}.rabbitPass`);
```

### IntelliJ RabbitMQ secret

```kotlin
// Store
PasswordSafe.instance.setPassword(
    CredentialAttributes(ServiceNameProvider.generateServiceName("PocketHive", "$envName/rabbitPass")),
    password
)

// Read
val password = PasswordSafe.instance.getPassword(
    CredentialAttributes(ServiceNameProvider.generateServiceName("PocketHive", "$envName/rabbitPass"))
)
```

## Environment variables injected at MCP spawn

The IDE plugin constructs this map and passes it to the MCP server process:

| Variable | Source | Required |
|---|---|---|
| `POCKETHIVE_BASE_URL` | `environments[activeEnvironment].baseUrl` | Yes |
| `POCKETHIVE_AUTH_TOKEN` | `environments[activeEnvironment].authToken` | No |
| `POCKETHIVE_ROOT` | `pockethiveRoot` setting | For validation |
| `BUNDLES_ROOT` | `activeBundlesFolder` setting | For bundle tools; should be a separate scenario-bundles repo checkout |
| `RABBITMQ_DEFAULT_USER` | `environments[activeEnvironment].rabbitUser` | No (default: guest) |
| `RABBITMQ_DEFAULT_PASS` | keychain `ph.env.<name>.rabbitPass` | No (default: guest) |
| `TCP_MOCK_BASE_URL` | `environments[activeEnvironment].tcpMockUrl` | No (auto-derived) |
| `WIREMOCK_BASE_URL` | `environments[activeEnvironment].wiremockUrl` | No (auto-derived) |
| `PH_BUNDLES_ROOTS` | JSON encoded `bundlesFolders` list | For context tools |

## Auto-derivation of service URLs

When `tcpMockUrl` and `wiremockUrl` are blank, the MCP server derives them
from the `baseUrl` host:

```javascript
const baseHost = new URL(POCKETHIVE_BASE_URL).hostname;
const TCP_MOCK_URL  = process.env.TCP_MOCK_BASE_URL  || `http://${baseHost}:8083`;
const WIREMOCK_URL  = process.env.WIREMOCK_BASE_URL  || `http://${baseHost}:8080`;
```

This means for a standard local stack, only `baseUrl` needs to be set.

## Environment switching flow

```
1. User clicks "Use" on nft-remote in Settings tree view
   OR
   AI agent calls env.switch { name: "nft-remote" }

2. Plugin updates settings:
   pockethive.activeEnvironment = "nft-remote"

3. Plugin triggers MCP server restart:
   mcpManager.restart()
     -> kill existing child process (SIGTERM)
     -> wait for exit (max 2s, then SIGKILL)
     -> build new env map from updated settings
     -> spawn new child process
     -> reconnect MCP client (stdio pipes)
     -> run health.check
     -> update status bar: "PocketHive: nft-remote ●"

4. All tree views refresh with new environment data

Total time: ~1 second
```

## Bundles folder switching flow

```
1. User clicks "Use" on payment-scenarios in Settings tree view
   OR
   AI agent calls context.set-bundles-root { path: "/path/to/payment-scenarios" }

2. Plugin updates settings:
   pockethive.activeBundlesFolder = "/path/to/payment-scenarios"

3. Plugin triggers MCP server restart (same as env switch)

4. Scenario tree view refreshes showing bundles from new folder

Total time: ~1 second
```

## First-run experience

On first activation with no environments configured:

```
Status bar: [🐝 PocketHive: not configured ○]

Notification:
  "PocketHive: No environment configured.
   [Add Environment]  [Open Settings]"
```

Clicking "Add Environment" opens the Add Environment quick input flow:
1. Name (text input, e.g. "local")
2. Base URL (text input, e.g. "http://localhost:8088")
3. Auth token (password input, optional)
4. Test connection (inline health check)
5. Save

After saving, the MCP server starts automatically.

## Settings migration from bundles repo

Users migrating from the bundles repo `.env` approach:

| Old (.env) | New (plugin settings) |
|---|---|
| `POCKETHIVE_BASE_URL=http://...` | `pockethive.environments[0].baseUrl` |
| `POCKETHIVE_ROOT=/path/to/ph` | `pockethive.pockethiveRoot` |
| GitHub issue token | External GitHub MCP config, not PocketHive MCP |
| `.env.local`, `.env.nft-remote` | `pockethive.environments` array entries |
| `scripts/switch-env.sh local` | click "Use" in Settings tree view |
| Bundles repo root = BUNDLES_ROOT | `pockethive.activeBundlesFolder` pointing at the separate scenario-bundles repo |
