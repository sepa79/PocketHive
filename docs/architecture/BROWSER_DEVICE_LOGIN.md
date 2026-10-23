# Callback free Pocket browser login

Owner: PH Auth, using Spring Authorization Server's OAuth device authorization
grant and the existing canonical OAuth authorization/consent stores. Approved UX:
confirm in a PH tab; the original Pocket app polls and completes login without
redirecting to its changing preview port. This is not another token issuer.

`BrowserOAuthClients` owns explicit `pockethive-device`, `pocketplanner-device` and `pocketdata-device`
registrations. They have no redirect URI, no secret, no refresh grant, and only
`pockethive:web` with audience `urn:pockethive:web`. Their access tokens use the
existing `phweb_` identity resolver and current-user checks. Product permissions
stay at the products; device confirmation never grants editing by itself.

`DeviceClientAuthenticationConverter` only decodes public client authentication;
`DeviceClientAuthenticationProvider` checks the canonical registration. The
existing resource boundary restricts exact browser scope/resource. Spring owns
device/user codes, expiry, pending responses, consent, denial, code
consumption and token issuance. No application-owned login transaction database
or duplicated code validator is introduced.

Protocol: POST `/oauth/device_authorization` with client_id/resource/scope;
open the returned verification_uri_complete at the fixed PH public issuer;
poll `/oauth/token` using the device_code grant, exact client_id and resource.
The device_code is a short-lived secret: never log it, use it as a URL parameter,
send it to another window or expose it to the confirmation page. A user_code in
the standard verification URL is not an access token. Honor interval, slow_down
and expires_in; terminate polling on denial, expiry, cancellation or success.
The selected Spring version does not itself enforce a polling throttle; this
slice does not claim server-side rate limiting. Consumers must honor the standard
five-second interval when interval is absent and handle slow_down if supplied.

`DeviceConfirmationController` maps the authenticated consent page and fixed
completion page to `DeviceConfirmationPageRenderer`; neither validates consent
state or writes tokens. Confirmation POSTs retain CSRF protection and Spring's
consent state validation. Pages escape all supplied display text and never accept
arbitrary return URLs. Existing authorization-code callback/PKCE rules are unchanged.

This first slice proves the grant through the official PH ingress. App consumers
must migrate explicitly; there is no silent switch from authorization code to
device login after an error. Retire displaced adapters at their app's cut-over.

## PocketHive UI cut-over

PH's own React UI uses the same callback-free browser credential lifecycle as
PP/PD, with its explicit `pockethive-device` registration. `PocketHiveSessionApi`
maps PH's existing OAuth and current-user endpoints through the shared bounded
HTTP transport; it does not mint tokens, persist identity or calculate permissions.
The shared controller owns polling, cancellation, expiry and credential storage.
`AuthProvider` projects the fresh PH identity and composes existing backend access
observations. The PH PKCE callback consumer is removed, not retained as a fallback.
Other explicitly registered redirect clients keep their existing exact-callback
rules; this migration does not loosen those rules or accept preview return URLs.

Sign-in opens the canonical PH Auth confirmation page in another tab. Its existing
provider login authenticates that page directly, without requiring a signed-in PH
React UI, so there is no circular login. The original UI remains on its current
origin and route, including journal run links and changing local preview ports.
A blocked popup offers an explicit confirmation button; cancelling stops polling.
Sign-out revokes this PH client's token, clears its local credential only on
successful revocation, then offers the existing CSRF-protected central logout.
No tokens or device secrets are transferred between windows or placed in URLs.

The TCP administration UI's `/auth-session.js` entry is a token-only projection
through the shared `BrowserTokenStore`; it does not persist another user/session
model. TCP still resolves identity and grants through its existing backend API.

