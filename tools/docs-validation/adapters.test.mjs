import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { access, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { promisify } from "node:util";

import { loadAdapterManifest } from "./adapters.mjs";
import {
  executableBinding,
  notApplicableExecutable,
  writeAdapterManifest,
} from "./adapter-test-helpers.mjs";
import { executeStage, parseOptions } from "./run.mjs";

const execFileAsync = promisify(execFile);
const REPOSITORY_ROOT = path.resolve(import.meta.dirname, "../..");

function validCliArgs(manifestPath) {
  return [
    "--profile", "static",
    "--repo", REPOSITORY_ROOT,
    "--report", path.join(REPOSITORY_ROOT, ".test-results", "adapter-test.json"),
    "--candidate-mode", "DIRTY_WORKTREE",
    "--adapter-manifest", manifestPath,
  ];
}

test("adapter manifest CLI input is mandatory, absolute, and existing", async () => {
  assert.throws(
    () => parseOptions(validCliArgs("relative-adapters.json")),
    /explicit absolute path/u,
  );
  const missing = path.join(os.tmpdir(), `missing-adapters-${process.pid}.json`);
  await assert.rejects(
    loadAdapterManifest({ manifestPath: missing }),
    /does not exist/u,
  );
});

test("configured executables reject relative paths, missing files, and digest mismatches", async (t) => {
  const relative = await writeAdapterManifest(t, (manifest) => {
    manifest.adapters.git.path = "git";
  });
  await assert.rejects(
    loadAdapterManifest({ manifestPath: relative.manifestPath }),
    /git CONFIGURED path must be absolute/u,
  );

  const missing = await writeAdapterManifest(t, (manifest, directory) => {
    manifest.adapters.git.path = path.join(directory, "missing-git.exe");
    manifest.adapters.git.sha256 = "a".repeat(64);
    manifest.adapters.git.sizeBytes = 1;
  });
  await assert.rejects(
    loadAdapterManifest({ manifestPath: missing.manifestPath }),
    /git CONFIGURED executable does not exist/u,
  );

  const digestMismatch = await writeAdapterManifest(t, (manifest) => {
    manifest.adapters.git.sha256 = manifest.adapters.git.sha256 === "f".repeat(64)
      ? "e".repeat(64)
      : "f".repeat(64);
  });
  await assert.rejects(
    loadAdapterManifest({ manifestPath: digestMismatch.manifestPath }),
    /git executable digest or size does not match/u,
  );
});

test("adapter manifest contract is closed and requires every adapter state", async (t) => {
  const missingAdapter = await writeAdapterManifest(t, (manifest) => {
    delete manifest.adapters.docker;
  });
  await assert.rejects(
    loadAdapterManifest({ manifestPath: missingAdapter.manifestPath }),
    /missing required property docker/u,
  );

  const unknownAdapter = await writeAdapterManifest(t, (manifest) => {
    manifest.adapters.autoDiscoveredBrowser = notApplicableExecutable();
  });
  await assert.rejects(
    loadAdapterManifest({ manifestPath: unknownAdapter.manifestPath }),
    /unexpected property autoDiscoveredBrowser/u,
  );
});

test("platform declaration and taskkill state fail closed", async (t) => {
  const wrongPlatform = await writeAdapterManifest(t, (manifest) => {
    manifest.platform = process.platform === "win32" ? "linux" : "win32";
  });
  await assert.rejects(
    loadAdapterManifest({ manifestPath: wrongPlatform.manifestPath }),
    /does not match runtime/u,
  );

  const wrongTaskkill = await writeAdapterManifest(t, (manifest) => {
    if (process.platform === "win32") {
      manifest.adapters.taskkill = notApplicableExecutable();
    } else {
      manifest.adapters.taskkill = structuredClone(manifest.adapters.git);
    }
  });
  await assert.rejects(
    loadAdapterManifest({ manifestPath: wrongTaskkill.manifestPath }),
    /taskkill must be/u,
  );
});

test("legacy fallback environment variables cannot select an adapter", async (t) => {
  const { manifestPath } = await writeAdapterManifest(t);
  const previous = Object.fromEntries(
    [
      "DOCS_TEST_BASH_EXECUTABLE",
      "DOCS_TEST_BROWSER_EXECUTABLE",
      "DOCS_TEST_MAVEN_EXECUTABLE",
      "DOCS_TEST_MAVEN_REPOSITORY",
      "DOCS_TEST_POWERSHELL_EXECUTABLE",
    ].map((name) => [name, process.env[name]]),
  );
  try {
    for (const name of Object.keys(previous)) process.env[name] = path.join(os.tmpdir(), "malicious");
    const loaded = await loadAdapterManifest({ manifestPath });
    assert.notEqual(loaded.adapters.git.path, path.join(os.tmpdir(), "malicious"));

    const environment = { ...process.env };
    delete environment.DOCS_VALIDATION_BASH_EXECUTABLE;
    delete environment.DOCS_VALIDATION_POWERSHELL_EXECUTABLE;
    await assert.rejects(
      execFileAsync(process.execPath, ["tools/docs-validation/content-audit.mjs"], {
        cwd: REPOSITORY_ROOT,
        env: environment,
        windowsHide: true,
      }),
      (error) => {
        assert.match(error.stderr, /DOCS_VALIDATION_BASH_EXECUTABLE must declare/u);
        return true;
      },
    );
  } finally {
    for (const [name, value] of Object.entries(previous)) {
      if (value === undefined) delete process.env[name];
      else process.env[name] = value;
    }
  }
});

test("canonical adapter identity is deterministic while raw bytes remain bound", async (t) => {
  const first = await writeAdapterManifest(t, undefined, 2);
  const second = await writeAdapterManifest(t, undefined, 0);
  const firstLoaded = await loadAdapterManifest({ manifestPath: first.manifestPath });
  const secondLoaded = await loadAdapterManifest({ manifestPath: second.manifestPath });
  assert.equal(
    firstLoaded.manifestIdentity.canonicalSha256,
    secondLoaded.manifestIdentity.canonicalSha256,
  );
  assert.equal(
    firstLoaded.manifestIdentity.canonicalJson,
    secondLoaded.manifestIdentity.canonicalJson,
  );
  assert.notEqual(firstLoaded.manifestIdentity.rawSha256, secondLoaded.manifestIdentity.rawSha256);
});

test("a required stage whose adapter is NOT_APPLICABLE records SKIP and fails the required gate", async (t) => {
  const { manifestPath, directory } = await writeAdapterManifest(t);
  const adapterManifest = await loadAdapterManifest({ manifestPath });
  const stage = {
    stageId: "ADAPTER_PROBE",
    commandSpecId: "ADAPTER_PROBE_V1",
    name: "required adapter probe",
    required: true,
    declaredTimeoutMs: 5_000,
    run: (context) => context.runCommand("npm", ["--version"]),
  };
  const result = await executeStage(stage, {
    repositoryRoot: directory,
    artifactDirectory: path.join(directory, "artifacts"),
    adapterManifest,
  });
  assert.equal(result.status, "SKIP");
  assert.equal(result.required, true);
  assert.match(result.detail, /npm is NOT_APPLICABLE/u);
});

test("Windows command files execute only through the declared command-shell adapter", async (t) => {
  if (process.platform !== "win32") {
    t.skip("Windows .cmd adapter semantics apply only on win32");
    return;
  }
  const fixture = await writeAdapterManifest(t, async (manifest, directory) => {
    const commandFile = path.join(directory, "probe.cmd");
    await writeFile(commandFile, "@echo off\r\n>\"%~1\" echo declared-shell\r\n", "utf8");
    manifest.adapters.maven = await executableBinding(commandFile);
  });
  const marker = path.join(fixture.directory, "shell-marker.txt");
  const adapterManifest = await loadAdapterManifest({ manifestPath: fixture.manifestPath });
  const stage = {
    stageId: "COMMAND_SHELL_PROBE",
    commandSpecId: "COMMAND_SHELL_PROBE_V1",
    name: "declared command-shell probe",
    required: true,
    declaredTimeoutMs: 5_000,
    run: (context) => context.runCommand("maven", [marker]),
  };
  const result = await executeStage(stage, {
    repositoryRoot: fixture.directory,
    artifactDirectory: path.join(fixture.directory, "artifacts"),
    adapterManifest,
  });
  assert.equal(result.status, "PASS");
  await access(marker);
});
