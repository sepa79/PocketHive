import assert from 'node:assert/strict';
import test from 'node:test';

import { ConnectionContractError } from '../connection/contracts';
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
  const client = new McpHttpClient('1.0.3-test', fetcher, () => new Date('2026-08-18T12:00:00Z'));
  const controller = new AbortController();

  const evidence = await client.connect('https://nft-lab.example/mcp', 'user-access-token', controller.signal);

  assert.deepEqual(evidence, {
    serverName: 'pockethive-mcp',
    serverVersion: '0.15.35',
    principalLabel: 'QA lead',
    capabilityFingerprint: 'sha256:abc',
    observedAt: '2026-08-18T12:00:00.000Z',
  });
  assert.equal(requests.length, 3);
  assert.equal(header(requests[0], 'Authorization'), 'Bearer user-access-token');
  assert.equal(header(requests[0], 'Content-Type'), 'application/json');
  assert.equal(header(requests[0], 'Accept'), 'application/json, text/event-stream');
  assert.equal(header(requests[0], 'Origin'), 'https://nft-lab.example');
  assert.equal(header(requests[0], 'MCP-Protocol-Version'), '2025-11-25');
  assert.equal(header(requests[0], 'Mcp-Session-Id'), null);
  assert.equal(header(requests[1], 'Mcp-Session-Id'), 'session-123');
  assert.equal(header(requests[2], 'Mcp-Session-Id'), 'session-123');
  assert.equal(requests.every(request => request.init?.method === 'POST'), true);
  assert.equal(requests.every(request => request.init?.signal === controller.signal), true);
  assert.deepEqual(JSON.parse(String(requests[0].init?.body)), {
    jsonrpc: '2.0', id: 1, method: 'initialize', params: {
      protocolVersion: '2025-11-25', capabilities: {},
      clientInfo: { name: 'pockethive-vscode', version: '1.0.3-test' },
    },
  });
  assert.deepEqual(JSON.parse(String(requests[1].init?.body)), {
    jsonrpc: '2.0', method: 'notifications/initialized',
  });
  assert.deepEqual(JSON.parse(String(requests[2].init?.body)), {
    jsonrpc: '2.0', id: 2, method: 'resources/read',
    params: { uri: 'pockethive://capabilities/current' },
  });
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
    new McpHttpClient('test', fetcher).connect('https://nft-lab.example/mcp', 'token'),
    /MCP_SERVER_IDENTITY_MISMATCH/,
  );
  assert.equal(calls, 1);
});

test('reads one exact JSON resource after connection without changing target', async () => {
  const requests: Array<{ url: string; init?: RequestInit }> = [];
  const responses = connectedResponses();
  responses.push(json({ jsonrpc: '2.0', id: 3, result: { contents: [{
    uri: 'pockethive://environment/health',
    mimeType: 'application/json',
    text: JSON.stringify({ status: 'HEALTHY', services: [], observedAt: '2026-08-21T12:00:00Z' }),
  }] } }));
  const fetcher: typeof fetch = async (url, init) => {
    requests.push({ url: String(url), init });
    const response = responses.shift();
    if (!response) throw new Error('unexpected request');
    return response;
  };
  const client = new McpHttpClient('test', fetcher);
  await client.connect('https://nft-lab.example/mcp', 'access-token');

  assert.deepEqual(await client.readResource('pockethive://environment/health'), {
    status: 'HEALTHY', services: [], observedAt: '2026-08-21T12:00:00Z',
  });
  assert.equal(requests.length, 4);
  assert.equal(requests.every(request => request.url === 'https://nft-lab.example/mcp'), true);
  assert.deepEqual(JSON.parse(String(requests[3].init?.body)), {
    jsonrpc: '2.0', id: 3, method: 'resources/read',
    params: { uri: 'pockethive://environment/health' },
  });
});

