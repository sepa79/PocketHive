import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import test from 'node:test';

import { PocketHiveOAuthAuthentication } from '../connection/oauthAuthentication';
import {
  BrowserAuthorizationPort,
  ConnectionContractError,
  OAuthSessionStore,
  POCKETHIVE_COMPANION_SCOPES,
} from '../connection/contracts';
import { createConnectionProfile } from '../connection/profile';

const NOW = new Date('2026-08-18T12:00:00Z');
const TEST_REDIRECT_URI = 'http://127.0.0.1:38125/callback';
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
  const browser = browserAuthorization(async authorizationUrl => {
      opened = new URL(authorizationUrl);
      return new URL(`${TEST_REDIRECT_URI}?code=code-123&state=${opened.searchParams.get('state')}`);
  });
  const oauth = new PocketHiveOAuthAuthentication(
    async (url, init) => {
      requests.push({ url: String(url), init });
      if (requests.length === 1) return json(metadata());
      return json({
        access_token: 'opaque-access-token', token_type: 'Bearer', expires_in: 900,
        refresh_token: 'opaque-refresh-token',
        scope: 'pockethive:mcp:discover pockethive:mcp:read pockethive:mcp:operate pockethive:mcp:author',
      });
    },
    browser,
    store(saved),
    length => Buffer.alloc(length, 7),
    () => NOW,
  );

  const session = await oauth.authenticate(profile, endpoint, new AbortController().signal);

  assert.deepEqual(session, {
    accessToken: 'opaque-access-token', expiresAt: '2026-08-18T12:15:00.000Z',
    scopes: ['pockethive:mcp:discover', 'pockethive:mcp:read',
      'pockethive:mcp:operate', 'pockethive:mcp:author'],
    renewal: { kind: 'ROTATING_REFRESH_TOKEN', refreshToken: 'opaque-refresh-token' },
  });
  assert.equal(requests[0].url,
    'http://127.0.0.1:8080/auth-service/.well-known/oauth-authorization-server');
  assert.equal(requests[1].url, 'http://127.0.0.1:8080/auth-service/oauth/token');
  assert.equal(opened?.searchParams.get('response_type'), 'code');
  assert.equal(opened?.searchParams.get('client_id'), 'pockethive-vscode');
  assert.equal(opened?.searchParams.get('redirect_uri'), TEST_REDIRECT_URI);
  assert.equal(opened?.searchParams.get('resource'), profile.mcpUrl);
  assert.equal(opened?.searchParams.get('code_challenge_method'), 'S256');
  const verifier = Buffer.alloc(64, 7).toString('base64url');
  assert.equal(opened?.searchParams.get('state'), Buffer.alloc(32, 7).toString('base64url'));
  assert.equal(opened?.searchParams.get('code_challenge'),
    createHash('sha256').update(verifier, 'ascii').digest('base64url'));
  assert.equal(opened?.searchParams.get('scope'), POCKETHIVE_COMPANION_SCOPES.join(' '));
  assert.deepEqual(requests[0].init, {
    method: 'GET', redirect: 'error', signal: requests[0].init?.signal,
    headers: { Accept: 'application/json' },
  });
  assert.equal(requests[0].init?.signal instanceof AbortSignal, true);
  assert.equal(requests[1].init?.method, 'POST');
  assert.equal(requests[1].init?.redirect, 'error');
  assert.equal(new Headers(requests[1].init?.headers).get('Accept'), 'application/json');
  assert.equal(new Headers(requests[1].init?.headers).get('Content-Type'), 'application/x-www-form-urlencoded');
  assert.equal(requests[1].init?.signal, requests[0].init?.signal);
  const tokenBody = new URLSearchParams(String(requests[1].init?.body));
  assert.equal(tokenBody.get('grant_type'), 'authorization_code');
  assert.equal(tokenBody.get('code'), 'code-123');
  assert.equal(tokenBody.get('redirect_uri'), TEST_REDIRECT_URI);
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
    browserAuthorization(async authorizationUrl => {
      const state = new URL(authorizationUrl).searchParams.get('state');
      return new URL(`${TEST_REDIRECT_URI}?error=access_denied&state=${state}`);
    }),
    store(new Map()),
    length => Buffer.alloc(length, 3),
    () => NOW,
  );

  await assert.rejects(oauth.authenticate(profile, endpoint, new AbortController().signal), error =>
    error instanceof Error && error.name === 'AuthenticationCancelledError');
  assert.equal(calls, 1);
});

