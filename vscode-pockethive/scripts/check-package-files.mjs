import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';

const run = promisify(execFile);
const { stdout } = await run('node_modules/.bin/vsce', ['ls'], { encoding: 'utf8' });
const files = stdout.split(/\r?\n/).filter(Boolean);
for (const required of [
  'package.json', 'README.md', 'LICENSE', 'media/companion.css',
  'resources/hive.svg', 'resources/logo-mark.svg', 'out/extension.js', 'out/webview/main.js',
]) {
  assert.equal(files.includes(required), true, `Required VSIX file missing: ${required}`);
}
for (const forbidden of ['node_modules/', 'src/', 'out/test/', 'scripts/', '.map', 'init.sh', '.vsix']) {
  assert.equal(files.some(file => file.includes(forbidden)), false,
    `Forbidden VSIX content remains: ${forbidden}`);
}
assert.ok(files.length < 50, `VSIX file set is unexpectedly large: ${files.length}`);
console.log(`PocketHive VSIX content checks passed (${files.length} files).`);
