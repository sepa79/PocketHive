import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import {
  accessSync,
  chmodSync,
  constants as FS_CONSTANTS,
  mkdtempSync,
  readFileSync,
  realpathSync,
  rmSync,
  writeFileSync
} from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";
import { pathToFileURL } from "node:url";
import { deflateSync, inflateSync } from "node:zlib";

import {
  assertObjectGraphIntegrity,
  assertRepositoryRoot,
  assertTreeObjectsAvailable,
  boundedGitErrorDetail,
  findMergeBase,
  readBlob,
  readChanges,
  readObjectFormat,
  readTreeEntries,
  resolveCommit
} from "./git-reader.mjs";
import { LIMITS } from "./constants.mjs";

const TEST_GIT_TIMEOUT_MS = 10_000;

test("bounds long Git stderr diagnostics explicitly", () => {
  const detail = boundedGitErrorDetail(Buffer.alloc(LIMITS.maxGitErrorDetailBytes + 4096, 0x78));
  assert.match(detail, /Git stderr omitted beyond/u);
  assert.ok(Buffer.byteLength(detail, "utf8") < LIMITS.maxGitErrorDetailBytes + 128);
});

function findTrustedGitExecutable() {
  const names = process.platform === "win32" ? ["git.exe"] : ["git"];
  for (const rawDirectory of (process.env.PATH ?? "").split(path.delimiter)) {
    const directory = rawDirectory.replace(/^"|"$/gu, "");
    if (!directory) {
      continue;
    }
    for (const name of names) {
      const candidate = path.resolve(directory, name);
      try {
        accessSync(candidate, FS_CONSTANTS.X_OK);
        return realpathSync(candidate);
      } catch {
        // Continue without executing any discovered candidate.
      }
    }
  }
  throw new Error("Tests require an absolute Git executable on PATH");
}

const TEST_GIT_EXECUTABLE = findTrustedGitExecutable();

function git(repoRoot, args) {
  return execFileSync(TEST_GIT_EXECUTABLE, args, {
    cwd: repoRoot,
    encoding: "utf8",
    stdio: ["ignore", "pipe", "pipe"],
    timeout: TEST_GIT_TIMEOUT_MS,
    windowsHide: true
  }).trim();
}

function commitFile(repoRoot, name, contents, message) {
  writeFileSync(path.join(repoRoot, name), contents, "utf8");
  git(repoRoot, ["add", "--", name]);
  git(repoRoot, [
    "-c",
    "user.name=Docs Impact Test",
    "-c",
    "user.email=docs-impact@example.invalid",
    "commit",
    "--quiet",
    "-m",
    message
  ]);
  return git(repoRoot, ["rev-parse", "HEAD"]);
}

function createRepository(t, objectFormat) {
  const repoRoot = mkdtempSync(path.join(tmpdir(), `docs-impact-${objectFormat}-`));
  t.after(() => rmSync(repoRoot, { recursive: true, force: true }));
  git(repoRoot, ["init", "--quiet", `--object-format=${objectFormat}`]);
  const firstCommit = commitFile(repoRoot, "README.md", "first\n", "first");
  return { firstCommit, repoRoot };
}

