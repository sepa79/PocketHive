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
serviceId: default
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

## OAuth HTTP Signature

Use `OAUTH2_HTTP_SIGNATURE` when the OAuth token endpoint requires an RSA-SHA256
HTTP Signature on the client-credentials request. The canonical type key
`oauth2-http-signature` is also accepted. This is a separate profile; ordinary
OAuth and HMAC profiles retain their existing configuration and behavior.

Create a new profile with a separate Redis token key:

```yaml
profiles:
  "api:oauth-http-signature":
    type: OAUTH2_HTTP_SIGNATURE
    storage:
      mode: REDIS
      tokenKey: api-oauth-http-signature
    refresh:
      refreshAheadSeconds: 0 # Both refresh-ahead settings are ignored for this profile.
      emergencyRefreshAheadSeconds: 0
      leaseSeconds: 30
    tokenUrl: "https://identity.example.com/oauth/token"
    clientId: "example-client"
    keyId: "example-rsa-key"
    privateKey:
      file: /run/secrets/oauth-signing-key.pem
    scopes:
      - accounts.read
      - payments.read
    audience: "https://api.example.com" # Optional; omit when not required.
```

A standalone [example authProfiles.yaml](examples/oauth2-http-signature/authProfiles.yaml)
is available to copy into a bundle. Replace its example endpoint, identifiers,
scopes, audience, and secret reference with provider-approved values.

| Field | Configuration |
| --- | --- |
| `tokenUrl` | Required absolute, ASCII-encoded HTTPS token endpoint URL, without userinfo or a fragment. Plain HTTP and hostnames ending with a dot are rejected; the JDK TLS client does not support trailing-dot hostnames. |
| `clientId` | Required nonblank OAuth client identifier, preserved exactly and sent as `client_id` in the token form. |
| `keyId` | Required nonblank, printable ASCII signing-key identifier understood by the token provider, preserved exactly and quoted in the Signature header. |
| `privateKey` | Required unencrypted PKCS#8 RSA PEM with a modulus of at least 2,048 bits, resolved using the existing `file` or `env` secret reference mechanism. |
| `scopes` | Required YAML list of nonempty OAuth scope tokens, each without whitespace, double quotes, or backslashes. Values are joined with spaces into the OAuth `scope` parameter. Use `[]` to omit that parameter. |
| `audience` | Optional nonblank string, preserved exactly and sent as `audience` when present. Omit the field entirely when it is not required. |
| `storage` | Required `mode: REDIS` and valid `tokenKey`, using the existing token store. |
| `refresh` | Uses `leaseSeconds` for Redis refresh coordination, with a minimum of `2` seconds; `30` seconds is recommended. This profile reuses tokens until expiration and ignores both `refreshAheadSeconds` and `emergencyRefreshAheadSeconds`; set both to `0` to make that policy explicit. |

The RSA PEM must have `-----BEGIN PRIVATE KEY-----` and
`-----END PRIVATE KEY-----` boundaries. PKCS#1 (`BEGIN RSA PRIVATE KEY`), encrypted
PEM, non-RSA keys, and RSA keys smaller than 2,048 bits are rejected. No key-format
or algorithm fallback runs.
An environment reference such as `privateKey: { env: OAUTH_SIGNING_PRIVATE_KEY }`
resolves to the PEM content, not a file path. Keep the private key in the external
secret source; PocketHive does not manage its generation, registration, or
rotation. The provider must already associate `keyId` with the matching public
key. This profile does not send `client_secret`.

Configured identifiers are not trimmed. Supply the exact `clientId`, `keyId`,
and `audience` values registered with the provider; leading or trailing spaces
remain part of those values.

### Signing and token acquisition

1. Build an HTTPS POST with the exact header
   `Content-Type: application/x-www-form-urlencoded;charset=UTF-8` and a UTF-8 body containing
   `grant_type=client_credentials`, `client_id`, the space-joined `scope` when
   nonempty, and `audience` when configured. Form encoding happens once before
   hashing and sending.
