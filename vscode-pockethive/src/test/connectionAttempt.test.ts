import assert from 'node:assert/strict';
import test from 'node:test';

import { ConnectionAttempt } from '../connection/connectionAttempt';
import {
  AuthenticationCancelledError,
  AuthenticationExpiredError,
  AuthenticationPort,
  ConnectionContractError,
  ConnectionAttemptView,
  ConnectionEvidence,
  EndpointValidationPort,
  McpConnectionTestPort,
  OAuthSession,
} from '../connection/contracts';
import { createConnectionProfile } from '../connection/profile';

const NOW = new Date('2026-08-18T12:00:00Z');
const profile = createConnectionProfile({
  id: 'nft-lab',
  displayName: 'NFT Lab',
  mcpUrl: 'https://nft-lab.example/mcp',
  endpointSecurityMode: 'REMOTE_HTTPS',
  secretKey: 'pockethive.profile.nft-lab.oauth',
});
const session: OAuthSession = { accessToken: 'secret-token', expiresAt: '2026-08-18T12:15:00Z' };
const evidence: ConnectionEvidence = {
  serverName: 'pockethive-mcp',
  serverVersion: '0.15.35',
  principalLabel: 'QA lead',
  capabilityFingerprint: 'sha256:abc',
  observedAt: NOW.toISOString(),
};

test('a new connection attempt exposes only explicit unvalidated and unauthenticated state', () => {
  const attempt = createAttempt([], async () => session, async () => evidence);
  assert.deepEqual(attempt.view(), {
    profileId: profile.id,
    state: 'EDITING',
    endpointValidated: false,
    authenticated: false,
    failure: undefined,
    evidence: undefined,
  });
});

test('the production clock records an ISO observation time when no clock is injected', async () => {
  const attempt = new ConnectionAttempt(profile,
    { validate: async () => { throw new Error('unreachable'); } },
    { authenticate: async () => session, session: async () => session },
    { test: async () => evidence },
    { changed: () => {} });
  const result = await attempt.connect();
  assert.match(result.failure?.observedAt ?? '', /^\d{4}-\d{2}-\d{2}T/);
});

test('connect validates endpoint, authenticates, then tests MCP and gates save', async () => {
  const calls: string[] = [];
  const changes: ConnectionAttemptView[] = [];
  const attempt = createAttempt(
    calls,
    async () => session,
    async () => evidence,
    changes,
  );

  const ready = await attempt.connect();

  assert.deepEqual(calls, ['endpoint', 'authenticate', 'mcp']);
  assert.equal(ready.state, 'READY_TO_SAVE');
  assert.equal(ready.endpointValidated, true);
  assert.equal(ready.authenticated, true);
  assert.equal(ready.evidence, evidence);
  assert.equal(attempt.save().state, 'SAVED');
  assert.deepEqual(changes.map(change => change.state), [
    'AUTHENTICATING', 'TESTING', 'READY_TO_SAVE', 'SAVED',
  ]);
});

test('authentication cancellation is terminal and never runs the MCP test', async () => {
  const calls: string[] = [];
  const attempt = createAttempt(
    calls,
    async () => { throw new AuthenticationCancelledError(); },
    async () => { throw new Error('must not be called'); },
  );

  const result = await attempt.connect();

  assert.equal(result.state, 'CANCELLED');
  assert.deepEqual(calls, ['endpoint', 'authenticate']);
});

test('ordinary authentication failure remains actionable and sign-in cancellation is terminal', async () => {
  const calls: string[] = [];
  let authenticationCount = 0;
  const attempt = createAttempt(
    calls,
    async () => {
      authenticationCount += 1;
      if (authenticationCount === 1) throw new Error('identity provider unavailable');
      throw new AuthenticationCancelledError();
    },
    async () => { throw new Error('must not be called'); },
  );

  const failed = await attempt.connect();
  assert.equal(failed.state, 'AUTHENTICATION_FAILED');
  assert.equal(failed.failure?.code, 'Error');
  assert.equal(failed.failure?.message, 'identity provider unavailable');
  assert.equal((await attempt.signInAgain()).state, 'CANCELLED');
  assert.deepEqual(calls, ['endpoint', 'authenticate', 'authenticate']);
});

