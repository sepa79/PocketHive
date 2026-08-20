import { execFile } from 'node:child_process';
import { stat } from 'node:fs/promises';
import { join } from 'node:path';
import { promisify } from 'node:util';

const run = promisify(execFile);

if (!(await exists(join('out', 'extension.js')))) {
  await run(process.execPath, [join('node_modules', 'typescript', 'bin', 'tsc'), '-p', './'], {
    encoding: 'utf8',
  });
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