2. Compute `Digest: SHA-256=<Base64(SHA-256(request body bytes))>` over those exact
   transmitted bytes, then generate the current HTTP `Date` in GMT.
3. Construct the signing string in the fixed order shown below, with lowercase
   field names, one space after each colon, LF separators, and no trailing
   newline. `(request-target)` contains lowercase `post`, the raw encoded path,
   and the raw query string when nonempty; an empty path is `/`. A trailing empty
   `?` is omitted to match the JDK transport.
4. Sign the UTF-8 canonical string with the configured RSA private key using
   SHA-256 and PKCS#1 v1.5 padding (`SHA256withRSA` in Java), then Base64-encode
   the signature.
5. Send the POST with the `Digest`, `Date`, and `Authorization` headers. This
   profile uses HTTP/1.1 and the JDK-generated `Host`; the signed host matches
   that authority, omitting default ports and retaining nondefault ports.
6. Validate the successful JSON response using this profile's strict Bearer-token
   rules below. Calculate expiry from the start of token acquisition, then store
   a still-valid token using the existing Redis record and lease operations.

For a token URL with a query string, the canonical shape is:

```text
(request-target): post /oauth/token?tenant=example
host: identity.example.com
date: <the exact Date header value>
digest: SHA-256=<Base64 digest of the token form>
```

The generated authorization value has this shape (angle-bracket values are
illustrative):

```text
Authorization: Signature keyId="example-rsa-key",algorithm="rsa-sha256",headers="(request-target) host date digest",signature="<Base64 RSA signature>"
```

