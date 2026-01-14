# PocketHive Template-Based Authorization System

**Version:** 1.0  
**Date:** 2024  
**Status:** Final  
**Owner:** PocketHive Core Team

---

## 1. Executive Summary

This document specifies a **template-based authorization system** for PocketHive's HTTP Builder service that:
- Declares auth configuration in HTTP templates via an `auth` section
- Auto-generates authorization headers without manual SpEL expressions
- Proactively refreshes tokens **before expiry** to prevent processor delays
- Uses Redis for shared token state and background refresh scheduling
- Supports all major authorization types (Postman parity)
- Manages token lifecycle aligned with swarm lifecycle

**Key Innovation:** Background token refresh ensures the processor **never waits** for auth.

**Swarm Isolation:** Uses unique swarm instance UUID (not name) to prevent conflicts when swarms are deleted and recreated.

**No Core Component Changes:** HTTP Builder self-manages lifecycle using Worker SDK control-plane integration and Redis state.

---

## 2. Architectural Decisions

### 2.1 Swarm Instance UUID for Isolation

**Problem:** Deleting swarm "load-test" and creating new swarm "load-test" causes auth data conflicts.

**Solution:** Use `swarmInstanceId` (UUID) instead of `swarmId` (name) for Redis keys.

```
Old: phauth:load-test:token:oauth2
New: phauth:550e8400-e29b-41d4-a716-446655440000:token:oauth2
```

**Implementation:** Swarm Controller already generates unique instance ID per swarm. Worker SDK exposes it via `context.info().swarmInstanceId()`.

### 2.2 Self-Registration (No Core Changes)

**Problem:** Need to track active swarms without modifying Swarm Controller.

**Solution:** HTTP Builder self-registers on first message:
```java
// First WorkItem received
redis.opsForHash().put("phauth:instances", swarmInstanceId, 
    json({"swarmId": "load-test", "registeredAt": now(), "lastActivity": now()})
);
```

### 2.3 Control-Plane Cleanup (No Orchestrator API)

**Problem:** Need to cleanup auth data on swarm removal without adding Orchestrator API.

**Solution:** Listen to `sig.swarm-remove` via Worker SDK:
```java
@EventListener
public void onControlPlaneSignal(ControlPlaneSignalEvent event) {
    if ("swarm-remove".equals(event.signal())) {
        cleanupSwarmAuth(context.info().swarmInstanceId());
    }
}
```

### 2.4 Two-Phase Variable Resolution

**Problem:** Template load happens at startup (no WorkItem), but `${header:NAME}` needs per-request data.

**Solution:**
- **Phase 1 (Startup):** Resolve `${env:*}`, `${file:*}`, `${redis:*}`
- **Phase 2 (Request):** Resolve `${header:*}` from WorkItem

### 2.5 Emergency Refresh via Token Deletion

**Problem:** Processor receives 401, token expired, but HTTP Builder doesn't know.

**Solution:** Processor deletes token from Redis on 401:
```java
// Processor detects 401
redis.delete("phauth:" + instanceId + ":token:" + tokenKey);
// Retry → HTTP Builder refreshes sync
```

### 2.6 Low-Cardinality Metrics

**Problem:** `ph_auth_refresh_total{swarm_id, token_key}` creates 1000+ unique label combinations.

**Solution:** Remove high-cardinality labels, use logs for debugging:
```
ph_auth_refresh_total{strategy, status}  # Low cardinality
```

---

## 3. Core Requirements

### 2.1 Zero-Delay Guarantee

**Requirement:** The processor MUST NEVER be delayed by token refresh.

**Solution:** Proactive token refresh using a background scheduler:

```
Token Lifecycle:
├─ Token acquired (t=0, expires at t=3600)
├─ Refresh scheduled (t=3540, 60s before expiry)
├─ Background refresh (t=3540-3550, async)
├─ New token stored (t=3550, expires at t=7150)
└─ Processor uses cached token (no delay)
```

**Guarantees:**
1. Tokens are refreshed **before** expiry (configurable buffer, default 60s)
2. Refresh happens **asynchronously** in background thread
3. Processor reads from Redis cache (< 5ms latency)
4. If refresh fails, retry with exponential backoff
5. If all retries fail, emit alert but continue with cached token until hard expiry

---

## 3. Architecture

### 3.1 Component Overview

```
┌─────────────────────────────────────────────────────────────┐
│                    HTTP Builder Service                      │
├─────────────────────────────────────────────────────────────┤
│                                                               │
│  ┌──────────────┐      ┌──────────────────────────────┐    │
│  │   Worker     │      │   Auth Strategy Registry     │    │
│  │  (onMessage) │─────▶│  - OAuth2                    │    │
│  └──────────────┘      │  - AWS Signature             │    │
│         │              │  - HTTP Signature            │    │
│         │              │  - Basic/Bearer/API Key      │    │
│         ▼              └──────────────────────────────┘    │
│  ┌──────────────┐                    │                      │
│  │  Template    │                    │                      │
│  │  Loader      │                    ▼                      │
│  └──────────────┘      ┌──────────────────────────────┐    │
│         │              │   Redis Token Store          │    │
│         │              │  - Get token (cached)        │    │
│         │              │  - Store token with TTL      │    │
│         │              │  - Pub/sub for updates       │    │
│         │              └──────────────────────────────┘    │
│         │                             │                     │
│         ▼                             │                     │
│  ┌──────────────────────────────────┐│                     │
│  │  Auth Header Generator           ││                     │
│  │  1. Get swarmId from WorkerContext                      │
│  │  2. Check Redis for token        ││                     │
│  │  3. If missing, refresh sync     ││                     │
│  │  4. Generate auth headers        ││                     │
│  │  5. Merge with template headers  ││                     │
│  │  6. Publish metrics via StatusPublisher                 │
│  └──────────────────────────────────┘│                     │
│                                       │                     │
└───────────────────────────────────────┼─────────────────────┘
                                        │
                    ┌───────────────────▼───────────────────┐
                    │  Background Token Refresh Scheduler   │
                    │  (Separate thread pool)               │
                    ├───────────────────────────────────────┤
                    │  1. Scan Redis for active swarms     │
                    │  2. Find tokens expiring soon        │
                    │  3. Refresh asynchronously           │
                    │  4. Update Redis atomically          │
                    │  5. Publish update event             │
                    └───────────────────────────────────────┘
```

