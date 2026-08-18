import assert from 'node:assert/strict';
import { readFile, readdir, stat } from 'node:fs/promises';
import { join } from 'node:path';

const manifest = JSON.parse(await readFile('package.json', 'utf8'));
const views = Object.values(manifest.contributes?.views ?? {}).flat();
assert.deepEqual(views, [{ type: 'webview', id: 'pockethive.companion', name: 'PocketHive' }]);
assert.equal(manifest.contributes?.configuration, undefined);
assert.equal(manifest.dependencies, undefined);

const sources = (await files('src')).filter(path => path.endsWith('.ts'));
const product = (await Promise.all(sources
  .filter(path => !path.includes('/test/'))
  .map(path => readFile(path, 'utf8')))).join('\n');
for (const forbidden of [
  'StdioClientTransport', 'registerTreeDataProvider', 'mcpServerPath',
  'bundlesFolders', 'activeBundlesFolder', 'wiremockUrl', 'tcpMockUrl', 'scenario_raw_write',
]) {
  assert.equal(product.includes(forbidden), false, `Legacy product boundary remains: ${forbidden}`);
}
const processSourceContents = (await Promise.all(sources.filter(path => !path.includes('/test/')).map(async path => ({
  path,
  content: await readFile(path, 'utf8'),
})))).filter(item => item.content.includes('node:child_process'));
assert.deepEqual(processSourceContents.map(item => item.path), ['src/scenarios/gitBundlePackager.ts']);
assert.match(processSourceContents[0].content, /execFile\('git'/);
assert.doesNotMatch(processSourceContents[0].content, /execFile\(['"](?:node|java)['"]/);
assert.equal((product.match(/registerWebviewViewProvider/g) ?? []).length, 1);
console.log('PocketHive VS Code atomic cutover checks passed.');

async function files(root) {
  const result = [];
  for (const entry of await readdir(root)) {
    const path = join(root, entry);
    if ((await stat(path)).isDirectory()) result.push(...await files(path));
    else result.push(path);
  }
  return result;
}
