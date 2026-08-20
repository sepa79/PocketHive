import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { readFile } from 'node:fs/promises';
import { join } from 'node:path';
import { promisify } from 'node:util';

const run = promisify(execFile);
const manifest = JSON.parse(await readFile('package.json', 'utf8'));
const activityIcon = manifest.contributes?.viewsContainers?.activitybar?.find(
  container => container.id === 'pockethive',
)?.icon;
assert.equal(activityIcon, 'resources/activity-mark.svg',
  'PocketHive Activity Bar must use its 24 px-optimized hexagon silhouette');
const activitySvg = await readFile('resources/activity-mark.svg', 'utf8');
assert.match(activitySvg, /data-pocket-hive-activity-icon="24px-silhouette"/,
  'PocketHive Activity Bar icon must declare its small-size silhouette contract');
assert.equal((activitySvg.match(/class="edge"/g) ?? []).length, 6,
  'PocketHive Activity Bar icon must retain all six connectors');
for (const requiredClass of ['hex', 'panel', 'lensOuter', 'lensInner', 'node']) {
  assert.match(activitySvg, new RegExp(`class="${requiredClass}"`),
    `PocketHive Activity Bar icon must retain ${requiredClass}`);
}
const vsceCommand = join('node_modules', '.bin', process.platform === 'win32' ? 'vsce.cmd' : 'vsce');
const { stdout } = await run(vsceCommand, ['ls'], { encoding: 'utf8' });
const files = stdout.split(/\r?\n/).filter(Boolean).map(path => path.replaceAll('\\', '/'));
for (const required of [
  'package.json', 'README.md', 'LICENSE', 'media/companion.css',
  'resources/activity-mark.svg', 'resources/brand-tokens.css', 'resources/logo-mark.svg',
  'out/extension.js', 'out/webview/eventFilters.js', 'out/webview/main.js',
]) {
  assert.equal(files.includes(required), true, `Required VSIX file missing: ${required}`);
}
for (const forbidden of [
  'node_modules/', 'src/', 'out/test/', 'scripts/', '.map', 'init.sh', '.vsix', 'resources/hive.svg',
]) {
  assert.equal(files.some(file => file.includes(forbidden)), false,
    `Forbidden VSIX content remains: ${forbidden}`);
}
assert.ok(files.length < 50, `VSIX file set is unexpectedly large: ${files.length}`);
console.log(`PocketHive VSIX content checks passed (${files.length} files).`);