test('rotates the stored base session without opening the browser and revokes both exact token types', async () => {
  const requests: Array<{ url: string; init?: RequestInit }> = [];
  const values = new Map<string, string>([[profile.secretKey, JSON.stringify({
    accessToken: 'old-access', expiresAt: '2026-08-18T12:01:00.000Z',
    scopes: ['pockethive:mcp:discover', 'pockethive:mcp:read'],
    renewal: { kind: 'ROTATING_REFRESH_TOKEN', refreshToken: 'old-refresh' },
  })]]);
  const authentication = new PocketHiveOAuthAuthentication(
    async (url, init) => {
      requests.push({ url: String(url), init });
      if (requests.length === 1 || requests.length === 3) return json(metadata());
      if (requests.length === 2) return json({
        access_token: 'new-access', refresh_token: 'new-refresh', token_type: 'Bearer', expires_in: 900,
        scope: 'pockethive:mcp:discover pockethive:mcp:read',
      });
      return new Response(undefined, { status: 200 });
    },
    browserAuthorization(async () => { throw new Error('browser must not open'); }),
    store(values),
    size => new Uint8Array(size).fill(7),
    () => NOW,
  );
  const previous = await authentication.session(profile);
  assert.ok(previous);

  const refreshed = await authentication.refresh(profile, endpoint, previous, new AbortController().signal);

  assert.deepEqual(refreshed, {
    accessToken: 'new-access', expiresAt: '2026-08-18T12:15:00.000Z',
    scopes: ['pockethive:mcp:discover', 'pockethive:mcp:read'],
    renewal: { kind: 'ROTATING_REFRESH_TOKEN', refreshToken: 'new-refresh' },
  });
  const refreshBody = new URLSearchParams(String(requests[1].init?.body));
  assert.equal(refreshBody.get('grant_type'), 'refresh_token');
  assert.equal(refreshBody.get('client_id'), 'pockethive-vscode');
  assert.equal(refreshBody.get('refresh_token'), 'old-refresh');
  assert.equal(refreshBody.get('resource'), profile.mcpUrl);
  assert.equal(requests[0].init?.method, 'GET');
  assert.equal(requests[0].init?.redirect, 'error');
  assert.equal(requests[1].init?.method, 'POST');
  assert.equal(requests[1].init?.redirect, 'error');
  assert.equal(new Headers(requests[1].init?.headers).get('Accept'), 'application/json');
  assert.equal(new Headers(requests[1].init?.headers).get('Content-Type'), 'application/x-www-form-urlencoded');
  assert.deepEqual(await authentication.session(profile), refreshed);

  await authentication.revoke(profile, endpoint, refreshed, new AbortController().signal);
  assert.equal(requests[2].init?.method, 'GET');
  assert.equal(requests[2].init?.redirect, 'error');
  for (const request of requests.slice(3)) {
    assert.equal(request.init?.method, 'POST');
    assert.equal(request.init?.redirect, 'error');
    assert.equal(new Headers(request.init?.headers).get('Accept'), 'application/json');
    assert.equal(new Headers(request.init?.headers).get('Content-Type'), 'application/x-www-form-urlencoded');
  }
  assert.deepEqual(requests.slice(3).map(request => {
    const body = new URLSearchParams(String(request.init?.body));
    return [request.url, body.get('token'), body.get('token_type_hint'), body.get('client_id')];
  }), [
    [`${endpoint.authorizationServer}/oauth/revoke`, 'new-access', 'access_token', 'pockethive-vscode'],
    [`${endpoint.authorizationServer}/oauth/revoke`, 'new-refresh', 'refresh_token', 'pockethive-vscode'],
  ]);
  await authentication.clear(profile);
  assert.equal(await authentication.session(profile), undefined);
});

test('distinguishes a definitively revoked refresh grant from a transient OAuth outage', async () => {
  const previous = {
    accessToken: 'old-access',
    expiresAt: '2026-08-18T12:01:00.000Z',
    scopes: ['pockethive:mcp:discover', 'pockethive:mcp:read'] as const,
    renewal: { kind: 'ROTATING_REFRESH_TOKEN' as const, refreshToken: 'old-refresh' },
  };
  for (const [response, expectedCode] of [
    [json({ error: 'invalid_grant', error_description: 'must not reach the UI' }, 400), 'OAUTH_REFRESH_REJECTED'],
    [json({ error: 'temporarily_unavailable' }, 503), 'OAUTH_HTTP_FAILED'],
  ] as const) {
    let calls = 0;
    const authentication = oauthClient(async () => {
      calls += 1;
      return calls === 1 ? json(metadata()) : response;
    });
    await rejectsContract(authentication.refresh(
      profile, endpoint, previous, new AbortController().signal,
    ), expectedCode);
    assert.equal(calls, 2);
  }

  for (const [status, body, expectedCode, expectedMessage] of [
    [400, { error: 'invalid_grant' }, 'OAUTH_REFRESH_REJECTED',
      'OAUTH_REFRESH_REJECTED: OAUTH_REFRESH_REJECTED'],
    [400, { error: 'temporarily_unavailable' }, 'OAUTH_HTTP_FAILED',
      'OAUTH_HTTP_FAILED: OAUTH_HTTP_FAILED: HTTP 400'],
    [401, { error: 'invalid_grant' }, 'OAUTH_HTTP_FAILED',
      'OAUTH_HTTP_FAILED: OAUTH_HTTP_FAILED: HTTP 401'],
  ] as const) {
    let calls = 0;
    const authentication = oauthClient(async () => {
      calls += 1;
      return calls === 1 ? json(metadata()) : json(body, status);
    });
    await rejectsContract(authentication.refresh(
      profile, endpoint, previous, new AbortController().signal,
    ), expectedCode, expectedMessage);
    assert.equal(calls, 2);
  }
});