### 3.2 Swarm Isolation Strategy

**Problem:** Prevent auth data conflicts when swarms are deleted and recreated with the same name.

**Solution:** Use **swarm instance UUID** instead of swarm name:

```
Key Pattern: phauth:<swarmInstanceId>:<suffix>

Example:
  Swarm name: "load-test"
  Instance 1: phauth:550e8400-e29b-41d4-a716-446655440000:token:oauth2
  Instance 2: phauth:7c9e6679-7425-40de-944b-e07fc1f90ae7:token:oauth2
```

**Benefits:**
- Each swarm instance has unique auth namespace
- Deleting swarm doesn't affect new swarm with same name
- No race conditions during delete/create cycles
- Automatic cleanup when swarm is removed

**Implementation:** Swarm Controller injects `POCKETHIVE_CONTROL_PLANE_SWARM_INSTANCE_ID` (UUID) alongside `POCKETHIVE_CONTROL_PLANE_SWARM_ID` (name). HTTP Builder uses instance ID for Redis keys, name for display/metrics.

### 3.2 Token Refresh Flow (Proactive)

```
Time: t=0
┌─────────────────────────────────────────────────────────────┐
│ First Request (Token Missing)                               │
├─────────────────────────────────────────────────────────────┤
│ 1. HTTP Builder receives WorkItem                           │
│ 2. Check Redis: phauth:swarm-alpha:demo-oauth2 → NOT FOUND │
│ 3. Synchronous refresh (blocks first request only)         │
│ 4. Store in Redis with metadata                            │
│ 5. Schedule background refresh at t=3540                    │
│ 6. Generate Authorization header                            │
│ 7. Continue processing (total delay: ~200ms)               │
└─────────────────────────────────────────────────────────────┘

Time: t=1 to t=3539
┌─────────────────────────────────────────────────────────────┐
│ Subsequent Requests (Token Cached)                          │
├─────────────────────────────────────────────────────────────┤
│ 1. HTTP Builder receives WorkItem                           │
│ 2. Check Redis: phauth:swarm-alpha:demo-oauth2 → FOUND     │
│ 3. Token valid (expiresAt=3600, now=1000)                  │
│ 4. Generate Authorization header (< 5ms)                    │
│ 5. Continue processing (NO DELAY)                           │
└─────────────────────────────────────────────────────────────┘

Time: t=3540 (Background Thread)
┌─────────────────────────────────────────────────────────────┐
│ Proactive Token Refresh (Async)                             │
├─────────────────────────────────────────────────────────────┤
│ 1. Scheduler wakes up (every 10s scan)                      │
│ 2. Query Redis: ZRANGEBYSCORE phauth:swarm-alpha:schedule  │
│ 3. Acquire lock: phauth:swarm-alpha:lock:demo-oauth2       │
│ 4. Refresh token (OAuth2 POST to token endpoint)           │
│ 5. Update Redis atomically                                  │
│ 6. Update schedule                                           │
│ 7. Publish event                                             │
│ 8. Release lock                                              │
│ 9. Emit metric: ph_auth_refresh_duration_seconds           │
└─────────────────────────────────────────────────────────────┘
```

---

## 4. HTTP Template Schema

### 4.1 Template Structure

```yaml
serviceId: payments
callId: AuthorizePayment
method: POST
pathTemplate: /api/payments
auth:
  type: oauth2-client-credentials
  tokenUrl: https://auth.example.com/oauth/token
  clientId: ${header:x-ph-client-id}
  clientSecret: ${env:CLIENT_SECRET}
  scope: api.read api.write
headersTemplate:
  Content-Type: application/json
  X-Idempotency-Key: "{{ eval(\"#uuid()\") }}"
bodyTemplate: "{{ payload }}"
```

**JSON equivalent:**
```json
{
  "serviceId": "payments",
  "callId": "AuthorizePayment",
  "method": "POST",
  "pathTemplate": "/api/payments",
  "auth": {
    "type": "oauth2-client-credentials",
    "tokenUrl": "https://auth.example.com/oauth/token",
    "clientId": "${header:x-ph-client-id}",
    "clientSecret": "${env:CLIENT_SECRET}",
    "scope": "api.read api.write"
  },
  "headersTemplate": {
    "Content-Type": "application/json",
    "X-Idempotency-Key": "{{ eval(\"#uuid()\") }}"
  },
  "bodyTemplate": "{{ payload }}"
}
```

### 4.2 Auth Section Schema

#### Auth Type Convention

YAML uses lowercase-with-hyphens (preferred); Java uses SCREAMING_SNAKE_CASE enum:

```java
public enum AuthType {
    OAUTH2_CLIENT_CREDENTIALS,
    OAUTH2_HTTP_SIGNATURE,
    BEARER_TOKEN,
    BASIC_AUTH,
    API_KEY,
    HTTP_SIGNATURE,
    AWS_SIGNATURE_V4,
    DIGEST_AUTH,
    OAUTH1,
    SESSION_BASED,
    NONE;
    
    public static AuthType parse(String value) {
        return valueOf(value.toUpperCase().replace('-', '_'));
    }
}
```

Both formats accepted:
- `oauth2-client-credentials` → `OAUTH2_CLIENT_CREDENTIALS` ✓ (preferred)
- `OAUTH2_CLIENT_CREDENTIALS` → `OAUTH2_CLIENT_CREDENTIALS` ✓
- `OAuth2-Client-Credentials` → `OAUTH2_CLIENT_CREDENTIALS` ✓

#### Simple Syntax (String)

For common auth types, use simple string syntax:

```yaml
auth: Bearer ${env:API_TOKEN}
```

```yaml
auth: Basic admin:${env:PASSWORD}
```

```yaml
auth: ApiKey X-API-Key:${env:API_KEY}
```

#### Complex Syntax (Object)

For advanced auth types, use object syntax:

```yaml
auth:
  type: string                    # Required: Auth strategy type (lowercase-with-hyphens)
  # Type-specific fields (flattened, no nested config)
  # OAuth2 fields:
  tokenUrl: string
  clientId: string
  clientSecret: string
  scope: string
  # HTTP Signature fields:
  keyId: string
  algorithm: string
  privateKey: string
  headers: array
  # Common options:
  refreshBuffer: integer          # Optional: Seconds before expiry (default: 60)
  emergencyRefreshBuffer: integer # Optional: Emergency threshold (default: 10)
```

### 4.3 Variable Syntax

Supports multiple variable sources with **two-phase resolution**:

**Phase 1 (Template Load):** Resolve static sources
```
${env:VAR_NAME}           - Environment variable
${file:/path/to/file}     - Read from file
${redis:KEY}              - Read from Redis
plain-text                - Literal value
```

**Phase 2 (Request Time):** Resolve dynamic sources
```
${header:HEADER_NAME}     - From WorkItem headers
```

**Examples:**
```yaml
# Resolved at template load (startup)
clientId: ${env:CLIENT_ID}
privateKey: ${file:/secrets/rsa-key.pem}
apiKey: ${redis:api-key-cache}
username: admin

# Resolved per request (from WorkItem)
keyId: ${header:x-ph-key-id}
tenantId: ${header:x-tenant-id}
```

**Rationale:** Static values are resolved once for performance; dynamic values support per-request customization.

### 4.4 Token Key Auto-Generation

The `tokenKey` (Redis key suffix) is auto-generated from `serviceId:callId`:

```
serviceId: "payments"
callId: "AuthorizePayment"
→ tokenKey: "payments:AuthorizePayment"
→ Redis key: phauth:<swarmInstanceId>:token:payments:AuthorizePayment
```

**Swarm Isolation:** Each swarm instance uses unique UUID in Redis keys, preventing conflicts when swarms are recreated.

Override if needed:
```yaml
auth:
  type: oauth2-client-credentials
  tokenKey: custom-key-name
  tokenUrl: https://auth.example.com/oauth/token
  clientId: ${env:CLIENT_ID}
  clientSecret: ${env:CLIENT_SECRET}
```

---

## 5. Supported Auth Types

### 5.1 OAuth2 Client Credentials

**Simple Example:**
```yaml
auth:
  type: oauth2-client-credentials
  tokenUrl: https://auth.example.com/oauth/token
  clientId: ${env:CLIENT_ID}
  clientSecret: ${env:CLIENT_SECRET}
  scope: api.read api.write
```

**With Custom Token Endpoint:**
```yaml
auth:
  type: oauth2-http-signature
  tokenUrl: https://api.example.com/api/security/token
  tokenMethod: POST
  tokenPath: /api/security/token
  tokenBody: grant_type=client_credentials&scope=payments
  tokenContentType: application/x-www-form-urlencoded
  host: api.example.com
  keyId: ${header:x-ph-key-id}
  privateKey: ${env:RSA_PRIVATE_KEY}
  signatureHeaders: (request-target) host date digest
```

**Generated Headers:**
```
Authorization: Bearer eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...
```

### 5.2 Bearer Token

**Simple Syntax:**
```yaml
auth: Bearer ${env:API_TOKEN}
```

**Object Syntax:**
```yaml
auth:
  type: bearer-token
  token: ${header:x-ph-bearer-token}
```

### 5.3 Basic Auth

**Simple Syntax:**
```yaml
auth: Basic admin:${env:ADMIN_PASSWORD}
```

**Object Syntax:**
```yaml
auth:
  type: basic-auth
  username: admin
  password: ${env:ADMIN_PASSWORD}
```

### 5.4 API Key

**Simple Syntax (Header):**
```yaml
auth: ApiKey X-API-Key:${env:API_KEY}
```

**Object Syntax (Query Parameter):**
```yaml
auth:
  type: api-key
  key: ${env:API_KEY}
  addTo: query
  keyName: api_key
```

### 5.5 HTTP Signature (RSA-SHA256)

```yaml
auth:
  type: http-signature
  keyId: ${header:x-ph-key-id}
  algorithm: rsa-sha256
  privateKey: ${env:RSA_PRIVATE_KEY}
  headers:
    - (request-target)
    - host
    - date
    - digest
```

**Generated Headers:**
```
Date: Wed, 15 Jan 2024 10:30:00 GMT
Digest: SHA-256=X48E9qOokqqrvdts8nOJRJN3OWDUoyWxBf7kbu9DBPE=
Authorization: Signature keyId="my-key-id",algorithm="rsa-sha256",headers="(request-target) host date digest",signature="..."
```

