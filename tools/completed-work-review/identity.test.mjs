import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { execFile } from "node:child_process";
import {
  access,
  chmod,
  copyFile,
  mkdir,
  mkdtemp,
  readFile,
  rm,
  stat,
  symlink,
  utimes,
  writeFile
} from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { pathToFileURL } from "node:url";
import { promisify } from "node:util";
import { spawn } from "node:child_process";
import test from "node:test";

import {
  candidateSnapshotDigest,
  captureCandidateIdentity,
  deterministicPatchArguments,
  identityId,
  verifyCandidateIdentity
} from "./identity.mjs";
import {
  gitEnvironment as hardenedGitEnvironment,
  gitHardeningArguments,
  runGitSync
} from "./git-command.mjs";
import { assertContract } from "./profile.mjs";

const execFileAsync = promisify(execFile);
const REMOTE = Object.freeze({
  name: "origin",
  url: "https://example.invalid/edenred/pockethive.git"
});
const REPOSITORY_ID = "edenred/PocketHive";
const CAPTURED_AT = "2026-08-17T12:00:00.000Z";

async function firstExistingAbsolutePath(candidates) {
  for (const candidate of candidates) {
    if (!candidate || !path.isAbsolute(candidate)) continue;
    try {
      await access(candidate);
      return candidate;
    } catch {
      // The next explicit platform path is checked.
    }
  }
  throw new Error("Set GIT_TEST_EXECUTABLE to an explicit absolute Git executable path");
}

const GIT_EXECUTABLE = await firstExistingAbsolutePath([
  process.env.GIT_TEST_EXECUTABLE,
  "C:\\Program Files\\Git\\mingw64\\bin\\git.exe",
  "/usr/bin/git",
  "/opt/homebrew/bin/git"
]);
const GIT_EXECUTABLE_SHA256 = sha256(await readFile(GIT_EXECUTABLE));

function gitEnvironment() {
  return {
    ...process.env,
    GIT_CONFIG_NOSYSTEM: "1",
    GIT_CONFIG_GLOBAL: process.platform === "win32" ? "NUL" : "/dev/null",
    GIT_TERMINAL_PROMPT: "0",
    LANG: "C",
    LC_ALL: "C"
  };
}

async function git(repositoryRoot, args, options = {}) {
  const result = await execFileAsync(GIT_EXECUTABLE, args, {
    cwd: repositoryRoot,
    encoding: options.encoding ?? "utf8",
    env: gitEnvironment(),
    maxBuffer: 64 * 1024 * 1024,
    windowsHide: true
  });
  return result.stdout;
}

async function makeRepository(t, { objectFormat = "sha1" } = {}) {
  const temporaryRoot = await mkdtemp(path.join(os.tmpdir(), "pockethive-identity-test-"));
  const repositoryRoot = path.join(temporaryRoot, "repository");
  t.after(async () => {
    const resolved = path.resolve(temporaryRoot);
    assert.equal(path.dirname(resolved), path.resolve(os.tmpdir()));
    await rm(resolved, { force: true, recursive: true });
  });
  const initArgs = ["init", "--initial-branch=main"];
  if (objectFormat === "sha256") initArgs.push("--object-format=sha256");
  initArgs.push(repositoryRoot);
  await git(temporaryRoot, initArgs);
  await git(repositoryRoot, ["config", "--local", "user.name", "Identity Test"]);
  await git(repositoryRoot, ["config", "--local", "user.email", "identity@example.invalid"]);
  await git(repositoryRoot, ["remote", "add", REMOTE.name, REMOTE.url]);
  await writeFile(path.join(repositoryRoot, "tracked.txt"), "base\n", "utf8");
  await git(repositoryRoot, ["add", "--", "tracked.txt"]);
  await git(repositoryRoot, ["commit", "-m", "base"]);
  const baseCommit = (await git(repositoryRoot, ["rev-parse", "HEAD"])).trim();
  await writeFile(path.join(repositoryRoot, "tracked.txt"), "candidate\n", "utf8");
  await git(repositoryRoot, ["add", "--", "tracked.txt"]);
  await git(repositoryRoot, ["commit", "-m", "candidate"]);
  const candidateCommit = (await git(repositoryRoot, ["rev-parse", "HEAD"])).trim();
  const candidateTree = (await git(repositoryRoot, ["rev-parse", "HEAD^{tree}"])).trim();
  return { repositoryRoot, baseCommit, candidateCommit, candidateTree, temporaryRoot };
}

