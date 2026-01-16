# PocketHive Authentication Guide

Complete guide to template-based authentication for HTTP and TCP protocols.

## Table of Contents

1. [Overview](#overview)
2. [Quick Start](#quick-start)
3. [Auth Strategies](#auth-strategies)
4. [Configuration Methods](#configuration-methods)
5. [Scenario Examples](#scenario-examples)
6. [Advanced Features](#advanced-features)
7. [Troubleshooting](#troubleshooting)

---

## Overview

PocketHive provides enterprise-grade authentication with:

- ✅ **11 auth strategies** (OAuth2, AWS Sig v4, ISO-8583 MAC, etc.)
- ✅ **Automatic token refresh** (background scheduler)
- ✅ **In-memory caching** (per worker instance)
- ✅ **Thread-safe** (concurrent request handling)
- ✅ **Template-based** (no code changes required)
- ✅ **HTTP + TCP support** (unique in the industry)

---

## Quick Start

### 1. Auth is Auto-Enabled

Auth is automatically enabled **globally** when the worker-sdk is present. It remains dormant (no overhead) until you use it in templates or worker config.

**Key Points:**
- ✅ Auth beans are always available
- ✅ Zero overhead when not used (no tokens = no work)
- ✅ No per-worker enable/disable needed
- ✅ Just add `auth:` blocks to templates OR worker config

### 2. Create Template with Auth

```yaml
# http-templates/GetUser.yaml
serviceId: api
callId: GetUser
protocol: HTTP
method: GET
pathTemplate: /users/{{ payloadAsJson.userId }}
auth:
  type: oauth2-client-credentials
  tokenKey: api:auth
  tokenUrl: https://auth.example.com/oauth/token
  clientId: my-client-id
  clientSecret: my-secret-key
  scope: users.read
headersTemplate:
  Accept: application/json
```

### 3. Run Scenario

The request builder automatically:
1. Detects auth config in template
2. Enables auth system (no manual config needed)
3. Starts token refresh scheduler
4. Fetches OAuth2 token
5. Caches it in memory
6. Adds `Authorization: Bearer <token>` header
7. Refreshes token before expiry

---

## Auth Strategies

### HTTP Strategies

#### OAuth2 Client Credentials
```yaml
auth:
  type: oauth2-client-credentials
  tokenKey: api:auth
  tokenUrl: https://auth.example.com/oauth/token
  clientId: my-client-id
  clientSecret: my-secret-key
  scope: api.read api.write
```

#### OAuth2 Password Grant
```yaml
auth:
  type: oauth2-password-grant
  tokenKey: user:auth
  tokenUrl: https://auth.example.com/oauth/token
  username: testuser
  password: testpass123
  scope: profile
```

#### Bearer Token (Static)
```yaml
auth:
  type: bearer-token
  tokenKey: static:auth
  token: eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
```

#### Basic Auth
```yaml
auth:
  type: basic-auth
  tokenKey: basic:auth
  username: admin
  password: admin123
```

#### API Key
```yaml
auth:
  type: api-key
  tokenKey: apikey:auth
  key: sk_live_1234567890abcdef
  location: header  # or query
  name: X-API-Key   # header/query param name
```

#### AWS Signature v4
```yaml
auth:
  type: aws-signature-v4
  tokenKey: aws:auth
  accessKeyId: AKIAIOSFODNN7EXAMPLE
  secretAccessKey: wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY
  region: us-east-1
  service: execute-api
```

#### HMAC Signature
```yaml
auth:
  type: hmac-signature
  tokenKey: hmac:auth
  secretKey: my-hmac-secret-key
  algorithm: HmacSHA256
```

### TCP Strategies

#### ISO-8583 MAC (Financial)
```yaml
protocol: TCP
auth:
  type: iso8583-mac
  tokenKey: atm:mac
  macKey: "0123456789ABCDEF0123456789ABCDEF"  # 16-byte hex
  algorithm: 3DES  # or DES
```

#### TLS Client Certificate
```yaml
protocol: TCP
auth:
  type: tls-client-cert
  tokenKey: tls:auth
  certPath: /certs/client.pem
  keyPath: /certs/client.key
```

---

## Configuration Methods

### Method 1: Template Auth (Recommended)

Auth in template file - request builder fetches token.

```yaml
# http-templates/CreateOrder.yaml
auth:
  type: oauth2-client-credentials
  tokenKey: orders:auth
  tokenUrl: https://orders.example.com/oauth/token
  clientId: orders-client
  clientSecret: orders-secret-key
```

**Pros:**
- ✅ Clean separation (template owns auth)
- ✅ Per-call auth configuration
- ✅ Easy to version control

### Method 2: Worker Config Auth

Auth in scenario worker config - shared across calls.

```yaml
# scenario.yaml
- role: request-builder
  config:
    worker:
      auth:
        - tokenKey: "api:auth"
          type: oauth2-client-credentials
          tokenUrl: "{{ sut.endpoints['default'].baseUrl }}/oauth/token"
          clientId: api-client-id
          clientSecret: api-secret-key
```

Generator sets header:
```yaml
- role: generator
  config:
    worker:
      message:
        headers:
          x-ph-auth-token-key: "api:auth"
```

**Pros:**
- ✅ Access to `sut.endpoints` variables
- ✅ Shared token across multiple calls
- ✅ Centralized auth config

### Method 3: Generator Fetches Token

Generator fetches token and passes in message body.

```yaml
# scenario.yaml
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
        body: '{"token": "{{ eval(''#authToken(\"api:auth\")'') }}"}'
```

Template extracts token:
```yaml
# http-templates/CreateOrder.yaml
headersTemplate:
  Authorization: "Bearer {{ payloadAsJson.token }}"
```

**Pros:**
- ✅ Token fetched once per batch
- ✅ Useful for batch processing

---

## Scenario Examples

### Example 1: OAuth2 API Testing

```yaml
# scenarios/bundles/oauth2-api/scenario.yaml
id: oauth2-api-test
name: OAuth2 API Load Test
template:
  bees:
    - role: generator
      image: generator:latest
      config:
        inputs:
          type: SCHEDULER
          scheduler:
            ratePerSec: 10
            maxMessages: 100
        worker:
          message:
            body: '{"userId": "{{ eval(''#randInt(1, 1000)'') }}"}'
            headers:
              x-ph-call-id: "GetUser"
      work:
        out: build

    - role: request-builder
      image: request-builder:latest
      config:
        worker:
          templateRoot: /app/scenario/http-templates
          serviceId: api
      work:
        in: build
        out: proc

    - role: processor
      image: processor:latest
      config:
        baseUrl: "https://api.example.com"
      work:
        in: proc
        out: post

    - role: postprocessor
      image: postprocessor:latest
      work:
        in: post
```

```yaml
# http-templates/GetUser.yaml
serviceId: api
callId: GetUser
protocol: HTTP
method: GET
pathTemplate: /users/{{ payloadAsJson.userId }}
auth:
  type: oauth2-client-credentials
  tokenKey: api:auth
  tokenUrl: https://auth.example.com/oauth/token
  clientId: api-client-id
  clientSecret: api-secret-key
  scope: users.read
headersTemplate:
  Accept: application/json
```

### Example 2: AWS API Gateway

```yaml
# http-templates/InvokeLambda.yaml
serviceId: aws
callId: InvokeLambda
protocol: HTTP
method: POST
pathTemplate: /prod/function
auth:
  type: aws-signature-v4
  tokenKey: aws:auth
  accessKeyId: AKIAIOSFODNN7EXAMPLE
  secretAccessKey: wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY
  region: us-east-1
  service: execute-api
headersTemplate:
  Content-Type: application/json
bodyTemplate: |
  {
    "action": "{{ payloadAsJson.action }}"
  }
```

### Example 3: ISO-8583 ATM Transaction

```yaml
# tcp-templates/ATMWithdrawal.yaml
serviceId: atm
callId: Withdrawal
protocol: TCP
behavior: REQUEST_RESPONSE
transport: socket
endTag: "\n"
auth:
  type: iso8583-mac
  tokenKey: atm:mac
  macKey: "0123456789ABCDEF0123456789ABCDEF"
  algorithm: 3DES
headersTemplate:
  X-Terminal-ID: "{{ payloadAsJson.terminalId }}"
bodyTemplate: |
  0200{{ payloadAsJson.pan }}{{ payloadAsJson.amount }}{{ payloadAsJson.stan }}
```

### Example 4: Multiple Auth Configs

```yaml
# scenario.yaml
- role: request-builder
  config:
    worker:
      auth:
        - tokenKey: "payments:auth"
          type: oauth2-client-credentials
          tokenUrl: "{{ sut.endpoints['payments'].baseUrl }}/oauth/token"
          clientId: payments-client
          clientSecret: payments-secret
          scope: payments.write
        - tokenKey: "cards:auth"
          type: oauth2-client-credentials
          tokenUrl: "{{ sut.endpoints['cards'].baseUrl }}/oauth/token"
          clientId: cards-client
          clientSecret: cards-secret
          scope: cards.read
```

Generator routes to different auth:
```yaml
- role: generator
  config:
    worker:
      message:
        headers:
          x-ph-call-id: "{{ pickWeighted('CreatePayment', 60, 'GetCard', 40) }}"
          x-ph-auth-token-key: "{{ eval('headers[\"x-ph-call-id\"].equals(\"CreatePayment\") ? \"payments:auth\" : \"cards:auth\"') }}"
```

---

## Advanced Features

### Configuration (Optional)

Auth is enabled globally by default. It's dormant (zero overhead) until you use it.

**Default behavior:**
- Auth beans are always available
- Token refresh scheduler runs but does nothing if no tokens exist
- No performance impact on workers without auth

**Override defaults if needed:**

```yaml
pockethive:
  auth:
    enabled: true              # default: true (globally enabled)
    scheduler:
      enabled: true            # default: true (auto-enabled)
      scanIntervalSeconds: 10  # default: 10
    refresh:
      refreshAheadSeconds: 60          # seconds before expiry to refresh
      emergencyRefreshAheadSeconds: 10 # emergency threshold
    http:
      connectTimeoutSeconds: 5
      readTimeoutSeconds: 10
```

### Disable Auth Globally (if needed)

To completely disable auth across all workers:

```yaml
pockethive:
  auth:
    enabled: false           # disables auth system globally
```

**Note:** You cannot disable auth per-worker. It's either globally enabled (default) or globally disabled.

### Token in Message Body

Use `#authToken()` SpEL function:

```yaml
bodyTemplate: |
  {
    "accessToken": "{{ eval('#authToken(\"api:auth\")') }}",
    "data": "{{ payloadAsJson.data }}"
  }
```

### Custom Headers

```yaml
headersTemplate:
  X-Custom-Token: "{{ eval('#authToken(\"api:auth\")') }}"
  X-Request-ID: "{{ eval('#uuid()') }}"
```

### Variable Resolution

Supported sources:
- Raw values - Direct strings/numbers in config
- `{{ sut.endpoints['name'].baseUrl }}` - From SUT environment (scenario config only)

---

## Troubleshooting

### Token Not Refreshing

**Auth is auto-enabled by default.** If tokens aren't refreshing:

**Check logs for errors:**
```yaml
pockethive:
  auth:
    scheduler:
      enabled: true
```

### Auth Headers Not Added

**Verify auth config:**
1. Check `tokenKey` matches between config and template
2. Ensure required fields are present (tokenUrl, clientId, etc.)
3. Check logs for auth errors

### Token Expired

**Adjust refresh buffer:**
```yaml
auth:
  refreshBuffer: 120  # Refresh 2 minutes before expiry
  emergencyRefreshBuffer: 30
```

### Environment Variables Not Resolved

**Use raw values or SUT variables:**
- ✅ `tokenUrl: https://auth.example.com/oauth/token`
- ✅ `tokenUrl: "{{ sut.endpoints['default'].baseUrl }}/oauth/token"`
- ❌ `tokenUrl: ${env:TOKEN_URL}` (not supported)

---

## Best Practices

1. **Use template auth** for per-call configuration
2. **Use worker config auth** when you need `sut.endpoints`
3. **Set refresh buffer** to 60+ seconds for production
4. **Store secrets in scenario config**, not hardcoded in templates
5. **Use tokenKey namespacing**: `service:purpose` (e.g., `payments:auth`)
6. **Enable scheduler** for automatic token refresh
7. **Monitor metrics**: `ph_auth_refresh_total`, `ph_auth_token_cache_hits_total`

---

## Metrics

Monitor auth performance:

```promql
# Token refresh success rate
rate(ph_auth_refresh_total{status="success"}[5m])
/ rate(ph_auth_refresh_total[5m])

# Cache hit rate
rate(ph_auth_token_cache_hits_total[5m])
/ (rate(ph_auth_token_cache_hits_total[5m]) + rate(ph_auth_token_cache_misses_total[5m]))
```

---

## Support

For issues or questions:
- Check logs for auth errors
- Verify environment variables are set
- Ensure auth type matches your API requirements
- Review scenario examples in `scenarios/bundles/auth-example/`