test('rejects a missing resource with the exact default resource contract', async () => {
  const responses = connectedResponses();
  responses.push(json({ jsonrpc: '2.0', id: 3, result: { contents: [] } }));
  const fetcher: typeof fetch = async () => {
    const response = responses.shift();
    if (!response) throw new Error('unexpected request');
    return response;
  };
  const client = new McpHttpClient('test', fetcher);
  await client.connect('https://nft-lab.example/mcp', 'access-token');

  await rejectsContract(
    client.readResource('pockethive://environment/health'),
    'MCP_RESOURCE_INVALID',
    'MCP_RESOURCE_INVALID: MCP_RESOURCE_INVALID: missing pockethive://environment/health',
  );
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
  const client = new McpHttpClient('test', fetcher);
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
  assert.deepEqual([...new Uint8Array(upload.init?.body as ArrayBuffer)], [1, 2, 3]);
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
  const client = new McpHttpClient('test', fetcher);
  await client.connect('https://nft-lab.example/mcp', 'user-access-token');

  await assert.rejects(
    client.uploadArchive('https://attacker.example/mcp/uploads/uv-forged', new Uint8Array([1])),
    /MCP_UPLOAD_URL_INVALID/,
  );
  assert.equal(calls, 3);
});

test('handshake rejects protocol, identity, capability, resource, and transport drift exactly', async () => {
  const initCases: Array<[unknown, string, string]> = [
    [initialize({ protocolVersion: '2024-11-05' }), 'MCP_PROTOCOL_REVISION_MISMATCH',
      'MCP_PROTOCOL_REVISION_MISMATCH: MCP_PROTOCOL_REVISION_MISMATCH: 2024-11-05'],
    [initialize({ serverInfo: undefined }), 'MCP_SERVER_IDENTITY_MISMATCH',
      'MCP_SERVER_IDENTITY_MISMATCH: MCP_SERVER_IDENTITY_MISMATCH: undefined'],
    [initialize({ serverInfo: { name: 'other', version: '1' } }), 'MCP_SERVER_IDENTITY_MISMATCH',
      'MCP_SERVER_IDENTITY_MISMATCH: MCP_SERVER_IDENTITY_MISMATCH: other'],
    [initialize({ capabilities: undefined }), 'MCP_CAPABILITY_MISSING',
      'MCP_CAPABILITY_MISSING: MCP_CAPABILITY_MISSING: tools and resources are required'],
    [initialize({ capabilities: { tools: {}, resources: undefined } }), 'MCP_CAPABILITY_MISSING',
      'MCP_CAPABILITY_MISSING: MCP_CAPABILITY_MISSING: tools and resources are required'],
    [initialize({ capabilities: { tools: undefined, resources: {} } }), 'MCP_CAPABILITY_MISSING',
      'MCP_CAPABILITY_MISSING: MCP_CAPABILITY_MISSING: tools and resources are required'],
  ];
  for (const [result, code, message] of initCases) {
    const { client, requests } = clientWithResponses([
      json({ jsonrpc: '2.0', id: 1, result }, { 'Mcp-Session-Id': 'session-123' }),
    ]);
    await rejectsContract(client.connect('https://nft-lab.example/mcp', 'token'), code, message);
    assert.equal(requests.length, 1);
  }

  const missingSession = clientWithResponses([
    json({ jsonrpc: '2.0', id: 1, result: initialize() }),
  ]);
  await rejectsContract(missingSession.client.connect('https://nft-lab.example/mcp', 'token'),
    'MCP_SESSION_ID_MISSING', 'MCP_SESSION_ID_MISSING: MCP initialize did not return a session ID');

  const notificationFailure = clientWithResponses([
    json({ jsonrpc: '2.0', id: 1, result: initialize() }, { 'Mcp-Session-Id': 'session-123' }),
    new Response(undefined, { status: 503 }),
  ]);
  await rejectsContract(notificationFailure.client.connect('https://nft-lab.example/mcp', 'token'),
    'MCP_HTTP_FAILED', 'MCP_HTTP_FAILED: MCP notification returned 503');
  assert.equal(notificationFailure.requests.length, 2);

  const resourceCases: Array<[unknown, string, string]> = [
    [{}, 'MCP_CAPABILITY_RESOURCE_INVALID',
      'MCP_CAPABILITY_RESOURCE_INVALID: MCP_CAPABILITY_RESOURCE_INVALID: missing current capabilities'],
    [{ contents: [] }, 'MCP_CAPABILITY_RESOURCE_INVALID',
      'MCP_CAPABILITY_RESOURCE_INVALID: MCP_CAPABILITY_RESOURCE_INVALID: missing current capabilities'],
    [{ contents: [{ uri: 'pockethive://capabilities/other', text: '{}' }] },
      'MCP_CAPABILITY_RESOURCE_INVALID',
      'MCP_CAPABILITY_RESOURCE_INVALID: MCP_CAPABILITY_RESOURCE_INVALID: missing current capabilities'],
    [{ contents: [{ uri: 'pockethive://capabilities/current' }] },
      'MCP_CAPABILITY_RESOURCE_INVALID',
      'MCP_CAPABILITY_RESOURCE_INVALID: MCP_CAPABILITY_RESOURCE_INVALID: missing current capabilities'],
    [{ contents: [{ uri: 'pockethive://capabilities/current', text: 'null' }] },
      'MCP_CAPABILITY_RESOURCE_INVALID',
      'MCP_CAPABILITY_RESOURCE_INVALID: MCP_CAPABILITY_RESOURCE_INVALID: not an object'],
    [{ contents: [{ uri: 'pockethive://capabilities/current', text: '[]' }] },
      'MCP_CAPABILITY_RESOURCE_INVALID',
      'MCP_CAPABILITY_RESOURCE_INVALID: MCP_CAPABILITY_RESOURCE_INVALID: not an object'],
    [{ contents: [{ uri: 'pockethive://capabilities/current', text: '{}' }] },
      'MCP_CAPABILITY_RESOURCE_INVALID',
      'MCP_CAPABILITY_RESOURCE_INVALID: MCP_CAPABILITY_RESOURCE_INVALID: catalogueDigest missing'],
    [{ contents: [{ uri: 'pockethive://capabilities/current', text: JSON.stringify({
      catalogueDigest: '', principalLabel: 'QA',
    }) }] }, 'MCP_CAPABILITY_RESOURCE_INVALID',
      'MCP_CAPABILITY_RESOURCE_INVALID: MCP_CAPABILITY_RESOURCE_INVALID: catalogueDigest missing'],
    [{ contents: [{ uri: 'pockethive://capabilities/current', text: JSON.stringify({
      catalogueDigest: 'sha256:abc', principalLabel: '',
    }) }] }, 'MCP_CAPABILITY_RESOURCE_INVALID',
      'MCP_CAPABILITY_RESOURCE_INVALID: MCP_CAPABILITY_RESOURCE_INVALID: principalLabel missing'],
    [{ contents: [{ uri: 'pockethive://capabilities/current', text: JSON.stringify({
      catalogueDigest: '   ', principalLabel: 'QA',
    }) }] }, 'MCP_CAPABILITY_RESOURCE_INVALID',
      'MCP_CAPABILITY_RESOURCE_INVALID: MCP_CAPABILITY_RESOURCE_INVALID: catalogueDigest missing'],
    [{ contents: [{ uri: 'pockethive://capabilities/current', text: JSON.stringify({
      catalogueDigest: 'sha256:abc', principalLabel: '   ',
    }) }] }, 'MCP_CAPABILITY_RESOURCE_INVALID',
      'MCP_CAPABILITY_RESOURCE_INVALID: MCP_CAPABILITY_RESOURCE_INVALID: principalLabel missing'],
  ];
  for (const [resource, code, message] of resourceCases) {
    const { client, requests } = clientWithResponses([
      json({ jsonrpc: '2.0', id: 1, result: initialize() }, { 'Mcp-Session-Id': 'session-123' }),
      new Response(undefined, { status: 202 }),
      json({ jsonrpc: '2.0', id: 2, result: resource }),
    ]);
    await rejectsContract(client.connect('https://nft-lab.example/mcp', 'token'), code, message);
    assert.equal(requests.length, 3);
  }

  const missingVersion = clientWithResponses([
    json({ jsonrpc: '2.0', id: 1, result: initialize({
      serverInfo: { name: 'pockethive-mcp', version: '' },
    }) }, { 'Mcp-Session-Id': 'session-123' }),
    new Response(undefined, { status: 202 }),
    capabilityResponse(),
  ]);
  await rejectsContract(missingVersion.client.connect('https://nft-lab.example/mcp', 'token'),
    'MCP_SERVER_IDENTITY_MISMATCH',
    'MCP_SERVER_IDENTITY_MISMATCH: MCP_SERVER_IDENTITY_MISMATCH: version missing');
});