function captureArguments(repository, overrides = {}) {
  return {
    repositoryRoot: repository.repositoryRoot,
    gitExecutablePath: GIT_EXECUTABLE,
    expectedGitExecutableSha256: GIT_EXECUTABLE_SHA256,
    repositoryId: REPOSITORY_ID,
    repositoryRemote: REMOTE,
    mode: "COMMITTED_GIT",
    baseCommit: repository.baseCommit,
    candidateCommit: repository.candidateCommit,
    capturedAt: CAPTURED_AT,
    ...overrides
  };
}

function verificationArguments(repository, identity, overrides = {}) {
  return {
    identity,
    repositoryRoot: repository.repositoryRoot,
    gitExecutablePath: GIT_EXECUTABLE,
    expectedGitExecutableSha256: GIT_EXECUTABLE_SHA256,
    repositoryId: REPOSITORY_ID,
    repositoryRemote: REMOTE,
    requireWorktreeMatch: false,
    ...overrides
  };
}

function reseal(identity) {
  identity.candidateSnapshotDigest = candidateSnapshotDigest(identity);
  identity.identityId = identityId(identity);
  return identity;
}

function tampered(identity, mutate) {
  const clone = structuredClone(identity);
  mutate(clone);
  return reseal(clone);
}

function sha256(bytes) {
  return createHash("sha256").update(bytes).digest("hex");
}

async function replaceSingleSha1IndexObjectId(repositoryRoot, replacementObjectId) {
  assert.match(replacementObjectId, /^[a-f0-9]{40}$/u);
  const indexPath = path.join(repositoryRoot, ".git", "index");
  const indexBytes = await readFile(indexPath);
  assert.equal(indexBytes.subarray(0, 4).toString("ascii"), "DIRC");
  assert.ok([2, 3].includes(indexBytes.readUInt32BE(4)));
  assert.equal(indexBytes.readUInt32BE(8), 1);
  const objectIdOffset = 12 + 40;
  Buffer.from(replacementObjectId, "hex").copy(indexBytes, objectIdOffset);
  const checksumOffset = indexBytes.byteLength - 20;
  createHash("sha1")
    .update(indexBytes.subarray(0, checksumOffset))
    .digest()
    .copy(indexBytes, checksumOffset);
  await writeFile(indexPath, indexBytes);
}

test("hardened Git environment disables lazy fetch and replacement objects", () => {
  const injected = {
    GIT_CONFIG_COUNT: process.env.GIT_CONFIG_COUNT,
    GIT_NO_LAZY_FETCH: process.env.GIT_NO_LAZY_FETCH,
    GIT_NO_REPLACE_OBJECTS: process.env.GIT_NO_REPLACE_OBJECTS
  };
  try {
    process.env.GIT_CONFIG_COUNT = "1";
    process.env.GIT_NO_LAZY_FETCH = "0";
    process.env.GIT_NO_REPLACE_OBJECTS = "0";
    const environment = hardenedGitEnvironment();
    const argumentsList = gitHardeningArguments({ literalPathspecs: true });
    assert.equal(environment.GIT_NO_LAZY_FETCH, "1");
    assert.equal(environment.GIT_NO_REPLACE_OBJECTS, "1");
    assert.equal(Object.hasOwn(environment, "GIT_CONFIG_COUNT"), false);
    assert.ok(argumentsList.includes("--literal-pathspecs"));
    assert.ok(argumentsList.includes("core.fsmonitor=false"));
    assert.ok(argumentsList.includes("core.ignoreStat=false"));
    assert.ok(argumentsList.includes("core.ignoreCase=false"));
    assert.ok(argumentsList.some((value) => value.startsWith("core.fileMode=")));
    assert.ok(argumentsList.includes("core.trustctime=true"));
    assert.ok(argumentsList.includes("core.checkStat=default"));
    assert.ok(argumentsList.includes("core.untrackedCache=false"));
    assert.ok(argumentsList.some((value) => value.startsWith("core.excludesFile=")));
    assert.ok(argumentsList.some((value) => value.startsWith("fsck.skipList=")));
  } finally {
    for (const [key, value] of Object.entries(injected)) {
      if (value === undefined) delete process.env[key];
      else process.env[key] = value;
    }
  }
});

test("capture requires explicit repository, remote, and absolute Git adapter settings", async (t) => {
  const repository = await makeRepository(t);
  await assert.rejects(
    captureCandidateIdentity(captureArguments(repository, { gitExecutablePath: "git" })),
    /explicit absolute path/u
  );
  await assert.rejects(
    captureCandidateIdentity(captureArguments(repository, {
      expectedGitExecutableSha256: undefined
    })),
    /explicit lowercase SHA-256 digest/u
  );
  await assert.rejects(
    captureCandidateIdentity(captureArguments(repository, {
      expectedGitExecutableSha256: "f".repeat(64)
    })),
    /does not match the externally expected SHA-256 digest/u
  );
  await assert.rejects(
    captureCandidateIdentity(captureArguments(repository, { repositoryId: undefined })),
    /repositoryId must be an explicit non-empty string/u
  );
  await assert.rejects(
    captureCandidateIdentity(captureArguments(repository, {
      repositoryRemote: { name: REMOTE.name, url: "https://example.invalid/wrong.git" }
    })),
    /does not exactly match/u
  );
  await assert.rejects(
    captureCandidateIdentity(captureArguments(repository, { repositoryRoot: path.dirname(repository.repositoryRoot) })),
    /exact Git worktree root|not a git repository/u
  );
});

