import assert from 'node:assert/strict';
import test from 'node:test';
import { createConnectionProfile } from '../connection/profile';
import { validateEndpointTransport, EndpointSecurityMode } from '../connection/endpointSecurityPolicy';
import { PocketHiveEndpointValidator } from '../connection/endpointValidator';
import { decodeWebviewCommand } from '../webview/messages';
import { McpConnectionProfileRepository } from '../storage/profileRepository';

test('remote HTTP requires an explicit mode and rejects unknown modes without protocol guessing', () => {
  const input = { id: 'lab', displayName: 'Lab', mcpUrl: 'http://lab.example:8088/mcp', secretKey: 'lab' };
  assert.throws(() => createConnectionProfile({ ...input, endpointSecurityMode: 'REMOTE_HTTPS' }), /HTTPS_REQUIRED/);
  assert.throws(() => createConnectionProfile({ ...input, endpointSecurityMode: 'LOCAL_LOOPBACK_HTTP' }), /LOOPBACK_REQUIRED/);
  assert.throws(() => createConnectionProfile({ ...input, endpointSecurityMode: 'AUTO' as EndpointSecurityMode }), /SECURITY_MODE_INVALID/);
  assert.equal(createConnectionProfile({ ...input, endpointSecurityMode: 'REMOTE_HTTP' }).mcpUrl, input.mcpUrl);
  for (const url of ['https://lab.example/mcp', 'ftp://lab.example/mcp', 'http://user@lab.example/mcp']) {
    assert.throws(() => createConnectionProfile({ ...input, mcpUrl: url, endpointSecurityMode: 'REMOTE_HTTP' }));
  }
  assert.throws(() => validateEndpointTransport(new URL('http://lab.example/auth-service'), 'REMOTE_HTTPS', true),
    /MCP_AUTHORIZATION_SERVER_INVALID/);
});

test('the explicit choice round-trips through command decoding and saved profiles', async () => {
  const command = decodeWebviewCommand({ type: 'connect', displayName: 'Lab',
    mcpUrl: 'http://lab.example:8088/mcp', endpointSecurityMode: 'REMOTE_HTTP' });
  assert.equal(command.type, 'connect');
  if (command.type !== 'connect') throw new Error('wrong command');
  const profile = createConnectionProfile({ ...command, id: 'lab', secretKey: 'lab' });
  const values = new Map<string, unknown>();
  const storage = { get: <T>(key: string) => values.get(key) as T | undefined,
    update: async (key: string, value: unknown) => { values.set(key, value); } };
  const secrets = { get: async () => undefined, store: async () => {}, delete: async () => {} };
  await new McpConnectionProfileRepository(storage, storage, secrets).save(profile);
  assert.deepEqual(new McpConnectionProfileRepository(storage, storage, secrets).list(), [profile]);
  assert.throws(() => decodeWebviewCommand({ type: 'connect', displayName: 'Lab', mcpUrl: profile.mcpUrl }),
    /WEBVIEW_MESSAGE_INVALID/);
});

test('remote HTTP discovery preserves exact resource and selected issuer transport', async () => {
  const profile = createConnectionProfile({ id: 'lab', displayName: 'Lab',
    mcpUrl: 'http://lab.example:8088/mcp', endpointSecurityMode: 'REMOTE_HTTP', secretKey: 'lab' });
  let resource = profile.mcpUrl;
  let issuer = 'http://lab.example:8088/auth-service';
  const requests: string[] = [];
  const validator = new PocketHiveEndpointValidator(async (url, init) => {
    requests.push(String(url));
    assert.equal(init?.redirect, 'error');
    return new Response(JSON.stringify({ resource, authorization_servers: [issuer] }),
      { headers: { 'Content-Type': 'application/json' } });
  });
  assert.equal((await validator.validate(profile, new AbortController().signal)).authorizationServer, issuer);
  assert.deepEqual(requests, ['http://lab.example:8088/.well-known/oauth-protected-resource']);
  issuer = 'https://lab.example/auth-service';
  await assert.rejects(validator.validate(profile, new AbortController().signal), /MCP_AUTHORIZATION_SERVER_INVALID/);
  issuer = 'http://lab.example:8088/auth-service';
  resource = 'http://different.example/mcp';
  await assert.rejects(validator.validate(profile, new AbortController().signal), /MCP_RESOURCE_METADATA_MISMATCH/);
});

test('IPv6 loopback reaches metadata using the production address resolver', async () => {
  const profile = createConnectionProfile({ id: 'local', displayName: 'Local',
    mcpUrl: 'http://[::1]:8088/mcp', endpointSecurityMode: 'LOCAL_LOOPBACK_HTTP', secretKey: 'local' });
  const validator = new PocketHiveEndpointValidator(async () => new Response(JSON.stringify({
    resource: profile.mcpUrl, authorization_servers: ['http://[::1]:8088/auth-service'],
  }), { headers: { 'Content-Type': 'application/json' } }));
  assert.equal((await validator.validate(profile, new AbortController().signal)).mcpUrl, profile.mcpUrl);
});