test('refresh may narrow but can never widen the previously granted companion scopes', async () => {
  const viewer = {
    accessToken: 'old-access', expiresAt: '2026-08-18T12:01:00.000Z',
    scopes: ['pockethive:mcp:discover', 'pockethive:mcp:read'] as const,
    renewal: { kind: 'ROTATING_REFRESH_TOKEN' as const, refreshToken: 'old-refresh' },
  };
  for (const widenedScope of [
    'pockethive:mcp:discover pockethive:mcp:read pockethive:mcp:operate pockethive:mcp:author',
    POCKETHIVE_COMPANION_SCOPES.join(' '),
  ]) {
    let calls = 0;
    const authentication = oauthClient(async () => {
      calls += 1;
      return calls === 1 ? json(metadata()) : json({
        ...validToken(), refresh_token: `rotated-${calls}`, scope: widenedScope,
      });
    });
    await rejectsContract(authentication.refresh(
      profile, endpoint, viewer, new AbortController().signal,
    ), 'OAUTH_REFRESH_SCOPE_WIDENED',
    'OAUTH_REFRESH_SCOPE_WIDENED: OAUTH_REFRESH_SCOPE_WIDENED');
    assert.equal(calls, 2);
  }
});

test('rejects metadata, callback state, malformed grants, and missing rotation without fallback', async () => {
  const invalidMetadata = new PocketHiveOAuthAuthentication(
    async () => json({ ...metadata(), code_challenge_methods_supported: ['plain'] }),
    browserAuthorization(async () => { throw new Error('must not open'); }), store(new Map()),
    length => Buffer.alloc(length, 1), () => NOW,
  );
  await assert.rejects(invalidMetadata.authenticate(profile, endpoint, new AbortController().signal), /OAUTH_METADATA_INVALID/);

  const wrongState = new PocketHiveOAuthAuthentication(
    async () => json(metadata()),
    browserAuthorization(async () => new URL(`${TEST_REDIRECT_URI}?code=x&state=wrong`)),
    store(new Map()), length => Buffer.alloc(length, 1), () => NOW,
  );
  await assert.rejects(wrongState.authenticate(profile, endpoint, new AbortController().signal), /OAUTH_STATE_MISMATCH/);

  let calls = 0;
  const malformedGrant = new PocketHiveOAuthAuthentication(
    async () => {
      calls += 1;
      return calls === 1 ? json(metadata()) : json({
        access_token: 'token', token_type: 'Bearer', expires_in: 900, refresh_token: 'forbidden',
      });
    },
    browserAuthorization(async authorizationUrl => new URL(
      `${TEST_REDIRECT_URI}?code=x&state=${new URL(authorizationUrl).searchParams.get('state')}`,
    )),
    store(new Map()), length => Buffer.alloc(length, 1), () => NOW,
  );
  await assert.rejects(malformedGrant.authenticate(
    profile, endpoint, new AbortController().signal,
  ), /OAUTH_TOKEN_RESPONSE_INVALID/);
  assert.equal(calls, 2);

  const nonRotating = new PocketHiveOAuthAuthentication(
    async (url, init) => String(url).endsWith('/.well-known/oauth-authorization-server')
      ? json(metadata())
      : json({
        access_token: 'new', refresh_token: 'same', token_type: 'Bearer', expires_in: 900,
        scope: 'pockethive:mcp:discover pockethive:mcp:read',
      }),
    browserAuthorization(async () => { throw new Error('browser must not open'); }),
    store(new Map()), length => Buffer.alloc(length, 1), () => NOW,
  );
  await rejectsContract(nonRotating.refresh(profile, endpoint, {
    accessToken: 'old', expiresAt: NOW.toISOString(),
    scopes: ['pockethive:mcp:discover', 'pockethive:mcp:read'],
    renewal: { kind: 'ROTATING_REFRESH_TOKEN', refreshToken: 'same' },
  }, new AbortController().signal), 'OAUTH_REFRESH_TOKEN_NOT_ROTATED',
  'OAUTH_REFRESH_TOKEN_NOT_ROTATED: OAUTH_REFRESH_TOKEN_NOT_ROTATED');
});