test("a fake core-layout executable is rejected by the external Git digest before invocation", async (t) => {
  if (process.platform !== "win32") {
    t.skip("The Git-for-Windows fake-layout regression is Windows-specific");
    return;
  }
  const shimPath = "C:\\Program Files\\Git\\bin\\git.exe";
  try {
    await access(shimPath);
  } catch {
    t.skip(`Git-for-Windows delegating shim is unavailable at ${shimPath}`);
    return;
  }
  const repository = await makeRepository(t);
  const fakeDirectory = path.join(repository.temporaryRoot, "fake", "mingw64", "bin");
  const fakeExecutable = path.join(fakeDirectory, "git.exe");
  await mkdir(fakeDirectory, { recursive: true });
  await copyFile(shimPath, fakeExecutable);
  assert.notEqual(sha256(await readFile(fakeExecutable)), GIT_EXECUTABLE_SHA256);
  await assert.rejects(
    captureCandidateIdentity(captureArguments(repository, {
      gitExecutablePath: fakeExecutable
    })),
    /does not match the externally expected SHA-256 digest/u
  );
});

test("Git-for-Windows delegating shims are rejected instead of being recorded as the core executable", async (t) => {
  if (process.platform !== "win32") {
    t.skip("Git-for-Windows delegating shims are Windows-specific");
    return;
  }
  const repository = await makeRepository(t);
  const shimPaths = [
    "C:\\Program Files\\Git\\bin\\git.exe",
    "C:\\Program Files\\Git\\cmd\\git.exe"
  ];
  let checked = 0;
  for (const shimPath of shimPaths) {
    try {
      await access(shimPath);
    } catch {
      continue;
    }
    checked += 1;
    await assert.rejects(
      captureCandidateIdentity(captureArguments(repository, { gitExecutablePath: shimPath })),
      /Git-for-Windows mingw32\/bin or mingw64\/bin core executable/u
    );
  }
  if (checked === 0) {
    t.skip(`Git-for-Windows delegating shims are unavailable: ${shimPaths.join(", ")}`);
  }
});

test("tracked assume-unchanged and skip-worktree index flags are rejected", async (t) => {
  const assumeUnchangedRepository = await makeRepository(t);
  await git(assumeUnchangedRepository.repositoryRoot, [
    "update-index",
    "--assume-unchanged",
    "--",
    "tracked.txt"
  ]);
  await assert.rejects(
    captureCandidateIdentity(captureArguments(assumeUnchangedRepository)),
    /forbidden assume-unchanged index flag/u
  );

  const skipWorktreeRepository = await makeRepository(t);
  await git(skipWorktreeRepository.repositoryRoot, [
    "update-index",
    "--skip-worktree",
    "--",
    "tracked.txt"
  ]);
  await assert.rejects(
    captureCandidateIdentity(captureArguments(skipWorktreeRepository)),
    /forbidden skip-worktree index flag/u
  );
});

test("repository-configured fsmonitor commands are rejected before identity capture", async (t) => {
  const repository = await makeRepository(t);
  const markerPath = path.join(repository.repositoryRoot, "fsmonitor-invoked.txt");
  const hookPath = path.join(repository.repositoryRoot, ".git", "fsmonitor-hook.sh");
  await writeFile(
    hookPath,
    "#!/bin/sh\nprintf 'invoked' > fsmonitor-invoked.txt\nprintf '\\n'\n",
    "utf8"
  );
  await chmod(hookPath, 0o755);
  await git(repository.repositoryRoot, ["config", "--local", "core.fsmonitor", ".git/fsmonitor-hook.sh"]);

  await git(repository.repositoryRoot, ["status", "--short"]);
  await access(markerPath);
  await rm(markerPath, { force: true });

  runGitSync({
    repositoryRoot: repository.repositoryRoot,
    gitExecutable: GIT_EXECUTABLE,
    argumentsList: ["status", "--short"],
    literalPathspecs: false,
    label: "Hardened synchronous Git fsmonitor test"
  });
  await assert.rejects(access(markerPath), (error) => error?.code === "ENOENT");

  await assert.rejects(
    captureCandidateIdentity(captureArguments(repository)),
    /Unsupported repository-local Git configuration key: core\.fsmonitor/u,
  );
  await assert.rejects(access(markerPath), (error) => error?.code === "ENOENT");
});

