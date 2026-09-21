import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { once } from 'node:events';
import test from 'node:test';
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