test('endpoint failure is emitted without authentication or MCP calls', async () => {
  const changes: ConnectionAttemptView[] = [];
  const attempt = new ConnectionAttempt(profile,
    { validate: async () => { throw new ConnectionContractError('ENDPOINT_REJECTED', 'unsafe endpoint'); } },
    {
      authenticate: async () => { throw new Error('must not authenticate'); },
      session: async () => { throw new Error('must not read session'); },
    },
    { test: async () => { throw new Error('must not test'); } },
    { changed: change => changes.push(change) },
    () => NOW);

  const result = await attempt.connect();

  assert.equal(result.state, 'EDITING');
  assert.equal(result.failure?.code, 'ENDPOINT_REJECTED');
  assert.equal(changes.length, 1);
  assert.equal(changes[0].failure?.message, 'ENDPOINT_REJECTED: unsafe endpoint');
});

test('explicit cancellation aborts an in-progress authentication and never runs the MCP test', async () => {
  const calls: string[] = [];
  const changes: ConnectionAttemptView[] = [];
  let started!: () => void;
  const authenticating = new Promise<void>(resolve => { started = resolve; });
  const attempt = createAttempt(
    calls,
    async (_profile, _endpoint, signal) => {
      started();
      await new Promise<void>((_resolve, reject) => signal.addEventListener('abort', () => {
        reject(new AuthenticationCancelledError());
      }, { once: true }));
      throw new Error('unreachable');
    },
    async () => { throw new Error('must not be called'); },
    changes,
  );

  const pending = attempt.connect();
  await authenticating;
  assert.equal(attempt.cancel().state, 'CANCELLED');
  assert.equal((await pending).state, 'CANCELLED');
  assert.equal(changes.filter(change => change.state === 'CANCELLED').length, 1);
  assert.deepEqual(calls, ['endpoint', 'authenticate']);
});

test('authentication that observes cancellation cannot continue into MCP testing', async () => {
  const calls: string[] = [];
  let attempt!: ConnectionAttempt;
  attempt = createAttempt(
    calls,
    async () => {
      attempt.cancel();
      return session;
    },
    async () => { throw new Error('must not be called'); },
  );

  assert.equal((await attempt.connect()).state, 'CANCELLED');
  assert.deepEqual(calls, ['endpoint', 'authenticate']);
});

test('a concurrent operation is rejected while endpoint validation is in progress', async () => {
  let validationStarted!: () => void;
  let releaseValidation!: () => void;
  const started = new Promise<void>(resolve => { validationStarted = resolve; });
  const release = new Promise<void>(resolve => { releaseValidation = resolve; });
  const attempt = new ConnectionAttempt(profile,
    { validate: async current => {
      validationStarted();
      await release;
      return {
        mcpUrl: current.mcpUrl,
        resourceMetadataUrl: 'https://nft-lab.example/.well-known/oauth-protected-resource',
        authorizationServer: 'https://nft-lab.example/auth-service',
      };
    } },
    { authenticate: async () => session, session: async () => session },
    { test: async () => evidence },
    { changed: () => {} },
    () => NOW);

  const pending = attempt.connect();
  await started;
  await assert.rejects(attempt.connect(), (error: unknown) =>
    error instanceof ConnectionContractError && error.code === 'CONNECTION_ATTEMPT_ALREADY_RUNNING');
  releaseValidation();
  assert.equal((await pending).state, 'READY_TO_SAVE');
});

test('test failure remains distinct and retry reuses only a current OAuth session', async () => {
  const calls: string[] = [];
  let testCount = 0;
  const attempt = createAttempt(
    calls,
    async () => session,
    async () => {
      testCount += 1;
      if (testCount === 1) throw new Error('MCP unavailable');
      return evidence;
    },
  );

  assert.equal((await attempt.connect()).state, 'CONNECTION_TEST_FAILED');
  assert.equal((await attempt.retryTest()).state, 'READY_TO_SAVE');
  assert.deepEqual(calls, ['endpoint', 'authenticate', 'mcp', 'session', 'mcp']);
});

