# Pocket browser auth

Shared, headless React device login for Pocket applications. Protocol owner:
[PH browser device login](../../docs/architecture/BROWSER_DEVICE_LOGIN.md).
This package does not choose roles, grant editing, theme a button, or issue tokens.

## API

```tsx
const auth = usePocketAuth({ storageKey: 'POCKETDATA_DEVICE_SESSION', onError })
// auth.session: { user: { username, displayName } | null, canEdit, configured }
// auth.loading: initial restoration or sign-out request is in flight
// auth.signingIn: waiting for PH device confirmation
// auth.error: PocketAuthError | null (safe message; no server response/token)
// auth.confirmationUrl: safe PH confirmation/logout link, or null
// auth.signIn(): Promise<void>
// auth.cancelSignIn(): void
// auth.openConfirmation(): void (call directly from a user click)
// auth.signOut(): Promise<void>
// auth.refresh(): Promise<void> (revalidate the current credential and full identity)
// auth.getAccessToken(): string | null (the controller's current, unexpired bearer)
// auth.fetchWithAuth(input, init?): Promise<Response>
```

Render `error.message` normally, not as HTML. When a popup is blocked, render a
button calling `openConfirmation()` or a link to `confirmationUrl` with
`target="_blank" rel="noopener noreferrer"`. Polling still runs in the original
window. Call `signIn` directly in the button handler: it opens a placeholder tab
synchronously, then navigates that tab to PH. No callback or preview origin is
registered. Do not place this hook in multiple components for the same storage key;
call it once at the product root and distribute its result through product context.

## Explicit transport adapters

`SessionApiPort<Session extends PocketSession>` defines `session(token, signal)`,
`start(token, signal)`, `poll(deviceCode, token, signal)` and `logout(token, signal)`.
It also supplies `anonymousSession`, a typed initial projection with `user: null`
and `canEdit: false`; `configured` is explicit for the product. The default
`SessionApi` starts with `configured: false` and retains the same-origin backend
contract below. An adapter maps wire responses only: it must not store credentials,
schedule polling, grant product permissions or manage token lifetime.

Pass a stable adapter instance as `usePocketAuth({ storageKey, api, onError })` to
retain a product's full session subtype throughout snapshots and results. A custom
session type requires an explicit adapter; there is no protocol fallback. Keep the
instance outside the component or memoize it: changing `api` or `storageKey` replaces
the controller and cancels its pending work. Default callers share one stateless
`SessionApi`, not a new adapter each render. `refresh()` uses the same authoritative
session restoration path, including cancellation, expiry and 401 handling.
`getAccessToken()` is a read-only projection of that controller's unexpired credential,
safe to read during rendering. It returns `null` at local expiry without publishing
state or writing storage; the expiry timer and authenticated request path own those
transitions. It adds no token cache or writer. Refresh discards a previously held
credential and its expiry timer when canonical storage is empty or expired, before
requesting the anonymous session. Identity and permissions still come from the backend.

`SessionHttpClient.request(path, init, token, signal, options?)` is the shared bounded
same-origin I/O boundary for both JSON and URL-encoded OAuth adapters. It returns
`{ body: unknown, status, requestedAt }`, always enforces no-store and no redirects,
and uses the credential attachment, timeout, bounded JSON and safe errors described
below. `polling: true` permits HTTP 400 for the canonical polling parser only.
`empty: true` skips reading/draining a successful response body and returns `body: null`
for operations such as OAuth revocation; errors and 401 remain explicit failures.
Adapters reuse the exported `parseDeviceAuthorization`, `parseDevicePoll`,
`readSessionJson` and `confirmationUrl`; they must not duplicate those validators.

`BrowserTokenStore` and `StoredToken` are exported for token-only consumers such as
a full-document transport surface. Those consumers may read the canonical store;
the mounted controller remains the sole lifecycle writer. Do not persist a second
identity projection, copy validation logic or introduce another credential writer.

