import assert from 'node:assert/strict';
import { readFile, readdir, stat } from 'node:fs/promises';
import { join } from 'node:path';

const manifest = JSON.parse(await readFile('package.json', 'utf8'));
const views = Object.values(manifest.contributes?.views ?? {}).flat();
assert.deepEqual(views, [{ type: 'webview', id: 'pockethive.companion', name: 'PocketHive' }]);
assert.equal(manifest.contributes?.configuration, undefined);
assert.equal(manifest.dependencies, undefined);

const sources = (await files('src')).filter(path => path.endsWith('.ts'));
const webviewSource = await readFile('src/webview/main.ts', 'utf8');
const webviewStyles = await readFile('media/companion.css', 'utf8');
const providerSource = await readFile('src/webview/companionProvider.ts', 'utf8');
const brandTokens = await readFile('resources/brand-tokens.css', 'utf8');
const canonicalLogo = await readFile('../ui-v2/public/logo.svg', 'utf8');
const canonicalHiveColour = canonicalLogo.match(/\.brandHive\s*\{[^}]*\bfill\s*:\s*(#[0-9a-f]{6})\s*;/i)?.[1];
assert.ok(canonicalHiveColour, 'Canonical PocketHive logo must declare the Hive colour');
assert.match(brandTokens, new RegExp(`--ph-brand-hive:\\s*${canonicalHiveColour}`, 'i'),
  'Generated webview token must use the canonical logo Hive colour');
assert.match(webviewSource, /text\('span', 'Hive', 'brand__hive'\)/,
  'Shared webview header must expose the Hive suffix for brand colouring');
assert.match(webviewStyles, /\.brand__hive\s*\{[^}]*color:\s*var\(--ph-brand-hive\)/s,
  'Shared webview header must consume the generated Hive colour');
assert.match(webviewStyles, /\.brand__name\s*\{[^}]*line-height:\s*1\.05/s,
  'Shared webview heading must retain the compact canonical logo rhythm');
assert.match(webviewStyles, /\.brand__copy p\s*\{[^}]*line-height:\s*1\.15/s,
  'Shared webview subtitle must sit tightly beneath the PocketHive heading');
assert.match(providerSource, /resources', 'brand-tokens\.css'/,
  'Webview must load the generated canonical brand token');
assert.match(webviewSource, /app\.append\(header\(\)\);\s*if \(model\.page === 'workspace'\)/,
  'Both companion pages must use the same branded header');
const sourceEntries = sources.map(path => ({ nativePath: path, portablePath: toPortablePath(path) }));
const product = (await Promise.all(sourceEntries
  .filter(({ portablePath }) => !isTestSource(portablePath))
  .map(({ nativePath }) => readFile(nativePath, 'utf8')))).join('\n');
for (const forbidden of [
  'StdioClientTransport', 'registerTreeDataProvider', 'mcpServerPath',
  'bundlesFolders', 'activeBundlesFolder', 'wiremockUrl', 'tcpMockUrl', 'scenario_raw_write',
]) {
  assert.equal(product.includes(forbidden), false, `Legacy product boundary remains: ${forbidden}`);
}
const processSourceContents = (await Promise.all(sourceEntries
  .filter(({ portablePath }) => !isTestSource(portablePath))
  .map(async ({ nativePath, portablePath }) => ({
    path: portablePath,
    content: await readFile(nativePath, 'utf8'),
  })))).filter(item => item.content.includes('node:child_process'));
assert.deepEqual(processSourceContents.map(item => item.path), ['src/scenarios/gitBundlePackager.ts']);
assert.match(processSourceContents[0].content, /execFile\('git'/);
assert.doesNotMatch(processSourceContents[0].content, /execFile\(['"](?:node|java)['"]/);
assert.equal((product.match(/registerWebviewViewProvider/g) ?? []).length, 1);
console.log('PocketHive VS Code atomic cutover checks passed.');

function isTestSource(path) {
  return path.includes('/test/');
}

function toPortablePath(path) {
  return path.replaceAll('\\', '/');
}

async function files(root) {
  const result = [];
  for (const entry of await readdir(root)) {
    const path = join(root, entry);
    if ((await stat(path)).isDirectory()) result.push(...await files(path));
    else result.push(path);
  }
  return result;
}
