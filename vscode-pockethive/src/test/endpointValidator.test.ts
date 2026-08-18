import assert from 'node:assert/strict';
import test from 'node:test';

import { PocketHiveEndpointValidator } from '../connection/endpointValidator';
import { createConnectionProfile } from '../connection/profile';

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

  const endpoint = await validator.validate(profile);

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

  await assert.rejects(validator.validate(profile), /MCP_RESOURCE_METADATA_MISMATCH/);
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

  await assert.rejects(validator.validate(profile), /MCP_ENDPOINT_LOOPBACK_RESOLUTION_FAILED/);
  assert.equal(fetched, false);
});