Local verification, 2026-10-21: 132 PH UI tests, 89 shared browser-auth tests,
91 focused/backend auth regression tests and the three repository import-boundary
checks pass. The normal UI image, including Docusaurus, builds successfully.
Deployed browser checks through the official PH ingress at 8088 pass with two
temporary forwarding origins: initial device sign-in, reload, central-session
reuse on a second port, token revocation, central logout, renewed provider login
and view-only user permission preservation. Both PP (8765) and PD (8785) reuse
that PH central login through their real UIs without another provider login;
their views stay unchanged. These checks create no application
data. Evidence is in `/tmp/ph-device-browser-evidence-20261021.json` and the
corresponding dated build/test logs. The desktop's private preview URL was not
reachable by the test browser; this is not evidence of its particular forwarding
or popup policy. The stack's native ports are PH 8088, PP 8765 and PD 8785.

Reference: https://docs.spring.io/spring-authorization-server/reference/protocol-endpoints.html

## Central logout without callbacks

Device consumers discover the canonical public sign-out URL with
`GET /api/auth/device/config?client_id=pocketdata-device` (or
`pocketplanner-device`). The response is `{ "logoutUri": "<PH issuer>/oauth/device/logout?client_id=<client>" }`
with `Cache-Control: no-store`. `BrowserOAuthClients.requireDeviceClient` validates
the registration; redirect clients and unknown clients return HTTP 400. The URL
is built from the configured PH issuer, never Host/Forwarded headers or an app
return address. The standard device authorization response is unchanged.

`DeviceLogoutController` owns this HTTP projection and delegates presentation to
`DeviceLogoutPageRenderer`. GET `/oauth/device/logout?client_id=...` displays a
PH-themed confirmation and does not sign the user out. POST on the same path
requires the browser's CSRF token and a registered device client, and delegates
central session invalidation/context clearing to Spring's
`SecurityContextLogoutHandler`. It returns a fixed completion page, not a redirect:
the user closes the PH tab and returns to the original tool. No callback,
return URL, login transaction or token issuer is added. Both pages are no-store;
display values are escaped. The existing application security chain protects
the POST; this path is not an OAuth protocol endpoint or an API CSRF exemption.

Consumers clear their local credentials and revoke their own access token through
the existing OAuth revocation endpoint separately. Central logout ends the PH
browser login only: it does not revoke previously issued tokens in other tools,
cancel an already approved device grant or sign out every app session. The page
makes this distinction explicit. Existing exact-redirect browser logout stays
separate and continues to reject device clients.

## Shared consumer boundary

`common/auth-client` owns bounded, timeout-limited, no-redirect HTTP transport for
identity and device operations. `AuthServiceClient` owns typed identity requests;
`DeviceAuthClient` owns device authorization, polling and token revocation. Neither
stores tokens, grants product permissions or retries a request. OAuth pending,
denied and expired responses retain their protocol error codes. HTTP failures and
malformed responses are safe explicit failures, never a switch to another flow.

The device response DTOs validate HTTP(S) URI structure without selecting a
consumer transport policy. `DeviceAuthClient` requires an explicit
`allowRemoteHttp` constructor argument for the returned public verification and
logout endpoints. Pass `false` for HTTPS and loopback-only HTTP; pass `true` only
for an approved remote HTTP deployment. Validation delegates to
`PublicEndpointTransportPolicy`; an HTTP response URL never enables the exception.
The separately configured auth-service base URL may be an internal HTTP service
address and does not determine the public endpoint permission.

The selected JDK HTTP transport cancels its original input stream on rejection;
it must not call a Spring response close operation that drains an untrusted body.
Only the URI/deadline constructor is available to production consumers. Raw
RestClient injection is package-private for the library's isolated transport tests.
The MCP owner-auth adapter also uses this boundary, with explicit configurable
`pockethive.mcp.owner-auth.connect-timeout` / `read-timeout` (both default 5s).

PocketData adopts this boundary through its session HTTP adapter. Its access
policy still verifies current active identity on each privileged request. The
shared browser module owns the one in-window polling transaction; the pure shared
UI package owns no credentials, token storage or product authorization decisions.
