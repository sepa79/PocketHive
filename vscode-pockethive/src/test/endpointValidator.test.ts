import assert from 'node:assert/strict';
import test from 'node:test';

import { PocketHiveEndpointValidator } from '../connection/endpointValidator';
import { createConnectionProfile } from '../connection/profile';

const remoteProfile = createConnectionProfile({
  id: 'remote', displayName: 'Remote', mcpUrl: 'https://nft-lab.example/mcp',
  endpointSecurityMode: 'REMOTE_HTTPS', secretKey: 'secret',
});

test('cancels an in-flight fetch and forwards cancellation to the transport', async () => {
  let transportSignal!: AbortSignal;
  const validator = new PocketHiveEndpointValidator(async (_url, init) => {
    transportSignal = init!.signal!;
    return new Promise<Response>(() => {});
  });
  const controller = new AbortController();
  const pending = validator.validate(remoteProfile, controller.signal);
  controller.abort(new Error('user cancelled'));
  await assert.rejects(pending, /user cancelled/);
  assert.equal(transportSignal.aborted, true);
});

for (const blockedPhase of ['headers', 'body', 'dns'] as const) {
  test(`the discovery deadline covers stalled ${blockedPhase}`, async () => {
    let transportSignal: AbortSignal | undefined;
    let fetches = 0;
    let releaseDns!: (addresses: string[]) => void;
    const dns = new Promise<string[]>(resolve => { releaseDns = resolve; });
    const validator = new PocketHiveEndpointValidator(async (_url, init) => {
      fetches++;
      transportSignal = init!.signal!;
      if (blockedPhase === 'headers') return new Promise<Response>(() => {});
      return new Response(new ReadableStream({ start() {} }), {
        headers: { 'Content-Type': 'application/json' },
      });
    }, async () => dns, 20);
    const profile = blockedPhase === 'dns' ? createConnectionProfile({
      id: 'local', displayName: 'Local', mcpUrl: 'http://localhost:8088/mcp',
      endpointSecurityMode: 'LOCAL_LOOPBACK_HTTP', secretKey: 'secret',
    }) : remoteProfile;
    await assert.rejects(validator.validate(profile, new AbortController().signal), /MCP_ENDPOINT_DISCOVERY_TIMEOUT/);
    if (blockedPhase === 'dns') {
      releaseDns(['127.0.0.1']);
      await new Promise(resolve => setImmediate(resolve));
      assert.equal(fetches, 0, 'Late DNS must not initiate traffic');
    } else assert.equal(transportSignal?.aborted, true);
  });
}

test('an already cancelled discovery performs no lookup or fetch', async () => {
  const validator = new PocketHiveEndpointValidator(async () => { throw new Error('must not fetch'); });
  const controller = new AbortController();
  controller.abort(new Error('already cancelled'));
  await assert.rejects(validator.validate(remoteProfile, controller.signal), /already cancelled/);
});

test('accepts only metadata whose resource exactly matches the entered MCP URL', async () => {
  const urls: string[] = [];
  const validator = new PocketHiveEndpointValidator(async url => {
    urls.push(String(url));
    return new Response(JSON.stringify({
      resource: 'https://nft-lab.example/mcp',
      authorization_servers: ['https://nft-lab.example/auth-service'],
    }), { status: 200, headers: { 'Content-Type': 'application/json' } });
  }, async () => ['203.0.113.4']);
  const profile = createConnectionProfile({
    id: 'nft', displayName: 'NFT Lab', mcpUrl: 'https://nft-lab.example/mcp',
    endpointSecurityMode: 'REMOTE_HTTPS', secretKey: 'secret',
  });

  const endpoint = await validator.validate(profile, new AbortController().signal);

  assert.deepEqual(endpoint, {
    mcpUrl: 'https://nft-lab.example/mcp',
    resourceMetadataUrl: 'https://nft-lab.example/.well-known/oauth-protected-resource',
    authorizationServer: 'https://nft-lab.example/auth-service',
  });
  assert.deepEqual(urls, ['https://nft-lab.example/.well-known/oauth-protected-resource']);
});

test('rejects resource mismatch and never tries a different metadata location', async () => {
  let calls = 0;
  const validator = new PocketHiveEndpointValidator(async () => {
    calls += 1;
    return new Response(JSON.stringify({
      resource: 'https://nft-lab.example/another-mcp',
      authorization_servers: ['https://nft-lab.example/auth-service'],
    }), { status: 200, headers: { 'Content-Type': 'application/json' } });
  }, async () => ['203.0.113.4']);
  const profile = createConnectionProfile({
    id: 'nft', displayName: 'NFT Lab', mcpUrl: 'https://nft-lab.example/mcp',
    endpointSecurityMode: 'REMOTE_HTTPS', secretKey: 'secret',
  });

  await assert.rejects(validator.validate(profile, new AbortController().signal), /MCP_RESOURCE_METADATA_MISMATCH/);
  assert.equal(calls, 1);
});

test('rechecks that a local hostname resolves only to loopback before HTTP', async () => {
  let fetched = false;
  const validator = new PocketHiveEndpointValidator(async () => {
    fetched = true;
    throw new Error('must not fetch');
  }, async () => ['127.0.0.1', '192.168.1.2']);
  const profile = createConnectionProfile({
    id: 'local', displayName: 'Local', mcpUrl: 'http://localhost:8080/mcp',
    endpointSecurityMode: 'LOCAL_LOOPBACK_HTTP', secretKey: 'secret',
  });

  await assert.rejects(validator.validate(profile, new AbortController().signal), /MCP_ENDPOINT_LOOPBACK_RESOLUTION_FAILED/);
  assert.equal(fetched, false);
});