test('tool requests enforce exact JSON-RPC, SSE, status, and connected-session contracts', async () => {
  await rejectsContract(new McpHttpClient('test', async () => { throw new Error('must not fetch'); }).listTools(),
    'MCP_NOT_CONNECTED', 'MCP_NOT_CONNECTED: MCP client is not connected');
  await rejectsContract(new McpHttpClient('test', async () => { throw new Error('must not fetch'); })
    .connect('', 'token'), 'MCP_NOT_CONNECTED', 'MCP_NOT_CONNECTED: MCP client is not connected');
  await rejectsContract(new McpHttpClient('test', async () => { throw new Error('must not fetch'); })
    .connect('https://nft-lab.example/mcp', ''), 'MCP_NOT_CONNECTED',
  'MCP_NOT_CONNECTED: MCP client is not connected');

  const cases: Array<[Response, string, string]> = [
    [new Response(undefined, { status: 503 }), 'MCP_HTTP_FAILED',
      'MCP_HTTP_FAILED: MCP HTTP request returned 503'],
    [json({ jsonrpc: '2.0', id: 3, error: { code: -32_000, message: 'owner rejected' } }),
      'MCP_JSON_RPC_FAILED', 'MCP_JSON_RPC_FAILED: MCP JSON-RPC error: owner rejected'],
    [json({ jsonrpc: '2.0', id: 3, error: { code: -32_000 } }),
      'MCP_JSON_RPC_FAILED', 'MCP_JSON_RPC_FAILED: MCP JSON-RPC error: -32000'],
    [json({ jsonrpc: '2.0', id: 4, result: {} }), 'MCP_JSON_RPC_INVALID',
      'MCP_JSON_RPC_INVALID: MCP JSON-RPC response did not match the request'],
    [json({ jsonrpc: '2.0', id: 3 }), 'MCP_JSON_RPC_INVALID',
      'MCP_JSON_RPC_INVALID: MCP JSON-RPC response did not match the request'],
    [new Response('', { status: 200, headers: { 'Content-Type': 'application/json' } }),
      'MCP_JSON_RPC_INVALID', 'MCP_JSON_RPC_INVALID: MCP response body was empty'],
    [new Response('null', { status: 200, headers: { 'Content-Type': 'application/json' } }),
      'MCP_JSON_RPC_INVALID', 'MCP_JSON_RPC_INVALID: MCP_JSON_RPC_INVALID: not an object'],
    [new Response('[]', { status: 200, headers: { 'Content-Type': 'application/json' } }),
      'MCP_JSON_RPC_INVALID', 'MCP_JSON_RPC_INVALID: MCP_JSON_RPC_INVALID: not an object'],
    [new Response('"text"', { status: 200, headers: { 'Content-Type': 'application/json' } }),
      'MCP_JSON_RPC_INVALID', 'MCP_JSON_RPC_INVALID: MCP_JSON_RPC_INVALID: not an object'],
    [new Response('1', { status: 200, headers: { 'Content-Type': 'application/json' } }),
      'MCP_JSON_RPC_INVALID', 'MCP_JSON_RPC_INVALID: MCP_JSON_RPC_INVALID: not an object'],
  ];
  for (const [response, code, message] of cases) {
    const connected = await connectedClient([response]);
    await rejectsContract(connected.client.listTools(), code, message);
    assert.deepEqual(JSON.parse(String(connected.requests[3].init?.body)), {
      jsonrpc: '2.0', id: 3, method: 'tools/list', params: {},
    });
  }

  for (const [body, expected] of [
    ['event: message\r\ndata: {"jsonrpc":"2.0","id":3,"result":{"first":true}}\r\n'
      + 'data: {"jsonrpc":"2.0","id":3,"result":{"last":true}}\r\n', { last: true }],
    ['ignored: x\ndata:   {"jsonrpc":"2.0","id":3,"result":{"trimmed":true}}  \n', { trimmed: true }],
  ] as const) {
    const connected = await connectedClient([new Response(body, {
      status: 200, headers: { 'Content-Type': 'text/event-stream;charset=utf-8' },
    })]);
    assert.deepEqual(await connected.client.listTools(), expected);
  }
  const emptySse = await connectedClient([new Response('event: ping\n\n', {
    status: 200, headers: { 'Content-Type': 'text/event-stream' },
  })]);
  await rejectsContract(emptySse.client.listTools(), 'MCP_JSON_RPC_INVALID',
    'MCP_JSON_RPC_INVALID: MCP response body was empty');
  const noContentType = await connectedClient([new Response(
    new TextEncoder().encode('{"jsonrpc":"2.0","id":3,"result":{"plain":true}}'),
    { status: 200 },
  )]);
  assert.deepEqual(await noContentType.client.listTools(), { plain: true });
});

