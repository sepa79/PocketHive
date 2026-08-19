import assert from 'node:assert/strict';
import test from 'node:test';

import { AuthorizedMcpSession, SESSION_RENEWAL_SKEW_MS } from '../connection/authorizedMcpSession';
import {
  ConnectionContractError,
  ConnectionEvidence,
  McpConnectionProfile,
  OAuthSession,
  RenewableAuthenticationPort,
  ValidatedEndpoint,
} from '../connection/contracts';

const profile: McpConnectionProfile = Object.freeze({
  id: 'local', displayName: 'Local', mcpUrl: 'http://127.0.0.1:8088/mcp',
  endpointSecurityMode: 'LOCAL_LOOPBACK_HTTP', authenticationMode: 'OAUTH_AUTHORIZATION_CODE_PKCE',
  secretKey: 'secret.local',
});
const endpoint: ValidatedEndpoint = Object.freeze({
  mcpUrl: profile.mcpUrl,
  resourceMetadataUrl: 'http://127.0.0.1:8088/.well-known/oauth-protected-resource',
  authorizationServer: 'http://127.0.0.1:8088/auth-service',
});
const current: OAuthSession = {
  accessToken: 'current-access', expiresAt: '2026-08-19T12:01:00.000Z',
  renewal: { kind: 'ROTATING_REFRESH_TOKEN', refreshToken: 'current-refresh' },
};
const refreshed: OAuthSession = {
  accessToken: 'refreshed-access', expiresAt: '2026-08-19T12:15:00.000Z',
  renewal: { kind: 'ROTATING_REFRESH_TOKEN', refreshToken: 'refreshed-refresh' },
};
const evidence: ConnectionEvidence = Object.freeze({
  serverName: 'pockethive-mcp', serverVersion: '0.15.35', principalLabel: 'Local Admin',
  capabilityFingerprint: 'sha256:abc', observedAt: '2026-08-19T12:00:00.000Z',
});

test('renews inside the exact skew once, reconnects a candidate, and coalesces concurrent callers', async () => {
  assert.equal(SESSION_RENEWAL_SKEW_MS, 60_000);
  const calls: string[] = [];
  let release!: () => void;
  const wait = new Promise<void>(resolve => { release = resolve; });
  const authentication = managedAuthentication(calls, async () => {
    await wait;
    return refreshed;
  });
  const managed = new AuthorizedMcpSession(
    { validate: async () => { calls.push('endpoint'); return endpoint; } },
    authentication,
    { test: async (_profile, session) => { calls.push(`mcp:${session.accessToken}`); return evidence; }, close: async () => {} },
    () => new Date('2026-08-19T12:00:00.000Z'),
  );
  managed.bind(profile, current);

  const first = managed.ensure(profile);
  const second = managed.ensure(profile);
  release();

  assert.equal(await first, evidence);
  assert.equal(await second, evidence);
  assert.deepEqual(calls, ['session', 'endpoint', 'refresh', 'mcp:refreshed-access']);
  calls.length = 0;
  assert.equal(await managed.ensure(profile), undefined);
  assert.deepEqual(calls, ['session']);
});

test('uses a fresh bound session without network work and reconnects an unbound stored token', async () => {
  const calls: string[] = [];
  const fresh = { ...refreshed, expiresAt: '2026-08-19T12:02:00.001Z' };
  const authentication = managedAuthentication(calls, async () => { throw new Error('must not refresh'); }, fresh);
  const managed = new AuthorizedMcpSession(
    { validate: async () => { calls.push('endpoint'); return endpoint; } }, authentication,
    { test: async (_profile, session) => { calls.push(`mcp:${session.accessToken}`); return evidence; }, close: async () => {} },
    () => new Date('2026-08-19T12:00:00.000Z'),
  );
  managed.bind(profile, fresh);
  assert.equal(await managed.ensure(profile), undefined);
  assert.deepEqual(calls, ['session']);

  managed.unbind();
  assert.equal(await managed.ensure(profile), evidence);
  assert.deepEqual(calls, ['session', 'session', 'endpoint', 'mcp:refreshed-access']);
});

test('sign-in binds a verified candidate and sign-out always clears local state with explicit revocation evidence', async () => {
  const calls: string[] = [];
  const authentication = managedAuthentication(calls, async () => refreshed, refreshed);
  const managed = new AuthorizedMcpSession(
    { validate: async () => { calls.push('endpoint'); return endpoint; } }, authentication,
    { test: async () => { calls.push('mcp'); return evidence; }, close: async () => { calls.push('close'); } },
    () => new Date('2026-08-19T12:00:00.000Z'),
  );

  assert.equal(await managed.signIn(profile), evidence);
  assert.deepEqual(calls, ['endpoint', 'authenticate', 'mcp']);
  calls.length = 0;
  assert.equal(await managed.ensure(profile), undefined);
  assert.deepEqual(calls, ['session']);
  calls.length = 0;
  assert.deepEqual(await managed.signOut(profile), {
    transportClosure: 'CONFIRMED', remoteRevocation: 'CONFIRMED',
  });
  assert.deepEqual(calls, ['close', 'session', 'endpoint', 'revoke', 'clear']);
});