test('retry and reconnect release their completed operations before later cancellation', async () => {
  const retryCalls: string[] = [];
  let retryTestCount = 0;
  let retrySignal: AbortSignal | undefined;
  const retryAttempt = createAttempt(
    retryCalls,
    async () => session,
    async (_profile, _session, signal) => {
      retryTestCount += 1;
      if (retryTestCount === 1) throw new Error('first test fails');
      retrySignal = signal;
      return evidence;
    },
  );
  await retryAttempt.connect();
  assert.equal((await retryAttempt.retryTest()).state, 'READY_TO_SAVE');
  assert.equal(retryAttempt.cancel().state, 'CANCELLED');
  assert.equal(retrySignal?.aborted, false);

  const reconnectCalls: string[] = [];
  let reconnectSignal: AbortSignal | undefined;
  const reconnectAttempt = createAttempt(
    reconnectCalls,
    async () => { throw new Error('interactive authentication must not run'); },
    async (_profile, _session, signal) => {
      reconnectSignal = signal;
      return evidence;
    },
  );
  assert.equal((await reconnectAttempt.reconnect()).state, 'READY_TO_SAVE');
  assert.equal(reconnectAttempt.cancel().state, 'CANCELLED');
  assert.equal(reconnectSignal?.aborted, false);
});

test('sign-in retry handles failure and releases a successful completed operation', async () => {
  const calls: string[] = [];
  const changes: ConnectionAttemptView[] = [];
  let authenticationCount = 0;
  let successfulSignal: AbortSignal | undefined;
  const attempt = createAttempt(
    calls,
    async (_profile, _endpoint, signal) => {
      authenticationCount += 1;
      if (authenticationCount < 3) throw new Error(`failure-${authenticationCount}`);
      successfulSignal = signal;
      return session;
    },
    async () => evidence,
    changes,
  );

  assert.equal((await attempt.connect()).state, 'AUTHENTICATION_FAILED');
  const secondFailure = await attempt.signInAgain();
  assert.equal(secondFailure.state, 'AUTHENTICATION_FAILED');
  assert.equal(secondFailure.failure?.message, 'failure-2');
  const ready = await attempt.signInAgain();
  assert.equal(ready.state, 'READY_TO_SAVE');
  assert.equal(ready.authenticated, true);
  assert.equal(changes.filter(change => change.state === 'AUTHENTICATING').length, 3);
  assert.equal(changes.filter(change => change.state === 'AUTHENTICATING')
    .every(change => change.authenticated === false), true);
  assert.equal(attempt.cancel().state, 'CANCELLED');
  assert.equal(successfulSignal?.aborted, false);
});

test('expired retry becomes authentication failure without calling MCP', async () => {
  const calls: string[] = [];
  const expired = { accessToken: 'expired', expiresAt: '2026-08-18T11:59:59Z' };
  const attempt = createAttempt(
    calls,
    async () => session,
    async () => { throw new Error('MCP unavailable'); },
    undefined,
    expired,
  );

  await attempt.connect();
  const result = await attempt.retryTest();

  assert.equal(result.state, 'AUTHENTICATION_FAILED');
  assert.equal(result.authenticated, false);
  assert.deepEqual(calls, ['endpoint', 'authenticate', 'mcp', 'session']);
});

test('a session expiring exactly now is rejected on retry', async () => {
  const calls: string[] = [];
  const expiresNow = { accessToken: 'expired', expiresAt: NOW.toISOString() };
  const attempt = createAttempt(
    calls,
    async () => session,
    async () => { throw new Error('MCP unavailable'); },
    undefined,
    expiresNow,
  );

  await attempt.connect();
  const result = await attempt.retryTest();

  assert.equal(result.state, 'AUTHENTICATION_FAILED');
  assert.equal(result.authenticated, false);
  assert.deepEqual(calls, ['endpoint', 'authenticate', 'mcp', 'session']);
});

test('saved-profile reconnect validates then tests a current stored session without opening OAuth', async () => {
  const calls: string[] = [];
  const attempt = createAttempt(
    calls,
    async () => { throw new Error('interactive authentication must not run'); },
    async () => evidence,
  );

  const result = await attempt.reconnect();

  assert.equal(result.state, 'READY_TO_SAVE');
  assert.equal(result.endpointValidated, true);
  assert.equal(result.authenticated, true);
  assert.deepEqual(calls, ['endpoint', 'session', 'mcp']);
});

test('saved-profile reconnect emits endpoint validation failure and stops', async () => {
  const changes: ConnectionAttemptView[] = [];
  const attempt = new ConnectionAttempt(profile,
    { validate: async () => { throw new ConnectionContractError('ENDPOINT_REJECTED', 'unsafe endpoint'); } },
    {
      authenticate: async () => { throw new Error('must not authenticate'); },
      session: async () => { throw new Error('must not read session'); },
    },
    { test: async () => { throw new Error('must not test'); } },
    { changed: change => changes.push(change) },
    () => NOW);

  const result = await attempt.reconnect();
  assert.equal(result.state, 'EDITING');
  assert.equal(result.failure?.code, 'ENDPOINT_REJECTED');
  assert.equal(changes.length, 1);
});

