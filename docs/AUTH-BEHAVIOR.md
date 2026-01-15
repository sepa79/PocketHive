# Auth System Behavior

## Global Enable/Disable

Auth is **globally enabled by default** across all workers in a PocketHive deployment.

### Key Characteristics

1. **Global Scope**: Auth cannot be enabled/disabled per-worker or per-swarm
2. **Dormant by Default**: When enabled, auth has zero overhead until actually used
3. **Usage-Triggered**: Auth activates when:
   - Templates contain `auth:` blocks, OR
   - Worker config contains `auth:` section
4. **Shared Token Store**: All workers share the same in-memory token cache

## How It Works

### Scenario 1: Worker Without Auth

```yaml
# Worker config - no auth specified
- role: generator
  config:
    worker:
      message:
        body: '{"data": "test"}'
```

**Behavior:**
- Auth beans exist but are never called
- No tokens fetched or cached
- Token refresh scheduler runs but finds no tokens (no-op)
- Zero performance impact

### Scenario 2: Worker With Auth in Template

```yaml
# Template with auth block
auth:
  type: oauth2-client-credentials
  tokenKey: api:auth
  tokenUrl: https://auth.example.com/oauth/token
  clientId: my-client
  clientSecret: my-secret
```

**Behavior:**
- Request builder calls `AuthHeaderGenerator.generate()`
- Token fetched and cached under `api:auth` key
- Token refresh scheduler detects token and schedules refresh
- Auth headers added to requests

### Scenario 3: Worker With Auth in Config

```yaml
# Worker config with auth section
- role: generator
  config:
    worker:
      auth:
        - tokenKey: "api:auth"
          type: oauth2-client-credentials
          tokenUrl: https://api.example.com/oauth/token
          clientId: api-client
          clientSecret: api-secret
      message:
        headers:
          x-ph-auth-token-key: "api:auth"
```

**Behavior:**
- Auth config registered in `AuthConfigRegistry`
- Generator sets `x-ph-auth-token-key` header
- Request builder looks up config by tokenKey
- Token fetched, cached, and refreshed
- Auth headers added to requests

### Scenario 4: Mixed Workers in Same Swarm

```yaml
# Swarm with 2 workers
- role: generator      # No auth
- role: request-builder  # Uses auth in templates
```

**Behavior:**
- Generator: Auth beans exist but unused (no overhead)
- Request Builder: Auth active, tokens cached and refreshed
- Both workers share the same token store

## Configuration

### Default (Recommended)

```yaml
# No configuration needed - auth is enabled globally
```

### Explicit Enable

```yaml
pockethive:
  auth:
    enabled: true  # default
    scheduler:
      enabled: true  # default
```

### Global Disable

```yaml
pockethive:
  auth:
    enabled: false  # disables auth for ALL workers
```

**Warning:** Setting `enabled: false` disables auth globally. Workers with `auth:` blocks in templates will fail.

## Token Lifecycle

### 1. First Request
- Worker calls `AuthHeaderGenerator.generate()`
- Token fetched synchronously (blocks first request)
- Token stored in `InMemoryTokenStore` with key `tokenKey`
- Subsequent requests use cached token

### 2. Background Refresh
- `TokenRefreshScheduler` scans token store every 10 seconds (default)
- Tokens within refresh window (60s before expiry) are refreshed
- Emergency refresh (10s before expiry) if background refresh missed

### 3. Token Expiry
- Expired tokens are removed from cache
- Next request triggers synchronous fetch (back to step 1)

## Performance Impact

### Workers Without Auth
- **Memory**: ~100KB for auth beans (negligible)
- **CPU**: Token refresh scheduler runs but exits immediately (no tokens)
- **Network**: Zero (no auth calls made)

### Workers With Auth
- **Memory**: ~1KB per cached token
- **CPU**: Background refresh every 10s per token
- **Network**: Token fetch on first use + periodic refresh

## FAQ

### Can I disable auth for specific workers?

No. Auth is global. However, workers without `auth:` blocks in templates have zero overhead.

### Can I disable auth for specific swarms?

No. Auth is deployment-wide. To disable auth, set `pockethive.auth.enabled=false` globally.

### What happens if I disable auth but templates use it?

Workers will fail when trying to generate auth headers. Remove `auth:` blocks from templates or re-enable auth.

### Do all workers share the same tokens?

Yes. The `InMemoryTokenStore` is shared across all workers in the same JVM. Different worker instances (different containers) have separate token stores.

### Can I use different auth configs in the same swarm?

Yes! Use different `tokenKey` values:

```yaml
# Template 1
auth:
  tokenKey: payments:auth
  type: oauth2-client-credentials
  ...

# Template 2
auth:
  tokenKey: cards:auth
  type: oauth2-client-credentials
  ...
```

Each `tokenKey` maintains its own token and refresh schedule.