test("repository Git filters are rejected before candidate code can execute", async (t) => {
  const repository = await makeRepository(t);
  const markerPath = path.join(repository.repositoryRoot, "filter-invoked.txt");
  await writeFile(
    path.join(repository.repositoryRoot, "filter.cjs"),
    "require('node:fs').writeFileSync('filter-invoked.txt','INVOKED');process.stdin.pipe(process.stdout);\n",
    "utf8",
  );
  await writeFile(
    path.join(repository.repositoryRoot, ".gitattributes"),
    "tracked.txt filter=evil\n",
    "utf8",
  );
  await writeFile(path.join(repository.repositoryRoot, "tracked.txt"), "dirty\n", "utf8");
  await git(repository.repositoryRoot, [
    "config",
    "--local",
    "filter.evil.clean",
    "node filter.cjs",
  ]);
  await assert.rejects(
    captureCandidateIdentity(captureArguments(repository, {
      mode: "DIRTY_WORKTREE",
      baseCommit: repository.candidateCommit,
      candidateCommit: undefined,
    })),
    /Unsupported repository-local Git configuration key: filter\.evil\.clean/u,
  );
  await assert.rejects(access(markerPath), (error) => error?.code === "ENOENT");
});

test("repository config includes are rejected before included commands can execute", async (t) => {
  const repository = await makeRepository(t);
  const markerPath = path.join(repository.repositoryRoot, "included-filter-invoked.txt");
  const includedConfig = path.join(repository.temporaryRoot, "included.gitconfig");
  await writeFile(
    includedConfig,
    "[filter \"evil\"]\n\tclean = node filter.cjs\n",
    "utf8",
  );
  await writeFile(
    path.join(repository.repositoryRoot, "filter.cjs"),
    "require('node:fs').writeFileSync('included-filter-invoked.txt','INVOKED');process.stdin.pipe(process.stdout);\n",
    "utf8",
  );
  await writeFile(
    path.join(repository.repositoryRoot, ".gitattributes"),
    "tracked.txt filter=evil\n",
    "utf8",
  );
  await writeFile(path.join(repository.repositoryRoot, "tracked.txt"), "dirty\n", "utf8");
  await git(repository.repositoryRoot, ["config", "--local", "include.path", includedConfig]);
  await assert.rejects(
    captureCandidateIdentity(captureArguments(repository, {
      mode: "DIRTY_WORKTREE",
      baseCommit: repository.candidateCommit,
      candidateCommit: undefined,
    })),
    /Unsupported repository-local Git configuration key: include\.path/u,
  );
  await assert.rejects(access(markerPath), (error) => error?.code === "ENOENT");
});

test("unbound info attributes and object alternates are rejected", async (t) => {
  for (const [repositoryPath, contents, expected] of [
    [".git/info/attributes", "*.txt filter=evil\n", /Git info attributes are unsupported/u],
    [".git/objects/info/alternates", "C:/untrusted-objects\n", /Git object alternates are unsupported/u],
  ]) {
    const repository = await makeRepository(t);
    await writeFile(path.join(repository.repositoryRoot, repositoryPath), contents, "utf8");
    await assert.rejects(captureCandidateIdentity(captureArguments(repository)), expected);
  }
});

test("info exclude cannot hide untracked candidate bytes", async (t) => {
  const repository = await makeRepository(t);
  await writeFile(path.join(repository.repositoryRoot, ".git", "info", "exclude"), "hidden.bin\n", "utf8");
  await writeFile(path.join(repository.repositoryRoot, "tracked.txt"), "dirty\n", "utf8");
  await writeFile(path.join(repository.repositoryRoot, "hidden.bin"), "bound despite info exclude", "utf8");
  const identity = await captureCandidateIdentity(captureArguments(repository, {
    mode: "DIRTY_WORKTREE",
    baseCommit: repository.candidateCommit,
    candidateCommit: undefined,
  }));
  assert.ok(identity.untrackedFiles.some(({ path: repositoryPath }) => repositoryPath === "hidden.bin"));
});

test("tracked worktree-conversion attributes are rejected before patch capture", async (t) => {
  const repository = await makeRepository(t);
  await writeFile(
    path.join(repository.repositoryRoot, ".gitattributes"),
    "tracked.txt text eol=lf\n",
    "utf8",
  );
  await writeFile(path.join(repository.repositoryRoot, "tracked.txt"), "dirty\r\n", "utf8");
  await assert.rejects(
    captureCandidateIdentity(captureArguments(repository, {
      mode: "DIRTY_WORKTREE",
      baseCommit: repository.candidateCommit,
      candidateCommit: undefined,
    })),
    /uses unsupported worktree-conversion attribute/u,
  );
});

