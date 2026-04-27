# Configuration Model

## Status
`IN PROGRESS`

## Principles

1. Config lives in IDE settings — never in .env files
2. Secrets live in OS keychain — never in settings.json or pockethive.xml
3. Config is injected into the MCP server as environment variables at spawn time
4. Switching environment or bundles folder respawns the MCP server (~1s, transparent)
5. All config is structured and typed — no free-form key=value strings

## VS Code settings schema

Stored in `settings.json` (global or workspace scope).

```jsonc
{
  // Named environment profiles
  "pockethive.environments": [
    {
      "name": "local",
      "baseUrl": "http://localhost:8088",
      "rabbitUser": "guest",
      "tcpMockUrl": "",       // blank = auto-derive from baseUrl host
      "wiremockUrl": ""       // blank = auto-derive from baseUrl host
    },
    {
      "name": "nft-remote",
      "baseUrl": "http://nft-server:8088",
      "rabbitUser": "admin",
      "tcpMockUrl": "http://nft-server:8083",
      "wiremockUrl": "http://nft-server:8080"
    }
  ],

  // Which environment is active
  "pockethive.activeEnvironment": "local",

  // Registered bundle folder paths
  "pockethive.bundlesFolders": [
    "/Users/tday/IdeaProjects/qa-nft-pockethive-bundles2",
    "/Private/projects/payment-scenarios"
  ],

  // Which bundles folder is active
  "pockethive.activeBundlesFolder": "/Users/tday/IdeaProjects/qa-nft-pockethive-bundles2",

  // Path to PocketHive repo checkout (for offline validation)
  "pockethive.pockethiveRoot": "/Private/projects/PocketHiveClean",

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
      <option value="/Users/tday/IdeaProjects/qa-nft-pockethive-bundles2" />
    </list>
  </option>
  <option name="activeBundlesFolder" value="/Users/tday/IdeaProjects/qa-nft-pockethive-bundles2" />
  <option name="pockethiveRoot" value="/Private/projects/PocketHiveClean" />
  <option name="mcpTransport" value="stdio" />
</component>
```

## Secrets storage

Secrets are NEVER stored in settings files. They use OS keychain APIs.

| Secret | Key pattern | VS Code API | IntelliJ API |
|---|---|---|---|
| Auth token | `ph.env.<name>.authToken` | `context.secrets` | `PasswordSafe` |
| RabbitMQ password | `ph.env.<name>.rabbitPass` | `context.secrets` | `PasswordSafe` |

### VS Code secrets

```typescript
// Store
await context.secrets.store(`ph.env.${envName}.authToken`, token);

// Read
const token = await context.secrets.get(`ph.env.${envName}.authToken`);

// Delete
await context.secrets.delete(`ph.env.${envName}.authToken`);
```

### IntelliJ secrets

```kotlin
// Store
PasswordSafe.instance.setPassword(
    CredentialAttributes(ServiceNameProvider.generateServiceName("PocketHive", "$envName/authToken")),
    token
)

// Read
val token = PasswordSafe.instance.getPassword(
    CredentialAttributes(ServiceNameProvider.generateServiceName("PocketHive", "$envName/authToken"))
)
```

## Environment variables injected at MCP spawn

The IDE plugin constructs this map and passes it to the MCP server process:

| Variable | Source | Required |
|---|---|---|
| `POCKETHIVE_BASE_URL` | `environments[activeEnvironment].baseUrl` | Yes |
| `POCKETHIVE_ROOT` | `pockethiveRoot` setting | For validation |
| `BUNDLES_ROOT` | `activeBundlesFolder` setting | For bundle tools |
| `RABBITMQ_DEFAULT_USER` | `environments[activeEnvironment].rabbitUser` | No (default: guest) |
| `RABBITMQ_DEFAULT_PASS` | keychain `ph.env.<name>.rabbitPass` | No (default: guest) |
| `GITHUB_TOKEN` | keychain `ph.env.<name>.authToken` | For GitHub tools |
| `GITHUB_REPO` | hardcoded `sepa79/PocketHive` | For GitHub tools |
| `TCP_MOCK_BASE_URL` | `environments[activeEnvironment].tcpMockUrl` | No (auto-derived) |
| `WIREMOCK_BASE_URL` | `environments[activeEnvironment].wiremockUrl` | No (auto-derived) |
| `PH_BUNDLES_ROOTS` | all `bundlesFolders` joined with `,` | For context tools |

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
| `GITHUB_TOKEN=ghp_...` | keychain via "Set token" action |
| `.env.local`, `.env.nft-remote` | `pockethive.environments` array entries |
| `scripts/switch-env.sh local` | click "Use" in Settings tree view |
| Bundles repo root = BUNDLES_ROOT | `pockethive.activeBundlesFolder` |
