const { execFileSync } = require("child_process");
const { createHash } = require("crypto");
const {
  closeSync,
  existsSync,
  mkdirSync,
  openSync,
  readFileSync,
  statSync,
  unlinkSync,
  writeFileSync,
} = require("fs");
const { dirname, resolve } = require("path");

const serverDir = __dirname;
const nodeModules = resolve(serverDir, "node_modules");
const sdkEntry = resolve(nodeModules, "@modelcontextprotocol", "sdk");
const packageLock = resolve(serverDir, "package-lock.json");
const packageJson = resolve(serverDir, "package.json");
const stampFile = resolve(nodeModules, ".pockethive-mcp-install.json");
const lockFile = resolve(serverDir, ".pockethive-mcp-install.lock");
const INSTALL_LOCK_STALE_MS = 5 * 60 * 1000;

function hashFile(path) {
  return createHash("sha256").update(readFileSync(path)).digest("hex");
}

function dependencyFingerprint() {
  return existsSync(packageLock) ? hashFile(packageLock) : hashFile(packageJson);
}

function readInstallStamp() {
  try {
    return JSON.parse(readFileSync(stampFile, "utf8"));
  } catch {
    return null;
  }
}

function writeInstallStamp(fingerprint) {
  mkdirSync(dirname(stampFile), { recursive: true });
  writeFileSync(stampFile, JSON.stringify({
    fingerprint,
    packageManager: existsSync(packageLock) ? "npm ci" : "npm install",
    updatedAt: new Date().toISOString(),
  }, null, 2));
}

function dependenciesAreFresh() {
  if (!existsSync(sdkEntry)) return false;
  return readInstallStamp()?.fingerprint === dependencyFingerprint();
}

function npmCommand() {
  return process.platform === "win32" ? "npm.cmd" : "npm";
}

function sleep(ms) {
  Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms);
}

function withInstallLock(fn) {
  while (true) {
    let fd;
    try {
      fd = openSync(lockFile, "wx");
      writeFileSync(fd, JSON.stringify({ pid: process.pid, startedAt: new Date().toISOString() }));
      try {
        return fn();
      } finally {
        closeSync(fd);
        try { unlinkSync(lockFile); } catch { /* already gone */ }
      }
    } catch (err) {
      if (fd !== undefined) {
        try { closeSync(fd); } catch { /* ignore */ }
      }
      if (err?.code !== "EEXIST") throw err;
      try {
        const ageMs = Date.now() - statSync(lockFile).mtimeMs;
        if (ageMs > INSTALL_LOCK_STALE_MS) {
          unlinkSync(lockFile);
          continue;
        }
      } catch {
        continue;
      }
      sleep(250);
    }
  }
}

function installDependencies({ stdio = "ignore" } = {}) {
  const args = existsSync(packageLock) ? ["ci", "--silent"] : ["install", "--silent"];
  execFileSync(npmCommand(), args, {
    cwd: serverDir,
    encoding: "utf8",
    timeout: 120000,
    stdio,
  });
  writeInstallStamp(dependencyFingerprint());
}

function ensureDependencies(options = {}) {
  if (dependenciesAreFresh()) return { installed: false, fresh: true };
  return withInstallLock(() => {
    if (dependenciesAreFresh()) return { installed: false, fresh: true };
    installDependencies(options);
    return { installed: true, fresh: true };
  });
}

module.exports = {
  dependenciesAreFresh,
  dependencyFingerprint,
  ensureDependencies,
  installDependencies,
  serverDir,
};
