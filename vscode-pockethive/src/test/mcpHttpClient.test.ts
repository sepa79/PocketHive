import assert from 'node:assert/strict';
import test from 'node:test';

import { McpHttpClient } from '../mcp/httpClient';

test('initializes exact protocol, binds session, and reads current capabilities', async () => {
  const requests: Array<{ url: string; init?: RequestInit }> = [];
  const responses = [
    json({ jsonrpc: '2.0', id: 1, result: {
      protocolVersion: '2025-11-25',
      serverInfo: { name: 'pockethive-mcp', version: '0.15.35' },
      capabilities: { tools: {}, resources: {} },
    } }, { 'Mcp-Session-Id': 'session-123' }),
    new Response(undefined, { status: 202 }),
    json({ jsonrpc: '2.0', id: 2, result: { contents: [{
      uri: 'pockethive://capabilities/current',
      mimeType: 'application/json',
      text: JSON.stringify({ catalogueDigest: 'sha256:abc', principalLabel: 'QA lead' }),
    }] } }),
  ];
  const fetcher: typeof fetch = async (url, init) => {
    requests.push({ url: String(url), init });
    const response = responses.shift();
    if (!response) throw new Error('unexpected request');
    return response;
  };
  const client = new McpHttpClient(fetcher, () => new Date('2026-08-18T12:00:00Z'));

  const evidence = await client.connect('https://nft-lab.example/mcp', 'user-access-token');

  assert.deepEqual(evidence, {
    serverName: 'pockethive-mcp',
    serverVersion: '0.15.35',
    principalLabel: 'QA lead',
    capabilityFingerprint: 'sha256:abc',
    observedAt: '2026-08-18T12:00:00.000Z',
  });
  assert.equal(requests.length, 3);
  assert.equal(header(requests[0], 'Authorization'), 'Bearer user-access-token');
  assert.equal(header(requests[0], 'MCP-Protocol-Version'), '2025-11-25');
  assert.equal(header(requests[0], 'Mcp-Session-Id'), null);
  assert.equal(header(requests[1], 'Mcp-Session-Id'), 'session-123');
  assert.equal(header(requests[2], 'Mcp-Session-Id'), 'session-123');
  assert.match(String(requests[0].init?.body), /"method":"initialize"/);
  assert.match(String(requests[1].init?.body), /"method":"notifications\/initialized"/);
  assert.match(String(requests[2].init?.body), /pockethive:\/\/capabilities\/current/);
});

test('rejects protocol or server identity drift without trying another endpoint', async () => {
  let calls = 0;
  const fetcher: typeof fetch = async () => {
    calls += 1;
    return json({ jsonrpc: '2.0', id: 1, result: {
      protocolVersion: '2025-11-25',
      serverInfo: { name: 'not-pockethive', version: '1.0.0' },
      capabilities: {},
    } }, { 'Mcp-Session-Id': 'session-123' });
  };

  await assert.rejects(
    new McpHttpClient(fetcher).connect('https://nft-lab.example/mcp', 'token'),
    /MCP_SERVER_IDENTITY_MISMATCH/,
  );
  assert.equal(calls, 1);
});

test('streams a ticket archive only to the exact selected MCP ingress', async () => {
  const requests: Array<{ url: string; init?: RequestInit }> = [];
  const responses = connectedResponses();
  responses.push(json({ validationReceipt: { receiptId: 'vr-1' } }));
  const fetcher: typeof fetch = async (url, init) => {
    requests.push({ url: String(url), init });
    const response = responses.shift();
    if (!response) throw new Error('unexpected request');
    return response;
  };
  const client = new McpHttpClient(fetcher);
  await client.connect('https://nft-lab.example/mcp', 'user-access-token');

  const result = await client.uploadArchive(
    'https://nft-lab.example/mcp/uploads/uv-123e4567-e89b-12d3-a456-426614174000',
    new Uint8Array([1, 2, 3]),
  );

  assert.deepEqual(result, { validationReceipt: { receiptId: 'vr-1' } });
  const upload = requests[3];
  assert.equal(upload.url, 'https://nft-lab.example/mcp/uploads/uv-123e4567-e89b-12d3-a456-426614174000');
  assert.equal(upload.init?.method, 'PUT');
  assert.equal(header(upload, 'Authorization'), 'Bearer user-access-token');
  assert.equal(header(upload, 'Content-Type'), 'application/zip');
  assert.equal(header(upload, 'Content-Length'), '3');
  assert.equal(header(upload, 'MCP-Protocol-Version'), '2025-11-25');
  assert.equal(header(upload, 'Mcp-Session-Id'), null);
});

test('rejects a forged upload URL without network fallback', async () => {
  let calls = 0;
  const responses = connectedResponses();
  const fetcher: typeof fetch = async () => {
    calls += 1;
    const response = responses.shift();
    if (!response) throw new Error('unexpected request');
    return response;
  };
  const client = new McpHttpClient(fetcher);
  await client.connect('https://nft-lab.example/mcp', 'user-access-token');

  await assert.rejects(
    client.uploadArchive('https://attacker.example/mcp/uploads/uv-forged', new Uint8Array([1])),
    /MCP_UPLOAD_URL_INVALID/,
  );
  assert.equal(calls, 3);
});

function connectedResponses(): Response[] {
  return [
    json({ jsonrpc: '2.0', id: 1, result: {
      protocolVersion: '2025-11-25',
      serverInfo: { name: 'pockethive-mcp', version: '0.15.35' },
      capabilities: { tools: {}, resources: {} },
    } }, { 'Mcp-Session-Id': 'session-123' }),
    new Response(undefined, { status: 202 }),
    json({ jsonrpc: '2.0', id: 2, result: { contents: [{
      uri: 'pockethive://capabilities/current',
      text: JSON.stringify({ catalogueDigest: 'sha256:abc', principalLabel: 'QA lead' }),
    }] } }),
  ];
}

function json(body: unknown, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json', ...headers },
  });
}

function header(request: { init?: RequestInit }, name: string): string | null {
  return new Headers(request.init?.headers).get(name);
}
