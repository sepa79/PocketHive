import assert from 'node:assert/strict';
import test from 'node:test';

import {
  AuthorizedMcpSession,
  SESSION_REFRESH_RETRY_MS,
  SESSION_RENEWAL_SKEW_MS,
  SessionTimerPort,
} from '../connection/authorizedMcpSession';
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
  scopes: ['pockethive:mcp:discover', 'pockethive:mcp:read', 'pockethive:mcp:operate'],
  renewal: { kind: 'ROTATING_REFRESH_TOKEN', refreshToken: 'current-refresh' },
};
const refreshed: OAuthSession = {
  accessToken: 'refreshed-access', expiresAt: '2026-08-19T12:15:00.000Z',
  scopes: ['pockethive:mcp:discover', 'pockethive:mcp:read', 'pockethive:mcp:operate'],
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
    scopes: current.scopes,
  }), 'OAUTH_SESSION_NOT_RENEWABLE');

  const renewal = managed.ensure(profile);
  const other = { ...profile, id: 'other', secretKey: 'secret.other' };
  assert.throws(() => managed.ensure(other), exactContract('OAUTH_SESSION_PROFILE_CONFLICT'));
  await assert.rejects(managed.signIn(profile), exactContract('OAUTH_SESSION_TRANSITION_IN_PROGRESS'));
  await assert.rejects(managed.signOut(profile), exactContract('OAUTH_SESSION_TRANSITION_IN_PROGRESS'));
  release();
  await renewal;
});

