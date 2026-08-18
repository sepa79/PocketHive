import assert from 'node:assert/strict';
import test from 'node:test';

import { PocketHiveOAuthAuthentication } from '../connection/oauthAuthentication';
import { BrowserAuthorizationPort, OAuthSessionStore } from '../connection/contracts';
import { createConnectionProfile } from '../connection/profile';

const NOW = new Date('2026-08-18T12:00:00Z');
const profile = createConnectionProfile({
  id: 'local', displayName: 'Local', mcpUrl: 'http://127.0.0.1:8080/mcp',
  endpointSecurityMode: 'LOCAL_LOOPBACK_HTTP', secretKey: 'ph.local.oauth',
});
const endpoint = {
  mcpUrl: profile.mcpUrl,
  resourceMetadataUrl: 'http://127.0.0.1:8080/.well-known/oauth-protected-resource',
  authorizationServer: 'http://127.0.0.1:8080/auth-service',
};

test('performs exact S256 authorization-code flow and stores only OAuth session material', async () => {
  const requests: Array<{ url: string; init?: RequestInit }> = [];
  const saved = new Map<string, string>();
  let opened: URL | undefined;
  const browser: BrowserAuthorizationPort = {
    authorize: async authorizationUrl => {
      opened = new URL(authorizationUrl);
      return new URL(`http://127.0.0.1:57548/callback?code=code-123&state=${opened.searchParams.get('state')}`);
    },
  };
  const oauth = new PocketHiveOAuthAuthentication(
    async (url, init) => {
      requests.push({ url: String(url), init });
      if (requests.length === 1) return json(metadata());
      return json({
        access_token: 'opaque-access-token', token_type: 'Bearer', expires_in: 900,
        scope: 'pockethive:mcp:discover pockethive:mcp:read',
      });
    },
    browser,
    store(saved),
    length => Buffer.alloc(length, 7),
    () => NOW,
  );

  const session = await oauth.authenticate(profile, endpoint, new AbortController().signal);

  assert.deepEqual(session, { accessToken: 'opaque-access-token', expiresAt: '2026-08-18T12:15:00.000Z' });
  assert.equal(requests[0].url,
    'http://127.0.0.1:8080/auth-service/.well-known/oauth-authorization-server');
  assert.equal(requests[1].url, 'http://127.0.0.1:8080/auth-service/oauth/token');
  assert.equal(opened?.searchParams.get('response_type'), 'code');
  assert.equal(opened?.searchParams.get('client_id'), 'pockethive-vscode');
  assert.equal(opened?.searchParams.get('redirect_uri'), 'http://127.0.0.1:57548/callback');
  assert.equal(opened?.searchParams.get('resource'), profile.mcpUrl);
  assert.equal(opened?.searchParams.get('code_challenge_method'), 'S256');
  assert.match(opened?.searchParams.get('code_challenge') ?? '', /^[A-Za-z0-9_-]{43}$/);
  assert.equal(opened?.searchParams.get('scope'), 'pockethive:mcp:discover pockethive:mcp:read');
  const tokenBody = new URLSearchParams(String(requests[1].init?.body));
  assert.equal(tokenBody.get('grant_type'), 'authorization_code');
  assert.equal(tokenBody.get('code'), 'code-123');
  assert.equal(tokenBody.get('resource'), profile.mcpUrl);
  assert.equal(tokenBody.get('code_verifier')?.length, 86);
  assert.deepEqual(await oauth.session(profile), session);
  assert.equal(saved.size, 1);
  assert.doesNotMatch(JSON.stringify(profile), /opaque-access-token/);
});

test('maps access denial to cancellation and makes no token request', async () => {
  let calls = 0;
  const oauth = new PocketHiveOAuthAuthentication(
    async () => { calls += 1; return json(metadata()); },
    { authorize: async authorizationUrl => {
      const state = new URL(authorizationUrl).searchParams.get('state');
      return new URL(`http://127.0.0.1:57548/callback?error=access_denied&state=${state}`);
    } },
    store(new Map()),
    length => Buffer.alloc(length, 3),
    () => NOW,
  );

  await assert.rejects(oauth.authenticate(profile, endpoint, new AbortController().signal), error =>
    error instanceof Error && error.name === 'AuthenticationCancelledError');
  assert.equal(calls, 1);
});