test('callback, cancellation, and OAuth errors fail explicitly without fallback', async () => {
  for (const callback of [
    'http://localhost:38125/callback?code=x&state=STATE',
    'http://127.0.0.1:38125/other?code=x&state=STATE',
    `${TEST_REDIRECT_URI}?code=x&state=wrong`,
  ]) {
    const oauth = oauthClient(async () => json(metadata()), async authorizationUrl => {
      const state = new URL(authorizationUrl).searchParams.get('state')!;
      return new URL(callback.replace('STATE', state));
    });
    await rejectsContract(oauth.authenticate(profile, endpoint, new AbortController().signal),
      callback.includes('state=wrong') ? 'OAUTH_STATE_MISMATCH' : 'OAUTH_CALLBACK_INVALID');
  }

  for (const [query, expected] of [
    ['error=server_error', 'OAUTH_AUTHORIZATION_FAILED: OAUTH_AUTHORIZATION_FAILED: server_error'],
    ['', 'OAUTH_AUTHORIZATION_FAILED: OAUTH_AUTHORIZATION_FAILED: code missing'],
  ] as const) {
    let calls = 0;
    const oauth = oauthClient(async () => { calls += 1; return json(metadata()); }, async authorizationUrl => {
      const state = new URL(authorizationUrl).searchParams.get('state');
      return new URL(`${TEST_REDIRECT_URI}?state=${state}${query ? `&${query}` : ''}`);
    });
    await rejectsContract(oauth.authenticate(profile, endpoint, new AbortController().signal),
      'OAUTH_AUTHORIZATION_FAILED', expected);
    assert.equal(calls, 1);
  }

  const alreadyAborted = new AbortController();
  alreadyAborted.abort();
  await assert.rejects(
    oauthClient(async () => json(metadata())).authenticate(profile, endpoint, alreadyAborted.signal),
    exactNamedError('AuthenticationCancelledError', 'Authentication was cancelled'),
  );

  const duringToken = new AbortController();
  let tokenCalls = 0;
  const abortingToken = oauthClient(async () => {
    tokenCalls += 1;
    if (tokenCalls === 1) return json(metadata());
    duringToken.abort();
    return json(validToken());
  });
  await assert.rejects(abortingToken.authenticate(profile, endpoint, duringToken.signal),
    exactNamedError('AuthenticationCancelledError', 'Authentication was cancelled'));

  const duringBrowser = new AbortController();
  const abortingBrowser = oauthClient(async () => json(metadata()), async () => {
    duringBrowser.abort();
    throw new Error('browser failure after cancellation');
  });
  await assert.rejects(abortingBrowser.authenticate(profile, endpoint, duringBrowser.signal),
    exactNamedError('AuthenticationCancelledError', 'Authentication was cancelled'));
});