test("tracked symbolic-link index entries are rejected without filesystem symlink privileges", async (t) => {
  const repository = await makeRepository(t);
  const linkTarget = path.join(repository.repositoryRoot, "link-target.txt");
  await writeFile(linkTarget, "target\n", "utf8");
  const blobId = (await git(repository.repositoryRoot, ["hash-object", "-w", "--", linkTarget])).trim();
  await git(repository.repositoryRoot, [
    "update-index",
    "--add",
    "--cacheinfo",
    `120000,${blobId},tracked-link`,
  ]);
  await assert.rejects(
    captureCandidateIdentity(captureArguments(repository, {
      mode: "DIRTY_WORKTREE",
      baseCommit: repository.candidateCommit,
      candidateCommit: undefined,
    })),
    /does not support tracked symbolic links/u,
  );
});

test("hardened Git stat policy exposes same-size edits with restored timestamps", async (t) => {
  const repository = await makeRepository(t);
  const trackedPath = path.join(repository.repositoryRoot, "tracked.txt");
  await git(repository.repositoryRoot, ["config", "--local", "core.trustctime", "false"]);
  await git(repository.repositoryRoot, ["config", "--local", "core.checkStat", "minimal"]);
  await git(repository.repositoryRoot, ["status", "--short"]);
  const cached = await stat(trackedPath);
  await writeFile(trackedPath, "evil\n", "utf8");
  await utimes(trackedPath, cached.atime, cached.mtime);
  const diff = runGitSync({
    repositoryRoot: repository.repositoryRoot,
    gitExecutable: GIT_EXECUTABLE,
    argumentsList: [
      "diff",
      "--binary",
      "--no-ext-diff",
      "--no-textconv",
      repository.candidateCommit,
      "--",
    ],
    literalPathspecs: true,
    label: "Hardened same-size tracked-edit capture",
  }).stdout;
  assert.notEqual(diff.length, 0);
  await assert.rejects(
    captureCandidateIdentity(captureArguments(repository, {
      mode: "DIRTY_WORKTREE",
      baseCommit: repository.candidateCommit,
      candidateCommit: undefined,
    })),
    /Unsupported repository-local Git configuration key: core\.(checkstat|trustctime)/u,
  );
});

test("content-verified patch capture defeats a forged matching index stat cache", async (t) => {
  const repository = await makeRepository(t);
  const trackedPath = path.join(repository.repositoryRoot, "tracked.txt");
  const candidateBlob = (await git(repository.repositoryRoot, [
    "rev-parse",
    `${repository.candidateCommit}:tracked.txt`,
  ])).trim();
  await writeFile(trackedPath, "malicious\n", "utf8");
  const oldTimestamp = new Date("2020-01-01T00:00:00.000Z");
  await utimes(trackedPath, oldTimestamp, oldTimestamp);
  await git(repository.repositoryRoot, ["add", "--", "tracked.txt"]);
  await replaceSingleSha1IndexObjectId(repository.repositoryRoot, candidateBlob);

  const hiddenPatch = runGitSync({
    repositoryRoot: repository.repositoryRoot,
    gitExecutable: GIT_EXECUTABLE,
    argumentsList: deterministicPatchArguments({ baseCommit: repository.candidateCommit }),
    literalPathspecs: true,
    label: "Forged-index control diff",
  }).stdout;
  assert.equal(hiddenPatch.length, 0);
  assert.match(await git(repository.repositoryRoot, ["ls-files", "-v", "--", "tracked.txt"]), /^H /u);

  const committedIdentity = await captureCandidateIdentity(captureArguments(repository));
  await assert.rejects(
    verifyCandidateIdentity(verificationArguments(repository, committedIdentity, {
      requireWorktreeMatch: true,
    })),
    /non-ignored live worktree .*exactly match candidateCommit/u,
  );

  await writeFile(path.join(repository.repositoryRoot, "visible-untracked.txt"), "visible\n", "utf8");
  const dirtyIdentity = await captureCandidateIdentity(captureArguments(repository, {
    mode: "DIRTY_WORKTREE",
    baseCommit: repository.candidateCommit,
    candidateCommit: undefined,
  }));
  assert.notEqual(dirtyIdentity.trackedPatchDigest, sha256(Buffer.alloc(0)));
  assert.deepEqual(dirtyIdentity.untrackedFiles.map(({ path: repositoryPath }) => repositoryPath), [
    "visible-untracked.txt",
  ]);
});

test("repository fsck suppressions are rejected before strict object verification", async (t) => {
  const repository = await makeRepository(t);
  await git(repository.repositoryRoot, ["config", "--local", "fsck.missingEmail", "ignore"]);
  await assert.rejects(
    captureCandidateIdentity(captureArguments(repository)),
    /Unsupported repository-local Git configuration key: fsck\.missingemail/u,
  );
});