### 5.6 AWS Signature v4

```yaml
auth:
  type: aws-signature-v4
  accessKeyId: ${env:AWS_ACCESS_KEY_ID}
  secretAccessKey: ${env:AWS_SECRET_ACCESS_KEY}
  sessionToken: ${env:AWS_SESSION_TOKEN}
  region: us-east-1
  service: execute-api
```

### 5.7 Digest Auth

```yaml
auth:
  type: digest-auth
  username: user
  password: ${env:USER_PASSWORD}
  realm: api
  algorithm: MD5
  qop: auth
```

### 5.8 OAuth 1.0a

```yaml
auth:
  type: oauth1
  consumerKey: ${env:OAUTH_CONSUMER_KEY}
  consumerSecret: ${env:OAUTH_CONSUMER_SECRET}
  token: ${env:OAUTH_TOKEN}
  tokenSecret: ${env:OAUTH_TOKEN_SECRET}
  signatureMethod: HMAC-SHA256
```

### 5.9 Session-Based Auth (Cookies)

```yaml
auth:
  type: session-based
  loginUrl: https://app.example.com/login
  username: ${env:APP_USERNAME}
  password: ${env:APP_PASSWORD}
  sessionCookieName: JSESSIONID
```

**Generated Headers:**
```
Cookie: JSESSIONID=ABC123XYZ...
```

---

## 6. Redis Schema

### 6.1 Key Pattern

All auth keys follow: `phauth:<swarmInstanceId>:<suffix>`

**Swarm Instance ID:** Unique UUID per swarm instance (not swarm name).

Benefits:
- **True swarm isolation** - each instance has unique namespace
- **No conflicts** - deleting and recreating swarm with same name is safe
- **Bulk cleanup** - delete all keys for instance on swarm removal
- **Easy debugging** - instance ID in logs correlates to Redis keys

### 6.2 Token Storage

```
Key: phauth:<swarmInstanceId>:token:<tokenKey>
Type: String (JSON)
TTL: (expiresAt - now) + 120  # Grace period for refresh overlap

Example: phauth:550e8400-e29b-41d4-a716-446655440000:token:demo-oauth2

Value:
{
  "accessToken": "eyJ...",
  "tokenType": "Bearer",
  "expiresAt": 1234567890,
  "refreshAt": 1234567830,
  "tokenKey": "demo-oauth2-token",
  "strategy": "oauth2-client-credentials",
  "refreshConfig": { ... },
  "metadata": {
    "lastRefreshed": 1234567000,
    "refreshCount": 5
  }
}
```

### 6.3 Refresh Schedule

```
Key: phauth:<swarmInstanceId>:schedule
Type: Sorted Set
Score: refreshAt (Unix timestamp)
Member: tokenKey

Example:
ZADD phauth:550e8400-e29b-41d4-a716-446655440000:schedule 1234567830 "demo-oauth2-token"

Query:
ZRANGEBYSCORE phauth:550e8400-e29b-41d4-a716-446655440000:schedule 0 <now>
```

### 6.4 Distributed Lock

```
Key: phauth:<swarmInstanceId>:lock:<tokenKey>
Type: String
TTL: 30 seconds
Value: <worker-instance-id>

Purpose: Prevent concurrent refreshes across HTTP Builder instances

Failure Handling:
- Lock acquisition timeout → skip refresh (another instance handling it)
- Lock holder crashes → TTL expires, next scan acquires lock
- Redis connection failure → log error, skip refresh attempt
```

### 6.5 Pub/Sub Events

```
Channel: phauth:<swarmInstanceId>:events

Messages:
{
  "event": "token-refreshed",
  "tokenKey": "demo-oauth2-token",
  "expiresAt": 1234567890,
  "timestamp": 1234567000
}
```

### 6.6 Swarm Registry

```
Key: phauth:instances
Type: Hash
Field: <swarmInstanceId>
Value: {"swarmId": "load-test", "registeredAt": 1234567890, "lastActivity": 1234567900}

Purpose: Track active swarm instances for scheduler and cleanup

Operations:
- HSET phauth:instances <instanceId> <json>
- HGETALL phauth:instances  # Get all active instances
- HDEL phauth:instances <instanceId>  # Remove on swarm deletion
```

---

## 7. Swarm Lifecycle Management

### 7.1 Swarm Creation

**Trigger:** HTTP Builder worker starts (receives first WorkItem)

**Actions:**
```java
// In HttpBuilderWorker.onMessage() - first call only
String swarmInstanceId = context.info().swarmInstanceId();
String swarmId = context.info().swarmId();

redis.opsForHash().put("phauth:instances", swarmInstanceId, 
    json({
        "swarmId": swarmId,
        "registeredAt": now(),
        "lastActivity": now()
    })
);
```

**No Core Component Changes:** HTTP Builder self-registers on first message. Swarm Controller doesn't need modification.

### 7.2 Swarm Deletion

**Trigger:** HTTP Builder receives `sig.swarm-remove` via control plane

**Actions:**
```java
@Component
public class AuthLifecycleListener {
    
    // Worker SDK already provides control-plane listener
    @EventListener
    public void onControlPlaneSignal(ControlPlaneSignalEvent event) {
        if ("swarm-remove".equals(event.signal())) {
            String instanceId = context.info().swarmInstanceId();
            cleanupSwarmAuth(instanceId);
        }
    }
    
    private void cleanupSwarmAuth(String instanceId) {
        // Delete all auth keys for this instance
        Set<String> keys = redis.keys("phauth:" + instanceId + ":*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
        
        // Unregister instance
        redis.opsForHash().delete("phauth:instances", instanceId);
        
        log.info("Cleaned up auth data for swarm instance: {}", instanceId);
    }
}
```