test('stored session parsing accepts only the exact explicit renewal contract', async () => {
  const base = {
    accessToken: 'access',
    expiresAt: '2026-08-19T13:00:00.000Z',
    scopes: ['pockethive:mcp:discover', 'pockethive:mcp:read'],
  } as const;
  const valid = [
    { ...base, renewal: { kind: 'NONE' } },
    { ...base, renewal: { kind: 'ROTATING_REFRESH_TOKEN', refreshToken: 'refresh' } },
  ];
  for (const session of valid) {
    const values = new Map([[profile.secretKey, JSON.stringify(session)]]);
    assert.deepEqual(await oauthClient(async () => json(metadata()), undefined, values).session(profile), session);
  }
  const reordered = Object.create(null) as Record<string, unknown>;
  reordered.renewal = { refreshToken: 'refresh', kind: 'ROTATING_REFRESH_TOKEN' };
  reordered.scopes = ['pockethive:mcp:discover', 'pockethive:mcp:read'];
  reordered.expiresAt = '2026-08-19T13:00:00.000Z';
  reordered.accessToken = 'access';
  assert.deepEqual(await oauthClient(async () => json(metadata()), undefined,
    new Map([[profile.secretKey, JSON.stringify(reordered)]])).session(profile), valid[1]);

  const invalid: Array<[unknown, string]> = [
    [null, 'not an object'], [[], 'not an object'], ['text', 'not an object'], [1, 'not an object'],
    [{ ...base }, 'unexpected fields'],
    [{ ...base, renewal: { kind: 'NONE' }, extra: true }, 'unexpected fields'],
    [{ ...base, accessToken: undefined, wrong: 'access', renewal: { kind: 'NONE' } }, 'unexpected fields'],
    [{ ...base, expiresAt: undefined, wrong: base.expiresAt, renewal: { kind: 'NONE' } }, 'unexpected fields'],
    [{ accessToken: base.accessToken, expiresAt: base.expiresAt, renewal: { kind: 'NONE' }, wrong: base.scopes },
      'unexpected fields'],
    [{ accessToken: base.accessToken, expiresAt: base.expiresAt, scopes: base.scopes, wrong: { kind: 'NONE' } },
      'unexpected fields'],
    [{ ...base, accessToken: '', renewal: { kind: 'NONE' } }, 'accessToken missing'],
    [{ ...base, accessToken: '   ', renewal: { kind: 'NONE' } }, 'accessToken missing'],
    [{ ...base, accessToken: 1, renewal: { kind: 'NONE' } }, 'accessToken missing'],
    [{ ...base, expiresAt: '', renewal: { kind: 'NONE' } }, 'expiresAt missing'],
    [{ ...base, expiresAt: 'not-a-date', renewal: { kind: 'NONE' } }, 'invalid expiry'],
    [{ ...base, scopes: [], renewal: { kind: 'NONE' } }, 'invalid scopes'],
    [{ ...base, scopes: 'pockethive:mcp:discover pockethive:mcp:read', renewal: { kind: 'NONE' } },
      'invalid scopes'],
    [{ ...base, scopes: ['pockethive:mcp:discover', 1, 'pockethive:mcp:read'], renewal: { kind: 'NONE' } },
      'invalid scopes'],
    [{ ...base, scopes: [1, 2], renewal: { kind: 'NONE' } }, 'invalid scopes'],
    [{ ...base, scopes: ['pockethive:mcp:discover pockethive:mcp:read'], renewal: { kind: 'NONE' } },
      'invalid scopes'],
    [{ ...base, scopes: ['pockethive:mcp:discover'], renewal: { kind: 'NONE' } }, 'invalid scopes'],
    [{ ...base, scopes: ['pockethive:mcp:discover', 'pockethive:mcp:read', 'pockethive:mcp:cleanup'],
      renewal: { kind: 'NONE' } }, 'invalid scopes'],
    [{ ...base, renewal: null }, 'invalid renewal'],
    [{ ...base, renewal: [] }, 'invalid renewal'],
    [{ ...base, renewal: {} }, 'invalid renewal'],
    [{ ...base, renewal: { kind: 'OTHER', refreshToken: 'x' } }, 'invalid renewal'],
    [{ ...base, renewal: { kind: 'NONE', extra: true } }, 'invalid renewal'],
    [{ ...base, renewal: { kind: 'ROTATING_REFRESH_TOKEN' } }, 'invalid renewal'],
    [{ ...base, renewal: { kind: 'ROTATING_REFRESH_TOKEN', refreshToken: '' } }, 'refreshToken missing'],
    [{ ...base, renewal: { kind: 'ROTATING_REFRESH_TOKEN', refreshToken: 'x', extra: true } }, 'invalid renewal'],
  ];
  for (const [session, reason] of invalid) {
    const values = new Map([[profile.secretKey, JSON.stringify(session)]]);
    await assert.rejects(oauthClient(async () => json(metadata()), undefined, values).session(profile), error =>
      error instanceof ConnectionContractError && error.code === 'OAUTH_SESSION_INVALID'
      && error.message.includes(reason));
  }
  for (const [session, field] of [
    [{ ...base, accessToken: '', renewal: { kind: 'NONE' } }, 'accessToken'],
    [{ ...base, expiresAt: '', renewal: { kind: 'NONE' } }, 'expiresAt'],
    [{ ...base, renewal: { kind: 'ROTATING_REFRESH_TOKEN', refreshToken: '' } }, 'refreshToken'],
  ] as const) {
    const values = new Map([[profile.secretKey, JSON.stringify(session)]]);
    await rejectsContract(oauthClient(async () => json(metadata()), undefined, values).session(profile),
      'OAUTH_SESSION_INVALID',
      `OAUTH_SESSION_INVALID: OAUTH_SESSION_INVALID: OAUTH_SESSION_INVALID: OAUTH_SESSION_INVALID: ${field} missing`);
  }
  const malformed = new Map([[profile.secretKey, '{']]);
  await rejectsContract(oauthClient(async () => json(metadata()), undefined, malformed).session(profile),
    'OAUTH_SESSION_INVALID');
  assert.equal(await oauthClient(async () => json(metadata()), undefined, new Map()).session(profile), undefined);
});