test("shallow repositories are rejected for exact object-integrity capture", async (t) => {
  const source = await makeRepository(t);
  const shallowRoot = path.join(source.temporaryRoot, "shallow-clone");
  await git(source.temporaryRoot, [
    "clone",
    "--depth",
    "1",
    "--no-local",
    pathToFileURL(source.repositoryRoot).href,
    shallowRoot,
  ]);
  const candidateCommit = (await git(shallowRoot, ["rev-parse", "HEAD"])).trim();
  const remoteUrl = (await git(shallowRoot, ["remote", "get-url", "origin"])).trim();
  await assert.rejects(
    captureCandidateIdentity({
      ...captureArguments(source),
      repositoryRoot: shallowRoot,
      repositoryRemote: { name: "origin", url: remoteUrl },
      baseCommit: candidateCommit,
      candidateCommit,
    }),
    /Shallow Git repositories are not supported/u,
  );
});

test("promised but locally missing reachable objects are rejected without lazy fetch", async (t) => {
  const source = await makeRepository(t);
  await git(source.repositoryRoot, ["config", "--local", "uploadpack.allowFilter", "true"]);
  const partialRoot = path.join(source.temporaryRoot, "partial-clone");
  await git(source.temporaryRoot, [
    "clone",
    "--filter=blob:none",
    "--no-checkout",
    "--no-local",
    pathToFileURL(source.repositoryRoot).href,
    partialRoot,
  ]);
  for (const key of [
    "extensions.partialClone",
    "remote.origin.partialclonefilter",
    "remote.origin.promisor",
  ]) {
    await git(partialRoot, ["config", "--local", "--unset-all", key]).catch(() => undefined);
  }
  const candidateCommit = (await git(partialRoot, ["rev-parse", "HEAD"])).trim();
  const remoteUrl = (await git(partialRoot, ["remote", "get-url", "origin"])).trim();
  await assert.rejects(
    captureCandidateIdentity({
      ...captureArguments(source),
      repositoryRoot: partialRoot,
      repositoryRemote: { name: "origin", url: remoteUrl },
      baseCommit: candidateCommit,
      candidateCommit,
    }),
    /promisor pack markers|object integrity verification|reachable Git object .*missing or invalid|availability verification/u,
  );
});

test("replacement refs cannot change the commit tree bound by identity capture", async (t) => {
  const repository = await makeRepository(t);
  await writeFile(path.join(repository.repositoryRoot, "tracked.txt"), "replacement\n", "utf8");
  await git(repository.repositoryRoot, ["add", "--", "tracked.txt"]);
  await git(repository.repositoryRoot, ["commit", "-m", "replacement"]);
  const replacementCommit = (await git(repository.repositoryRoot, ["rev-parse", "HEAD"])).trim();
  await git(repository.repositoryRoot, ["replace", repository.candidateCommit, replacementCommit]);

  const identity = await captureCandidateIdentity(captureArguments(repository));
  assert.equal(identity.candidateCommit, repository.candidateCommit);
  assert.equal(identity.candidateGitTree, repository.candidateTree);
  await verifyCandidateIdentity(verificationArguments(repository, identity));
});

test("reachable object bytes are verified independently with bounded Git fsck", async (t) => {
  const repository = await makeRepository(t);
  const blobId = (await git(repository.repositoryRoot, [
    "rev-parse",
    `${repository.candidateCommit}:tracked.txt`
  ])).trim();
  const objectPath = path.join(
    repository.repositoryRoot,
    ".git",
    "objects",
    blobId.slice(0, 2),
    blobId.slice(2)
  );
  await access(objectPath);
  const objectBytes = await readFile(objectPath);
  const corrupted = Buffer.from(objectBytes);
  corrupted[Math.floor(corrupted.length / 2)] ^= 0xff;
  await chmod(objectPath, 0o600);
  await writeFile(objectPath, corrupted);

  await assert.rejects(
    captureCandidateIdentity(captureArguments(repository)),
    /Git object integrity verification.*(?:failed|missing or invalid)/u
  );
});

