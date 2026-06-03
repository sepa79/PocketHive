import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js';

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);
const REPO_ROOT = resolve(__dirname, '../..');

const SERVER = process.env.PH_MCP_SERVER_PATH || resolve(__dirname, 'server.mjs');
const BUNDLES = process.env.BUNDLES_ROOT || resolve(REPO_ROOT, 'scenarios', 'bundles');

const transport = new StdioClientTransport({
  command: 'node',
  args: [SERVER],
  env: {
    ...process.env,
    POCKETHIVE_BASE_URL: process.env.POCKETHIVE_BASE_URL || 'http://localhost:8088',
    POCKETHIVE_ROOT: process.env.POCKETHIVE_ROOT || REPO_ROOT,
    BUNDLES_ROOT: BUNDLES,
    PH_BUNDLES_ROOTS: process.env.PH_BUNDLES_ROOTS || JSON.stringify([BUNDLES]),
    PH_ACTIVE_ENVIRONMENT: process.env.PH_ACTIVE_ENVIRONMENT || 'local',
    PH_ENVIRONMENTS: process.env.PH_ENVIRONMENTS || JSON.stringify([
      {
        name: 'local',
        baseUrl: process.env.POCKETHIVE_BASE_URL || 'http://localhost:8088',
        authUsername: process.env.POCKETHIVE_AUTH_USERNAME || '',
        rabbitUser: 'guest'
      }
    ]),
  },
});

const client = new Client({ name: 'smoke-test', version: '1.0.0' }, { capabilities: {} });

try {
  await client.connect(transport);
  console.log('✓ Connected to MCP server');

  const tools = await client.listTools();
  const toolNames = tools.tools.map(t => t.name);
  for (const required of ['context_get', 'health_check', 'bundle_list', 'scenario_contracts_get', 'scenario_raw_read', 'debug_hive_journal', 'env_status', 'wizard_start']) {
    if (!toolNames.includes(required)) {
      throw new Error(`Missing MCP tool: ${required}`);
    }
  }
  console.log(`✓ listTools: ${toolNames.length} tools available`);

  const ctx = await client.callTool({ name: 'context_get', arguments: {} });
  const ctxData = JSON.parse(ctx.content[0].text);
  console.log('✓ context_get:');
  console.log('  bundlesRoot:', ctxData.bundlesRoot);
  console.log('  baseUrl:    ', ctxData.baseUrl);
  console.log('  platform:   ', ctxData.platform);

  const bundles = await client.callTool({ name: 'bundle_list', arguments: {} });
  const bundleData = JSON.parse(bundles.content[0].text);
  console.log(`✓ bundle_list: ${bundleData.bundles.length} bundles found`);
  bundleData.bundles.slice(0, 5).forEach(b => console.log(`  - ${b.name}`));

  const health = await client.callTool({ name: 'health_check', arguments: {} });
  const healthData = JSON.parse(health.content[0].text);
  console.log('✓ health_check:');
  console.log('  orchestrator:     ', healthData.orchestrator);
  console.log('  scenario-manager: ', healthData['scenario-manager']);

  const envStatus = await client.callTool({ name: 'env_status', arguments: {} });
  const envStatusData = JSON.parse(envStatus.content[0].text);
  console.log(`✓ env_status: ${envStatusData.environments.length} environment(s) checked`);
  envStatusData.environments.forEach(env => console.log(`  - ${env.name}: ${env.state}`));

  const buzz = await client.callTool({ name: 'debug_hive_journal', arguments: { limit: 5 } });
  const buzzData = JSON.parse(buzz.content[0].text);
  const buzzItems = Array.isArray(buzzData.items) ? buzzData.items.length : 0;
  console.log(`✓ debug_hive_journal: ${buzzItems} item(s) returned`);

  const scenarios = await client.callTool({ name: 'scenario_list', arguments: {} });
  const scenarioData = JSON.parse(scenarios.content[0].text);
  const scenario = Array.isArray(scenarioData) ? scenarioData[0] : null;
  if (scenario?.id) {
    const raw = await client.callTool({ name: 'scenario_raw_read', arguments: { scenarioId: scenario.id } });
    const rawData = JSON.parse(raw.content[0].text);
    console.log(`✓ scenario_raw_read: ${scenario.id} (${rawData.content.length} chars)`);
  }

  await client.close();
  console.log('\n✓ All smoke tests passed — MCP server is working.');
} catch (err) {
  console.error('✗ Smoke test failed:', err.message);
  process.exit(1);
}