This implements the requested legacy `Authorization: Signature` format described
by [draft-cavage-http-signatures-12](https://datatracker.ietf.org/doc/html/draft-cavage-http-signatures-12).
That draft is expired; this profile does not implement
[RFC 9421 HTTP Message Signatures](https://www.rfc-editor.org/rfc/rfc9421.html). Confirm that the provider expects this exact algorithm, signed-field
set, and canonicalization. SDK verification does not establish interoperability
with a particular provider.

### Token reuse and downstream requests

This profile validates the token response separately from ordinary OAuth profiles:

- `access_token` must be a nonempty JSON string containing a valid Bearer token:
  ASCII letters, digits, `-`, `.`, `_`, `~`, `+`, or `/`, followed by optional `=`
  padding. Whitespace, control characters, and other characters are rejected.
- `token_type` is required and must be the JSON string `Bearer`, matched
  case-insensitively.
- `expires_in` is required and must be a positive integer JSON number no greater
  than `2147483647`. Strings, fractional numbers, zero, negative values, and
  missing values are rejected; this profile supplies no default token lifetime.

Expiry is calculated conservatively from the start of token acquisition, so
signing, network, and response-processing time do not extend the provider's token
lifetime. The runtime checks the current time again after token-endpoint and
Redis I/O and refresh metrics callbacks before returning a token. A response that
is already expired is rejected.
Failed HTTP responses, invalid token responses, and signing failures release the
refresh claim; invalid tokens are never cached.

A cached token is reused until expiration. For this profile only,
both `refreshAheadSeconds` and `emergencyRefreshAheadSeconds` are ignored, and the
stored refresh time equals expiration;
set both fields to `0` to describe the behavior explicitly. The ordinary
OAuth profiles retain their existing refresh-ahead policy and response parsing.
Renewal makes another signed client-credentials request; it does not use an OAuth
`refresh_token` grant.

When a token is missing or expired, the existing Redis lease coordinates token
acquisition. A worker that obtains the lease checks the cache again before
contacting the token endpoint. If another worker holds the lease, the caller
polls for a valid shared token every 25 milliseconds, with a 30-second deadline.
Individual Redis commands retain their existing connection timeouts. Timeout,
interruption, or Redis unavailability fails the acquisition explicitly; an
expired token is never returned. Redis remains the only token cache.

Use `leaseSeconds: 30` to leave time for signing and token acquisition. The
resolved lease must be at least two seconds. The token HTTP deadline covers
response headers and the complete response body. It is at most 15 seconds and is
reduced to the remaining lease time minus a one-second safety budget. Timeout or
interruption cancels the in-flight HTTP request. The existing 15-second lease
default remains accepted, with a correspondingly shorter HTTP deadline. An exhausted lease budget
fails acquisition; the runtime does not publish a token after its lease expires.

Shared `tokenKey` values require matching resolved profile fingerprints. This
profile computes fingerprints deterministically across JVMs, so workers with the
same resolved configuration can share tokens. Different configurations fail
explicitly on a shared key. The token record contains no private key or signed
request. Fingerprints and lifecycle changes apply only to this profile.

Activate the new profile on each downstream HTTP template:

```yaml
protocol: HTTP
callId: get-account
serviceId: default
method: GET
pathTemplate: /accounts/{{ vars.accountId }}
headersTemplate: {}
bodyTemplate: ""
authRef:
  profileId: "api:oauth-http-signature"
  applyAs: HTTP_AUTHORIZATION_BEARER
```

PocketHive acquires or reuses the token and adds
`Authorization: Bearer <access_token>` automatically. The downstream API request
uses this Bearer header; the Signature, Digest, and Date generated during token
acquisition stay on the token request.

### Adoption and authoring

Existing profiles need no migration. To adopt signed token acquisition, add a
new profile with a distinct `profileId` and `tokenKey`, then reference it from
only the templates that need this integration. Existing auth schemas and
application modes remain unchanged.

If you evaluated an earlier prototype of `OAUTH2_HTTP_SIGNATURE`, recheck the
HTTPS, key-size, and token-response requirements above. The revised fingerprint
calculation is profile-specific. Use a fresh `storage.tokenKey` and coordinate
the configuration change across workers that share the profile, so old and new
fingerprints do not compete for the same record. Leave existing Redis data
untouched; no Redis clearing or migration is required for other profiles.

Author the profile directly in `authProfiles.yaml`. With the
[Java MCP](mcp/README.md#worker-oauth-authoring), pass that file and the downstream
request templates as complete `files[{path,content}]` entries to
`scenario_workflow_generate` after confirming the workflow requirements. The MCP
preserves each text value exactly, including custom profile IDs, token keys,
`keyId`, `scopes: []`, omitted or present `audience`, and `env`/`file` references.
Keep each template's `authRef.profileId` equal to the intended profile ID and
include its required `serviceId`. Ordinary OAuth profiles retain their own fields.

Generation produces proposed files. Review and commit them in the bundle's Git
workspace, package the exact committed files, prepare a validation ticket, upload
the ZIP through its `/mcp/uploads/<ticket>` URL, and read the owner's receipt.
Scenario Manager validates the bundle's structural auth references, types and
storage settings. The worker SDK validates resolved signing settings and performs
key loading and token acquisition at runtime. Proposal generation and ticket
preparation do not establish profile validity or provider acceptance.

The removed Node wizard's import, clone and enrich APIs are not part of this Java
flow. Keep private keys in approved external secret sources and author references
only; the MCP does not load them or contact the OAuth token endpoint.

### SDK verification

The signature, response-validation, concurrency, and HTTPS deadline tests run
with the worker SDK tests. HTTPS fixtures generate temporary certificates using
the Java 21 JDK's `keytool`; they do not disable certificate or hostname checks.
Set `AUTH_OPENSSL_TEST_EXECUTABLE` to the absolute path of the OpenSSL executable
for the required independent signature oracle. See
[Authentication regression tests](ci/auth-testing.md) for all prerequisites.

To include the public-runtime HTTPS/Redis integration test, set
`AUTH_REDIS_TEST_HOST` and `AUTH_REDIS_TEST_PORT` to a disposable Redis instance
before running:

```bash
./mvnw -pl common/worker-sdk,common/auth-client,scenario-manager-service -am test
```

The cross-JVM Redis test is skipped when those environment variables are absent.
It exercises signed token acquisition, shared caching, expiration, and endpoint
failure using a local HTTPS verifier. Provider acceptance still requires the
provider's sandbox and registered signing key.


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