test('metadata validation rejects every issuer-owned endpoint and capability drift independently', async () => {
  const invalid: Record<string, unknown>[] = [];
  const variants: Array<[string, unknown]> = [
    ['issuer', 'https://other.example/auth-service'],
    ['issuer', `${endpoint.authorizationServer}//`],
    ['authorization_endpoint', 'https://other.example/oauth/authorize'],
    ['authorization_endpoint', `${endpoint.authorizationServer}@attacker.example/oauth/authorize`],
    ['authorization_endpoint', `${endpoint.authorizationServer}/oauth/authorize?x=1`],
    ['authorization_endpoint', `${endpoint.authorizationServer}/oauth/authorize#x`],
    ['token_endpoint', 'not a url'],
    ['token_endpoint', `${endpoint.authorizationServer}/oauth/token?x=1`],
    ['revocation_endpoint', `${endpoint.authorizationServer}/oauth/revoke#x`],
    ['response_types_supported', []],
    ['response_types_supported', ['code', 'token']],
    ['response_types_supported', ['token']],
    ['response_types_supported', ['code', 1]],
    ['grant_types_supported', ['refresh_token', 'authorization_code']],
    ['grant_types_supported', ['authorization_code']],
    ['grant_types_supported', ['authorization_code', 'refresh_token', 'client_credentials']],
    ['grant_types_supported', ['authorization_code', 'wrong']],
    ['grant_types_supported', ['wrong', 'refresh_token']],
    ['token_endpoint_auth_methods_supported', []],
    ['token_endpoint_auth_methods_supported', ['none', 'client_secret_basic']],
    ['revocation_endpoint_auth_methods_supported', ['client_secret_basic']],
    ['code_challenge_methods_supported', ['plain']],
    ['scopes_supported', ['pockethive:mcp:discover']],
    ['scopes_supported', 'pockethive:mcp:discover pockethive:mcp:read'],
    ['scopes_supported', ['pockethive:mcp:discover', 'pockethive:mcp:read', 1]],
    ['scopes_supported', [1, 2, 3]],
    ['scopes_supported', [...POCKETHIVE_COMPANION_SCOPES, 1]],
  ];
  for (const [key, value] of variants) invalid.push({ ...metadata(), [key]: value });
  for (const candidate of invalid) {
    let browserCalls = 0;
    const oauth = oauthClient(async () => json(candidate), async () => {
      browserCalls += 1;
      throw new Error('must not open');
    });
    await rejectsContract(oauth.authenticate(profile, endpoint, new AbortController().signal),
      'OAUTH_METADATA_INVALID', 'OAUTH_METADATA_INVALID: OAUTH_METADATA_INVALID: contract mismatch');
    assert.equal(browserCalls, 0);
  }

  const otherIssuer = 'https://other.example/auth-service';
  const issuerOnlyDrift = {
    ...metadata(),
    issuer: otherIssuer,
    authorization_endpoint: `${otherIssuer}/oauth/authorize`,
    token_endpoint: `${otherIssuer}/oauth/token`,
    revocation_endpoint: `${otherIssuer}/oauth/revoke`,
  };
  await rejectsContract(oauthClient(async () => json(issuerOnlyDrift)).authenticate(
    profile, endpoint, new AbortController().signal,
  ), 'OAUTH_METADATA_INVALID', 'OAUTH_METADATA_INVALID: OAUTH_METADATA_INVALID: contract mismatch');

  for (const field of ['issuer', 'authorization_endpoint', 'token_endpoint', 'revocation_endpoint'] as const) {
    for (const value of [undefined, '', '   ']) {
      const candidate = { ...metadata(), [field]: value };
      await assert.rejects(oauthClient(async () => json(candidate)).authenticate(
        profile, endpoint, new AbortController().signal,
      ), error => error instanceof ConnectionContractError && error.code === 'OAUTH_METADATA_INVALID'
        && error.message.includes(`${field} missing`));
    }
  }

  const trailingIssuer = { ...metadata(), issuer: `${endpoint.authorizationServer}/` };
  let browserOpened = false;
  let calls = 0;
  const accepted = oauthClient(async () => {
    calls += 1;
    return calls === 1 ? json(trailingIssuer) : json(validToken());
  }, async authorizationUrl => {
    browserOpened = true;
    const state = new URL(authorizationUrl).searchParams.get('state');
    return new URL(`${TEST_REDIRECT_URI}?code=x&state=${state}`);
  });
  assert.equal((await accepted.authenticate(profile, endpoint, new AbortController().signal)).accessToken, 'access');
  assert.equal(browserOpened, true);
});

