import { readdir } from 'node:fs/promises';
import { spawnSync } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
const testDirectory = path.resolve(scriptDirectory, '..', 'out', 'test');
const testFiles = (await readdir(testDirectory, { withFileTypes: true }))
  .filter(entry => entry.isFile() && entry.name.endsWith('.test.js'))
  .map(entry => path.join(testDirectory, entry.name))
  .sort((left, right) => left.localeCompare(right, 'en'));

if (testFiles.length === 0) {
  throw new Error('POCKETHIVE_EXTENSION_TESTS_NOT_FOUND');
}

const result = spawnSync(process.execPath, ['--test', ...testFiles], {
  stdio: 'inherit',
  windowsHide: true,
});
if (result.error) throw result.error;
if (result.signal) throw new Error(`POCKETHIVE_EXTENSION_TESTS_TERMINATED:${result.signal}`);
process.exitCode = result.status ?? 1;
