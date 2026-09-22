import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { once } from 'node:events';
import test from 'node:test';
import { EXPECTED_MCP_SERVER_NAME, MCP_PROTOCOL_REVISION } from '../connection/contracts';
import { McpHttpClient } from '../mcp/httpClient';

test('actual MCP transport refuses redirects instead of sending a request to another endpoint', async () => {
  const paths: string[] = [];
  const server = createServer((request, response) => {
    paths.push(request.url!);
    response.writeHead(307, { Location: '/another-endpoint' });
    response.end();
  });
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  try {
    const address = server.address();
    if (!address || typeof address === 'string') throw new Error('missing test listener');
    const client = new McpHttpClient('test');
    await assert.rejects(client.connect(`http://127.0.0.1:${address.port}/mcp`, 'synthetic-test-token',
      AbortSignal.timeout(3000)), /fetch failed/);
    assert.deepEqual(paths, ['/mcp']);
  } finally {
    server.closeAllConnections();
    await new Promise<void>((resolve, reject) => server.close(error => error ? reject(error) : resolve()));
  }
});

for (const operation of ['archive upload', 'session close'] as const) {
  test(`actual MCP ${operation} refuses redirects without reaching the destination`, async () => {
    const paths: string[] = [];
    const responses = [
      { jsonrpc: '2.0', id: 1, result: {
        protocolVersion: MCP_PROTOCOL_REVISION,
        serverInfo: { name: EXPECTED_MCP_SERVER_NAME, version: 'test' },
        capabilities: { tools: {}, resources: {} },
      } },
      null,
      { jsonrpc: '2.0', id: 2, result: { contents: [{
        uri: 'pockethive://capabilities/current',
        text: JSON.stringify({ catalogueDigest: 'sha256:fixture', principalLabel: 'Fixture' }),
      }] } },
    ];
    const server = createServer((request, response) => {
      paths.push(`${request.method} ${request.url}`);
      request.resume();
      if (request.method === 'POST' && request.url === '/mcp') {
        const body = responses.shift();
        if (body === null) {
          response.writeHead(202);
        } else if (body) {
          response.writeHead(200, { 'Content-Type': 'application/json', 'Mcp-Session-Id': 'session-fixture' });
        } else {
          response.writeHead(500);
        }
        response.end(body ? JSON.stringify(body) : undefined);
        return;
      }
      response.writeHead(307, { Location: '/another-endpoint' });
      response.end();
    });
    server.listen(0, '127.0.0.1');
    await once(server, 'listening');
    try {
      const address = server.address();
      if (!address || typeof address === 'string') throw new Error('missing test listener');
      const origin = `http://127.0.0.1:${address.port}`;
      const client = new McpHttpClient('test');
      await client.connect(`${origin}/mcp`, 'synthetic-test-token', AbortSignal.timeout(3000));
      const uploadPath = '/mcp/uploads/uv-123e4567-e89b-12d3-a456-426614174000';
      await assert.rejects(
        operation === 'archive upload'
          ? client.uploadArchive(`${origin}${uploadPath}`, new Uint8Array([1, 2, 3]), AbortSignal.timeout(3000))
          : client.close(),
        /fetch failed/,
      );
      assert.deepEqual(paths, [
        'POST /mcp', 'POST /mcp', 'POST /mcp',
        operation === 'archive upload' ? `PUT ${uploadPath}` : 'DELETE /mcp',
      ]);
    } finally {
      server.closeAllConnections();
      await new Promise<void>((resolve, reject) => server.close(error => error ? reject(error) : resolve()));
    }
  });
}