**No Core Component Changes:** Uses existing Worker SDK control-plane integration.

### 7.3 Orphan Cleanup

**Schedule:** Daily at 2 AM

**Strategy:** Use Redis TTL on instance registry instead of Orchestrator API

**Logic:**
```java
@Scheduled(cron = "0 0 2 * * *")
public void cleanupOrphans() {
    if (!config.isOrphanCleanupEnabled()) {
        return;
    }
    
    Map<String, String> instances = redis.opsForHash()
        .entries("phauth:instances");
    
    long now = Instant.now().getEpochSecond();
    
    for (Map.Entry<String, String> entry : instances.entrySet()) {
        String instanceId = entry.getKey();
        InstanceInfo info = parseJson(entry.getValue());
        
        long inactiveSeconds = now - info.lastActivity();
        
        // Only cleanup if inactive for > 24 hours
        if (inactiveSeconds > 86400) {
            if (config.isDryRun()) {
                log.info("[DRY-RUN] Would cleanup instance: {} (inactive {}h)",
                    instanceId, inactiveSeconds / 3600);
            } else {
                log.warn("Cleaning up orphaned instance: {} (inactive {}h)",
                    instanceId, inactiveSeconds / 3600);
                cleanupSwarmAuth(instanceId);
            }
        }
    }
}
```

**Activity Tracking:** Update `lastActivity` on every token refresh:
```java
private void updateActivity(String instanceId) {
    redis.opsForHash().put("phauth:instances", instanceId,
        json({"lastActivity": now()})
    );
}
```

**No Orchestrator API Required:** Self-contained in HTTP Builder using Redis state.

### 7.4 Emergency Token Refresh (401 Handling)

**Problem:** Processor receives 401 Unauthorized, token may be expired.

**Solution:** Processor deletes token from Redis, HTTP Builder refreshes on retry.

**Processor Side (No Changes to HTTP Builder):**
```java
// In processor-service
private WorkItem handleHttpResponse(HttpResponse response, WorkItem item) {
    if (response.statusCode() == 401) {
        // Extract token key from request headers or WorkItem metadata
        String tokenKey = extractTokenKey(item);
        String instanceId = context.info().swarmInstanceId();
        
        // Delete cached token
        String redisKey = "phauth:" + instanceId + ":token:" + tokenKey;
        redis.delete(redisKey);
        
        log.warn("Deleted expired token, will refresh on retry: {}", tokenKey);
        
        // Throw exception to trigger retry
        throw new TokenExpiredException("Token expired, deleted from cache");
    }
    return item;
}
```

**HTTP Builder Side (Existing Logic):**
```java
// Already handles missing tokens
Optional<TokenInfo> token = tokenStore.getToken(storeKey);
if (token.isEmpty()) {
    // Synchronous refresh - processor retry will get new token
    token = Optional.of(refreshTokenSync(instanceId, authConfig));
}
```

**No New Events Required:** Uses existing Redis + retry mechanism.

---

## 8. Implementation Guide

### 8.1 Module Structure

```
http-builder-service/
├── src/main/java/io/pockethive/httpbuilder/
│   ├── auth/
│   │   ├── AuthConfig.java
│   │   ├── AuthStrategy.java
│   │   ├── AuthStrategyRegistry.java
│   │   ├── AuthHeaderGenerator.java
│   │   ├── RedisTokenStore.java
│   │   ├── TokenInfo.java
│   │   ├── TokenRefreshScheduler.java
│   │   ├── AuthSwarmLifecycleManager.java
│   │   └── strategies/
│   │       ├── NoAuthStrategy.java
│   │       ├── ApiKeyStrategy.java
│   │       ├── BearerTokenStrategy.java
│   │       ├── BasicAuthStrategy.java
│   │       ├── OAuth2ClientCredentialsStrategy.java
│   │       ├── HttpSignatureStrategy.java
│   │       ├── AwsSignatureV4Strategy.java
│   │       ├── DigestAuthStrategy.java
│   │       └── OAuth1Strategy.java
│   └── template/
│       └── HttpCallTemplate.java
```

### 8.2 Core Interfaces

