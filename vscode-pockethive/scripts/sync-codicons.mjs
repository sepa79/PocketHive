import assert from 'node:assert/strict';
import { copyFile, readFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const scriptDirectory = dirname(fileURLToPath(import.meta.url));
const extensionRoot = join(scriptDirectory, '..');
const packageRoot = join(extensionRoot, 'node_modules', '@vscode', 'codicons');
const check = process.argv.includes('--check');
const assets = Object.freeze([
  ['dist/codicon.css', 'resources/codicon.css'],
  ['dist/codicon.ttf', 'resources/codicon.ttf'],
  ['LICENSE', 'resources/codicons.LICENSE'],
]);

for (const [source, target] of assets) {
  const sourcePath = join(packageRoot, source);
  const targetPath = join(extensionRoot, target);
  if (check) {
    assert.deepEqual(await readFile(targetPath), await readFile(sourcePath),
      `${target} differs from the pinned @vscode/codicons package`);
  } else {
    await copyFile(sourcePath, targetPath);
  }
}

console.log(`PocketHive Codicon assets ${check ? 'match' : 'synced from'} the pinned package.`);
