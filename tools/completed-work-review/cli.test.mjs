import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import {
  mkdir,
  mkdtemp,
  readFile,
  realpath,
  rm,
} from "node:fs/promises";
import path from "node:path";
import test from "node:test";

import { sha256 } from "../docs-impact/canonical.mjs";
import { parseArguments, writeCapturedIdentity } from "./cli.mjs";

const ROOT = await realpath(path.resolve("."));
const IGNORED_ROOT = path.join(ROOT, ".test-results", "completed-work-review");
const REPOSITORY_ID = "sepa79/PocketHive";
const REMOTE_NAME = "origin";

function commandOutput(command, argumentsList) {
  const result = spawnSync(command, argumentsList, {
    cwd: ROOT,
    encoding: "utf8",
    maxBuffer: 16 * 1024 * 1024,
    shell: false,
    stdio: ["ignore", "pipe", "pipe"],
    windowsHide: true,
  });
  if (result.error) throw result.error;
  if (result.status !== 0) {
    throw new Error(`${command} ${argumentsList.join(" ")} failed: ${(result.stderr || result.stdout).trim()}`);
  }
  return result.stdout.trim();
}

async function gitExecutable() {
  if (process.platform === "win32") {
    const candidate = process.env.GIT_TEST_EXECUTABLE
      ?? "C:\\Program Files\\Git\\mingw64\\bin\\git.exe";
    if (!path.isAbsolute(candidate)) {
      throw new Error("GIT_TEST_EXECUTABLE must be the absolute Git-for-Windows core executable");
    }
    return realpath(candidate);
  }
  const located = commandOutput("which", ["git"]).split(/\r?\n/u)[0];
  if (!path.isAbsolute(located)) throw new Error("Git discovery did not return an absolute path");
  return realpath(located);
}

test("identity CLI writes once through a validated direct parent", async () => {
  await mkdir(IGNORED_ROOT, { recursive: true });
  const fixtureRoot = await mkdtemp(path.join(IGNORED_ROOT, "cli-identity-"));
  const expectedPrefix = `${IGNORED_ROOT}${path.sep}`;
  if (!fixtureRoot.startsWith(expectedPrefix)) {
    throw new Error("CLI test fixture escaped the verified ignored test root");
  }
  try {
    const git = await gitExecutable();
    const expectedGitExecutableSha256 = sha256(await readFile(git));
    const outputPath = path.join(fixtureRoot, "identity.json");
    const options = {
      repositoryRoot: ROOT,
      gitExecutable: git,
      expectedGitExecutableSha256,
      outputPath,
      repositoryId: REPOSITORY_ID,
      repositoryRemote: {
        name: REMOTE_NAME,
        url: commandOutput(git, ["remote", "get-url", REMOTE_NAME]),
      },
      mode: "DIRTY_WORKTREE",
      baseCommit: commandOutput(git, ["rev-parse", "HEAD"]),
      capturedAt: new Date().toISOString(),
    };
    const captured = await writeCapturedIdentity(options);
    assert.deepEqual(JSON.parse(await readFile(outputPath, "utf8")), captured.identity);
    await assert.rejects(
      writeCapturedIdentity(options),
      /already exists and will not be overwritten/u,
    );

    if (process.platform === "win32" && /^[A-Z]:/u.test(ROOT)) {
      const differentlyCasedOutput = `${ROOT[0].toLowerCase()}${outputPath.slice(1)}`;
      await assert.rejects(
        writeCapturedIdentity({ ...options, outputPath: differentlyCasedOutput }),
        /must be inside the repository/u,
      );
    }
  } finally {
    await rm(fixtureRoot, { recursive: true, force: true });
  }
});

test("capture and assemble CLI parsing require an external Git executable digest", () => {
  assert.throws(
    () => parseArguments([
      "capture-identity",
      "--repo", ROOT,
      "--git-executable", path.join(ROOT, "git.exe"),
      "--output", path.join(ROOT, ".test-results", "identity.json"),
      "--repository-id", REPOSITORY_ID,
      "--remote-name", REMOTE_NAME,
      "--remote-url", "https://example.invalid/repository.git",
      "--mode", "DIRTY_WORKTREE",
      "--base-commit", "a".repeat(40),
      "--captured-at", "2026-08-17T12:00:00.000Z",
    ]),
    /--git-executable-sha256 is required/u,
  );
  assert.throws(
    () => parseArguments([
      "assemble",
      "--repo", ROOT,
      "--request", path.join(ROOT, "request.json"),
      "--request-schema", path.join(ROOT, "request.schema.json"),
      "--producer-registry", path.join(ROOT, "registry.json"),
      "--producer-registry-schema", path.join(ROOT, "registry.schema.json"),
      "--git-executable", path.join(ROOT, "git.exe"),
      "--repository-id", REPOSITORY_ID,
      "--remote-name", REMOTE_NAME,
      "--remote-url", "https://example.invalid/repository.git",
      "--evaluation-time", "2026-08-17T12:00:00.000Z",
      "--producer-registry-digest", "a".repeat(64),
      "--candidate-identity-id", "b".repeat(64),
      "--baseline-identity-id", "NONE",
      "--deployment-identity-id", "NONE",
    ]),
    /--git-executable-sha256 is required/u,
  );
});