```java
// AuthStrategy.java
public interface AuthStrategy {
    String getType();
    Map<String, String> generateHeaders(
        AuthConfig config,
        TokenInfo token,
        WorkItem item
    );
    boolean requiresRefresh(TokenInfo token, AuthConfig config);
    TokenInfo refresh(AuthConfig config);
}

// AuthConfig.java
public record AuthConfig(
    String type,
    String tokenKey,
    int refreshBuffer,
    int emergencyRefreshBuffer,
    Map<String, String> properties  // Resolved static values
) {
    // Phase 1: Template load - resolve env/file/redis only
    public static AuthConfig fromTemplate(
        Map<String, Object> authSection,
        String serviceId,
        String callId,
        RedisTemplate redis
    ) {
        String type = (String) authSection.get("type");
        if (type == null) {
            throw new IllegalArgumentException("auth.type is required");
        }
        
        // Validate type
        AuthType authType = AuthType.parse(type);
        
        // Auto-generate tokenKey
        String tokenKey = (String) authSection.getOrDefault(
            "tokenKey",
            serviceId + ":" + callId
        );
        
        int refreshBuffer = (int) authSection.getOrDefault("refreshBuffer", 60);
        int emergencyBuffer = (int) authSection.getOrDefault("emergencyRefreshBuffer", 10);
        
        // Resolve static variables only
        Map<String, String> properties = new HashMap<>();
        authSection.forEach((key, value) -> {
            if (!key.equals("type") && !key.equals("tokenKey") && 
                !key.equals("refreshBuffer") && !key.equals("emergencyRefreshBuffer")) {
                properties.put(key, resolveStaticVariable(value.toString(), redis));
            }
        });
        
        // Validate required fields per auth type
        validateAuthType(authType, properties);
        
        return new AuthConfig(type, tokenKey, refreshBuffer, emergencyBuffer, properties);
    }
    
    // Phase 2: Request time - resolve headers
    public AuthConfig resolveHeaders(WorkItem item) {
        Map<String, String> resolved = new HashMap<>(properties);
        properties.forEach((key, value) -> {
            if (value.startsWith("${header:")) {
                String headerName = value.substring(10, value.length() - 1);
                String headerValue = item.headers().get(headerName);
                if (headerValue != null) {
                    resolved.put(key, headerValue);
                }
            }
        });
        return new AuthConfig(type, tokenKey, refreshBuffer, emergencyRefreshBuffer, resolved);
    }
    
    private static String resolveStaticVariable(String value, RedisTemplate redis) {
        if (!value.startsWith("${") || !value.endsWith("}")) {
            return value;  // Literal
        }
        
        String expr = value.substring(2, value.length() - 1);
        String[] parts = expr.split(":", 2);
        if (parts.length != 2) return value;
        
        String source = parts[0];
        String name = parts[1];
        
        return switch (source) {
            case "env" -> System.getenv(name);
            case "file" -> {
                try {
                    yield Files.readString(Path.of(name)).trim();
                } catch (IOException e) {
                    throw new IllegalArgumentException("Failed to read file: " + name, e);
                }
            }
            case "redis" -> (String) redis.opsForValue().get(name);
            case "header" -> value;  // Keep placeholder for phase 2
            default -> value;
        };
    }
    
    private static void validateAuthType(AuthType type, Map<String, String> props) {
        switch (type) {
            case OAUTH2_CLIENT_CREDENTIALS:
                requireField(props, "tokenUrl", "clientId", "clientSecret");
                break;
            case BASIC_AUTH:
                requireField(props, "username", "password");
                break;
            case BEARER_TOKEN:
                requireField(props, "token");
                break;
            case API_KEY:
                requireField(props, "key");
                break;
            case HTTP_SIGNATURE:
                requireField(props, "keyId", "algorithm", "privateKey");
                break;
            // ... other types
        }
    }
    
    private static void requireField(Map<String, String> props, String... fields) {
        for (String field : fields) {
            if (!props.containsKey(field) || props.get(field) == null) {
                throw new IllegalArgumentException("Required field missing: " + field);
            }
        }
    }
}

// TokenInfo.java
public record TokenInfo(
    String accessToken,
    String tokenType,
    long expiresAt,
    long refreshAt,
    String strategy,
    Map<String, String> refreshConfig,
    Map<String, Object> metadata
) {
    public boolean isExpired() {
        return Instant.now().getEpochSecond() >= expiresAt;
    }
    
    public boolean needsRefresh(int buffer) {
        return Instant.now().getEpochSecond() >= refreshAt;
    }
}
```

### 8.3 Token Refresh Scheduler

```java
@Component
public class TokenRefreshScheduler {
    private final RedisTemplate<String, String> redis;
    private final AuthStrategyRegistry registry;
    private final ScheduledExecutorService scheduler;
    private final RestTemplate authHttpClient;
    private final String instanceId;
    
    @PostConstruct
    public void start() {
        scheduler.scheduleAtFixedRate(
            this::scanAndRefresh,
            0, 10, TimeUnit.SECONDS
        );
    }
    
    private void scanAndRefresh() {
        // Get all active swarm instances
        Map<String, String> instances = redis.opsForHash()
            .entries("phauth:instances");
        
        for (String instanceId : instances.keySet()) {
            scanInstanceTokens(instanceId);
        }
    }
    
    private void scanInstanceTokens(String instanceId) {
        long now = Instant.now().getEpochSecond();
        String scheduleKey = "phauth:" + instanceId + ":schedule";
        
        Set<String> tokenKeys = redis.opsForZSet()
            .rangeByScore(scheduleKey, 0, now);
        
        for (String tokenKey : tokenKeys) {
            CompletableFuture.runAsync(() -> 
                refreshToken(instanceId, tokenKey)
            );
        }
    }
    
    private void refreshToken(String instanceId, String tokenKey) {
        String lockKey = "phauth:" + instanceId + ":lock:" + tokenKey;
        
        try {
            Boolean acquired = redis.opsForValue()
                .setIfAbsent(lockKey, this.instanceId, Duration.ofSeconds(30));
            
            if (!acquired) {
                log.debug("Lock held by another instance: {}", lockKey);
                return;
            }
            
            try {
                TokenInfo newToken = performRefresh(instanceId, tokenKey);
                storeToken(instanceId, tokenKey, newToken);
                updateActivity(instanceId);
                
                metrics.counter("ph_auth_refresh_total",
                    "status", "success",
                    "strategy", newToken.strategy()
                ).increment();
            } catch (Exception e) {
                log.error("Token refresh failed: {}", tokenKey, e);
                metrics.counter("ph_auth_refresh_total",
                    "status", "error"
                ).increment();
            } finally {
                redis.delete(lockKey);
            }
        } catch (RedisConnectionFailureException e) {
            log.error("Redis unavailable during lock acquisition", e);
        }
    }
    
    private void storeToken(String instanceId, String tokenKey, TokenInfo token) {
        String key = "phauth:" + instanceId + ":token:" + tokenKey;
        long ttl = token.expiresAt() - Instant.now().getEpochSecond() + 120;
        redis.opsForValue().set(key, toJson(token), Duration.ofSeconds(ttl));
        
        // Update schedule for next refresh
        String scheduleKey = "phauth:" + instanceId + ":schedule";
        redis.opsForZSet().add(scheduleKey, tokenKey, token.refreshAt());
    }
    
    private void updateActivity(String instanceId) {
        redis.opsForHash().put("phauth:instances", instanceId,
            json({"lastActivity": Instant.now().getEpochSecond()})
        );
    }
}
```