test('tool results preserve structured owner data, explicit errors, and content fallback', async () => {
  const connected = await connectedClient([
    json({ jsonrpc: '2.0', id: 3, result: { structuredContent: { swarms: 2 }, content: ['ignored'] } }),
    json({ jsonrpc: '2.0', id: 4, result: { content: [{ type: 'text', text: 'fallback' }] } }),
    json({ jsonrpc: '2.0', id: 5, result: { isError: false, structuredContent: false, content: ['ignored'] } }),
    json({ jsonrpc: '2.0', id: 6, result: { isError: true, structuredContent: { code: 'DENIED' } } }),
    json({ jsonrpc: '2.0', id: 7, result: { isError: true, content: [{ text: 'failed' }] } }),
  ]);
  assert.deepEqual(await connected.client.callTool('one', { a: 1 }), { swarms: 2 });
  assert.deepEqual(await connected.client.callTool('two'), [{ type: 'text', text: 'fallback' }]);
  assert.equal(await connected.client.callTool('three'), false);
  await rejectsContract(connected.client.callTool('four'), 'MCP_TOOL_FAILED',
    'MCP_TOOL_FAILED: {"code":"DENIED"}');
  await rejectsContract(connected.client.callTool('five'), 'MCP_TOOL_FAILED',
    'MCP_TOOL_FAILED: [{"text":"failed"}]');
  assert.deepEqual(JSON.parse(String(connected.requests[3].init?.body)), {
    jsonrpc: '2.0', id: 3, method: 'tools/call', params: { name: 'one', arguments: { a: 1 } },
  });
  assert.deepEqual(JSON.parse(String(connected.requests[4].init?.body)), {
    jsonrpc: '2.0', id: 4, method: 'tools/call', params: { name: 'two', arguments: {} },
  });
});