test('token parsing and JSON transport reject malformed boundary values with exact contracts', async () => {
  const invalidTokens: Record<string, unknown>[] = [
    { ...validToken(), token_type: 'bearer' },
    { ...validToken(), expires_in: '900' },
    { ...validToken(), expires_in: 1.5 },
    { ...validToken(), expires_in: 0 },
    { ...validToken(), expires_in: -1 },
    { ...validToken(), expires_in: 86_401 },
    { ...validToken(), access_token: '' },
    { ...validToken(), access_token: 1 },
    { ...validToken(), refresh_token: '' },
    { ...validToken(), scope: '' },
    { ...validToken(), scope: '   ' },
    { ...validToken(), scope: ' pockethive:mcp:discover pockethive:mcp:read' },
    { ...validToken(), scope: 'pockethive:mcp:discover  pockethive:mcp:read' },
    { ...validToken(), scope: 'pockethive:mcp:discover' },
    { ...validToken(), scope: 'pockethive:mcp:read' },
    { ...validToken(), scope: 'pockethive:mcp:discover pockethive:mcp:read pockethive:mcp:operate' },
    { ...validToken(), scope: 'pockethive:mcp:discover pockethive:mcp:read pockethive:mcp:author pockethive:mcp:publish' },
    { ...validToken(), scope: 'pockethive:mcp:discover pockethive:mcp:read unsupported' },
    { ...validToken(), scope: 'pockethive:mcp:discover pockethive:mcp:read pockethive:mcp:cleanup' },
    { ...validToken(), scope: 'pockethive:mcp:discover pockethive:mcp:read pockethive:mcp:read' },
  ];
  for (const token of invalidTokens) {
    await rejectsContract(authorizeWithToken(token), 'OAUTH_TOKEN_RESPONSE_INVALID');
  }
  const boundary = await authorizeWithToken({ ...validToken(), expires_in: 86_400 });
  assert.equal(boundary.expiresAt, '2026-08-19T12:00:00.000Z');

  const transportFailures: Array<[Response, string, string]> = [
    [new Response('{}', { status: 500, headers: { 'Content-Type': 'application/json' } }),
      'OAUTH_HTTP_FAILED', 'OAUTH_HTTP_FAILED: OAUTH_HTTP_FAILED: HTTP 500'],
    [new Response('{}', { status: 200, headers: { 'Content-Type': 'text/plain' } }),
      'OAUTH_HTTP_FAILED', 'OAUTH_HTTP_FAILED: OAUTH_HTTP_FAILED: HTTP 200'],
    [new Response(`{"value":"${'x'.repeat(65_536)}"}`, {
      status: 200, headers: { 'Content-Type': 'application/json' },
    }), 'OAUTH_RESPONSE_INVALID', 'OAUTH_RESPONSE_INVALID: OAUTH_RESPONSE_INVALID: response too large'],
    [new Response('null', { status: 200, headers: { 'Content-Type': 'application/json' } }),
      'OAUTH_RESPONSE_INVALID', 'OAUTH_RESPONSE_INVALID: OAUTH_RESPONSE_INVALID: not an object'],
    [new Response('[]', { status: 200, headers: { 'Content-Type': 'application/json' } }),
      'OAUTH_RESPONSE_INVALID', 'OAUTH_RESPONSE_INVALID: OAUTH_RESPONSE_INVALID: not an object'],
    [new Response('"text"', { status: 200, headers: { 'Content-Type': 'application/json' } }),
      'OAUTH_RESPONSE_INVALID', 'OAUTH_RESPONSE_INVALID: OAUTH_RESPONSE_INVALID: not an object'],
    [new Response('1', { status: 200, headers: { 'Content-Type': 'application/json' } }),
      'OAUTH_RESPONSE_INVALID', 'OAUTH_RESPONSE_INVALID: OAUTH_RESPONSE_INVALID: not an object'],
    [new Response('{}', { status: 200 }), 'OAUTH_HTTP_FAILED',
      'OAUTH_HTTP_FAILED: OAUTH_HTTP_FAILED: HTTP 200'],
    [new Response(new TextEncoder().encode('{}'), { status: 200 }), 'OAUTH_HTTP_FAILED',
      'OAUTH_HTTP_FAILED: OAUTH_HTTP_FAILED: HTTP 200'],
    [new Response('not-json', { status: 200, headers: { 'Content-Type': 'application/json' } }),
      'OAUTH_RESPONSE_INVALID', ''],
  ];
  for (const [response, code, message] of transportFailures) {
    await rejectsContract(oauthClient(async () => response).authenticate(
      profile, endpoint, new AbortController().signal,
    ), code, message || undefined);
  }

  let authorizationCalls = 0;
  const authorizationGrantFailure = oauthClient(async () => {
    authorizationCalls += 1;
    return authorizationCalls === 1 ? json(metadata()) : json({ error: 'invalid_grant' }, 400);
  });
  await rejectsContract(authorizationGrantFailure.authenticate(
    profile, endpoint, new AbortController().signal,
  ), 'OAUTH_HTTP_FAILED', 'OAUTH_HTTP_FAILED: OAUTH_HTTP_FAILED: HTTP 400');

  const exactLimitBase = JSON.stringify({ value: '' });
  const exactLimit = JSON.stringify({ value: 'x'.repeat(65_536 - exactLimitBase.length) });
  assert.equal(exactLimit.length, 65_536);
  await rejectsContract(oauthClient(async () => new Response(exactLimit, {
    status: 200, headers: { 'Content-Type': 'application/json;charset=utf-8' },
  })).authenticate(profile, endpoint, new AbortController().signal), 'OAUTH_METADATA_INVALID');
});

test('production randomness and clock create a usable unpredictable base session', async () => {
  let calls = 0;
  let opened: URL | undefined;
  const before = Date.now();
  const oauth = new PocketHiveOAuthAuthentication(
    async () => {
      calls += 1;
      return calls === 1 ? json(metadata()) : json(validToken());
    },
    browserAuthorization(async authorizationUrl => {
      opened = new URL(authorizationUrl);
      return new URL(`${TEST_REDIRECT_URI}?code=x&state=${opened.searchParams.get('state')}`);
    }),
    store(new Map()),
  );
  const session = await oauth.authenticate(profile, endpoint, new AbortController().signal);
  const after = Date.now();
  assert.match(opened?.searchParams.get('state') ?? '', /^[A-Za-z0-9_-]{43}$/);
  assert.match(opened?.searchParams.get('code_challenge') ?? '', /^[A-Za-z0-9_-]{43}$/);
  assert.equal(Date.parse(session.expiresAt) >= before + 900_000, true);
  assert.equal(Date.parse(session.expiresAt) <= after + 900_000, true);
});

