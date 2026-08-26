import assert from 'node:assert/strict';
import test from 'node:test';

import { companionOAuthRedirectUri } from '../connection/companionOAuthClient';
import { LoopbackBrowserAuthorization } from '../connection/loopbackBrowser';
import { CALLBACK_LOGO_DATA_URI } from '../generated/callbackLogo';

test('accepts one exact IPv4 loopback callback and closes the listener', async () => {
  let redirectUri: string | undefined;
  let listenerReady!: () => void;
  const ready = new Promise<void>(resolve => { listenerReady = resolve; });
  const browser = new LoopbackBrowserAuthorization(async url => {
    const authorization = new URL(url);
    redirectUri = authorization.searchParams.get('redirect_uri') ?? undefined;
    assertDynamicRedirect(redirectUri);
    listenerReady();
    return true;
  });
  const authorization = browser.authorize(
    callback => `https://issuer.example/oauth/authorize?redirect_uri=${encodeURIComponent(callback)}`,
    new AbortController().signal,
  );
  await ready;

  const callbackUrl = `${redirectUri}?code=code&state=state`;
  const response = await fetch(callbackUrl);
  assert.equal(response.status, 200);
  const html = await response.text();
  assert.match(html, /PocketHive sign-in complete/);
  assert.match(html, /Return to VS Code/);
  assert.match(html, /auth-shell/);
  await assertCanonicalCallbackLogo(response, html);
  assert.equal((await authorization).callback.toString(), callbackUrl);
  assert.equal((await authorization).redirectUri, redirectUri);
  await assert.rejects(fetch(redirectUri!), /fetch failed/);
});

test('renders a themed cancellation callback page before handing control back to VS Code', async () => {
  let redirectUri: string | undefined;
  let listenerReady!: () => void;
  const ready = new Promise<void>(resolve => { listenerReady = resolve; });
  const browser = new LoopbackBrowserAuthorization(async url => {
    redirectUri = new URL(url).searchParams.get('redirect_uri') ?? undefined;
    assertDynamicRedirect(redirectUri);
    listenerReady();
    return true;
  });
  const authorization = browser.authorize(
    callback => `https://issuer.example/oauth/authorize?redirect_uri=${encodeURIComponent(callback)}`,
    new AbortController().signal,
  );
  await ready;

  const callbackUrl = `${redirectUri}?error=access_denied&state=state`;
  const response = await fetch(callbackUrl);
  assert.equal(response.status, 200);
  const html = await response.text();
  assert.match(html, /Sign-in cancelled/);
  assert.match(html, /PocketHive/);
  await assertCanonicalCallbackLogo(response, html);
  assert.equal((await authorization).callback.toString(), callbackUrl);
});

test('renders a themed OAuth error page for non-cancel redirect failures', async () => {
  let redirectUri: string | undefined;
  let listenerReady!: () => void;
  const ready = new Promise<void>(resolve => { listenerReady = resolve; });
  const browser = new LoopbackBrowserAuthorization(async url => {
    redirectUri = new URL(url).searchParams.get('redirect_uri') ?? undefined;
    assertDynamicRedirect(redirectUri);
    listenerReady();
    return true;
  });
  const authorization = browser.authorize(
    callback => `https://issuer.example/oauth/authorize?redirect_uri=${encodeURIComponent(callback)}`,
    new AbortController().signal,
  );
  await ready;

  const callbackUrl = `${redirectUri}?error=server_error&state=state`;
  const response = await fetch(callbackUrl);
  assert.equal(response.status, 200);
  const html = await response.text();
  assert.match(html, /did not complete/);
  assert.match(html, /server_error/);
  await assertCanonicalCallbackLogo(response, html);
  assert.equal((await authorization).callback.toString(), callbackUrl);
});

test('aborting authorization closes the callback listener', async () => {
  let redirectUri: string | undefined;
  let listenerReady!: () => void;
  const ready = new Promise<void>(resolve => { listenerReady = resolve; });
  const controller = new AbortController();
  const browser = new LoopbackBrowserAuthorization(async url => {
    redirectUri = new URL(url).searchParams.get('redirect_uri') ?? undefined;
    assertDynamicRedirect(redirectUri);
    listenerReady();
    return true;
  });
  const authorization = browser.authorize(
    callback => `https://issuer.example/oauth/authorize?redirect_uri=${encodeURIComponent(callback)}`,
    controller.signal,
  );
  await ready;

  controller.abort();

  await assert.rejects(authorization, /OAUTH_AUTHORIZATION_CANCELLED/);
  await assert.rejects(fetch(redirectUri!), /fetch failed/);
});

test('constructs only valid explicit TCP callback ports', () => {
  assert.equal(companionOAuthRedirectUri(1), 'http://127.0.0.1:1/callback');
  assert.equal(companionOAuthRedirectUri(65_535), 'http://127.0.0.1:65535/callback');
  for (const port of [0, -1, 65_536, 1.5, Number.NaN, Number.POSITIVE_INFINITY]) {
    assert.throws(() => companionOAuthRedirectUri(port), /OAUTH_CALLBACK_PORT_INVALID/);
  }
});

function assertDynamicRedirect(value: string | undefined): asserts value is string {
  assert.ok(value);
  const redirect = new URL(value);
  assert.equal(redirect.protocol, 'http:');
  assert.equal(redirect.hostname, '127.0.0.1');
  assert.equal(redirect.pathname, '/callback');
  assert.match(redirect.port, /^[1-9][0-9]{0,4}$/);
  assert.equal(Number(redirect.port) <= 65_535, true);
}

async function assertCanonicalCallbackLogo(response: Response, html: string): Promise<void> {
  assert.equal(response.headers.get('content-security-policy'),
    "default-src 'none'; img-src data:; style-src 'unsafe-inline'");
  const source = html.match(
    /<img class="auth-brand__logo" src="([^"]+)" alt="PocketHive">/,
  )?.[1];
  assert.equal(source, CALLBACK_LOGO_DATA_URI,
    'Callback must render the generated canonical PocketHive logo');
  assert.doesNotMatch(html, /auth-brand__mark/, 'Callback must not render a CSS-drawn substitute logo');
  assert.doesNotMatch(html, /VS Code connection hand-off/,
    'Callback must not duplicate the tagline already present in the canonical logo');
  assert.match(html, /width: min\(100%, 220px\)/,
    'Callback must render the canonical logo at the approved narrow-screen size');
}
