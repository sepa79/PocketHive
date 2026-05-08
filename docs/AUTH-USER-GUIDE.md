# Auth User Guide

Status: implemented redesign

This guide describes the current PocketHive auth model. Legacy inline template `auth:` and `#authToken()` templates are no longer supported.

## Quick Start

Create `authProfiles.yaml` at the scenario root:

```yaml
profiles:
  "api:static":
    type: STATIC_TOKEN
    storage:
      mode: NONE
    token: "local-test-token"
```

Reference it from a request template:

```yaml
protocol: HTTP
callId: get-account
method: GET
pathTemplate: /accounts/{{ vars.accountId }}
headersTemplate: {}
bodyTemplate: ""
authRef:
  profileId: "api:static"
  applyAs: HTTP_AUTHORIZATION_BEARER
```

Only workers that process this template activate auth.

## Profile File

Profiles are keyed by YAML map keys so duplicate profile IDs are structurally invalid:

```yaml
profiles:
  "tenant-a:oauth":
    type: OAUTH2_CLIENT_CREDENTIALS
    storage:
      mode: REDIS
      tokenKey: tenant-a-api
    tokenUrl: "{{ sut.auth.tokenUrl }}"
    clientId: "{{ vars.clientId }}"
    clientSecret:
      env: TENANT_A_CLIENT_SECRET
```

Profile values may render `{{ vars.* }}`, `{{ sut.* }}`, `{{ swarm.id }}`, `{{ worker.id }}`, and `{{ worker.role }}` before use.

## Secret References

PocketHive does not own secrets. Profiles may point at external runtime material:

```yaml
clientSecret:
  env: PAYMENT_CLIENT_SECRET
```

```yaml
keyStorePassword:
  file: /run/secrets/client-cert-password
```

PocketHive reads these values to execute the auth strategy. It does not store, rotate, journal, log, or publish them.

## HTTP Examples

Bearer token:

```yaml
authRef:
  profileId: "api:static"
  applyAs: HTTP_AUTHORIZATION_BEARER
```

API key header:

```yaml
profiles:
  "api:key":
    type: API_KEY
    storage:
      mode: NONE
    key: "dummy-local-key"
    headerName: X-Api-Key
```

```yaml
authRef:
  profileId: "api:key"
  applyAs: HTTP_HEADER
```

API key query parameter:

```yaml
profiles:
  "api:query-key":
    type: API_KEY
    storage:
      mode: NONE
    key: "dummy-local-key"
    queryParam: api_key
```

```yaml
authRef:
  profileId: "api:query-key"
  applyAs: HTTP_QUERY_PARAM
```

Basic auth:

```yaml
profiles:
  "api:basic":
    type: BASIC_AUTH
    storage:
      mode: NONE
    username: demo
    password:
      env: DEMO_PASSWORD
```

```yaml
authRef:
  profileId: "api:basic"
  applyAs: HTTP_HEADER
```

OAuth client credentials:

```yaml
profiles:
  "api:oauth":
    type: OAUTH2_CLIENT_CREDENTIALS
    storage:
      mode: REDIS
      tokenKey: api-oauth
    refresh:
      refreshAheadSeconds: 60
      leaseSeconds: 15
    tokenUrl: "{{ sut.auth.tokenUrl }}"
    clientId: "{{ vars.clientId }}"
    clientSecret:
      env: API_CLIENT_SECRET
```

```yaml
authRef:
  profileId: "api:oauth"
  applyAs: HTTP_AUTHORIZATION_BEARER
```

## HTTP Sequence

Every step can use its own `authRef`. Steps may share the same profile, mix profiles, or repeat a profile in any order. Token reuse is controlled by the Redis `tokenKey` and resolved profile fingerprint, not by sequence position.

```yaml
steps:
  - id: token-backed-read
    callId: get-account
  - id: api-key-write
    callId: post-update
```

The templates named by `callId` carry their own `authRef`.

## TCP And ISO8583

TCP payload prefix:

```yaml
profiles:
  "tcp:prefix":
    type: MESSAGE_FIELD_AUTH
    storage:
      mode: NONE
    value: "AUTH|"
```

```yaml
authRef:
  profileId: "tcp:prefix"
  applyAs: TCP_PAYLOAD_PREFIX
```

ISO8583 MAC:

```yaml
profiles:
  "iso:mac":
    type: ISO8583_MAC
    storage:
      mode: NONE
    macKey:
      env: ISO_MAC_KEY
```

```yaml
authRef:
  profileId: "iso:mac"
  applyAs: ISO8583_MAC_FIELD
```

mTLS client certificate:

```yaml
profiles:
  "tcp:mtls":
    type: TLS_CLIENT_CERT
    storage:
      mode: NONE
    keyStorePath: /run/secrets/client.p12
    keyStorePassword:
      env: CLIENT_KEYSTORE_PASSWORD
    keyStoreType: PKCS12
```

```yaml
authRef:
  profileId: "tcp:mtls"
  applyAs: MTLS_CLIENT_CERT
```

Use a `tcps://` base URL for TLS transports. The processor turns the mTLS profile into typed transport options; it is never represented as an HTTP-style header.

## Redis Keys

Refreshable tokens use this key family:

```text
ph:tokens:<swarmId>:record:<tokenKey>
ph:tokens:<swarmId>:lease:<tokenKey>
ph:tokens:<swarmId>:due
```

Use stable, human-readable `tokenKey` values such as `payments-api` or `tenant-a-api`. Do not include secrets in token keys.

## Migration

Replace legacy inline template `auth:` with:

1. a profile in `authProfiles.yaml`
2. an `authRef` on each template that needs auth
3. an explicit `applyAs` that matches the transport/application point

There is no runtime shim for old inline auth. This keeps no-auth workers clean and makes auth activation visible at the template boundary.