`fetchWithAuth` accepts only the current origin, overwrites Authorization with the
owned bearer, and refuses redirects. A 401 clears only the credential that made
that request, never a newer login. Credential invalidation also retires pending
session restoration: a delayed refresh success cannot restore the cleared bearer
or identity, and a delayed failure cannot overwrite the invalidation error. An
explicit replacement sign-in remains independent. Product HTTP errors other than 401 remain the
caller's concern. Token/device codes never appear in URLs, callbacks, cross-window
messages or error text. Browser session storage is cleared on local expiry, logout
and 401. No refresh, retry-on-failure or legacy PKCE adapter exists in this package.

## Same-origin backend contract

| Request | Response |
| --- | --- |
| GET `/api/session` | `{user: {username, displayName} \| null, canEdit: boolean, configured: boolean}` |
| POST `/api/session/device/start` body `{}` | RFC `device_code`, `user_code`, `verification_uri`, `verification_uri_complete`, `expires_in`; optional `interval` |
| POST `/api/session/device/poll` body `{deviceCode}` | RFC `access_token`, `token_type: "Bearer"`, `expires_in`, `scope` |
| Poll HTTP 400 | `{error: "authorization_pending" \| "slow_down" \| "access_denied" \| "expired_token"}` |
| POST `/api/session/logout` body `{}` | `{logoutUrl: string}` |

All calls include the current bearer when available. Identity/permissions always
come from the fresh session endpoint, not from token claims or a username test.
Requests use no-store, same-origin credentials, no redirects, a 15-second timeout
and a 64-KiB JSON response limit. Verification URLs must be absolute HTTP(S),
share an origin, and contain no embedded credentials,
fragment or OAuth secret query fields. A nonempty logout URL obeys the same rules;
the backend is responsible for selecting canonical PH destinations and deployment
TLS policy. This headless client does not guess a production hostname or LAN policy.

The RFC default polling interval is five seconds; `slow_down` increases the current
interval by five seconds. There is only one in-flight poll. Expiry, cancellation,
denial, unmount, a newer attempt and any terminal error stop requests and timers.
The device grant is held only in memory; reloading cancels the pending attempt.
Stored access tokens include a local expiry and are revalidated through PH before
the hook exposes a signed-in identity. An unavailable PH is an explicit error, not
a local authorization fallback.

Logout removes this tab's credential only after the backend confirms successful
revocation. A failed request closes the reserved tab, reports the error and keeps
the existing credential/identity so the user is informed before any local logout.
A nonempty `logoutUrl` opens the
canonical PH logout page in another tab. **An empty URL means local logout only**:
no claim that the central PH session ended. A successful backend response must
still mean the bearer was revoked; the empty URL suppresses only the PH-tab step. Nothing
redirects the original application to PH or a fixed preview port.

## RESP-POCKET-BROWSER-AUTH

Current owner: `PocketAuthController` owns one mounted browser session lifecycle.
`usePocketAuth` binds that controller to React. `DeviceTokenPoller` alone owns the
device polling schedule; the explicitly selected `SessionApiPort` adapter maps its
wire protocol (`SessionApi` is the default). `SessionHttpClient` alone owns bounded
auth HTTP I/O; boundary parsers validate their own responses. `BrowserTokenStore` owns session
storage, `ConfirmationTab` owns PH-tab interaction and `authenticatedRequest` owns
same-origin credential attachment. None owns PH identity state, product policy,
token issuance or product UI. Consumers must remove their displaced adapters at
cut-over; adding the library is not proof that a consumer already uses it.

## Local build

Run `npm ci`, `npm test`, `npm run typecheck`, then `npm run build`. Use
`npm pack --pack-destination /tmp` for a self-contained local dependency, or a
`file:../packages/browser-auth` dependency **after building this package**. React is
a peer dependency and must be deduplicated by the consuming Vite application.
The archive includes compiled ESM and declarations, not test fixtures. No server,
operational data or release/publish step is involved.