test('saved-profile reconnect rejects an expired session before MCP testing', async () => {
  const calls: string[] = [];
  const attempt = createAttempt(
    calls,
    async () => { throw new Error('interactive authentication must not run'); },
    async () => { throw new Error('MCP must not run'); },
    undefined,
    { accessToken: 'expired', expiresAt: NOW.toISOString() },
  );

  const result = await attempt.reconnect();

  assert.equal(result.state, 'AUTHENTICATION_FAILED');
  assert.equal(result.endpointValidated, true);
  assert.equal(result.authenticated, false);
  assert.deepEqual(calls, ['endpoint', 'session']);
});

test('cancellation that races with a successful MCP response remains terminal', async () => {
  const calls: string[] = [];
  let attempt!: ConnectionAttempt;
  attempt = createAttempt(
    calls,
    async () => session,
    async () => {
      assert.equal(attempt.cancel().state, 'CANCELLED');
      return evidence;
    },
  );

  assert.equal((await attempt.connect()).state, 'CANCELLED');
  assert.deepEqual(calls, ['endpoint', 'authenticate', 'mcp']);
});

test('cancellation that races with MCP errors remains terminal', async () => {
  for (const mcpError of [new Error('transport failed'), new AuthenticationExpiredError()]) {
    const calls: string[] = [];
    let attempt!: ConnectionAttempt;
    attempt = createAttempt(
      calls,
      async () => session,
      async () => {
        assert.equal(attempt.cancel().state, 'CANCELLED');
        throw mcpError;
      },
    );

    const result = await attempt.connect();
    assert.equal(result.state, 'CANCELLED');
    assert.equal(result.authenticated, true);
  }
});

test('an MCP authentication-expiry response clears authenticated state', async () => {
  const calls: string[] = [];
  const attempt = createAttempt(
    calls,
    async () => session,
    async () => { throw new AuthenticationExpiredError(); },
  );

  const result = await attempt.connect();

  assert.equal(result.state, 'AUTHENTICATION_FAILED');
  assert.equal(result.authenticated, false);
  assert.equal(result.failure?.code, 'AuthenticationExpiredError');
});

test('invalid transitions and non-Error failures fail closed with stable contract codes', async () => {
  const calls: string[] = [];
  const attempt = createAttempt(
    calls,
    async () => session,
    async () => { throw 'opaque failure'; },
  );

  const failed = await attempt.connect();
  assert.equal(failed.state, 'CONNECTION_TEST_FAILED');
  assert.equal(failed.failure?.code, 'CONNECTION_FAILED');
  assert.equal(failed.failure?.message, 'opaque failure');
  await assert.rejects(attempt.connect(), (error: unknown) =>
    error instanceof ConnectionContractError
    && error.code === 'CONNECTION_ATTEMPT_TRANSITION_INVALID'
    && error.message.includes('Expected EDITING, found CONNECTION_TEST_FAILED'));
});

test('cancelling an idle attempt fails with the explicit transition contract', () => {
  const attempt = createAttempt([], async () => session, async () => evidence);
  assert.throws(() => attempt.cancel(), (error: unknown) =>
    error instanceof ConnectionContractError && error.code === 'CONNECTION_ATTEMPT_TRANSITION_INVALID');
});

test('profile validation rejects protocol and path fallback', () => {
  assert.throws(() => createConnectionProfile({
    id: 'remote', displayName: 'Remote', mcpUrl: 'http://remote.example/mcp',
    endpointSecurityMode: 'REMOTE_HTTPS', secretKey: 'secret',
  }), (error: unknown) => error instanceof ConnectionContractError
    && error.code === 'MCP_ENDPOINT_HTTPS_REQUIRED'
    && error.message.includes('Remote MCP environments require HTTPS'));
  assert.throws(() => createConnectionProfile({
    id: 'local', displayName: 'Local', mcpUrl: 'http://192.168.1.2/mcp',
    endpointSecurityMode: 'LOCAL_LOOPBACK_HTTP', secretKey: 'secret',
  }), /explicit loopback host/);
  assert.throws(() => createConnectionProfile({
    id: 'local-https', displayName: 'Local HTTPS', mcpUrl: 'https://localhost/mcp',
    endpointSecurityMode: 'LOCAL_LOOPBACK_HTTP', secretKey: 'secret',
  }), (error: unknown) => error instanceof ConnectionContractError
    && error.code === 'MCP_ENDPOINT_LOOPBACK_REQUIRED');
  assert.throws(() => createConnectionProfile({
    id: 'wrong-path', displayName: 'Wrong', mcpUrl: 'https://example.test/api',
    endpointSecurityMode: 'REMOTE_HTTPS', secretKey: 'secret',
  }), /exactly \/mcp/);
});

