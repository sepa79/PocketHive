import assert from 'node:assert/strict';
import test from 'node:test';

import { POCKETHIVE_MCP_SCOPES } from '../connection/contracts';
import { ScopedMcpToolRunner } from '../operations/scopedMcpToolRunner';

const profile = {
  id: 'local',
  displayName: 'Local',
  mcpUrl: 'http://127.0.0.1:8088/mcp',
  endpointSecurityMode: 'LOCAL_LOOPBACK_HTTP' as const,
  authenticationMode: 'OAUTH_AUTHORIZATION_CODE_PKCE' as const,
  secretKey: 'local-secret',
};

test('scoped lifecycle calls validate and request only discover read operate before one exact call', async () => {
  const observed: unknown[] = [];
  const runner = new ScopedMcpToolRunner(
    { validate: async value => {
      observed.push(['validate', value.id]);
      return { mcpUrl: value.mcpUrl, resourceMetadataUrl: 'metadata', authorizationServer: 'issuer' };
    } },
    { authenticateForScopes: async (_profile, _endpoint, scopes) => {
      observed.push(['scopes', scopes]);
      return { accessToken: 'token', expiresAt: '2099-01-01T00:00:00Z', renewal: { kind: 'NONE' } };
    } },
    () => ({
      connect: async endpoint => { observed.push(['connect', endpoint]); return evidence(); },
      callTool: async (name, args) => { observed.push(['call', name, args]); return { status: 'Running' }; },
      close: async () => { observed.push(['close']); },
    }),
  );

  assert.deepEqual(await runner.call(profile, 'swarm_start', { swarmId: 'swarm-a', idempotencyKey: 'key-a' }), {
    status: 'Running',
  });
  assert.deepEqual(observed, [
    ['validate', 'local'],
    ['scopes', [
      POCKETHIVE_MCP_SCOPES.DISCOVER,
      POCKETHIVE_MCP_SCOPES.READ,
      POCKETHIVE_MCP_SCOPES.OPERATE,
    ]],
    ['connect', profile.mcpUrl],
    ['call', 'swarm_start', { swarmId: 'swarm-a', idempotencyKey: 'key-a' }],
    ['close'],
  ]);
});

test('scoped lifecycle runner closes its temporary client after owner failure', async () => {
  let closed = false;
  const runner = new ScopedMcpToolRunner(
    { validate: async value => ({ mcpUrl: value.mcpUrl, resourceMetadataUrl: 'metadata', authorizationServer: 'issuer' }) },
    { authenticateForScopes: async () => ({
      accessToken: 'token', expiresAt: '2099-01-01T00:00:00Z', renewal: { kind: 'NONE' },
    }) },
    () => ({
      connect: async () => evidence(),
      callTool: async () => { throw new Error('owner rejected'); },
      close: async () => { closed = true; },
    }),
  );

  await assert.rejects(() => runner.call(profile, 'swarm_stop', { swarmId: 'swarm-a' }), /owner rejected/);
  assert.equal(closed, true);
});

function evidence() {
  return {
    serverName: 'pockethive-mcp' as const,
    serverVersion: '1.0.0',
    principalLabel: 'QA lead',
    capabilityFingerprint: 'sha256:' + 'a'.repeat(64),
    observedAt: '2026-08-19T10:00:00Z',
  };
}
