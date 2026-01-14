# PocketHive Template-Based Authorization - User Guide

**Version:** 1.0  
**Audience:** PocketHive Users, Scenario Authors, Operators

---

## Table of Contents

1. [Overview](#overview)
2. [Quick Start](#quick-start)
3. [Auth Types](#auth-types)
4. [Template Syntax](#template-syntax)
5. [Variable Resolution](#variable-resolution)
6. [Configuration](#configuration)
7. [Troubleshooting](#troubleshooting)
8. [Best Practices](#best-practices)

---

## Overview

Template-based authorization automatically generates authorization headers for HTTP requests without manual SpEL expressions. The system:

- **Proactively refreshes tokens** before expiry (zero processor delays)
- **Shares tokens** across all workers in a swarm
- **Isolates swarms** using unique instance UUIDs
- **Supports 9+ auth types** (OAuth2, Bearer, Basic, API Key, HTTP Signature, AWS Signature v4, etc.)

### Key Benefits

✅ **Zero-delay guarantee** - Background token refresh  
✅ **Template-driven** - Declare auth in YAML, no code changes  
✅ **Swarm-isolated** - No conflicts when recreating swarms  
✅ **Observable** - Metrics and status via control plane

---

## Quick Start

### 1. Add Auth to HTTP Template

Create `http-templates/payments/AuthorizePayment.yaml`:

```yaml
serviceId: payments
callId: AuthorizePayment
method: POST
pathTemplate: /api/payments
auth:
  type: bearer-token
  token: ${env:API_TOKEN}
headersTemplate:
  Content-Type: application/json
bodyTemplate: "{{ payload }}"
```

### 2. Set Environment Variable

```bash
export API_TOKEN="your-token-here"
```

### 3. Start Swarm

The HTTP Builder worker automatically:
- Loads the template
- Resolves `${env:API_TOKEN}`
- Adds `Authorization: Bearer your-token-here` to requests

---

## Auth Types

### Bearer Token

**Simple syntax:**
```yaml
auth: Bearer ${env:API_TOKEN}
```

**Object syntax:**
```yaml
auth:
  type: bearer-token
  token: ${env:API_TOKEN}
```

**Generated header:**
```
Authorization: Bearer eyJhbGc...
```

---

### Basic Auth

**Simple syntax:**
```yaml
auth: Basic admin:${env:PASSWORD}
```

**Object syntax:**
```yaml
auth:
  type: basic-auth
  username: admin
  password: ${env:PASSWORD}
```

**Generated header:**
```
Authorization: Basic YWRtaW46cGFzc3dvcmQ=
```

---

### API Key

**Simple syntax (header):**
```yaml
auth: ApiKey X-API-Key:${env:API_KEY}
```

**Object syntax (query parameter):**
```yaml
auth:
  type: api-key
  key: ${env:API_KEY}
  addTo: query
  keyName: api_key
```

**Generated:**
- Header: `X-API-Key: abc123`
- Query: `?api_key=abc123`

---

### OAuth2 Client Credentials

```yaml
auth:
  type: oauth2-client-credentials
  tokenUrl: https://auth.example.com/oauth/token
  clientId: ${env:CLIENT_ID}
  clientSecret: ${env:CLIENT_SECRET}
  scope: api.read api.write
```

**How it works:**
1. First request: HTTP Builder fetches token from `tokenUrl` (blocks ~200ms)
2. Token stored in Redis with TTL
3. Subsequent requests: Read from Redis (< 5ms)
4. Background refresh 60s before expiry (no delays)

**Generated header:**
```
Authorization: Bearer eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...
```

---

## Variable Resolution

### Two-Phase Resolution

**Phase 1 (Template Load - Startup):**
- `${env:VAR}` - Environment variable
- `${file:/path}` - Read from file
- `${redis:KEY}` - Read from Redis

**Phase 2 (Per Request):**
- `${header:NAME}` - From WorkItem headers

### Examples

```yaml
# Static (resolved once at startup)
clientId: ${env:CLIENT_ID}
privateKey: ${file:/secrets/rsa-key.pem}
apiKey: ${redis:api-key-cache}
username: admin

# Dynamic (resolved per request)
keyId: ${header:x-ph-key-id}
tenantId: ${header:x-tenant-id}
```

---

## Configuration

### Enable Auth System

```yaml
pockethive:
  auth:
    enabled: true
    scheduler:
      enabled: true
      scanInterval: 10s
    refresh:
      defaultBuffer: 60
```

### Redis Connection

```yaml
spring:
  redis:
    host: ${REDIS_HOST:redis}
    port: ${REDIS_PORT:6379}
```

---

## Troubleshooting

### Token Not Refreshing

**Check:**
1. Background scheduler enabled
2. Redis connection healthy
3. Token endpoint reachable

**Solution:**
```bash
grep "Token refresh" logs/http-builder.log
redis-cli KEYS "phauth:*"
```

---

### Template Not Found

**Check:**
1. Template file exists
2. `templateRoot` configured correctly

**Solution:**
```yaml
pockethive:
  worker:
    config:
      templateRoot: /app/http-templates
```

---

## Best Practices

### Security

✅ Use environment variables for secrets
✅ Use files for private keys
✅ Enable SSL verification in production

### Performance

✅ Use static variables when possible
✅ Set appropriate refresh buffer
✅ Monitor token refresh metrics

---

## Metrics

```
ph_auth_refresh_total{strategy, status}
ph_auth_token_cache_hits_total
ph_auth_emergency_refresh_total
```

---

**End of User Guide**