for (const [objectFormat, expectedLength] of [["sha1", 40], ["sha256", 64]]) {
  test(`requires exact full ${objectFormat} object IDs`, (t) => {
    const { firstCommit, repoRoot } = createRepository(t, objectFormat);
    const secondCommit = commitFile(repoRoot, "README.md", "second\n", "second");

    assert.equal(readObjectFormat(repoRoot, TEST_GIT_EXECUTABLE), objectFormat);
    assert.equal(assertRepositoryRoot(repoRoot, TEST_GIT_EXECUTABLE), path.resolve(repoRoot));
    assert.equal(firstCommit.length, expectedLength);
    assert.equal(resolveCommit(repoRoot, firstCommit, TEST_GIT_EXECUTABLE), firstCommit);
    assert.equal(
      findMergeBase(repoRoot, firstCommit, secondCommit, TEST_GIT_EXECUTABLE),
      firstCommit
    );
    assert.equal(
      readBlob(repoRoot, secondCommit, "README.md", TEST_GIT_EXECUTABLE).toString("utf8"),
      "second\n"
    );
    assert.doesNotThrow(() =>
      assertObjectGraphIntegrity(repoRoot, [firstCommit, secondCommit], TEST_GIT_EXECUTABLE)
    );

    const entries = readTreeEntries(repoRoot, secondCommit, TEST_GIT_EXECUTABLE);
    assert.equal(entries.length, 1);
    assert.equal(entries[0].objectId.length, expectedLength);
    assert.doesNotThrow(() =>
      assertTreeObjectsAvailable(repoRoot, entries, TEST_GIT_EXECUTABLE)
    );
    assert.throws(
      () => assertTreeObjectsAvailable(repoRoot, [{
        mode: "100644",
        type: "blob",
        objectId: "f".repeat(expectedLength),
        path: "missing.txt"
      }], TEST_GIT_EXECUTABLE),
      /unavailable/u
    );

    const changes = readChanges(repoRoot, firstCommit, secondCommit, TEST_GIT_EXECUTABLE);
    assert.deepEqual(changes.map(({ path: changedPath, status }) => ({ changedPath, status })), [
      { changedPath: "README.md", status: "M" }
    ]);

    const abbreviated = firstCommit.slice(0, -1);
    const overlong = `${firstCommit}0`;
    const expectedError = new RegExp(`full ${expectedLength}-character ${objectFormat}`);
    assert.throws(() => resolveCommit(repoRoot, abbreviated, TEST_GIT_EXECUTABLE), expectedError);
    assert.throws(() => resolveCommit(repoRoot, overlong, TEST_GIT_EXECUTABLE), expectedError);
    assert.throws(
      () => findMergeBase(repoRoot, abbreviated, secondCommit, TEST_GIT_EXECUTABLE),
      expectedError
    );
    assert.throws(
      () => readTreeEntries(repoRoot, abbreviated, TEST_GIT_EXECUTABLE),
      expectedError
    );
    assert.throws(
      () => readBlob(repoRoot, abbreviated, "README.md", TEST_GIT_EXECUTABLE),
      expectedError
    );
    assert.throws(
      () => readChanges(repoRoot, abbreviated, secondCommit, TEST_GIT_EXECUTABLE),
      expectedError
    );

    git(repoRoot, [
      "-c",
      "user.name=Docs Impact Test",
      "-c",
      "user.email=docs-impact@example.invalid",
      "tag",
      "--annotate",
      "v1",
      "--message",
      "annotated tag",
      secondCommit
    ]);
    const tagObjectId = git(repoRoot, ["rev-parse", "refs/tags/v1"]);
    assert.equal(tagObjectId.length, expectedLength);
    assert.throws(
      () => resolveCommit(repoRoot, tagObjectId, TEST_GIT_EXECUTABLE),
      /must identify commit objects, not tag/u
    );
  });
}

test("rejects shallow repositories before analysis", (t) => {
  const { repoRoot: sourceRoot } = createRepository(t, "sha1");
  commitFile(sourceRoot, "README.md", "second\n", "second");

  const cloneParent = mkdtempSync(path.join(tmpdir(), "docs-impact-shallow-"));
  t.after(() => rmSync(cloneParent, { recursive: true, force: true }));
  const cloneRoot = path.join(cloneParent, "clone");
  git(cloneParent, [
    "clone",
    "--quiet",
    "--depth=1",
    pathToFileURL(sourceRoot).href,
    cloneRoot
  ]);

  assert.equal(git(cloneRoot, ["rev-parse", "--is-shallow-repository"]), "true");
  assert.throws(
    () => assertRepositoryRoot(cloneRoot, TEST_GIT_EXECUTABLE),
    /Shallow repositories are not supported/u
  );
});

test("requires a trusted absolute Git executable outside the analyzed repository", (t) => {
  const { repoRoot } = createRepository(t, "sha1");
  const fakeName = process.platform === "win32" ? "git.exe" : "git";
  const fakeGit = path.join(repoRoot, fakeName);
  writeFileSync(fakeGit, "candidate executable must never run\n", "utf8");
  if (process.platform !== "win32") {
    chmodSync(fakeGit, 0o755);
  }

  assert.throws(
    () => assertRepositoryRoot(repoRoot, "git"),
    /explicit absolute path/u
  );
  assert.throws(
    () => assertRepositoryRoot(repoRoot, fakeGit),
    /outside the analyzed repository/u
  );
  assert.equal(assertRepositoryRoot(repoRoot, TEST_GIT_EXECUTABLE), path.resolve(repoRoot));
});

test("rejects a loose object whose bytes do not match its object ID", (t) => {
  const { firstCommit, repoRoot } = createRepository(t, "sha1");
  const blobId = git(repoRoot, ["rev-parse", `${firstCommit}:README.md`]);
  const looseObjectPath = path.join(
    repoRoot,
    ".git",
    "objects",
    blobId.slice(0, 2),
    blobId.slice(2)
  );
  const objectBytes = inflateSync(readFileSync(looseObjectPath));
  const separator = objectBytes.indexOf(0);
  assert.ok(separator > 0);
  assert.equal(objectBytes.subarray(separator + 1).toString("utf8"), "first\n");
  Buffer.from("forge\n").copy(objectBytes, separator + 1);
  chmodSync(looseObjectPath, 0o644);
  writeFileSync(looseObjectPath, deflateSync(objectBytes));

  assert.equal(
    readBlob(repoRoot, firstCommit, "README.md", TEST_GIT_EXECUTABLE).toString("utf8"),
    "forge\n"
  );
  assert.throws(
    () => assertObjectGraphIntegrity(repoRoot, [firstCommit], TEST_GIT_EXECUTABLE),
    /git fsck failed|hash-path mismatch/u
  );
});