test('archive uploads reject every URL adornment and preserve exact owner failures', async () => {
  const invalidTargets = [
    'https://attacker.example/mcp/uploads/uv-123e4567-e89b-12d3-a456-426614174000',
    'https://user@nft-lab.example/mcp/uploads/uv-123e4567-e89b-12d3-a456-426614174000',
    'https://user:pass@nft-lab.example/mcp/uploads/uv-123e4567-e89b-12d3-a456-426614174000',
    'https://nft-lab.example/mcp/uploads/uv-123e4567-e89b-12d3-a456-426614174000?x=1',
    'https://nft-lab.example/mcp/uploads/uv-123e4567-e89b-12d3-a456-426614174000#x',
    'https://nft-lab.example/mcp/uploads/xuv-123e4567-e89b-12d3-a456-426614174000',
    'https://nft-lab.example/prefix/mcp/uploads/uv-123e4567-e89b-12d3-a456-426614174000',
    'https://nft-lab.example/mcp/uploads/uv-123e4567-e89b-12d3-a456-426614174000/extra',
    'not a url',
  ];
  for (const target of invalidTargets) {
    const connected = await connectedClient([]);
    await rejectsContract(connected.client.uploadArchive(target, new Uint8Array([1])),
      'MCP_UPLOAD_URL_INVALID', 'MCP_UPLOAD_URL_INVALID: MCP_UPLOAD_URL_INVALID');
    assert.equal(connected.requests.length, 3);
  }

  for (const endpoint of ['https://nft-lab.example/not-mcp', 'https://nft-lab.example/mcp?x=1',
    'https://nft-lab.example/mcp#x']) {
    const connected = await connectedClient([], endpoint);
    await rejectsContract(connected.client.uploadArchive(
      'https://nft-lab.example/mcp/uploads/uv-123e4567-e89b-12d3-a456-426614174000',
      new Uint8Array([1])), 'MCP_UPLOAD_URL_INVALID', 'MCP_UPLOAD_URL_INVALID: MCP_UPLOAD_URL_INVALID');
    assert.equal(connected.requests.length, 3);
  }

  const uploadUrl = 'https://nft-lab.example/mcp/uploads/up-123e4567-e89b-12d3-a456-426614174000';
  const failures: Array<[Response, string, string]> = [
    [new Response('x'.repeat(100_001), { status: 200 }), 'MCP_UPLOAD_RESPONSE_INVALID',
      'MCP_UPLOAD_RESPONSE_INVALID: MCP upload response was too large'],
    [new Response('not-json', { status: 200 }), 'MCP_UPLOAD_RESPONSE_INVALID',
      'MCP_UPLOAD_RESPONSE_INVALID: MCP_UPLOAD_RESPONSE_INVALID: Unexpected token'],
    [new Response('not-json', { status: 503 }), 'MCP_UPLOAD_FAILED',
      'MCP_UPLOAD_FAILED: MCP upload returned 503'],
    [new Response('{}', { status: 409 }), 'MCP_UPLOAD_FAILED',
      'MCP_UPLOAD_FAILED: MCP_UPLOAD_FAILED: HTTP 409'],
    [new Response('{"code":"OWNER_REJECTED"}', { status: 409 }), 'OWNER_REJECTED',
      'OWNER_REJECTED: OWNER_REJECTED: HTTP 409'],
    [new Response('{"code":1}', { status: 409 }), 'MCP_UPLOAD_FAILED',
      'MCP_UPLOAD_FAILED: MCP_UPLOAD_FAILED: HTTP 409'],
    [new Response('null', { status: 200 }), 'MCP_UPLOAD_RESPONSE_INVALID',
      'MCP_UPLOAD_RESPONSE_INVALID: MCP_UPLOAD_RESPONSE_INVALID: not an object'],
  ];
  for (const [response, code, messagePrefix] of failures) {
    const connected = await connectedClient([response]);
    await assert.rejects(connected.client.uploadArchive(uploadUrl, new Uint8Array([7])), error =>
      error instanceof ConnectionContractError && error.code === code
      && error.message.startsWith(messagePrefix));
  }

  const ambiguous = await connectedClient([
    new Response('{"code":"PUBLICATION_RESULT_AMBIGUOUS","attemptId":"pa-123"}', { status: 409 }),
  ]);
  await assert.rejects(ambiguous.client.uploadArchive(uploadUrl, new Uint8Array([7])), error =>
    error instanceof ConnectionContractError
      && error.code === 'PUBLICATION_RESULT_AMBIGUOUS'
      && error.details?.attemptId === 'pa-123');

  const exactLimitBase = JSON.stringify({ value: '' });
  const exactLimit = JSON.stringify({ value: 'x'.repeat(100_000 - exactLimitBase.length) });
  assert.equal(exactLimit.length, 100_000);
  const exact = await connectedClient([new Response(exactLimit, { status: 200 })]);
  assert.deepEqual(await exact.client.uploadArchive(uploadUrl, new Uint8Array([1])),
    { value: 'x'.repeat(100_000 - exactLimitBase.length) });
});