test("COMMITTED_GIT binds exact commits, tree, merge base, remote, and executable even with a dirty worktree", async (t) => {
  const repository = await makeRepository(t);
  await writeFile(path.join(repository.repositoryRoot, "tracked.txt"), "left dirty on purpose\n", "utf8");
  const identity = await captureCandidateIdentity(captureArguments(repository));

  assert.equal(identity.schemaVersion, 2);
  assert.equal(identity.gitObjectFormat, "sha1");
  assert.equal(identity.baseCommit, repository.baseCommit);
  assert.equal(identity.mergeBaseCommit, repository.baseCommit);
  assert.equal(identity.candidateCommit, repository.candidateCommit);
  assert.equal(identity.candidateGitTree, repository.candidateTree);
  assert.equal(identity.dirty, false);
  assert.deepEqual(identity.repositoryRemote, REMOTE);
  assert.equal(path.isAbsolute(identity.gitExecutable.path), true);
  assert(identity.gitExecutable.sizeBytes > 0);
  await verifyCandidateIdentity(verificationArguments(repository, identity));
  await assert.rejects(
    verifyCandidateIdentity(verificationArguments(repository, identity, {
      requireWorktreeMatch: true
    })),
    /COMMITTED_GIT candidate role requires HEAD and the non-ignored live worktree/u
  );

  const fabricatedCommit = "f".repeat(40);
  await assert.rejects(
    verifyCandidateIdentity(verificationArguments(repository, tampered(identity, (value) => {
      value.candidateCommit = fabricatedCommit;
    }))),
    /Candidate commit type verification failed/u
  );
  await assert.rejects(
    verifyCandidateIdentity(verificationArguments(repository, tampered(identity, (value) => {
      value.baseCommit = fabricatedCommit;
    }))),
    /Base commit type verification failed/u
  );
  await assert.rejects(
    verifyCandidateIdentity(verificationArguments(repository, tampered(identity, (value) => {
      value.mergeBaseCommit = fabricatedCommit;
    }))),
    /does not exactly match the live Git repository state/u
  );
  await assert.rejects(
    verifyCandidateIdentity(verificationArguments(repository, tampered(identity, (value) => {
      value.candidateGitTree = fabricatedCommit;
    }))),
    /does not exactly match the live Git repository state/u
  );
  await assert.rejects(
    verifyCandidateIdentity(verificationArguments(repository, tampered(identity, (value) => {
      value.repositoryRemote.url = "https://example.invalid/fabricated.git";
    }))),
    /remote does not match/u
  );
  await assert.rejects(
    verifyCandidateIdentity(verificationArguments(repository, tampered(identity, (value) => {
      value.gitExecutable.sha256 = "f".repeat(64);
    }))),
    /does not exactly match the live Git repository state/u
  );

  await git(repository.repositoryRoot, ["remote", "set-url", REMOTE.name, "https://example.invalid/changed.git"]);
  await assert.rejects(
    verifyCandidateIdentity(verificationArguments(repository, identity)),
    /does not exactly match the declared URL/u
  );
});

test("DIRTY_WORKTREE binds exact binary patch and every non-ignored untracked regular file", async (t) => {
  const repository = await makeRepository(t);
  const trackedBytes = Buffer.from("dirty tracked\n\0binary\n", "utf8");
  const untrackedBytes = Buffer.from([0, 1, 2, 127, 128, 254, 255]);
  await writeFile(path.join(repository.repositoryRoot, "tracked.txt"), trackedBytes);
  await writeFile(path.join(repository.repositoryRoot, "untracked.bin"), untrackedBytes);
  await writeFile(path.join(repository.repositoryRoot, "ignored.bin"), "ignored", "utf8");
  await writeFile(path.join(repository.repositoryRoot, ".gitignore"), "ignored.bin\n", "utf8");

  const args = captureArguments(repository, {
    mode: "DIRTY_WORKTREE",
    baseCommit: repository.candidateCommit,
    candidateCommit: undefined
  });
  const identity = await captureCandidateIdentity(args);
  const rawPatch = await git(repository.repositoryRoot, [
    ...gitHardeningArguments({ literalPathspecs: true }),
    ...deterministicPatchArguments({ baseCommit: repository.candidateCommit }),
  ], { encoding: "buffer" });

  assert.equal(identity.trackedPatchDigest, sha256(rawPatch));
  assert.deepEqual(identity.untrackedFiles.map(({ path: repositoryPath }) => repositoryPath), [
    ".gitignore",
    "untracked.bin"
  ]);
  assert.equal(identity.untrackedFiles[1].sha256, sha256(untrackedBytes));
  assert.equal(identity.untrackedFiles[1].sizeBytes, untrackedBytes.length);
  await verifyCandidateIdentity(verificationArguments(repository, identity));

  const fakePatch = tampered(identity, (value) => {
    value.trackedPatchDigest = "f".repeat(64);
  });
  await assert.rejects(
    verifyCandidateIdentity(verificationArguments(repository, fakePatch)),
    /does not exactly match the live Git repository state/u
  );

  await writeFile(path.join(repository.repositoryRoot, "tracked.txt"), "changed again\n", "utf8");
  await assert.rejects(
    verifyCandidateIdentity(verificationArguments(repository, identity)),
    /does not exactly match the live Git repository state/u
  );
  await writeFile(path.join(repository.repositoryRoot, "tracked.txt"), trackedBytes);
  await verifyCandidateIdentity(verificationArguments(repository, identity));

  await writeFile(path.join(repository.repositoryRoot, "untracked.bin"), Buffer.from([9, 8, 7]));
  await assert.rejects(
    verifyCandidateIdentity(verificationArguments(repository, identity)),
    /does not exactly match the live Git repository state/u
  );
});

