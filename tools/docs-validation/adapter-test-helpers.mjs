import { createHash } from "node:crypto";
import { access, mkdtemp, readFile, realpath, rm, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";

import { canonicalDigest, canonicalJson } from "../docs-impact/canonical.mjs";

export const ADAPTER_SCHEMA_ID =
  "https://pockethive.dev/schemas/docs-validation-adapter-manifest-v1.schema.json";

function digest(bytes) {
  return createHash("sha256").update(bytes).digest("hex");
}

async function firstExisting(candidates, label) {
  for (const candidate of candidates) {
    if (!candidate || !path.isAbsolute(candidate)) continue;
    try {
      await access(candidate);
      return realpath(candidate);
    } catch {
      // Try the next explicit platform location.
    }
  }
  throw new Error(`No explicit ${label} test executable exists`);
}

export async function executableBinding(executablePath) {
  const canonicalPath = await realpath(executablePath);
  const bytes = await readFile(canonicalPath);
  return {
    state: "CONFIGURED",
    path: canonicalPath,
    sha256: digest(bytes),
    sizeBytes: bytes.byteLength,
  };
}

export function notApplicableExecutable() {
  return { state: "NOT_APPLICABLE", path: null, sha256: null, sizeBytes: null };
}

export async function baseAdapterManifest() {
  const gitPath = await firstExisting([
    process.env.GIT_TEST_EXECUTABLE,
    "C:\\Program Files\\Git\\mingw64\\bin\\git.exe",
    "C:\\Program Files\\Git\\cmd\\git.exe",
    "/usr/bin/git",
    "/opt/homebrew/bin/git",
  ], "Git");
  const commandShellPath = await firstExisting(
    process.platform === "win32"
      ? ["C:\\Windows\\System32\\cmd.exe"]
      : ["/bin/sh", "/usr/bin/sh"],
    "command shell",
  );
  const taskkill = process.platform === "win32"
    ? await executableBinding(await firstExisting(
      ["C:\\Windows\\System32\\taskkill.exe"],
      "taskkill",
    ))
    : notApplicableExecutable();
  return {
    schemaVersion: 1,
    schemaId: ADAPTER_SCHEMA_ID,
    platform: process.platform,
    adapters: {
      node: await executableBinding(process.execPath),
      git: await executableBinding(gitPath),
      npm: notApplicableExecutable(),
      commandShell: await executableBinding(commandShellPath),
      taskkill,
      bash: notApplicableExecutable(),
      powerShell: notApplicableExecutable(),
      java: notApplicableExecutable(),
      maven: notApplicableExecutable(),
      localRepository: { state: "NOT_APPLICABLE", path: null },
      docker: notApplicableExecutable(),
      chromium: notApplicableExecutable(),
    },
  };
}

export async function writeAdapterManifest(t, mutate = undefined, formatting = 2) {
  const directory = await mkdtemp(path.join(os.tmpdir(), "pockethive-adapters-test-"));
  t.after(async () => {
    const resolved = path.resolve(directory);
    if (path.dirname(resolved) !== path.resolve(os.tmpdir())) {
      throw new Error(`Refusing unsafe adapter-test cleanup: ${resolved}`);
    }
    await rm(resolved, { force: true, recursive: true });
  });
  const manifest = await baseAdapterManifest();
  await mutate?.(manifest, directory);
  const manifestPath = path.join(directory, "adapters.json");
  await writeFile(manifestPath, `${JSON.stringify(manifest, null, formatting)}\n`, "utf8");
  return { manifest, manifestPath: await realpath(manifestPath), directory };
}

export function syntheticAdapterIdentity(repositoryRoot, digestValue) {
  const configured = (adapterPath) => ({
    state: "CONFIGURED",
    path: adapterPath,
    sha256: digestValue,
    sizeBytes: 1,
  });
  const notApplicable = notApplicableExecutable();
  const windows = process.platform === "win32";
  const adapters = {
    node: configured(process.execPath),
    git: configured(path.join(path.parse(repositoryRoot).root, "git-test")),
    npm: notApplicable,
    commandShell: configured(path.join(path.parse(repositoryRoot).root, "shell-test")),
    taskkill: windows
      ? configured(path.join(path.parse(repositoryRoot).root, "taskkill-test"))
      : notApplicable,
    bash: notApplicable,
    powerShell: notApplicable,
    java: notApplicable,
    maven: notApplicable,
    localRepository: { state: "NOT_APPLICABLE", path: null },
    docker: notApplicable,
    chromium: notApplicable,
  };
  const manifest = {
    schemaVersion: 1,
    schemaId: ADAPTER_SCHEMA_ID,
    platform: process.platform,
    adapters,
  };
  const canonical = canonicalJson(manifest);
  return {
    manifest: {
      path: path.join(repositoryRoot, "adapters.json"),
      rawSha256: digestValue,
      rawSizeBytes: 1,
      canonicalSha256: canonicalDigest(manifest),
      canonicalJson: canonical,
    },
    ...structuredClone(adapters),
  };
}