test('refresh and revocation require renewable sessions and report every failed revocation', async () => {
  const ephemeral = {
    accessToken: 'access', expiresAt: NOW.toISOString(),
    scopes: ['pockethive:mcp:discover', 'pockethive:mcp:read'] as const,
    renewal: { kind: 'NONE' as const },
  };
  const oauth = oauthClient(async () => json(metadata()));
  await rejectsContract(oauth.refresh(profile, endpoint, ephemeral, new AbortController().signal),
    'OAUTH_SESSION_NOT_RENEWABLE', 'OAUTH_SESSION_NOT_RENEWABLE: OAUTH_SESSION_NOT_RENEWABLE');
  await rejectsContract(oauth.revoke(profile, endpoint, ephemeral, new AbortController().signal),
    'OAUTH_SESSION_NOT_RENEWABLE', 'OAUTH_SESSION_NOT_RENEWABLE: OAUTH_SESSION_NOT_RENEWABLE');

  for (const failed of [new Set(['access_token']), new Set(['refresh_token']), new Set(['access_token', 'refresh_token'])]) {
    const requests: string[] = [];
    const failing = oauthClient(async (url, init) => {
      if (String(url).endsWith('/.well-known/oauth-authorization-server')) return json(metadata());
      const hint = new URLSearchParams(String(init?.body)).get('token_type_hint')!;
      requests.push(hint);
      return new Response(undefined, { status: failed.has(hint) ? 503 : 200 });
    });
    const expectedCount = failed.size;
    await rejectsContract(failing.revoke(profile, endpoint, {
      accessToken: 'access', expiresAt: NOW.toISOString(),
      scopes: ['pockethive:mcp:discover', 'pockethive:mcp:read'],
      renewal: { kind: 'ROTATING_REFRESH_TOKEN', refreshToken: 'refresh' },
    }, new AbortController().signal), 'OAUTH_REVOCATION_UNCONFIRMED',
    `OAUTH_REVOCATION_UNCONFIRMED: OAUTH_REVOCATION_UNCONFIRMED: ${expectedCount} request(s) failed`);
    assert.deepEqual(requests, ['access_token', 'refresh_token']);
  }
});

function metadata(): Record<string, unknown> {
  return {
    issuer: endpoint.authorizationServer,
    authorization_endpoint: `${endpoint.authorizationServer}/oauth/authorize`,
    token_endpoint: `${endpoint.authorizationServer}/oauth/token`,
    revocation_endpoint: `${endpoint.authorizationServer}/oauth/revoke`,
    response_types_supported: ['code'],
    grant_types_supported: ['authorization_code', 'refresh_token'],
    token_endpoint_auth_methods_supported: ['none'],
    revocation_endpoint_auth_methods_supported: ['none'],
    code_challenge_methods_supported: ['S256'],
    scopes_supported: [
      'pockethive:mcp:discover', 'pockethive:mcp:read', 'pockethive:mcp:operate',
      'pockethive:mcp:author', 'pockethive:mcp:publish', 'pockethive:mcp:cleanup',
    ],
  };
}

function validToken(): Record<string, unknown> {
  return {
    access_token: 'access', token_type: 'Bearer', expires_in: 900, refresh_token: 'refresh',
    scope: POCKETHIVE_COMPANION_SCOPES.join(' '),
  };
}

function oauthClient(
  fetcher: typeof fetch,
  authorize?: AuthorizationHandler,
  values: Map<string, string> = new Map(),
): PocketHiveOAuthAuthentication {
  return new PocketHiveOAuthAuthentication(
    fetcher,
    browserAuthorization(authorize ?? (async authorizationUrl => {
      const state = new URL(authorizationUrl).searchParams.get('state');
      return new URL(`${TEST_REDIRECT_URI}?code=code&state=${state}`);
    })),
    store(values),
    length => Buffer.alloc(length, 1),
    () => NOW,
  );
}

type AuthorizationHandler = (authorizationUrl: string) => Promise<URL>;

function browserAuthorization(authorize: AuthorizationHandler): BrowserAuthorizationPort {
  return {
    authorize: async authorizationUrl => ({
      callback: await authorize(authorizationUrl(TEST_REDIRECT_URI)),
      redirectUri: TEST_REDIRECT_URI,
    }),
  };
}

async function authorizeWithToken(token: Record<string, unknown>) {
  let calls = 0;
  const oauth = oauthClient(async () => {
    calls += 1;
    return calls === 1 ? json(metadata()) : json(token);
  });
  return oauth.authenticate(profile, endpoint, new AbortController().signal);
}

async function rejectsContract(
  promise: Promise<unknown>,
  code: string,
  message?: string,
): Promise<void> {
  await assert.rejects(promise, error => error instanceof ConnectionContractError
    && error.code === code
    && (message === undefined ? error.message.length > code.length + 2 : error.message === message));
}

function exactNamedError(name: string, message: string): (error: unknown) => boolean {
  return error => error instanceof Error && error.name === name && error.message === message;
}

function store(values: Map<string, string>): OAuthSessionStore {
  return {
    get: async key => values.get(key),
    store: async (key, value) => { values.set(key, value); },
    delete: async key => { values.delete(key); },
  };
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}