test('sign-out reports unconfirmed remote revocation but still closes, clears, and unbinds', async () => {
  const calls: string[] = [];
  const authentication = managedAuthentication(calls, async () => refreshed, refreshed);
  authentication.revoke = async () => { calls.push('revoke'); throw new Error('offline'); };
  const managed = new AuthorizedMcpSession(
    { validate: async () => { calls.push('endpoint'); return endpoint; } }, authentication,
    { test: async () => evidence, close: async () => { calls.push('close'); } },
    () => new Date('2026-08-19T12:00:00.000Z'),
  );
  managed.bind(profile, refreshed);

  assert.deepEqual(await managed.signOut(profile), {
    transportClosure: 'CONFIRMED', remoteRevocation: 'UNCONFIRMED',
  });
  assert.deepEqual(calls, ['close', 'session', 'endpoint', 'revoke', 'clear']);
  calls.length = 0;
  await managed.ensure(profile).catch(() => undefined);
  assert.deepEqual(calls, ['session']);
});

test('rejects non-renewable bindings and conflicting transitions with exact contract errors', async () => {
  const calls: string[] = [];
  let release!: () => void;
  const wait = new Promise<void>(resolve => { release = resolve; });
  const managed = new AuthorizedMcpSession(
    { validate: async () => endpoint },
    managedAuthentication(calls, async () => { await wait; return refreshed; }),
    { test: async () => evidence, close: async () => {} },
    () => new Date('2026-08-19T12:00:00.000Z'),
  );
  assertContract(() => managed.bind(profile, {
    accessToken: 'ephemeral', expiresAt: refreshed.expiresAt, renewal: { kind: 'NONE' },
  }), 'OAUTH_SESSION_NOT_RENEWABLE');

  const renewal = managed.ensure(profile);
  const other = { ...profile, id: 'other', secretKey: 'secret.other' };
  assert.throws(() => managed.ensure(other), exactContract('OAUTH_SESSION_PROFILE_CONFLICT'));
  await assert.rejects(managed.signIn(profile), exactContract('OAUTH_SESSION_TRANSITION_IN_PROGRESS'));
  await assert.rejects(managed.signOut(profile), exactContract('OAUTH_SESSION_TRANSITION_IN_PROGRESS'));
  release();
  await renewal;
});

test('fails closed for missing or ephemeral stored sessions and clears a failed refresh', async () => {
  for (const stored of [null, {
    accessToken: 'ephemeral', expiresAt: current.expiresAt, renewal: { kind: 'NONE' as const },
  }]) {
    const calls: string[] = [];
    const managed = new AuthorizedMcpSession(
      { validate: async () => { calls.push('endpoint'); return endpoint; } },
      managedAuthentication(calls, async () => refreshed, stored),
      { test: async () => evidence, close: async () => {} },
      () => new Date('2026-08-19T12:00:00.000Z'),
    );
    await assert.rejects(managed.ensure(profile), error => error instanceof Error
      && error.name === 'AuthenticationExpiredError');
    assert.deepEqual(calls, ['session']);
  }

  const calls: string[] = [];
  const failedRefresh = managedAuthentication(calls, async () => { throw new Error('refresh rejected'); });
  const managed = new AuthorizedMcpSession(
    { validate: async () => { calls.push('endpoint'); return endpoint; } }, failedRefresh,
    { test: async () => evidence, close: async () => {} },
    () => new Date('2026-08-19T12:00:00.000Z'),
  );
  managed.bind(profile, current);
  await assert.rejects(managed.ensure(profile), /refresh rejected/);
  assert.deepEqual(calls, ['session', 'endpoint', 'refresh', 'clear']);
  calls.length = 0;
  await assert.rejects(managed.ensure(profile), error => error instanceof Error
    && error.name === 'AuthenticationExpiredError');
  assert.deepEqual(calls, ['session']);
});

test('requires both exact profile and token binding and binds only after a verified candidate', async () => {
  for (const boundProfile of [profile, { ...profile, id: 'other-profile' }]) {
    const calls: string[] = [];
    const stored = boundProfile.id === profile.id
      ? { ...refreshed, accessToken: 'different-token', expiresAt: '2026-08-19T12:02:00.001Z' }
      : { ...refreshed, expiresAt: '2026-08-19T12:02:00.001Z' };
    const managed = new AuthorizedMcpSession(
      { validate: async () => { calls.push('endpoint'); return endpoint; } },
      managedAuthentication(calls, async () => refreshed, stored),
      { test: async () => { calls.push('mcp'); return evidence; }, close: async () => {} },
      () => new Date('2026-08-19T12:00:00.000Z'),
    );
    managed.bind(boundProfile, { ...stored, accessToken: refreshed.accessToken });
    assert.equal(await managed.ensure(profile), evidence);
    assert.deepEqual(calls, ['session', 'endpoint', 'mcp']);
    calls.length = 0;
    assert.equal(await managed.ensure(profile), undefined);
    assert.deepEqual(calls, ['session']);
  }
});