test('session close is idempotent, accepts owner absence, and retains retry after failure', async () => {
  const unused: Array<{ url: string; init?: RequestInit }> = [];
  const never = new McpHttpClient('test', async (url, init) => {
    unused.push({ url: String(url), init });
    throw new Error('must not fetch');
  });
  await never.close();
  assert.equal(unused.length, 0);

  for (const status of [200, 404]) {
    const connected = await connectedClient([new Response(undefined, { status })]);
    await connected.client.close();
    await connected.client.close();
    assert.equal(connected.requests.length, 4);
    const close = connected.requests[3];
    assert.equal(close.url, 'https://nft-lab.example/mcp');
    assert.equal(close.init?.method, 'DELETE');
    assert.equal(header(close, 'Mcp-Session-Id'), 'session-123');
    assert.equal(close.init?.body, undefined);
  }

  const failed = await connectedClient([
    new Response(undefined, { status: 503 }),
    new Response(undefined, { status: 200 }),
  ]);
  await rejectsContract(failed.client.close(), 'MCP_SESSION_CLOSE_FAILED',
    'MCP_SESSION_CLOSE_FAILED: MCP session close returned 503');
  await failed.client.close();
  assert.equal(failed.requests.length, 5);

  const partial = clientWithResponses([
    json({ jsonrpc: '2.0', id: 1, result: initialize() }),
  ]);
  await rejectsContract(partial.client.connect('https://nft-lab.example/mcp', 'token'),
    'MCP_SESSION_ID_MISSING', 'MCP_SESSION_ID_MISSING: MCP initialize did not return a session ID');
  await rejectsContract(partial.client.listTools(), 'MCP_NOT_CONNECTED',
    'MCP_NOT_CONNECTED: MCP client is not connected');
  await rejectsContract(partial.client.uploadArchive(
    'https://nft-lab.example/mcp/uploads/uv-123e4567-e89b-12d3-a456-426614174000',
    new Uint8Array([1])), 'MCP_NOT_CONNECTED', 'MCP_NOT_CONNECTED: MCP client is not connected');
  await partial.client.close();
  assert.equal(partial.requests.length, 1);

  for (const [endpoint, token] of [
    ['', 'token'],
    ['https://nft-lab.example/mcp', ''],
  ]) {
    const calls: string[] = [];
    const client = new McpHttpClient('test', async url => {
      calls.push(String(url));
      throw new Error('must not fetch');
    });
    await rejectsContract(client.connect(endpoint, token), 'MCP_NOT_CONNECTED',
      'MCP_NOT_CONNECTED: MCP client is not connected');
    await client.close();
    assert.deepEqual(calls, []);
  }
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

function initialize(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    protocolVersion: '2025-11-25',
    serverInfo: { name: 'pockethive-mcp', version: '0.15.35' },
    capabilities: { tools: {}, resources: {} },
    ...overrides,
  };
}

function capabilityResponse(): Response {
  return json({ jsonrpc: '2.0', id: 2, result: { contents: [{
    uri: 'pockethive://capabilities/current',
    text: JSON.stringify({ catalogueDigest: 'sha256:abc', principalLabel: 'QA lead' }),
  }] } });
}

function clientWithResponses(responses: Response[]): {
  client: McpHttpClient;
  requests: Array<{ url: string; init?: RequestInit }>;
} {
  const requests: Array<{ url: string; init?: RequestInit }> = [];
  const client = new McpHttpClient('test', async (url, init) => {
    requests.push({ url: String(url), init });
    const response = responses.shift();
    if (!response) throw new Error('unexpected request');
    return response;
  }, () => new Date('2026-08-18T12:00:00Z'));
  return { client, requests };
}

async function connectedClient(
  extra: Response[],
  endpoint = 'https://nft-lab.example/mcp',
): Promise<{ client: McpHttpClient; requests: Array<{ url: string; init?: RequestInit }> }> {
  const value = clientWithResponses([...connectedResponses(), ...extra]);
  await value.client.connect(endpoint, 'user-access-token');
  return value;
}

async function rejectsContract(promise: Promise<unknown>, code: string, message: string): Promise<void> {
  await assert.rejects(promise, error => error instanceof ConnectionContractError
    && error.code === code && error.message === message);
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