test('fails closed for missing or ephemeral stored sessions and retains a transiently unavailable grant', async () => {
  for (const stored of [null, {
    accessToken: 'ephemeral', expiresAt: current.expiresAt, renewal: { kind: 'NONE' as const },
    scopes: current.scopes,
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
  assert.deepEqual(calls, ['session', 'endpoint', 'refresh']);
  calls.length = 0;
  await assert.rejects(managed.ensure(profile), /refresh rejected/);
  assert.deepEqual(calls, ['session', 'endpoint', 'refresh']);
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

test('a definitively rejected refresh unbinds before local clear so a later stored token is reverified', async () => {
  const calls: string[] = [];
  let value: OAuthSession = current;
  const authentication: RenewableAuthenticationPort = {
    authenticate: async () => refreshed,
    session: async () => { calls.push('session'); return value; },
    refresh: async () => {
      calls.push('refresh');
      throw new ConnectionContractError('OAUTH_REFRESH_REJECTED', 'OAUTH_REFRESH_REJECTED');
    },
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
  await assert.rejects(managed.ensure(profile), exactContract('OAUTH_REFRESH_REJECTED'));
  assert.equal(await managed.ensure(profile), evidence);
  assert.deepEqual(calls, ['session', 'endpoint', 'refresh', 'clear', 'session', 'endpoint', 'mcp']);
});

test('bind schedules a silent single-flight renewal and reports the verified replacement', async () => {
  const scheduled: Array<{ delay: number; action: () => Promise<void> }> = [];
  const cancelled: unknown[] = [];
  const timers: SessionTimerPort = {
    schedule: (delay, action) => {
      const handle = { delay };
      scheduled.push({ delay, action });
      return handle;
    },
    cancel: handle => { cancelled.push(handle); },
  };
  const observed: unknown[] = [];
  const calls: string[] = [];
  let now = new Date('2026-08-19T12:00:00.000Z');
  const managed = new AuthorizedMcpSession(
    { validate: async () => { calls.push('endpoint'); return endpoint; } },
    managedAuthentication(calls, async () => refreshed),
    { test: async () => { calls.push('mcp'); return evidence; }, close: async () => {} },
    () => now,
    timers,
    {
      renewed: (_profile, value) => { observed.push(value); },
      unavailable: (_profile, error) => { observed.push(error); },
    },
  );

  managed.bind(profile, refreshed);
  assert.equal(scheduled[0].delay, 14 * 60_000);
  now = new Date('2026-08-19T12:14:00.000Z');
  await scheduled[0].action();

  assert.deepEqual(calls, ['session', 'endpoint', 'refresh', 'mcp']);
  assert.deepEqual(observed, [evidence]);
  assert.equal(cancelled.length, 1);
  assert.equal(scheduled.length, 2);
});

test('a missing command scope fails explicitly without authentication or browser fallback', async () => {
  const calls: string[] = [];
  const viewer = {
    ...refreshed,
    scopes: ['pockethive:mcp:discover', 'pockethive:mcp:read'] as const,
  };
  const authentication = managedAuthentication(calls, async () => refreshed, viewer);
  authentication.authenticate = async () => {
    calls.push('authenticate');
    throw new Error('browser must not open');
  };
  const managed = new AuthorizedMcpSession(
    { validate: async () => endpoint }, authentication,
    { test: async () => evidence, close: async () => {} },
    () => new Date('2026-08-19T12:00:00.000Z'),
  );
  managed.bind(profile, viewer);

  await assert.rejects(
    managed.ensure(profile, ['pockethive:mcp:publish']),
    exactContract('OAUTH_SCOPE_NOT_GRANTED'),
  );
  assert.deepEqual(calls, ['session']);
  assert.equal(SESSION_REFRESH_RETRY_MS, 15_000);
});

test('concurrent callers share renewal but retain their own exact command scope check', async () => {
  const calls: string[] = [];
  let release!: () => void;
  const wait = new Promise<void>(resolve => { release = resolve; });
  const viewer = {
    ...refreshed,
    scopes: ['pockethive:mcp:discover', 'pockethive:mcp:read'] as const,
  };
  const authentication = managedAuthentication(calls, async () => {
    await wait;
    return viewer;
  });
  const managed = new AuthorizedMcpSession(
    { validate: async () => { calls.push('endpoint'); return endpoint; } }, authentication,
    { test: async () => { calls.push('mcp'); return evidence; }, close: async () => {} },
    () => new Date('2026-08-19T12:00:00.000Z'),
  );
  managed.bind(profile, current);

  const read = managed.ensure(profile, ['pockethive:mcp:read']);
  const publish = managed.ensure(profile, ['pockethive:mcp:publish']);
  release();

  assert.equal(await read, evidence);
  await assert.rejects(publish, exactContract('OAUTH_SCOPE_NOT_GRANTED'));
  assert.deepEqual(calls, ['session', 'endpoint', 'refresh', 'mcp', 'session']);
});

test('a refresh that narrows permission fails the waiting command before reconnecting', async () => {
  const calls: string[] = [];
  const publisher: OAuthSession = {
    ...current,
    scopes: [...current.scopes, 'pockethive:mcp:author', 'pockethive:mcp:publish'],
  };
  const viewer: OAuthSession = {
    ...refreshed,
    scopes: ['pockethive:mcp:discover', 'pockethive:mcp:read'],
  };
  const managed = new AuthorizedMcpSession(
    { validate: async () => { calls.push('endpoint'); return endpoint; } },
    managedAuthentication(calls, async () => viewer, publisher),
    { test: async () => { calls.push('mcp'); return evidence; }, close: async () => {} },
    () => new Date('2026-08-19T12:00:00.000Z'),
  );
  managed.bind(profile, publisher);

  await assert.rejects(managed.ensure(profile, ['pockethive:mcp:publish']),
    exactContract('OAUTH_SCOPE_NOT_GRANTED'));
  assert.deepEqual(calls, ['session', 'endpoint', 'refresh']);
});

test('a joined command fails closed if the coalesced renewal no longer has a renewable stored session', async () => {
  for (const joinedSession of [undefined, {
    ...refreshed, renewal: { kind: 'NONE' as const },
  }]) {
    const calls: string[] = [];
    const cancelled: unknown[] = [];
    const scheduled: Array<{ delay: number; action: () => Promise<void> }> = [];
    let release!: () => void;
    const wait = new Promise<void>(resolve => { release = resolve; });
    let sessionCalls = 0;
    const authentication: RenewableAuthenticationPort = {
      authenticate: async () => refreshed,
      session: async () => {
        calls.push('session');
        sessionCalls += 1;
        return sessionCalls === 1 ? current : joinedSession;
      },
      refresh: async () => { calls.push('refresh'); await wait; return refreshed; },
      revoke: async () => {},
      clear: async () => {},
    };
    const managed = new AuthorizedMcpSession(
      { validate: async () => { calls.push('endpoint'); return endpoint; } }, authentication,
      { test: async () => { calls.push('mcp'); return evidence; }, close: async () => {} },
      () => new Date('2026-08-19T12:00:00.000Z'),
      {
        schedule: (delay, action) => { scheduled.push({ delay, action }); return action; },
        cancel: handle => { cancelled.push(handle); },
      },
    );
    managed.bind(profile, current);
    const first = managed.ensure(profile);
    const joined = managed.ensure(profile, ['pockethive:mcp:read']);
    release();

    assert.equal(await first, evidence);
    await assert.rejects(joined, error => error instanceof Error
      && error.name === 'AuthenticationExpiredError' && error.message === 'OAuth session expired');
    assert.deepEqual(calls, ['session', 'endpoint', 'refresh', 'mcp', 'session']);
    assert.equal(cancelled.length, 2);
    assert.equal(scheduled.length, 2);
  }
});

test('unbind cancels one active schedule and is idempotent once detached', () => {
  const cancelled: unknown[] = [];
  const handle = Object.freeze({ id: 1 });
  const managed = new AuthorizedMcpSession(
    { validate: async () => endpoint }, managedAuthentication([], async () => refreshed),
    { test: async () => evidence, close: async () => {} },
    () => new Date('2026-08-19T12:00:00.000Z'),
    { schedule: () => handle, cancel: value => { cancelled.push(value); } },
  );
  managed.unbind();
  assert.deepEqual(cancelled, []);
  managed.bind(profile, refreshed);
  managed.unbind();
  managed.unbind();
  assert.deepEqual(cancelled, [handle]);
});

test('scheduled refresh retries one transient outage, reports recovery, and never opens a browser', async () => {
  const scheduled: Array<{ delay: number; action: () => Promise<void>; handle: object }> = [];
  const cancelled: unknown[] = [];
  const observed: string[] = [];
  const calls: string[] = [];
  let attempt = 0;
  const managed = new AuthorizedMcpSession(
    { validate: async () => { calls.push('endpoint'); return endpoint; } },
    managedAuthentication(calls, async () => {
      attempt += 1;
      if (attempt === 1) throw new Error('temporary outage');
      return refreshed;
    }),
    { test: async () => { calls.push('mcp'); return evidence; }, close: async () => {} },
    () => new Date('2026-08-19T12:00:00.000Z'),
    {
      schedule: (delay, action) => {
        const item = { delay, action, handle: {} };
        scheduled.push(item);
        return item.handle;
      },
      cancel: handle => { cancelled.push(handle); },
    },
    {
      renewed: (_profile, value) => { observed.push(`renewed:${value.serverName}`); },
      unavailable: (_profile, error) => { observed.push(`unavailable:${String(error)}`); },
    },
  );
  managed.bind(profile, current);

  assert.equal(scheduled[0].delay, 0);
  await scheduled[0].action();
  assert.equal(scheduled[1].delay, SESSION_REFRESH_RETRY_MS);
  assert.deepEqual(observed, ['unavailable:Error: temporary outage']);
  await scheduled[1].action();

  assert.deepEqual(observed, ['unavailable:Error: temporary outage', 'renewed:pockethive-mcp']);
  assert.deepEqual(calls, [
    'session', 'endpoint', 'refresh',
    'session', 'endpoint', 'refresh', 'mcp',
  ]);
  assert.equal(scheduled.length, 3);
  assert.deepEqual(cancelled, [scheduled[1].handle]);
});

test('scheduled transient retry is bounded to one attempt and reports both failures', async () => {
  const scheduled: Array<{ delay: number; action: () => Promise<void> }> = [];
  const observed: string[] = [];
  const managed = new AuthorizedMcpSession(
    { validate: async () => endpoint },
    managedAuthentication([], async () => { throw new Error('offline'); }),
    { test: async () => evidence, close: async () => {} },
    () => new Date('2026-08-19T12:00:00.000Z'),
    {
      schedule: (delay, action) => { scheduled.push({ delay, action }); return action; },
      cancel: () => {},
    },
    {
      renewed: () => { observed.push('renewed'); },
      unavailable: () => { observed.push('unavailable'); },
    },
  );
  managed.bind(profile, current);
  await scheduled[0].action();
  await scheduled[1].action();
  assert.deepEqual(observed, ['unavailable', 'unavailable']);
  assert.equal(scheduled.length, 2);
});

test('scheduled refresh does not report renewal when the bound token is still fresh', async () => {
  const scheduled: Array<{ delay: number; action: () => Promise<void> }> = [];
  const observed: string[] = [];
  const fresh = { ...refreshed, expiresAt: '2026-08-19T12:15:00.000Z' };
  const managed = new AuthorizedMcpSession(
    { validate: async () => { throw new Error('network must not run'); } },
    managedAuthentication([], async () => { throw new Error('refresh must not run'); }, fresh),
    { test: async () => evidence, close: async () => {} },
    () => new Date('2026-08-19T12:00:00.000Z'),
    {
      schedule: (delay, action) => { scheduled.push({ delay, action }); return action; },
      cancel: () => {},
    },
    {
      renewed: () => { observed.push('renewed'); },
      unavailable: () => { observed.push('unavailable'); },
    },
  );
  managed.bind(profile, fresh);
  await scheduled[0].action();
  assert.deepEqual(observed, []);
  assert.equal(scheduled.length, 1);
});

test('definitive scheduled failures clear or invalidate once and never retry', async () => {
  for (const code of [
    'OAUTH_REFRESH_REJECTED',
    'OAUTH_REFRESH_TOKEN_NOT_ROTATED',
    'OAUTH_REFRESH_SCOPE_WIDENED',
    'OAUTH_TOKEN_RESPONSE_INVALID',
  ]) {
    const scheduled: Array<() => Promise<void>> = [];
    const calls: string[] = [];
    const observed: unknown[] = [];
    const managed = new AuthorizedMcpSession(
      { validate: async () => endpoint },
      managedAuthentication(calls, async () => { throw new ConnectionContractError(code, code); }),
      { test: async () => evidence, close: async () => {} },
      () => new Date('2026-08-19T12:00:00.000Z'),
      {
        schedule: (_delay, action) => { scheduled.push(action); return action; },
        cancel: () => {},
      },
      {
        renewed: () => { observed.push('renewed'); },
        unavailable: (_profile, error) => { observed.push(error); },
      },
    );
    managed.bind(profile, current);
    await scheduled[0]();
    assert.equal(scheduled.length, 1, code);
    assert.equal(observed.length, 1, code);
    assert.equal(observed[0] instanceof ConnectionContractError, true, code);
    assert.deepEqual(calls, ['session', 'refresh', 'clear'], code);
  }

  const scheduled: Array<() => Promise<void>> = [];
  const observed: unknown[] = [];
  const managed = new AuthorizedMcpSession(
    { validate: async () => endpoint }, managedAuthentication([], async () => refreshed, null),
    { test: async () => evidence, close: async () => {} },
    () => new Date('2026-08-19T12:00:00.000Z'),
    {
      schedule: (_delay, action) => { scheduled.push(action); return action; }, cancel: () => {},
    },
    { renewed: () => {}, unavailable: (_profile, error) => { observed.push(error); } },
  );
  managed.bind(profile, current);
  await scheduled[0]();
  assert.equal(scheduled.length, 1);
  assert.equal(observed[0] instanceof Error && (observed[0] as Error).name === 'AuthenticationExpiredError', true);
});

test('an unbound profile never schedules a retry after an in-flight transient failure', async () => {
  const scheduled: Array<() => Promise<void>> = [];
  const managed = new AuthorizedMcpSession(
    { validate: async () => endpoint },
    managedAuthentication([], async () => { throw new Error('offline'); }),
    { test: async () => evidence, close: async () => {} },
    () => new Date('2026-08-19T12:00:00.000Z'),
    {
      schedule: (_delay, action) => { scheduled.push(action); return action; }, cancel: () => {},
    },
  );
  managed.bind(profile, current);
  const action = scheduled[0];
  managed.unbind();
  await action();
  assert.equal(scheduled.length, 1);
});

test('a non-definitive OAuth contract outage remains retryable', async () => {
  const scheduled: Array<() => Promise<void>> = [];
  const managed = new AuthorizedMcpSession(
    { validate: async () => endpoint },
    managedAuthentication([], async () => {
      throw new ConnectionContractError('OAUTH_HTTP_FAILED', 'OAUTH_HTTP_FAILED');
    }),
    { test: async () => evidence, close: async () => {} },
    () => new Date('2026-08-19T12:00:00.000Z'),
    {
      schedule: (_delay, action) => { scheduled.push(action); return action; }, cancel: () => {},
    },
  );
  managed.bind(profile, current);
  await scheduled[0]();
  assert.equal(scheduled.length, 2);
});

test('a stale scheduled retry does not report renewal for an already fresh replacement', async () => {
  const scheduled: Array<() => Promise<void>> = [];
  const observed: string[] = [];
  let value = current;
  const authentication: RenewableAuthenticationPort = {
    authenticate: async () => refreshed,
    session: async () => value,
    refresh: async () => { throw new Error('offline'); },
    revoke: async () => {},
    clear: async () => {},
  };
  const managed = new AuthorizedMcpSession(
    { validate: async () => endpoint },
    authentication,
    { test: async () => evidence, close: async () => {} },
    () => new Date('2026-08-19T12:00:00.000Z'),
    {
      schedule: (_delay, action) => { scheduled.push(action); return action; }, cancel: () => {},
    },
    {
      renewed: () => { observed.push('renewed'); },
      unavailable: () => { observed.push('unavailable'); },
    },
  );
  managed.bind(profile, current);
  await scheduled[0]();
  const retry = scheduled[1];
  value = { ...current, accessToken: 'fresh', expiresAt: '2026-08-19T12:15:00.000Z' };
  managed.bind(profile, value);
  await retry();
  assert.deepEqual(observed, ['unavailable']);
});

test('default timers execute due refresh and unbind cancels a due refresh', async () => {
  const calls: string[] = [];
  let renewed!: () => void;
  const renewalObserved = new Promise<void>(resolve => { renewed = resolve; });
  const managed = new AuthorizedMcpSession(
    { validate: async () => endpoint }, managedAuthentication(calls, async () => refreshed),
    { test: async () => evidence, close: async () => {} },
    () => new Date('2026-08-19T12:00:00.000Z'),
    undefined,
    { renewed: () => { renewed(); }, unavailable: () => {} },
  );
  managed.bind(profile, current);
  await Promise.race([
    renewalObserved,
    new Promise<never>((_resolve, reject) => setTimeout(() => reject(new Error('timer did not run')), 250)),
  ]);
  assert.deepEqual(calls, ['session', 'refresh']);

  const cancelledCalls: string[] = [];
  const cancelled = new AuthorizedMcpSession(
    { validate: async () => endpoint }, managedAuthentication(cancelledCalls, async () => refreshed),
    { test: async () => evidence, close: async () => {} },
    () => new Date('2026-08-19T12:00:00.000Z'),
  );
  cancelled.bind(profile, current);
  cancelled.unbind();
  await new Promise(resolve => setTimeout(resolve, 25));
  assert.deepEqual(cancelledCalls, []);
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
