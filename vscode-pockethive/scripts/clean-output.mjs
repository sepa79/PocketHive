import assert from 'node:assert/strict';
import { readdir, realpath, rmdir, stat, unlink } from 'node:fs/promises';
import { join, resolve, sep } from 'node:path';

const workspace = await realpath(process.cwd());
const output = resolve(workspace, 'out');
assert.equal(output, `${workspace}${sep}out`, 'Refusing to clean an unexpected output path');

if (await exists(output)) await clean(output, false);

async function clean(directory, removeDirectory) {
  for (const entry of await readdir(directory)) {
    const path = join(directory, entry);
    const metadata = await stat(path);
    if (metadata.isDirectory()) await clean(path, true);
    else if (entry.endsWith('.js') || entry.endsWith('.js.map')) await unlink(path);
  }
  if (removeDirectory && (await readdir(directory)).length === 0) await rmdir(directory);
}

async function exists(path) {
  try {
    await stat(path);
    return true;
  } catch (error) {
    if (error?.code === 'ENOENT') return false;
    throw error;
  }
}