### 8.4 Auth Header Generator

```java
@Component
public class AuthHeaderGenerator {
    private final RedisTokenStore tokenStore;
    private final AuthStrategyRegistry registry;
    
    public Map<String, String> generate(
        WorkerContext context,  // Get swarmInstanceId from context
        AuthConfig authConfig,
        WorkItem item
    ) {
        if (authConfig == null || "none".equals(authConfig.type())) {
            return Map.of();
        }
        
        // Resolve dynamic variables (headers)
        AuthConfig resolved = authConfig.resolveHeaders(item);
        
        String instanceId = context.info().swarmInstanceId();
        String tokenKey = resolved.tokenKey();
        String storeKey = "phauth:" + instanceId + ":token:" + tokenKey;
        
        Optional<TokenInfo> token = tokenStore.getToken(storeKey);
        
        if (token.isEmpty()) {
            token = Optional.of(refreshTokenSync(instanceId, resolved));
            context.statusPublisher()
                .update(status -> status.data("auth.cacheHit", false));
        } else if (isNearExpiry(token.get(), resolved.emergencyRefreshBuffer())) {
            token = Optional.of(refreshTokenSync(instanceId, resolved));
            context.statusPublisher()
                .update(status -> status.data("auth.emergencyRefresh", true));
        } else {
            context.statusPublisher()
                .update(status -> status.data("auth.cacheHit", true));
        }
        
        // Publish auth metrics
        context.statusPublisher()
            .update(status -> status
                .data("auth.tokenKey", tokenKey)
                .data("auth.strategy", resolved.type())
                .data("auth.expiresIn", token.get().expiresAt() - Instant.now().getEpochSecond())
            );
        
        AuthStrategy strategy = registry.getStrategy(resolved.type());
        return strategy.generateHeaders(resolved, token.get(), item);
    }
    
    private TokenInfo refreshTokenSync(String instanceId, AuthConfig config) {
        // Synchronous refresh with retry
        return retryTemplate.execute(ctx -> {
            TokenInfo token = strategy.refresh(config);
            storeToken(instanceId, config.tokenKey(), token);
            return token;
        });
    }
}
```

### 8.5 Simple Auth Parser

```java
@Component
public class SimpleAuthParser {
    
    public AuthConfig parseSimpleAuth(String authString, String serviceId, String callId) {
        // "Bearer ${env:API_TOKEN}"
        if (authString.startsWith("Bearer ")) {
            String token = authString.substring(7);
            return new AuthConfig(
                "bearer-token",
                serviceId + ":" + callId,
                0, 0,
                Map.of("token", token)
            );
        }
        
        // "Basic admin:${env:PASSWORD}"
        if (authString.startsWith("Basic ")) {
            String credentials = authString.substring(6);
            String[] parts = credentials.split(":", 2);
            return new AuthConfig(
                "basic-auth",
                serviceId + ":" + callId,
                0, 0,
                Map.of("username", parts[0], "password", parts[1])
            );
        }
        
        // "ApiKey X-API-Key:${env:API_KEY}"
        if (authString.startsWith("ApiKey ")) {
            String keyDef = authString.substring(7);
            String[] parts = keyDef.split(":", 2);
            return new AuthConfig(
                "api-key",
                serviceId + ":" + callId,
                0, 0,
                Map.of(
                    "keyName", parts[0],
                    "key", parts[1],
                    "addTo", "header"
                )
            );
        }
        
        throw new IllegalArgumentException("Unknown simple auth format: " + authString);
    }
}
```

```java
@Component
public class AuthSwarmLifecycleManager {
    private final RedisTemplate<String, String> redis;
    private final AuthConfig config;
    
    // Cleanup on swarm removal (Worker SDK integration)
    @EventListener
    public void onControlPlaneSignal(ControlPlaneSignalEvent event) {
        if ("swarm-remove".equals(event.signal())) {
            String instanceId = event.context().info().swarmInstanceId();
            cleanupSwarmAuth(instanceId);
        }
    }
    
    private void cleanupSwarmAuth(String instanceId) {
        Set<String> keys = redis.keys("phauth:" + instanceId + ":*");
        
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
            log.info("Deleted {} auth keys for instance: {}", keys.size(), instanceId);
        }
        
        redis.opsForHash().delete("phauth:instances", instanceId);
    }
    
    @Scheduled(cron = "0 0 2 * * *")
    public void cleanupOrphans() {
        if (!config.isOrphanCleanupEnabled()) {
            return;
        }
        
        Map<String, String> instances = redis.opsForHash()
            .entries("phauth:instances");
        
        long now = Instant.now().getEpochSecond();
        
        for (Map.Entry<String, String> entry : instances.entrySet()) {
            String instanceId = entry.getKey();
            InstanceInfo info = parseJson(entry.getValue());
            
            long inactiveSeconds = now - info.lastActivity();
            
            if (inactiveSeconds > 86400) {
                if (config.isDryRun()) {
                    log.info("[DRY-RUN] Would cleanup instance: {} (inactive {}h)",
                        instanceId, inactiveSeconds / 3600);
                } else {
                    log.warn("Cleaning up orphaned instance: {} (inactive {}h)",
                        instanceId, inactiveSeconds / 3600);
                    cleanupSwarmAuth(instanceId);
                    
                    metrics.counter("ph_auth_orphan_cleanup_total").increment();
                }
            }
        }
    }
}
```

