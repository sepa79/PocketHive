# Auth Behavior

Status: implemented redesign

PocketHive auth is explicit, worker-scoped, and activated only by templates that declare `authRef`.

## Runtime Shape

- `authProfiles.yaml` is the scenario-bundle source of truth.
- Request templates reference profiles with `authRef.profileId` and `authRef.applyAs`.
- Workers without reachable `authRef` do not create an auth runtime, Redis token store, refresh path, or auth metrics.
- Inline template `auth:` is rejected at template-load time. There is no runtime compatibility path for legacy inline auth.

## Bundle Layout

`authProfiles.yaml` belongs at the scenario root or an ancestor of the template root:

```yaml
profiles:
  "payments:oauth":
    type: OAUTH2_CLIENT_CREDENTIALS
    storage:
      mode: REDIS
      tokenKey: payments-api
    tokenUrl: "{{ sut.auth.tokenUrl }}"
    clientId: "{{ vars.clientId }}"
    clientSecret:
      env: PAYMENTS_CLIENT_SECRET
```

HTTP/TCP/ISO8583 templates then opt in:

```yaml
protocol: HTTP
callId: get-balance
method: GET
pathTemplate: /balance/{{ vars.accountId }}
headersTemplate: {}
bodyTemplate: ""
authRef:
  profileId: "payments:oauth"
  applyAs: HTTP_AUTHORIZATION_BEARER
```

## Validation

PocketHive rejects:

- duplicate YAML profile keys
- unknown profile references
- unknown `applyAs` values
- inline `auth:`
- templates containing both `auth:` and `authRef`
- refreshable profiles without `storage.mode: REDIS`
- non-refresh profiles without `storage.mode: NONE`
- reused Redis token keys with different profile fingerprints in the same activation set

Validation errors name the profile, template, or apply mode where possible.

## Token Lifecycle

Refreshable strategies use Redis only. Keys are scoped by swarm:

- `ph:tokens:<swarmId>:record:<tokenKey>`
- `ph:tokens:<swarmId>:lease:<tokenKey>`
- `ph:tokens:<swarmId>:due`

Redis operations claim leases, store records, release leases, and maintain the due index atomically with Lua. There is no in-memory token fallback and no `KEYS` scan.

Profiles sharing a `tokenKey` may reuse the same token only when their config fingerprint matches. If two active profiles use the same token key with different resolved configuration, startup/work-item processing fails explicitly.

The deployed proof scenario `scenarios/e2e/auth-proving-profile-collision` exercises this case: no protected request is sent, no OAuth token endpoint is called, no Redis token record is written, and one redacted `runtime.exception` journal alert is emitted for the repeated worker failures.

## Strategies

Supported profile types:

- `BEARER_TOKEN`
- `STATIC_TOKEN`
- `BASIC_AUTH`
- `API_KEY`
- `OAUTH2_CLIENT_CREDENTIALS`
- `OAUTH2_PASSWORD_GRANT`
- `OAUTH2_HTTP_SIGNATURE`
- `HMAC_SIGNATURE`
- `AWS_SIGNATURE_V4`
- `MESSAGE_FIELD_AUTH`
- `ISO8583_MAC`
- `TLS_CLIENT_CERT`

Refreshable strategies are OAuth client credentials, OAuth password grant, and OAuth HTTP Signature. Static strategies do not write Redis token records.

### OAuth HTTP Signature

`OAUTH2_HTTP_SIGNATURE` is an isolated client-credentials profile. It signs only
the token-acquisition POST with RSA-SHA256, using the fixed signing order
`(request-target) host date digest`. The `Digest` covers the exact form-encoded
request bytes; the canonical string uses LF separators without a trailing
newline. The token request carries `Authorization: Signature ...`.

The token endpoint must use HTTPS. The form's exact content type is
`application/x-www-form-urlencoded;charset=UTF-8`. Keys must be unencrypted
PKCS#8 RSA PEM with a modulus of at least 2,048 bits; configured identifiers are
preserved without trimming.

After acquisition, `authRef.applyAs: HTTP_AUTHORIZATION_BEARER` applies the access
token to downstream requests. Signing headers and private-key material are not
applied to those requests. The profile reuses existing Redis records, atomic
leases, and redacted failure reporting, with these isolated lifecycle rules:

- Require a valid Bearer `access_token` string, a case-insensitive `Bearer`
  `token_type` string, and a positive integer JSON `expires_in` no greater than
  `2147483647`; no missing-field defaults or invalid tokens enter the cache.
- Calculate expiry from token-acquisition start and recheck expiry against the
  current time after token-endpoint I/O, Redis I/O, and refresh metrics callbacks.
- Reuse a cached token until expiration; `refreshAheadSeconds` and
  `emergencyRefreshAheadSeconds` are ignored for this profile, and its stored
  refresh time equals expiration.
- Check the shared cache again after claiming the refresh lease; when another
  worker owns the lease, poll every 25 milliseconds with a 30-second deadline
  for a valid shared token. Redis commands retain their existing timeouts;
  timeout, interruption, and Redis unavailability fail explicitly.
- Limit the token HTTP deadline, including complete response-body consumption,
  to 15 seconds or the remaining lease time minus one second, whichever is
  shorter. Timeout or interruption cancels the request; an expired lease fails
  acquisition.
- Release refresh claims on signing, HTTP, or token-response failure.
- Compute configuration fingerprints deterministically across JVMs for this
  profile, preserving shared-token isolation.

This profile has its own `keyId`, RSA `privateKey`, required `scopes` list, and
optional `audience`. Existing profile fields, schemas, response parsing,
fingerprints, and refresh behavior remain unchanged. See
[OAuth HTTP Signature configuration and flow](AUTH-USER-GUIDE.md#oauth-http-signature)
for the complete configuration, prototype migration guidance, and protocol
boundary. Actual provider interoperability remains unverified.

## Application Points

HTTP request auth supports bearer authorization, headers, query parameters, and HMAC headers.

TCP request auth supports payload prefix and payload field material in the request builder, plus processor-stage mTLS transport options.

ISO8583 request auth supports processor-stage MAC application. The processor applies ISO8583 auth from typed request metadata, not pseudo-headers.

mTLS uses `applyAs: MTLS_CLIENT_CERT` with a `TLS_CLIENT_CERT` profile that points to an explicit external keystore reference. PocketHive reads the keystore to build the client TLS context but does not manage or rotate certificate material.

## Failure And Observability

Auth failures fail the affected work item or startup path explicitly. Tokens, passwords, signing keys, private keys, certificate passwords, and signed payload material must not be logged or journaled.

The runtime:

- increments auth apply, refresh, contention, failure, and recovery metrics
- logs the first failure per worker/profile/apply mode/stage
- logs throttled repeated-failure summaries
- logs recovery once the same profile/apply mode succeeds again
- publishes redacted status deltas for failure, summary, and recovery events

The runtime intentionally avoids logging exception messages for auth failures because those messages can contain URLs, paths, or provider details.