test('requests an explicit action scope set without replacing the stored connection session', async () => {
  const requests: Array<{ url: string; init?: RequestInit }> = [];
  let opened: URL | undefined;
  const values = new Map<string, string>([[profile.secretKey, JSON.stringify({
    accessToken: 'connection-token', expiresAt: '2026-08-18T13:00:00.000Z',
  })]]);
  const authentication = new PocketHiveOAuthAuthentication(
    async (url, init) => {
      requests.push({ url: String(url), init });
      return requests.length === 1 ? json(metadata()) : json({
        access_token: 'access-token', token_type: 'Bearer', expires_in: 900,
      });
    },
    { authorize: async authorizationUrl => {
      opened = new URL(authorizationUrl);
      return new URL(`http://127.0.0.1:57548/callback?code=action-code&state=${opened.searchParams.get('state')}`);
    } },
    store(values),
    size => new Uint8Array(size).fill(7),
    () => new Date('2026-08-18T12:00:00Z'),
  );

  const result = await authentication.authenticateForScopes(
    profile,
    endpoint,
    ['pockethive:mcp:discover', 'pockethive:mcp:read', 'pockethive:mcp:author'],
    new AbortController().signal,
  );

  assert.equal(opened?.searchParams.get('scope'),
    'pockethive:mcp:discover pockethive:mcp:read pockethive:mcp:author');
  assert.equal(result.accessToken, 'access-token');
  assert.equal(JSON.parse(values.get(profile.secretKey) ?? '{}').accessToken, 'connection-token');
});

test('rejects metadata, callback state, and refresh-token drift without fallback', async () => {
  const invalidMetadata = new PocketHiveOAuthAuthentication(
    async () => json({ ...metadata(), code_challenge_methods_supported: ['plain'] }),
    { authorize: async () => { throw new Error('must not open'); } }, store(new Map()),
    length => Buffer.alloc(length, 1), () => NOW,
  );
  await assert.rejects(invalidMetadata.authenticate(profile, endpoint, new AbortController().signal), /OAUTH_METADATA_INVALID/);

  const wrongState = new PocketHiveOAuthAuthentication(
    async () => json(metadata()),
    { authorize: async () => new URL('http://127.0.0.1:57548/callback?code=x&state=wrong') },
    store(new Map()), length => Buffer.alloc(length, 1), () => NOW,
  );
  await assert.rejects(wrongState.authenticate(profile, endpoint, new AbortController().signal), /OAUTH_STATE_MISMATCH/);

  let calls = 0;
  const refresh = new PocketHiveOAuthAuthentication(
    async () => {
      calls += 1;
      return calls === 1 ? json(metadata()) : json({
        access_token: 'token', token_type: 'Bearer', expires_in: 900, refresh_token: 'forbidden',
      });
    },
    { authorize: async authorizationUrl => new URL(
      `http://127.0.0.1:57548/callback?code=x&state=${new URL(authorizationUrl).searchParams.get('state')}`,
    ) },
    store(new Map()), length => Buffer.alloc(length, 1), () => NOW,
  );
  await assert.rejects(refresh.authenticate(profile, endpoint, new AbortController().signal), /OAUTH_TOKEN_RESPONSE_INVALID/);
  assert.equal(calls, 2);
});

function metadata(): Record<string, unknown> {
  return {
    issuer: endpoint.authorizationServer,
    authorization_endpoint: `${endpoint.authorizationServer}/oauth/authorize`,
    token_endpoint: `${endpoint.authorizationServer}/oauth/token`,
    response_types_supported: ['code'],
    grant_types_supported: ['authorization_code'],
    token_endpoint_auth_methods_supported: ['none'],
    code_challenge_methods_supported: ['S256'],
    scopes_supported: [
      'pockethive:mcp:discover', 'pockethive:mcp:read', 'pockethive:mcp:operate',
      'pockethive:mcp:author', 'pockethive:mcp:publish', 'pockethive:mcp:cleanup',
    ],
  };
}

function store(values: Map<string, string>): OAuthSessionStore {
  return {
    get: async key => values.get(key),
    store: async (key, value) => { values.set(key, value); },
    delete: async key => { values.delete(key); },
  };
}

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