---

## 9. Configuration

```yaml
# Spring Boot Redis connection (reuse Worker SDK Redis)
spring:
  redis:
    host: ${REDIS_HOST:redis}
    port: ${REDIS_PORT:6379}
    password: ${REDIS_PASSWORD:}
    database: 0
    lettuce:
      pool:
        max-active: 20
        max-idle: 10
        min-idle: 5

pockethive:
  auth:
    enabled: true
    http:
      connectTimeout: 5s
      readTimeout: 10s
      maxConnections: 50
      followRedirects: true
      sslVerification: true  # Disable for dev/test only
    scheduler:
      enabled: true
      scanInterval: 10s
      threadPoolSize: 5
    refresh:
      defaultBuffer: 60
      emergencyBuffer: 10
      retryPolicy:
        maxAttempts: 3
        initialDelay: 1000
        maxDelay: 30000
        multiplier: 2.0
    redis:
      keyPrefix: "phauth"
      lockTimeout: 30s
    cleanup:
      orphanScanCron: "0 0 2 * * *"
      enabled: true
      dryRun: false
      inactiveThresholdHours: 24
```

---

## 10. Metrics

```
ph_auth_refresh_total{strategy, status}
ph_auth_refresh_duration_seconds{strategy}
ph_auth_token_cache_hits_total
ph_auth_token_cache_misses_total
ph_auth_emergency_refresh_total
ph_auth_orphan_cleanup_total
```

**Note:** Removed high-cardinality labels (`swarm_id`, `token_key`) to prevent Prometheus performance issues. Use logs for per-token debugging.

---

## 11. Testing Strategy

### 11.1 Unit Tests
- Test each auth strategy in isolation
- Mock Redis and HTTP clients
- Test token expiry logic
- Test retry logic

### 11.2 Integration Tests
- Test background scheduler with real Redis
- Test concurrent refresh (distributed lock)
- Test emergency refresh on 401
- Test swarm lifecycle

### 11.3 Load Tests
- 1000 req/s with token refresh
- Verify < 5ms auth overhead
- Test cleanup with 100+ swarms

---

## 12. Success Criteria

✅ Zero processor delays  
✅ < 5ms auth overhead  
✅ 99.9% refresh success rate  
✅ Shared tokens across workers  
✅ Observable via metrics/logs  
✅ Clean swarm lifecycle management  

---

## 13. Implementation Phases

### Phase 0: Foundation (Week 1)
- [ ] Add `swarmInstanceId` to Worker SDK `WorkerInfo` (read from `POCKETHIVE_CONTROL_PLANE_SWARM_INSTANCE_ID`)
- [ ] Add Redis connection config (reuse Worker SDK Redis)
- [ ] Add HTTP client config with timeouts and SSL
- [ ] Add Spring Boot auto-configuration for auth module
- [ ] Add health indicator for auth system

### Phase 1: Core Infrastructure (Week 2)
- [ ] Variable resolver (env/file/redis - static only)
- [ ] Two-phase variable resolution (headers at request time)
- [ ] Simple auth parser (string syntax)
- [ ] Redis token store with correct TTL (expiresAt + 120s)
- [ ] Auth strategy interface
- [ ] Auth header generator with WorkerContext integration
- [ ] Template validation (required fields per auth type)

### Phase 2: Basic Auth Types (Week 3)
- [ ] No Auth
- [ ] API Key (simple + object syntax)
- [ ] Bearer Token (simple + object syntax)
- [ ] Basic Auth (simple + object syntax)
- [ ] OAuth2 Client Credentials

### Phase 3: Background Scheduler (Week 4)
- [ ] Token refresh scheduler (scan phauth:instances)
- [ ] Distributed locking with failure handling
- [ ] Retry logic with exponential backoff
- [ ] Metrics (low cardinality - no swarm_id/token_key labels)
- [ ] Activity tracking (update lastActivity on refresh)

### Phase 4: Advanced Auth (Week 5-6)
- [ ] HTTP Signature (RSA-SHA256)
- [ ] OAuth2 with HTTP Signature (custom token endpoint)
- [ ] AWS Signature v4
- [ ] Digest Auth
- [ ] OAuth 1.0a

### Phase 5: Lifecycle Management (Week 7)
- [ ] Self-registration on first message (phauth:instances)
- [ ] Control-plane signal listener (swarm-remove cleanup)
- [ ] Orphan cleanup (Redis-based, no Orchestrator API)
- [ ] Emergency refresh (Processor deletes token on 401)

### Phase 6: Session-Based Auth (Week 8)
- [ ] Session-based auth strategy
- [ ] Cookie management
- [ ] Session refresh logic

### Phase 7: Testing & Documentation (Week 9)
- [ ] Unit tests (80%+ coverage)
- [ ] Integration tests with Testcontainers (Redis + RabbitMQ)
- [ ] Load tests (1000 req/s)
- [ ] User guide with examples
- [ ] Migration guide from manual auth
- [ ] Troubleshooting guide

---

**End of Document**
