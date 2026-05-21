const { spawn } = require('child_process');
const os = require('os');

let _wslAvailable = null;
let _wslMountPrefix = null; // detected lazily: '/mnt' or ''
const IS_WINDOWS = os.platform() === 'win32';

/** Detect whether WSL mounts drives at /mnt/c or /c.
 *  Derives from POCKETHIVE_ROOT env var if it is already a POSIX path — avoids
 *  a WSL probe that can cache the wrong result if WSL isn't fully up at startup.
 */
async function getWslMountPrefix() {
  if (_wslMountPrefix !== null) return _wslMountPrefix;
  // Derive from POCKETHIVE_ROOT if it is already a POSIX path
  const pr = process.env.POCKETHIVE_ROOT || '';
  if (pr.startsWith('/')) {
    _wslMountPrefix = pr.startsWith('/mnt/') ? '/mnt' : '';
    return _wslMountPrefix;
  }
  // Probe live WSL instance
  try {
    const r = await spawnProcess('wsl', ['bash', '-lc', 'test -d /mnt/c && echo mnt || echo root'], {});
    _wslMountPrefix = (r.success && r.stdout.trim() === 'mnt') ? '/mnt' : '';
  } catch {
    _wslMountPrefix = '/mnt'; // safe default
  }
  return _wslMountPrefix;
}

function toWslPath(winPath, mountPrefix = '/mnt') {
  return winPath.replace(/\\/g, '/').replace(/^([A-Za-z]):/, (_, d) => `${mountPrefix}/${d.toLowerCase()}`);
}

function spawnProcess(cmd, args, opts) {
  return new Promise((resolve, reject) => {
    const proc = spawn(cmd, args, { stdio: 'pipe', ...opts });
    let stdout = '', stderr = '';
    proc.stdout.on('data', d => { stdout += d.toString(); });
    proc.stderr.on('data', d => { stderr += d.toString(); });
    proc.on('close', code => resolve({ success: code === 0, stdout, stderr, exitCode: code }));
    proc.on('error', err => reject(err));
  });
}

async function wslAvailable() {
  if (!IS_WINDOWS) return false;
  if (_wslAvailable !== null) return _wslAvailable;
  try {
    const r = await spawnProcess('wsl', ['bash', '-lc', 'echo ok'], {});
    _wslAvailable = r.success && r.stdout.trim() === 'ok';
  } catch {
    _wslAvailable = false;
  }
  return _wslAvailable;
}

/**
 * Run a shell command cross-platform.
 *
 * Resolution order:
 *   1. Linux / macOS → direct bash -c
 *   2. Windows + WSL available → wsl bash -lc
 *   3. Windows without WSL → PowerShell
 */
async function execute(command, workingDir) {
  if (!IS_WINDOWS) {
    const cdPart = workingDir ? `cd '${workingDir}' && ` : '';
    return spawnProcess('bash', ['-c', `${cdPart}${command}`], {});
  }

  if (await wslAvailable()) {
    const prefix = await getWslMountPrefix();
    const cdPart = workingDir ? `cd '${toWslPath(workingDir, prefix)}' && ` : '';
    return spawnProcess('wsl', ['bash', '-lc', `${cdPart}${command}`], {});
  }

  // Pure Windows fallback — PowerShell
  const setPart = workingDir ? `Set-Location '${workingDir}'; ` : '';
  return spawnProcess('powershell', [
    '-NoProfile', '-Command',
    `${setPart}${command}`
  ], { shell: false });
}

/**
 * Check if a binary exists on the system.
 */
async function isAvailable(bin) {
  try {
    if (!IS_WINDOWS) {
      const r = await spawnProcess('bash', ['-c', `command -v ${bin}`], {});
      return r.success;
    }
    if (await wslAvailable()) {
      const r = await spawnProcess('wsl', ['bash', '-lc', `command -v ${bin}`], {});
      return r.success;
    }
    const r = await spawnProcess('powershell', [
      '-NoProfile', '-Command',
      `Get-Command ${bin} -ErrorAction SilentlyContinue`
    ], { shell: false });
    return r.success;
  } catch {
    return false;
  }
}

module.exports = { execute, isAvailable, toWslPath, getWslMountPrefix };
