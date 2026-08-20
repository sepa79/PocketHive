import assert from 'node:assert/strict';
import test from 'node:test';

import { LoopbackBrowserAuthorization } from '../connection/loopbackBrowser';

test('accepts one exact IPv4 loopback callback and closes the listener', async () => {
  let listenerReady!: () => void;
  const ready = new Promise<void>(resolve => { listenerReady = resolve; });
  const browser = new LoopbackBrowserAuthorization(async url => {
    assert.equal(url, 'https://issuer.example/oauth/authorize');
    listenerReady();
    return true;
  });
  const authorization = browser.authorize(
    'https://issuer.example/oauth/authorize',
    new AbortController().signal,
  );
  await ready;

  const response = await fetch('http://127.0.0.1:57548/callback?code=code&state=state');
  assert.equal(response.status, 200);
  const html = await response.text();
  assert.match(html, /PocketHive sign-in complete/);
  assert.match(html, /Return to VS Code/);
  assert.match(html, /auth-shell/);
  assert.equal((await authorization).toString(),
    'http://127.0.0.1:57548/callback?code=code&state=state');
  await assert.rejects(fetch('http://127.0.0.1:57548/callback'), /fetch failed/);
});

test('renders a themed cancellation callback page before handing control back to VS Code', async () => {
  let listenerReady!: () => void;
  const ready = new Promise<void>(resolve => { listenerReady = resolve; });
  const browser = new LoopbackBrowserAuthorization(async () => {
    listenerReady();
    return true;
  });
  const authorization = browser.authorize(
    'https://issuer.example/oauth/authorize',
    new AbortController().signal,
  );
  await ready;

  const response = await fetch('http://127.0.0.1:57548/callback?error=access_denied&state=state');
  assert.equal(response.status, 200);
  const html = await response.text();
  assert.match(html, /Sign-in cancelled/);
  assert.match(html, /PocketHive/);
  assert.equal((await authorization).toString(),
    'http://127.0.0.1:57548/callback?error=access_denied&state=state');
});

test('renders a themed OAuth error page for non-cancel redirect failures', async () => {
  let listenerReady!: () => void;
  const ready = new Promise<void>(resolve => { listenerReady = resolve; });
  const browser = new LoopbackBrowserAuthorization(async () => {
    listenerReady();
    return true;
  });
  const authorization = browser.authorize(
    'https://issuer.example/oauth/authorize',
    new AbortController().signal,
  );
  await ready;

  const response = await fetch('http://127.0.0.1:57548/callback?error=server_error&state=state');
  assert.equal(response.status, 200);
  const html = await response.text();
  assert.match(html, /did not complete/);
  assert.match(html, /server_error/);
  assert.equal((await authorization).toString(),
    'http://127.0.0.1:57548/callback?error=server_error&state=state');
});

test('aborting authorization closes the callback listener', async () => {
  let listenerReady!: () => void;
  const ready = new Promise<void>(resolve => { listenerReady = resolve; });
  const controller = new AbortController();
  const browser = new LoopbackBrowserAuthorization(async () => {
    listenerReady();
    return true;
  });
  const authorization = browser.authorize('https://issuer.example/oauth/authorize', controller.signal);
  await ready;

  controller.abort();

  await assert.rejects(authorization, /OAUTH_AUTHORIZATION_CANCELLED/);
  await assert.rejects(fetch('http://127.0.0.1:57548/callback'), /fetch failed/);
});