test('profile validation rejects URL adornments and every blank required field with exact codes', () => {
  assert.throws(() => createConnectionProfile({
    id: 'profile', displayName: 'Profile', mcpUrl: 'not a URL',
    endpointSecurityMode: 'REMOTE_HTTPS', secretKey: 'secret',
  }), (error: unknown) => error instanceof ConnectionContractError
    && error.code === 'MCP_ENDPOINT_INVALID'
    && error.message === 'MCP_ENDPOINT_INVALID: MCP URL must be an absolute URL');
  for (const mcpUrl of [
    'https://user@example.test/mcp',
    'https://example.test/mcp?mode=unsafe',
    'https://example.test/mcp#fragment',
  ]) {
    assert.throws(() => createConnectionProfile({
      id: 'profile', displayName: 'Profile', mcpUrl,
      endpointSecurityMode: 'REMOTE_HTTPS', secretKey: 'secret',
    }), (error: unknown) => error instanceof ConnectionContractError
      && error.code === 'MCP_ENDPOINT_INVALID'
      && error.message.includes('credentials, query parameters, or a fragment'));
  }
  assert.throws(() => createConnectionProfile({
    id: 'profile', displayName: 'Profile', mcpUrl: 'https://example.test/not-mcp',
    endpointSecurityMode: 'REMOTE_HTTPS', secretKey: 'secret',
  }), (error: unknown) => error instanceof ConnectionContractError && error.code === 'MCP_ENDPOINT_PATH_INVALID');

  const fields = [
    ['id', 'PROFILE_ID_REQUIRED'],
    ['displayName', 'PROFILE_NAME_REQUIRED'],
    ['secretKey', 'PROFILE_SECRET_KEY_REQUIRED'],
  ] as const;
  for (const [field, code] of fields) {
    const input = {
      id: 'profile', displayName: 'Profile', mcpUrl: 'https://example.test/mcp',
      endpointSecurityMode: 'REMOTE_HTTPS' as const, secretKey: 'secret',
    };
    input[field] = '   ';
    assert.throws(() => createConnectionProfile(input), (error: unknown) =>
      error instanceof ConnectionContractError && error.code === code);
  }
});

test('profile validation accepts the explicitly supported IPv6 loopback host', () => {
  const ipv6 = createConnectionProfile({
    id: 'ipv6', displayName: 'IPv6 local', mcpUrl: 'http://[::1]/mcp',
    endpointSecurityMode: 'LOCAL_LOOPBACK_HTTP', secretKey: 'secret',
  });
  assert.equal(ipv6.mcpUrl, 'http://[::1]/mcp');
});

function createAttempt(
  calls: string[],
  authenticate: AuthenticationPort['authenticate'],
  testMcp: McpConnectionTestPort['test'],
  changes: ConnectionAttemptView[] = [],
  currentSession: OAuthSession | undefined = session,
): ConnectionAttempt {
  const endpoints: EndpointValidationPort = {
    validate: async current => {
      calls.push('endpoint');
      return {
        mcpUrl: current.mcpUrl,
        resourceMetadataUrl: 'https://nft-lab.example/.well-known/oauth-protected-resource',
        authorizationServer: 'https://nft-lab.example/auth-service',
      };
    },
  };
  const authentication: AuthenticationPort = {
    authenticate: async (current, endpoint, signal) => {
      calls.push('authenticate');
      return authenticate(current, endpoint, signal);
    },
    session: async () => {
      calls.push('session');
      return currentSession;
    },
  };
  const mcp: McpConnectionTestPort = {
    test: async (current, currentSessionValue, signal) => {
      calls.push('mcp');
      return testMcp(current, currentSessionValue, signal);
    },
  };
  return new ConnectionAttempt(profile, endpoints, authentication, mcp,
    { changed: change => changes.push(change) }, () => NOW);
}
