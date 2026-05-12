import { pathToFileURL } from 'node:url';
import { resolve } from 'node:path';

const SDK_ROOT = resolve('C:/Private/projects/PocketHiveClean/vscode-pockethive/node_modules/@modelcontextprotocol/sdk/dist/esm');
const { Client } = await import(pathToFileURL(resolve(SDK_ROOT, 'client/index.js')).href);
const { StdioClientTransport } = await import(pathToFileURL(resolve(SDK_ROOT, 'client/stdio.js')).href);

const SERVER  = resolve('C:/Users/tday/AppData/Roaming/npm/node_modules/@pockethive/mcp-server/server.mjs');
const BUNDLES = resolve('C:/Private/projects/PocketHiveClean/scenarios/bundles');

const transport = new StdioClientTransport({
  command: 'node',
  args: [SERVER],
  env: { ...process.env, POCKETHIVE_BASE_URL: 'http://localhost:8088', BUNDLES_ROOT: BUNDLES },
});

const client = new Client({ name: 'smoke-test', version: '1.0.0' }, { capabilities: {} });

try {
  await client.connect(transport);
  console.log('✓ Connected to MCP server');

  const ctx = await client.callTool({ name: 'context.get', arguments: {} });
  const ctxData = JSON.parse(ctx.content[0].text);
  console.log('✓ context.get:');
  console.log('  bundlesRoot:', ctxData.bundlesRoot);
  console.log('  baseUrl:    ', ctxData.baseUrl);
  console.log('  platform:   ', ctxData.platform);

  const bundles = await client.callTool({ name: 'bundle.list', arguments: {} });
  const bundleData = JSON.parse(bundles.content[0].text);
  console.log(`✓ bundle.list: ${bundleData.bundles.length} bundles found`);
  bundleData.bundles.slice(0, 5).forEach(b => console.log(`  - ${b.name}`));

  await client.close();
  console.log('\n✓ All smoke tests passed — MCP server is working.');
} catch (err) {
  console.error('✗ Smoke test failed:', err.message);
  process.exit(1);
}
