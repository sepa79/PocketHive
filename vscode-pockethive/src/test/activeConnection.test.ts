import assert from 'node:assert/strict';
import test from 'node:test';

import { ActiveMcpConnection } from '../mcp/activeConnection';
import { McpHttpClient } from '../mcp/httpClient';
import { McpConnectionProfile, OAuthSession } from '../connection/contracts';

const profile: McpConnectionProfile = {
  id: 'local', displayName: 'Local', mcpUrl: 'http://127.0.0.1:8088/mcp',
  endpointSecurityMode: 'LOCAL_LOOPBACK_HTTP', authenticationMode: 'OAUTH_AUTHORIZATION_CODE_PKCE',
  secretKey: 'secret.local',
};
const session: OAuthSession = {
  accessToken: 'access', expiresAt: '2026-08-19T13:00:00.000Z',
  renewal: { kind: 'ROTATING_REFRESH_TOKEN', refreshToken: 'refresh' },
};
const evidence = {
  serverName: 'pockethive-mcp', serverVersion: '0.15.35', principalLabel: 'QA lead',
  capabilityFingerprint: 'sha256:abc', observedAt: '2026-08-19T12:00:00.000Z',
};

test('atomically replaces a verified client, delegates calls, and closes once', async () => {
  const calls: string[] = [];
  const first = client('first', calls);
  const second = client('second', calls);
  const values = [first, second];
  const active = new ActiveMcpConnection(() => values.shift()!);

  assert.deepEqual(await active.test(profile, session, new AbortController().signal), evidence);
  assert.deepEqual(await active.test(profile, session, new AbortController().signal), evidence);
  assert.deepEqual(await active.callTool('swarm_list', { exact: true }), {
    client: 'second', name: 'swarm_list', args: { exact: true },
  });
  await active.close();
  await active.close();
  assert.deepEqual(calls, [
    'first:connect:http://127.0.0.1:8088/mcp:access',
    'second:connect:http://127.0.0.1:8088/mcp:access',
    'first:close',
    'second:call:swarm_list',
    'second:close',
  ]);
});

test('a rejected candidate is closed and never replaces the prior verified client', async () => {
  const calls: string[] = [];
  const prior = client('prior', calls);
  const rejected = client('rejected', calls, new Error('candidate rejected'), new Error('close also failed'));
  const values = [prior, rejected];
  const active = new ActiveMcpConnection(() => values.shift()!);
  await active.test(profile, session, new AbortController().signal);
  await assert.rejects(active.test(profile, session, new AbortController().signal), /candidate rejected/);
  assert.deepEqual(await active.callTool('swarm_list'), {
    client: 'prior', name: 'swarm_list', args: {},
  });
  assert.deepEqual(calls, [
    'prior:connect:http://127.0.0.1:8088/mcp:access',
    'rejected:connect:http://127.0.0.1:8088/mcp:access',
    'rejected:close',
    'prior:call:swarm_list',
  ]);
});

test('fails explicitly without an active client and clears ownership before close failure', async () => {
  const calls: string[] = [];
  const closing = client('closing', calls, undefined, new Error('close failed'));
  const active = new ActiveMcpConnection(() => closing);
  await assert.rejects(active.callTool('swarm_list'), error => error instanceof Error
    && error.message === 'MCP_NOT_CONNECTED');
  await active.test(profile, session, new AbortController().signal);
  await assert.rejects(active.close(), /close failed/);
  await assert.rejects(active.callTool('swarm_list'), error => error instanceof Error
    && error.message === 'MCP_NOT_CONNECTED');
  assert.deepEqual(calls, ['closing:connect:http://127.0.0.1:8088/mcp:access', 'closing:close']);
});

function client(
  label: string,
  calls: string[],
  connectFailure?: Error,
  closeFailure?: Error,
): McpHttpClient {
  return {
    connect: async (url: string, accessToken: string) => {
      calls.push(`${label}:connect:${url}:${accessToken}`);
      if (connectFailure) throw connectFailure;
      return evidence;
    },
    callTool: async (name: string, args: Record<string, unknown>) => {
      calls.push(`${label}:call:${name}`);
      return { client: label, name, args };
    },
    close: async () => {
      calls.push(`${label}:close`);
      if (closeFailure) throw closeFailure;
    },
  } as unknown as McpHttpClient;
}