test("DIRTY_WORKTREE rejects untracked symbolic links or junction-like entries", async (t) => {
  const repository = await makeRepository(t);
  const target = path.join(repository.temporaryRoot, "outside.txt");
  const link = path.join(repository.repositoryRoot, "untracked-link.txt");
  await writeFile(target, "outside\n", "utf8");
  try {
    await symlink(target, link, "file");
  } catch (error) {
    if (error.code === "EPERM" || error.code === "EACCES") {
      t.skip(`Symbolic-link creation is unavailable: ${error.code}`);
      return;
    }
    throw error;
  }
  await assert.rejects(
    captureCandidateIdentity(captureArguments(repository, {
      mode: "DIRTY_WORKTREE",
      baseCommit: repository.candidateCommit,
      candidateCommit: undefined
    })),
    /symbolic link or junction-like/u
  );
});

test("DIRTY_WORKTREE rejects concurrent mutation during its double capture", async (t) => {
  const repository = await makeRepository(t);
  const target = path.join(repository.repositoryRoot, "moving.bin");
  await writeFile(target, Buffer.alloc(8 * 1024 * 1024, 65));
  const script = [
    "const fs=require('node:fs');",
    "const p=process.argv[1];",
    "let counter=0;",
    "process.stdout.write('ready\\n');",
    "setInterval(()=>{",
    "const b=Buffer.alloc(8*1024*1024,65+(counter%26));",
    "b.writeBigUInt64LE(BigInt(counter),0);counter+=1;fs.writeFileSync(p,b);",
    "},0);"
  ].join("");
  const writer = spawn(process.execPath, ["-e", script, target], {
    stdio: ["ignore", "pipe", "pipe"],
    windowsHide: true
  });
  t.after(() => writer.kill("SIGKILL"));
  await new Promise((resolve, reject) => {
    writer.once("error", reject);
    writer.stdout.once("data", resolve);
  });
  await assert.rejects(
    captureCandidateIdentity(captureArguments(repository, {
      mode: "DIRTY_WORKTREE",
      baseCommit: repository.candidateCommit,
      candidateCommit: undefined
    })),
    /changed|regular file|capture/u
  );
  writer.kill("SIGKILL");
});

test("Git object IDs are exact and storage-format aware", async (t) => {
  const sha1Repository = await makeRepository(t);
  await assert.rejects(
    captureCandidateIdentity(captureArguments(sha1Repository, {
      baseCommit: sha1Repository.baseCommit.slice(0, 12)
    })),
    /full lowercase sha1 object ID/u
  );
  await assert.rejects(
    captureCandidateIdentity(captureArguments(sha1Repository, {
      baseCommit: "a".repeat(64)
    })),
    /full lowercase sha1 object ID/u
  );

  let sha256Repository;
  try {
    sha256Repository = await makeRepository(t, { objectFormat: "sha256" });
  } catch (error) {
    if (/object-format|unknown option|unsupported/u.test(error.message)) {
      t.skip(`Installed Git cannot create SHA-256 repositories: ${error.message}`);
      return;
    }
    throw error;
  }
  const identity = await captureCandidateIdentity(captureArguments(sha256Repository));
  assert.equal(identity.gitObjectFormat, "sha256");
  assert.equal(identity.baseCommit.length, 64);
  assert.equal(identity.candidateCommit.length, 64);
  assert.equal(identity.candidateGitTree.length, 64);
  await verifyCandidateIdentity(verificationArguments(sha256Repository, identity, {
    requireWorktreeMatch: true
  }));
});

test("candidate identity v2 schema is closed and rejects legacy or fabricated adapter fields", async (t) => {
  const repository = await makeRepository(t);
  const identity = await captureCandidateIdentity(captureArguments(repository));
  const schema = JSON.parse(await readFile(
    new URL("./contracts/candidate-identity.schema.json", import.meta.url),
    "utf8"
  ));
  assert.doesNotThrow(() => assertContract(schema, identity, "captured identity"));
  assert.throws(
    () => assertContract(schema, { ...identity, schemaVersion: 1 }, "legacy identity"),
    /must equal 2/u
  );
  assert.throws(
    () => assertContract(schema, {
      ...identity,
      gitExecutable: { ...identity.gitExecutable, inferredFromPath: true }
    }, "fabricated identity"),
    /unexpected property inferredFromPath/u
  );
});