test('default clock works and sign-out reports close failure or no stored session explicitly', async () => {
  const freshNow = new Date(Date.now() + 120_001).toISOString();
  const defaultClockSession = { ...refreshed, expiresAt: freshNow };
  const defaultClockCalls: string[] = [];
  const defaultClock = new AuthorizedMcpSession(
    { validate: async () => endpoint },
    managedAuthentication(defaultClockCalls, async () => refreshed, defaultClockSession),
    { test: async () => evidence, close: async () => {} },
  );
  defaultClock.bind(profile, defaultClockSession);
  assert.equal(await defaultClock.ensure(profile), undefined);
  assert.deepEqual(defaultClockCalls, ['session']);

  const calls: string[] = [];
  const managed = new AuthorizedMcpSession(
    { validate: async () => { calls.push('endpoint'); return endpoint; } },
    managedAuthentication(calls, async () => refreshed, null),
    { test: async () => evidence, close: async () => { calls.push('close'); throw new Error('close failed'); } },
  );
  assert.deepEqual(await managed.signOut(profile), {
    transportClosure: 'UNCONFIRMED', remoteRevocation: 'NOT_REQUIRED',
  });
  assert.deepEqual(calls, ['close', 'session', 'clear']);
});

test('session invalidation and sign-out unbind even when storage still returns the old token', async () => {
  let value: OAuthSession | undefined = refreshed;
  const calls: string[] = [];
  const authentication: RenewableAuthenticationPort = {
    authenticate: async () => refreshed,
    session: async () => { calls.push('session'); return value; },
    refresh: async () => refreshed,
    revoke: async () => { calls.push('revoke'); },
    clear: async () => { calls.push('clear'); },
  };
  const managed = new AuthorizedMcpSession(
    { validate: async () => { calls.push('endpoint'); return endpoint; } }, authentication,
    { test: async () => { calls.push('mcp'); return evidence; }, close: async () => { calls.push('close'); } },
    () => new Date('2026-08-19T12:00:00.000Z'),
  );
  managed.bind(profile, refreshed);
  value = undefined;
  await assert.rejects(managed.ensure(profile), error => error instanceof Error
    && error.name === 'AuthenticationExpiredError');
  value = refreshed;
  assert.equal(await managed.ensure(profile), evidence);
  assert.deepEqual(calls, ['session', 'session', 'endpoint', 'mcp']);

  calls.length = 0;
  assert.deepEqual(await managed.signOut(profile), {
    transportClosure: 'CONFIRMED', remoteRevocation: 'CONFIRMED',
  });
  assert.equal(await managed.ensure(profile), evidence);
  assert.deepEqual(calls, ['close', 'session', 'endpoint', 'revoke', 'clear', 'session', 'endpoint', 'mcp']);
});

test('a failed refresh unbinds before local clear so a later stored token is reverified', async () => {
  const calls: string[] = [];
  let value: OAuthSession = current;
  const authentication: RenewableAuthenticationPort = {
    authenticate: async () => refreshed,
    session: async () => { calls.push('session'); return value; },
    refresh: async () => { calls.push('refresh'); throw new Error('refresh failed'); },
    revoke: async () => {},
    clear: async () => {
      calls.push('clear');
      value = { ...current, expiresAt: '2026-08-19T12:02:00.001Z' };
    },
  };
  const managed = new AuthorizedMcpSession(
    { validate: async () => { calls.push('endpoint'); return endpoint; } }, authentication,
    { test: async () => { calls.push('mcp'); return evidence; }, close: async () => {} },
    () => new Date('2026-08-19T12:00:00.000Z'),
  );
  managed.bind(profile, current);
  await assert.rejects(managed.ensure(profile), /refresh failed/);
  assert.equal(await managed.ensure(profile), evidence);
  assert.deepEqual(calls, ['session', 'endpoint', 'refresh', 'clear', 'session', 'endpoint', 'mcp']);
});

function exactContract(code: string): (error: unknown) => boolean {
  return error => error instanceof ConnectionContractError
    && error.code === code && error.message === `${code}: ${code}`;
}

function assertContract(action: () => void, code: string): void {
  assert.throws(action, exactContract(code));
}

function managedAuthentication(
  calls: string[],
  refresh: RenewableAuthenticationPort['refresh'],
  stored: OAuthSession | null = current,
): RenewableAuthenticationPort {
  let value: OAuthSession | undefined = stored ?? undefined;
  return {
    authenticate: async () => { calls.push('authenticate'); return refreshed; },
    session: async () => { calls.push('session'); return value; },
    refresh: async (...args) => {
      calls.push('refresh');
      value = await refresh(...args);
      return value;
    },
    revoke: async () => { calls.push('revoke'); },
    clear: async () => { calls.push('clear'); value = undefined; },
  };
}
