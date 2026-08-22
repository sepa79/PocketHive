import { spawn } from "node:child_process";
import { createHash } from "node:crypto";
import { mkdtemp, readdir, rmdir, unlink } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { TextDecoder } from "node:util";

import { canonicalDigest, canonicalJson, sha256 } from "../docs-impact/canonical.mjs";
import { CANDIDATE_IDENTITY_MODE } from "./contracts/constants.mjs";
import {
  assertDirectPathSnapshot,
  assertPathAbsent,
  captureDirectDirectorySnapshot,
  captureDirectFileSnapshot,
  captureStableRegularFile,
  HARD_LINK_POLICY,
  isPathInside,
  resolveDirectDirectoryPath,
  sameFilesystemPath,
} from "./file-safety.mjs";
import { assertContract, CONTRACT_VALUES } from "./profile.mjs";
import {
  gitEnvironment,
  gitHardeningArguments,
  verifyGitExecutableAdapter,
} from "./git-command.mjs";

const UTF8_DECODER = new TextDecoder("utf-8", { fatal: true });
const EMPTY_BYTES_SHA256 = sha256(Buffer.alloc(0));
const GIT_COMMAND_TIMEOUT_MS = 120_000;
const MAX_GIT_OUTPUT_BYTES = CONTRACT_VALUES.limits.maxTrackedPatchBytes;
const GIT_INTEGRITY_TIMEOUT_MS = 120_000;
const MAX_GIT_INTEGRITY_OUTPUT_BYTES = CONTRACT_VALUES.limits.maxGitIntegrityBytes;
const TEMPORARY_INDEX_DIRECTORY_PREFIX = "pockethive-completed-work-index-";
const TEMPORARY_INDEX_FILE_NAME = "index";
const TRACKED_WORKTREE_ENTRY_KIND = Object.freeze({
  ABSENT: "ABSENT",
  FILE: "FILE",
});
const OBJECT_ID_LENGTH = Object.freeze({ sha1: 40, sha256: 64 });
const MODE = Object.freeze({
  COMMITTED: CANDIDATE_IDENTITY_MODE.COMMITTED_GIT,
  DIRTY: CANDIDATE_IDENTITY_MODE.DIRTY_WORKTREE,
});
const SAFE_LOCAL_GIT_CONFIG_KEY_PATTERNS = Object.freeze([
  /^branch\..+\.(merge|remote|vscode-merge-base)$/u,
  /^core\.(bare|filemode|ignorecase|logallrefupdates|precomposeunicode|repositoryformatversion|symlinks)$/u,
  /^extensions\.objectformat$/u,
  /^remote\..+\.(fetch|url)$/u,
  /^user\.(email|name)$/u,
]);
const WORKTREE_CONVERSION_ATTRIBUTES = Object.freeze([
  "binary",
  "crlf",
  "diff",
  "eol",
  "filter",
  "ident",
  "text",
  "working-tree-encoding",
]);

function withoutKey(value, key) {
  return Object.fromEntries(Object.entries(value).filter(([candidate]) => candidate !== key));
}

function compareText(left, right) {
  return left < right ? -1 : left > right ? 1 : 0;
}

function assertNonEmptyString(value, label) {
  if (typeof value !== "string" || value.length === 0) {
    throw new Error(`${label} must be an explicit non-empty string`);
  }
  if (/[\u0000-\u001f\u007f]/u.test(value)) {
    throw new Error(`${label} must not contain control characters`);
  }
  return value;
}

function assertAbsolutePath(value, label) {
  assertNonEmptyString(value, label);
  if (!path.isAbsolute(value)) {
    throw new Error(`${label} must be an explicit absolute path`);
  }
  return value;
}

async function captureBoundFile({ anchorPath, hardLinkPolicy, path: filePath, label, maxBytes }) {
  const bytes = await captureStableRegularFile({
    anchorPath,
    hardLinkPolicy,
    path: filePath,
    label,
    maxBytes,
  });
  return {
    bytes,
    sha256: sha256(bytes),
    sizeBytes: bytes.byteLength,
  };
}

function assertSameFileCapture(first, second, label) {
  if (
    first.sha256 !== second.sha256
    || first.sizeBytes !== second.sizeBytes
    || !first.bytes.equals(second.bytes)
  ) {
    throw new Error(`${label} changed during the double capture`);
  }
}

async function runGit({
  executablePath,
  repositoryRoot,
  args,
  label,
  maxOutputBytes = MAX_GIT_OUTPUT_BYTES,
  timeoutMs = GIT_COMMAND_TIMEOUT_MS,
  stdinBytes = null,
  temporaryIndexPath = null,
}) {
  if (!Number.isSafeInteger(maxOutputBytes) || maxOutputBytes < 1) {
    throw new Error(`${label} requires an explicit positive Git output limit`);
  }
  if (!Number.isSafeInteger(timeoutMs) || timeoutMs < 1) {
    throw new Error(`${label} requires an explicit positive Git timeout`);
  }
  if (stdinBytes !== null && (!Buffer.isBuffer(stdinBytes)
    || stdinBytes.byteLength > CONTRACT_VALUES.limits.maxTrackedPatchBytes)) {
    throw new Error(`${label} Git input must be an explicit bounded Buffer or null`);
  }
  if (temporaryIndexPath !== null) {
    assertAbsolutePath(temporaryIndexPath, `${label} temporary Git index`);
    if (sameFilesystemPath(repositoryRoot, temporaryIndexPath)
      || isPathInside(repositoryRoot, temporaryIndexPath)) {
      throw new Error(`${label} temporary Git index must exist outside the repository`);
    }
  }
  const fullArgs = [
    ...gitHardeningArguments({ literalPathspecs: true }),
    ...args
  ];
  return new Promise((resolve, reject) => {
    const child = spawn(executablePath, fullArgs, {
      cwd: repositoryRoot,
      env: {
        ...gitEnvironment(),
        ...(temporaryIndexPath === null ? {} : { GIT_INDEX_FILE: temporaryIndexPath }),
      },
      shell: false,
      stdio: [stdinBytes === null ? "ignore" : "pipe", "pipe", "pipe"],
      windowsHide: true
    });
    const stdout = [];
    const stderr = [];
    let stdoutBytes = 0;
    let stderrBytes = 0;
    let settled = false;

    const finish = (error, value) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      if (error) reject(error);
      else resolve(value);
    };
    const capture = (chunks, kind) => (chunk) => {
      if (kind === "stdout") stdoutBytes += chunk.length;
      else stderrBytes += chunk.length;
      if (stdoutBytes + stderrBytes > maxOutputBytes) {
        child.kill("SIGKILL");
        finish(new Error(`${label} exceeded the explicit Git output limit`));
        return;
      }
      chunks.push(chunk);
    };

    child.stdout.on("data", capture(stdout, "stdout"));
    child.stderr.on("data", capture(stderr, "stderr"));
    if (stdinBytes !== null) {
      child.stdin.on("error", (error) => finish(new Error(`${label} input failed: ${error.message}`)));
      child.stdin.end(stdinBytes);
    }
    child.on("error", (error) => finish(new Error(`${label} could not start: ${error.message}`)));
    child.on("close", (code, signal) => {
      const stdoutBuffer = Buffer.concat(stdout);
      const stderrBuffer = Buffer.concat(stderr);
      if (code !== 0) {
        const detail = stderrBuffer.toString("utf8").trim();
        finish(new Error(
          `${label} failed with exit ${String(code)}${signal ? ` (signal ${signal})` : ""}`
          + `${detail ? `: ${detail}` : ""}`
        ));
        return;
      }
      finish(null, stdoutBuffer);
    });
    const timer = setTimeout(() => {
      child.kill("SIGKILL");
      finish(new Error(`${label} timed out after ${timeoutMs} ms`));
    }, timeoutMs);
    timer.unref();
  });
}

function decodeUtf8(bytes, label) {
  try {
    return UTF8_DECODER.decode(bytes);
  } catch {
    throw new Error(`${label} is not valid UTF-8`);
  }
}

function decodeSingleLine(bytes, label) {
  const decoded = decodeUtf8(bytes, label).replace(/\r?\n$/u, "");
  if (decoded.length === 0 || /[\r\n]/u.test(decoded)) {
    throw new Error(`${label} must contain exactly one non-empty line`);
  }
  return decoded;
}

function decodeNullList(bytes, label) {
  if (bytes.length === 0) return [];
  if (bytes.at(-1) !== 0) {
    throw new Error(`${label} must be NUL terminated`);
  }
  return bytes
    .subarray(0, -1)
    .toString("binary")
    .split("\0")
    .map((binary, index) => decodeUtf8(Buffer.from(binary, "binary"), `${label}[${index}]`));
}

function assertObjectId(value, objectFormat, label) {
  const length = OBJECT_ID_LENGTH[objectFormat];
  if (length === undefined) {
    throw new Error(`Unsupported Git object format: ${String(objectFormat)}`);
  }
  if (typeof value !== "string" || value.length !== length || !/^[a-f0-9]+$/u.test(value)) {
    throw new Error(`${label} must be a full lowercase ${objectFormat} object ID (${length} hexadecimal characters)`);
  }
  return value;
}

async function assertCommit(context, objectId, label) {
  assertObjectId(objectId, context.objectFormat, label);
  const objectType = decodeSingleLine(await runGit({
    ...context,
    args: ["cat-file", "-t", objectId],
    label: `${label} type verification`
  }), `${label} object type`);
  if (objectType !== "commit") {
    throw new Error(`${label} must identify a commit object; found ${objectType}`);
  }
  const resolved = decodeSingleLine(await runGit({
    ...context,
    args: ["rev-parse", "--verify", `${objectId}^{commit}`],
    label: `${label} resolution`
  }), `${label} resolution`);
  if (resolved !== objectId) {
    throw new Error(`${label} did not resolve to its exact commit object ID`);
  }
}

async function assertTree(context, objectId, label) {
  assertObjectId(objectId, context.objectFormat, label);
  const objectType = decodeSingleLine(await runGit({
    ...context,
    args: ["cat-file", "-t", objectId],
    label: `${label} type verification`
  }), `${label} object type`);
  if (objectType !== "tree") {
    throw new Error(`${label} must identify a tree object; found ${objectType}`);
  }
}

async function assertObjectIntegrity(context, objectIds, label) {
  if (!Array.isArray(objectIds) || objectIds.length === 0) {
    throw new Error(`${label} requires an explicit non-empty Git object set`);
  }
  const roots = [...new Set(objectIds)].sort(compareText);
  for (const [index, objectId] of roots.entries()) {
    assertObjectId(objectId, context.objectFormat, `${label}[${index}]`);
  }
  const reachableBytes = await runGit({
    ...context,
    args: [
      "rev-list",
      "--objects",
      "--missing=error",
      "--no-filter",
      "--no-object-names",
      ...roots,
      "--",
    ],
    label: `${label} reachable-object enumeration`,
    maxOutputBytes: MAX_GIT_INTEGRITY_OUTPUT_BYTES,
    timeoutMs: GIT_INTEGRITY_TIMEOUT_MS,
  });
  const reachableObjectIds = decodeSingleLineList(
    reachableBytes,
    `${label} reachable-object enumeration`,
  );
  if (reachableObjectIds.length === 0) {
    throw new Error(`${label} returned no reachable Git objects`);
  }
  if (reachableObjectIds.length > CONTRACT_VALUES.limits.maxGitReachableObjects) {
    throw new Error(
      `${label} exceeds the explicit `
      + `${CONTRACT_VALUES.limits.maxGitReachableObjects}-object reachability limit`,
    );
  }
  if (new Set(reachableObjectIds).size !== reachableObjectIds.length) {
    throw new Error(`${label} returned duplicate reachable Git object IDs`);
  }
  for (const [index, objectId] of reachableObjectIds.entries()) {
    assertObjectId(objectId, context.objectFormat, `${label} reachable object ${index}`);
  }
  const batchInput = Buffer.from(`${reachableObjectIds.join("\n")}\n`, "ascii");
  const batchBytes = await runGit({
    ...context,
    args: ["cat-file", "--batch-check=%(objectname) %(objecttype) %(objectsize)"],
    label: `${label} reachable-object availability verification`,
    maxOutputBytes: MAX_GIT_INTEGRITY_OUTPUT_BYTES,
    timeoutMs: GIT_INTEGRITY_TIMEOUT_MS,
    stdinBytes: batchInput,
  });
  const batchLines = decodeSingleLineList(
    batchBytes,
    `${label} reachable-object availability verification`,
  );
  if (batchLines.length !== reachableObjectIds.length) {
    throw new Error(`${label} did not verify every reachable Git object`);
  }
  for (const [index, line] of batchLines.entries()) {
    const match = line.match(/^([a-f0-9]+) (blob|commit|tag|tree) ([0-9]+)$/u);
    if (match === null || match[1] !== reachableObjectIds[index]) {
      throw new Error(`${label} reachable Git object ${index} is missing or invalid`);
    }
    if (!Number.isSafeInteger(Number(match[3]))) {
      throw new Error(`${label} reachable Git object ${index} has an unsupported size`);
    }
  }
  await runGit({
    ...context,
    args: [
      "fsck",
      "--full",
      "--strict",
      "--no-progress",
      "--no-dangling",
      "--no-reflogs",
      "--no-cache",
      "--no-references",
      ...roots,
    ],
    label,
    maxOutputBytes: MAX_GIT_INTEGRITY_OUTPUT_BYTES,
    timeoutMs: GIT_INTEGRITY_TIMEOUT_MS,
  });
}

async function resolveHeadCommit(context) {
  const objectId = decodeSingleLine(await runGit({
    ...context,
    args: ["rev-parse", "--verify", "HEAD^{commit}"],
    label: "HEAD commit resolution"
  }), "HEAD commit resolution");
  await assertCommit(context, objectId, "HEAD commit");
  return objectId;
}

async function resolveCommitTree(context, commitId) {
  const treeId = decodeSingleLine(await runGit({
    ...context,
    args: ["rev-parse", "--verify", `${commitId}^{tree}`],
    label: "Candidate commit tree resolution"
  }), "Candidate commit tree resolution");
  await assertTree(context, treeId, "Candidate Git tree");
  return treeId;
}

async function resolveMergeBase(context, baseCommit, candidateCommit) {
  const mergeBase = decodeSingleLine(await runGit({
    ...context,
    args: ["merge-base", baseCommit, candidateCommit],
    label: "Git merge-base resolution"
  }), "Git merge-base resolution");
  await assertCommit(context, mergeBase, "Merge-base commit");
  return mergeBase;
}

function assertRepositoryPath(repositoryPath, label) {
  if (
    typeof repositoryPath !== "string"
    || repositoryPath.length === 0
    || repositoryPath.includes("\\")
    || /[\u0000-\u001f\u007f]/u.test(repositoryPath)
    || path.posix.isAbsolute(repositoryPath)
    || repositoryPath.split("/").some((segment) => segment === "" || segment === "." || segment === "..")
  ) {
    throw new Error(`${label} is not a safe repository-relative path`);
  }
}

async function captureUntrackedFile(repositoryRoot, repositoryPath) {
  assertRepositoryPath(repositoryPath, `Untracked path ${JSON.stringify(repositoryPath)}`);
  const absolutePath = path.resolve(repositoryRoot, ...repositoryPath.split("/"));
  if (!isPathInside(repositoryRoot, absolutePath)) {
    throw new Error(`Untracked path ${JSON.stringify(repositoryPath)} escapes the repository`);
  }
  const capture = await captureBoundFile({
    anchorPath: repositoryRoot,
    hardLinkPolicy: HARD_LINK_POLICY.REJECT,
    path: absolutePath,
    label: `Untracked file ${repositoryPath}`,
    maxBytes: CONTRACT_VALUES.limits.maxUntrackedFileBytes,
  });
  return {
    capture,
    manifestEntry: {
      path: repositoryPath,
      sha256: capture.sha256,
      sizeBytes: capture.sizeBytes
    }
  };
}

function gitBlobObjectId(bytes, objectFormat) {
  if (!Buffer.isBuffer(bytes)) {
    throw new Error("Tracked worktree blob hashing requires an exact Buffer");
  }
  if (!Object.hasOwn(OBJECT_ID_LENGTH, objectFormat)) {
    throw new Error(`Unsupported Git object format: ${String(objectFormat)}`);
  }
  const hash = createHash(objectFormat);
  hash.update(Buffer.from(`blob ${bytes.byteLength}\0`, "ascii"));
  hash.update(bytes);
  return hash.digest("hex");
}

function parseTrackedIndexEntries(stagedEntries, context) {
  const records = decodeNullList(stagedEntries, "Tracked Git entry capture");
  if (records.length > CONTRACT_VALUES.limits.maxTrackedFiles) {
    throw new Error(
      `Dirty worktree exceeds the explicit ${CONTRACT_VALUES.limits.maxTrackedFiles}-file tracked limit`,
    );
  }
  const entries = [];
  const paths = new Set();
  for (const [index, entry] of records.entries()) {
    const match = entry.match(/^([0-7]{6}) ([a-f0-9]+) ([0-3])\t([\s\S]+)$/u);
    if (match === null) {
      throw new Error(`Git returned an invalid tracked index entry at position ${index}`);
    }
    const [, mode, objectId, stage, repositoryPath] = match;
    assertObjectId(objectId, context.objectFormat, `Tracked Git index object ${index}`);
    assertRepositoryPath(repositoryPath, `Tracked Git index path ${index}`);
    if (paths.has(repositoryPath)) {
      throw new Error(`Git returned a duplicate tracked index path: ${repositoryPath}`);
    }
    paths.add(repositoryPath);
    if (stage !== "0") {
      throw new Error(`Tracked Git path ${repositoryPath} has an unsupported unmerged index stage`);
    }
    if (mode === "160000") {
      throw new Error("DIRTY_WORKTREE identity does not support Git submodules");
    }
    if (mode === "120000") {
      throw new Error("DIRTY_WORKTREE identity does not support tracked symbolic links");
    }
    if (mode !== "100644" && mode !== "100755") {
      throw new Error(`Tracked Git path ${repositoryPath} has unsupported index mode ${mode}`);
    }
    entries.push(Object.freeze({ mode, objectId, path: repositoryPath }));
  }
  return Object.freeze(entries);
}

async function captureBaseTreeEntries(context, baseCommit) {
  const records = decodeNullList(await runGit({
    ...context,
    args: ["ls-tree", "-r", "-z", "--full-tree", baseCommit, "--"],
    label: "Base-tree entry capture",
  }), "Base-tree entry capture");
  if (records.length > CONTRACT_VALUES.limits.maxTrackedFiles) {
    throw new Error(
      `Base tree exceeds the explicit ${CONTRACT_VALUES.limits.maxTrackedFiles}-file tracked limit`,
    );
  }
  const entries = [];
  const paths = new Set();
  for (const [index, record] of records.entries()) {
    const match = record.match(/^([0-7]{6}) (blob|commit) ([a-f0-9]+)\t([\s\S]+)$/u);
    if (match === null) {
      throw new Error(`Git returned an invalid base-tree entry at position ${index}`);
    }
    const [, mode, type, objectId, repositoryPath] = match;
    assertObjectId(objectId, context.objectFormat, `Base-tree Git object ${index}`);
    assertRepositoryPath(repositoryPath, `Base-tree Git path ${index}`);
    if (paths.has(repositoryPath)) {
      throw new Error(`Git returned a duplicate base-tree path: ${repositoryPath}`);
    }
    paths.add(repositoryPath);
    const supported = (type === "blob" && ["100644", "100755", "120000"].includes(mode))
      || (type === "commit" && mode === "160000");
    if (!supported) {
      throw new Error(`Base-tree path ${repositoryPath} has unsupported ${mode} ${type} entry`);
    }
    entries.push(Object.freeze({ mode, objectId, path: repositoryPath }));
  }
  return Object.freeze(entries);
}

async function captureStableTrackedAbsence(repositoryRoot, absolutePath, repositoryPath) {
  let candidateParent = path.dirname(absolutePath);
  while (sameFilesystemPath(candidateParent, repositoryRoot)
    || isPathInside(repositoryRoot, candidateParent)) {
    let parentSnapshot;
    try {
      parentSnapshot = await captureDirectDirectorySnapshot({
        anchorPath: repositoryRoot,
        path: candidateParent,
        label: `Tracked file ${repositoryPath} nearest existing parent`,
      });
    } catch (error) {
      if (error?.code !== "ENOENT") throw error;
      if (sameFilesystemPath(candidateParent, repositoryRoot)) throw error;
      candidateParent = path.dirname(candidateParent);
      continue;
    }
    const remaining = path.relative(candidateParent, absolutePath).split(path.sep);
    const firstMissingPath = path.resolve(candidateParent, remaining[0]);
    await assertPathAbsent(
      firstMissingPath,
      `Tracked file ${repositoryPath} expected absent path component`,
    );
    await assertDirectPathSnapshot(parentSnapshot);
    return Object.freeze({
      kind: TRACKED_WORKTREE_ENTRY_KIND.ABSENT,
      mode: null,
      objectId: null,
      path: repositoryPath,
      sha256: null,
      sizeBytes: 0,
    });
  }
  throw new Error(`Tracked file ${repositoryPath} absence escaped the repository`);
}

async function captureTrackedWorktreeEntry(context, indexEntry) {
  const absolutePath = path.resolve(context.repositoryRoot, ...indexEntry.path.split("/"));
  if (!isPathInside(context.repositoryRoot, absolutePath)) {
    throw new Error(`Tracked path ${JSON.stringify(indexEntry.path)} escapes the repository`);
  }
  let pathSnapshot;
  try {
    pathSnapshot = await captureDirectFileSnapshot({
      anchorPath: context.repositoryRoot,
      path: absolutePath,
      label: `Tracked file ${indexEntry.path}`,
    });
  } catch (error) {
    if (error?.code !== "ENOENT") throw error;
    return captureStableTrackedAbsence(context.repositoryRoot, absolutePath, indexEntry.path);
  }
  let capture;
  try {
    capture = await captureBoundFile({
      anchorPath: context.repositoryRoot,
      hardLinkPolicy: HARD_LINK_POLICY.REJECT,
      path: absolutePath,
      label: `Tracked file ${indexEntry.path}`,
      maxBytes: CONTRACT_VALUES.limits.maxTrackedFileBytes,
    });
  } catch (error) {
    if (error?.code !== "ENOENT") throw error;
    return captureStableTrackedAbsence(context.repositoryRoot, absolutePath, indexEntry.path);
  }
  await assertDirectPathSnapshot(pathSnapshot);
  const filesystemMode = BigInt(pathSnapshot.targetIdentity.mode);
  const mode = process.platform === "win32"
    ? indexEntry.mode
    : ((filesystemMode & 0o111n) === 0n ? "100644" : "100755");
  return Object.freeze({
    kind: TRACKED_WORKTREE_ENTRY_KIND.FILE,
    mode,
    objectId: gitBlobObjectId(capture.bytes, context.objectFormat),
    path: indexEntry.path,
    sha256: capture.sha256,
    sizeBytes: capture.sizeBytes,
  });
}

async function captureTrackedWorktreeState(context, indexEntries) {
  const state = [];
  let aggregateBytes = 0;
  for (const indexEntry of indexEntries) {
    const captured = await captureTrackedWorktreeEntry(context, indexEntry);
    aggregateBytes += captured.sizeBytes;
    if (aggregateBytes > CONTRACT_VALUES.limits.maxAggregateTrackedBytes) {
      throw new Error(
        `Dirty worktree exceeds the explicit `
        + `${CONTRACT_VALUES.limits.maxAggregateTrackedBytes}-byte aggregate tracked limit`,
      );
    }
    state.push(captured);
  }
  return Object.freeze(state);
}

function expectedTrackedChangePaths(baseEntries, indexEntries, trackedWorktreeState) {
  const baseByPath = new Map(baseEntries.map((entry) => [entry.path, entry]));
  const indexByPath = new Map(indexEntries.map((entry) => [entry.path, entry]));
  const worktreeByPath = new Map(trackedWorktreeState.map((entry) => [entry.path, entry]));
  const paths = [...new Set([...baseByPath.keys(), ...indexByPath.keys()])].sort(compareText);
  const changed = [];
  for (const repositoryPath of paths) {
    const base = baseByPath.get(repositoryPath) ?? null;
    const indexed = indexByPath.get(repositoryPath) ?? null;
    const worktree = worktreeByPath.get(repositoryPath) ?? null;
    const candidate = indexed !== null && worktree?.kind === TRACKED_WORKTREE_ENTRY_KIND.FILE
      ? { mode: worktree.mode, objectId: worktree.objectId }
      : null;
    const baseline = base === null ? null : { mode: base.mode, objectId: base.objectId };
    if (canonicalJson(candidate) !== canonicalJson(baseline)) changed.push(repositoryPath);
  }
  return changed;
}

async function removeTemporaryIndexDirectory(directorySnapshot, indexPath) {
  await assertDirectPathSnapshot(directorySnapshot);
  const allowed = new Set([
    path.basename(indexPath),
    `${path.basename(indexPath)}.lock`,
  ]);
  const entries = await readdir(directorySnapshot.target);
  const unexpected = entries.filter((entry) => !allowed.has(entry));
  if (unexpected.length !== 0) {
    throw new Error(
      `Temporary Git index directory contains unexpected entries: ${unexpected.sort(compareText).join(", ")}`,
    );
  }
  for (const entry of entries) {
    try {
      await unlink(path.resolve(directorySnapshot.target, entry));
    } catch (error) {
      if (error?.code !== "ENOENT") throw error;
    }
  }
  await rmdir(directorySnapshot.target);
}

async function withTemporaryIndex(context, stagedEntries, callback) {
  const temporaryRoot = path.resolve(tmpdir());
  const temporaryDirectory = await mkdtemp(
    path.join(temporaryRoot, TEMPORARY_INDEX_DIRECTORY_PREFIX),
  );
  const directorySnapshot = await captureDirectDirectorySnapshot({
    anchorPath: temporaryRoot,
    path: temporaryDirectory,
    label: "Temporary Git index directory",
  });
  const indexPath = path.resolve(temporaryDirectory, TEMPORARY_INDEX_FILE_NAME);
  try {
    await assertPathAbsent(indexPath, "Temporary Git index");
    await runGit({
      ...context,
      args: ["read-tree", "--empty"],
      label: "Temporary Git index initialization",
      temporaryIndexPath: indexPath,
    });
    if (stagedEntries.byteLength !== 0) {
      await runGit({
        ...context,
        args: ["update-index", "-z", "--index-info"],
        label: "Temporary Git index entry reconstruction",
        stdinBytes: stagedEntries,
        temporaryIndexPath: indexPath,
      });
    }
    const reconstructedEntries = await runGit({
      ...context,
      args: ["ls-files", "--stage", "-z", "--"],
      label: "Temporary Git index reconstruction verification",
      temporaryIndexPath: indexPath,
    });
    if (!reconstructedEntries.equals(stagedEntries)) {
      throw new Error("Temporary Git index does not exactly reproduce the validated tracked entries");
    }
    await assertDirectPathSnapshot(directorySnapshot);
    return await callback(indexPath);
  } finally {
    await removeTemporaryIndexDirectory(directorySnapshot, indexPath);
  }
}

async function captureContentVerifiedTrackedPatch({
  context,
  baseCommit,
  stagedEntries,
  indexEntries,
}) {
  const baseEntries = await captureBaseTreeEntries(context, baseCommit);
  const firstWorktreeState = await captureTrackedWorktreeState(context, indexEntries);
  const expectedPaths = expectedTrackedChangePaths(baseEntries, indexEntries, firstWorktreeState);
  const captured = await withTemporaryIndex(context, stagedEntries, async (temporaryIndexPath) => {
    const patch = await runGit({
      ...context,
      args: deterministicPatchArguments({ baseCommit }),
      label: "Content-verified tracked Git patch capture",
      temporaryIndexPath,
    });
    const changedPaths = decodeNullList(await runGit({
      ...context,
      args: deterministicChangedPathArguments({ baseCommit }),
      label: "Content-verified tracked Git changed-path capture",
      temporaryIndexPath,
    }), "Content-verified tracked Git changed-path capture");
    return { changedPaths, patch };
  });
  const secondWorktreeState = await captureTrackedWorktreeState(context, indexEntries);
  if (canonicalJson(firstWorktreeState) !== canonicalJson(secondWorktreeState)) {
    throw new Error("Tracked worktree files changed during content-verified patch capture");
  }
  for (const [index, repositoryPath] of captured.changedPaths.entries()) {
    assertRepositoryPath(repositoryPath, `Tracked Git changed path ${index}`);
  }
  if (new Set(captured.changedPaths).size !== captured.changedPaths.length) {
    throw new Error("Git returned duplicate tracked changed paths");
  }
  const actualPaths = [...captured.changedPaths].sort(compareText);
  if (canonicalJson(actualPaths) !== canonicalJson(expectedPaths)) {
    throw new Error(
      "Content-verified tracked paths do not exactly match the deterministic Git patch paths",
    );
  }
  return { patch: captured.patch, trackedWorktreeState: firstWorktreeState };
}

async function assertNoWorktreeConversionAttributes(context) {
  const trackedPaths = await runGit({
    ...context,
    args: ["ls-files", "--cached", "-z", "--"],
    label: "Tracked Git path capture for attribute policy",
  });
  if (trackedPaths.length === 0) return;
  const attributeBytes = await runGit({
    ...context,
    args: ["check-attr", "-z", "--stdin", ...WORKTREE_CONVERSION_ATTRIBUTES],
    label: "Tracked Git worktree-conversion attribute capture",
    stdinBytes: trackedPaths,
  });
  const fields = decodeNullList(
    attributeBytes,
    "Tracked Git worktree-conversion attribute capture",
  );
  if (fields.length % 3 !== 0) {
    throw new Error("Git returned an invalid worktree-conversion attribute response");
  }
  for (let index = 0; index < fields.length; index += 3) {
    const [repositoryPath, attribute, value] = fields.slice(index, index + 3);
    assertRepositoryPath(repositoryPath, "Tracked Git attribute path");
    if (!WORKTREE_CONVERSION_ATTRIBUTES.includes(attribute)) {
      throw new Error(`Git returned an unexpected worktree-conversion attribute: ${attribute}`);
    }
    if (value !== "unspecified") {
      throw new Error(
        `Tracked Git path ${repositoryPath} uses unsupported worktree-conversion attribute `
        + `${attribute}=${value}`,
      );
    }
  }
}

async function captureDirtyState(context, baseCommit) {
  await assertNoHiddenIndexEntries(context);
  await assertAttributeFreeBase(context, baseCommit);
  await assertNoWorktreeConversionAttributes(context);
  const stagedEntries = await runGit({
    ...context,
    args: ["ls-files", "--stage", "-z", "--"],
    label: "Tracked Git entry capture"
  });
  const indexEntries = parseTrackedIndexEntries(stagedEntries, context);
  const headCommit = await resolveHeadCommit(context);
  const mergeBaseCommit = await resolveMergeBase(context, baseCommit, headCommit);
  await assertObjectIntegrity(
    context,
    [baseCommit, headCommit, mergeBaseCommit],
    "Dirty-worktree Git object integrity verification"
  );
  const trackedCapture = await captureContentVerifiedTrackedPatch({
    context,
    baseCommit,
    stagedEntries,
    indexEntries,
  });
  const untrackedPaths = decodeNullList(await runGit({
    ...context,
    args: ["ls-files", "--others", "--exclude-per-directory=.gitignore", "-z", "--"],
    label: "Untracked Git path capture"
  }), "Untracked Git path capture").sort(compareText);
  if (new Set(untrackedPaths).size !== untrackedPaths.length) {
    throw new Error("Git returned duplicate untracked paths");
  }
  if (untrackedPaths.length > CONTRACT_VALUES.limits.maxUntrackedFiles) {
    throw new Error(
      `Dirty worktree exceeds the explicit ${CONTRACT_VALUES.limits.maxUntrackedFiles}-file untracked limit`
    );
  }
  const untracked = [];
  let aggregateUntrackedBytes = 0;
  for (const repositoryPath of untrackedPaths) {
    const captured = await captureUntrackedFile(context.repositoryRoot, repositoryPath);
    aggregateUntrackedBytes += captured.manifestEntry.sizeBytes;
    if (aggregateUntrackedBytes > CONTRACT_VALUES.limits.maxAggregateUntrackedBytes) {
      throw new Error(
        `Dirty worktree exceeds the explicit `
        + `${CONTRACT_VALUES.limits.maxAggregateUntrackedBytes}-byte aggregate untracked limit`
      );
    }
    untracked.push(captured);
  }
  return {
    headCommit,
    mergeBaseCommit,
    trackedPatch: trackedCapture.patch,
    trackedWorktreeState: trackedCapture.trackedWorktreeState,
    untracked,
  };
}

async function assertNoHiddenIndexEntries(context) {
  const taggedEntries = decodeNullList(await runGit({
    ...context,
    args: ["ls-files", "-v", "-z", "--"],
    label: "Tracked Git index-flag capture"
  }), "Tracked Git index-flag capture");
  for (const entry of taggedEntries) {
    if (entry.length < 3 || entry[1] !== " ") {
      throw new Error("Git returned an invalid tracked index-flag entry");
    }
    const tag = entry[0];
    const repositoryPath = entry.slice(2);
    assertRepositoryPath(repositoryPath, "Tracked Git index path");
    if (tag === "S" || tag === "s") {
      throw new Error(`Tracked Git path ${repositoryPath} has the forbidden skip-worktree index flag`);
    }
    if (/^[a-z]$/u.test(tag)) {
      throw new Error(`Tracked Git path ${repositoryPath} has the forbidden assume-unchanged index flag`);
    }
  }
}

function assertSameDirtyState(first, second) {
  if (first.headCommit !== second.headCommit) {
    throw new Error("HEAD changed during the dirty-worktree double capture");
  }
  if (first.mergeBaseCommit !== second.mergeBaseCommit) {
    throw new Error("Merge base changed during the dirty-worktree double capture");
  }
  if (!first.trackedPatch.equals(second.trackedPatch)) {
    throw new Error("Tracked patch changed during the dirty-worktree double capture");
  }
  if (canonicalJson(first.trackedWorktreeState) !== canonicalJson(second.trackedWorktreeState)) {
    throw new Error("Tracked worktree state changed during the dirty-worktree double capture");
  }
  const firstPaths = first.untracked.map(({ manifestEntry }) => manifestEntry.path);
  const secondPaths = second.untracked.map(({ manifestEntry }) => manifestEntry.path);
  if (canonicalJson(firstPaths) !== canonicalJson(secondPaths)) {
    throw new Error("Untracked path set changed during the dirty-worktree double capture");
  }
  for (let index = 0; index < first.untracked.length; index += 1) {
    assertSameFileCapture(
      first.untracked[index].capture,
      second.untracked[index].capture,
      `Untracked file ${firstPaths[index]}`
    );
  }
}

function validateRemoteInput(repositoryRemote) {
  if (repositoryRemote === null || typeof repositoryRemote !== "object" || Array.isArray(repositoryRemote)) {
    throw new Error("repositoryRemote must explicitly declare name and URL");
  }
  const keys = Object.keys(repositoryRemote).sort(compareText);
  if (canonicalJson(keys) !== canonicalJson(["name", "url"])) {
    throw new Error("repositoryRemote must contain only name and url");
  }
  assertNonEmptyString(repositoryRemote.name, "repositoryRemote.name");
  assertNonEmptyString(repositoryRemote.url, "repositoryRemote.url");
  return { name: repositoryRemote.name, url: repositoryRemote.url };
}

async function assertRemote(context, expectedRemote) {
  const names = decodeSingleLineList(await runGit({
    ...context,
    args: ["remote"],
    label: "Git remote-name capture"
  }), "Git remote-name capture");
  if (names.filter((name) => name === expectedRemote.name).length !== 1) {
    throw new Error(`Repository does not declare exactly one remote named ${expectedRemote.name}`);
  }
  const key = `remote.${expectedRemote.name}.url`;
  const urls = decodeNullList(await runGit({
    ...context,
    args: ["config", "--local", "--no-includes", "--null", "--get-all", key],
    label: `Git remote URL capture for ${expectedRemote.name}`
  }), `Git remote URL capture for ${expectedRemote.name}`);
  if (urls.length !== 1 || urls[0] !== expectedRemote.url) {
    throw new Error(`Repository remote ${expectedRemote.name} URL does not exactly match the declared URL`);
  }
}

function decodeSingleLineList(bytes, label) {
  const decoded = decodeUtf8(bytes, label);
  if (decoded.length === 0) return [];
  const normalized = decoded.endsWith("\n") ? decoded.slice(0, -1) : decoded;
  if (normalized.includes("\r")) {
    throw new Error(`${label} contains an unexpected carriage return`);
  }
  const lines = normalized.split("\n");
  if (lines.some((line) => line.length === 0)) {
    throw new Error(`${label} contains an empty entry`);
  }
  return lines;
}

async function captureSafeLocalGitConfiguration(context) {
  const capture = async (label) => runGit({
    ...context,
    args: ["config", "--local", "--no-includes", "--null", "--list"],
    label,
    maxOutputBytes: MAX_GIT_INTEGRITY_OUTPUT_BYTES,
  });
  const validate = (bytes, label) => {
    const records = decodeNullList(bytes, label);
    for (const record of records) {
      const separator = record.indexOf("\n");
      if (separator < 1) {
        throw new Error(`${label} contains an invalid key/value record`);
      }
      const key = record.slice(0, separator);
      assertNonEmptyString(key, "Repository-local Git configuration key");
      const normalized = key.toLowerCase();
      if (!SAFE_LOCAL_GIT_CONFIG_KEY_PATTERNS.some((pattern) => pattern.test(normalized))) {
        throw new Error(`Unsupported repository-local Git configuration key: ${key}`);
      }
    }
  };
  const firstRaw = await capture("Repository-local Git configuration capture");
  validate(firstRaw, "Repository-local Git configuration capture");
  const secondRaw = await capture("Repository-local Git configuration recapture");
  validate(secondRaw, "Repository-local Git configuration recapture");
  if (!firstRaw.equals(secondRaw)) {
    throw new Error("Repository-local Git configuration changed during its stable capture");
  }
  return firstRaw;
}

async function assertCompleteRepository(context) {
  const shallow = decodeSingleLine(await runGit({
    ...context,
    args: ["rev-parse", "--is-shallow-repository"],
    label: "Git shallow-repository policy capture",
    maxOutputBytes: 64,
  }), "Git shallow-repository policy capture");
  if (shallow !== "false") {
    throw new Error("Shallow Git repositories are not supported for exact object-integrity capture");
  }
}

async function resolveGitMetadataPath(context, repositoryPath, label) {
  const reportedPath = decodeSingleLine(await runGit({
    ...context,
    args: ["rev-parse", "--path-format=absolute", "--git-path", repositoryPath],
    label,
    maxOutputBytes: MAX_GIT_INTEGRITY_OUTPUT_BYTES,
  }), label);
  assertAbsolutePath(reportedPath, label);
  const parent = await resolveDirectDirectoryPath({
    path: path.dirname(reportedPath),
    label: `${label} parent`,
  });
  return path.resolve(parent, path.basename(reportedPath));
}

async function captureSafeGitMetadata(context) {
  const prohibitedFiles = [
    ["objects/info/alternates", "Git object alternates"],
    ["objects/info/http-alternates", "Git HTTP object alternates"],
    ["info/attributes", "Git info attributes"],
    ["info/grafts", "Git grafts"],
  ];
  const absentPaths = [];
  for (const [repositoryPath, label] of prohibitedFiles) {
    const metadataPath = await resolveGitMetadataPath(context, repositoryPath, label);
    await assertPathAbsent(metadataPath, `${label} are unsupported`);
    absentPaths.push(metadataPath);
  }
  const packDirectory = await resolveGitMetadataPath(
    context,
    "objects/pack/.identity-pack-directory-anchor",
    "Git object pack directory",
  ).then((anchor) => path.dirname(anchor));
  const packSnapshot = await captureDirectDirectorySnapshot({
    path: packDirectory,
    label: "Git object pack directory",
  });
  const firstEntries = (await readdir(packDirectory)).sort(compareText);
  await assertDirectPathSnapshot(packSnapshot);
  const secondEntries = (await readdir(packDirectory)).sort(compareText);
  await assertDirectPathSnapshot(packSnapshot);
  if (canonicalJson(firstEntries) !== canonicalJson(secondEntries)) {
    throw new Error("Git object pack directory changed during its stable capture");
  }
  const promisor = firstEntries.find((entry) => entry.toLowerCase().endsWith(".promisor"));
  if (promisor !== undefined) {
    throw new Error(`Git promisor pack markers are unsupported: ${promisor}`);
  }
  return Object.freeze({
    absentPaths: Object.freeze(absentPaths),
    packDirectory,
    packEntries: Object.freeze(firstEntries),
  });
}

async function assertAttributeFreeBase(context, baseCommit) {
  const paths = decodeNullList(await runGit({
    ...context,
    args: ["ls-tree", "-r", "-z", "--name-only", baseCommit, "--"],
    label: "Base-tree attribute-policy capture",
  }), "Base-tree attribute-policy capture");
  const attributePath = paths.find((repositoryPath) => (
    path.posix.basename(repositoryPath).toLowerCase() === ".gitattributes"
  ));
  if (attributePath !== undefined) {
    throw new Error(
      `Base commit uses unsupported Git attributes at ${attributePath}; `
      + "the current capture adapter requires an attribute-free base tree",
    );
  }
  const infoAttributesPath = await resolveGitMetadataPath(
    context,
    "info/attributes",
    "Git info attributes",
  );
  await assertPathAbsent(infoAttributesPath, "Git info attributes are unsupported");
}

function deterministicDiffArguments({
  baseCommit,
  candidateCommit = null,
  outputArguments,
}) {
  assertNonEmptyString(baseCommit, "Patch base commit");
  if (candidateCommit !== null) assertNonEmptyString(candidateCommit, "Patch candidate commit");
  if (!Array.isArray(outputArguments) || outputArguments.length === 0) {
    throw new Error("Deterministic Git diff requires explicit output arguments");
  }
  return [
    `--attr-source=${baseCommit}`,
    "diff",
    ...outputArguments,
    "--no-ext-diff",
    "--no-textconv",
    "--no-renames",
    "--diff-algorithm=myers",
    "--no-indent-heuristic",
    "--unified=3",
    "--inter-hunk-context=0",
    "--default-prefix",
    "--no-relative",
    "--no-color",
    "--ws-error-highlight=none",
    "--ignore-submodules=none",
    "--submodule=short",
    baseCommit,
    ...(candidateCommit === null ? [] : [candidateCommit]),
    "--",
  ];
}

export function deterministicPatchArguments({ baseCommit, candidateCommit = null }) {
  return deterministicDiffArguments({
    baseCommit,
    candidateCommit,
    outputArguments: ["--patch", "--binary", "--full-index"],
  });
}

function deterministicChangedPathArguments({ baseCommit }) {
  return deterministicDiffArguments({
    baseCommit,
    outputArguments: ["--name-only", "-z"],
  });
}

async function prepareContext({
  repositoryRoot,
  gitExecutablePath,
  expectedGitExecutableSha256,
  repositoryRemote,
}) {
  assertAbsolutePath(repositoryRoot, "repositoryRoot");
  assertAbsolutePath(gitExecutablePath, "gitExecutablePath");
  const expectedRemote = validateRemoteInput(repositoryRemote);
  const [canonicalRepositoryRoot, verifiedGitExecutable] = await Promise.all([
    resolveDirectDirectoryPath({ path: repositoryRoot, label: "Repository root" }),
    verifyGitExecutableAdapter({ gitExecutablePath, expectedGitExecutableSha256 }),
  ]);
  const canonicalGitExecutable = verifiedGitExecutable.executablePath;
  if (sameFilesystemPath(canonicalRepositoryRoot, canonicalGitExecutable)
    || isPathInside(canonicalRepositoryRoot, canonicalGitExecutable)) {
    throw new Error("Git executable must exist outside the repository under review");
  }
  const executableCapture = verifiedGitExecutable.capture;
  const provisionalContext = {
    executablePath: canonicalGitExecutable,
    repositoryRoot: canonicalRepositoryRoot
  };
  const reportedRoot = decodeSingleLine(await runGit({
    ...provisionalContext,
    args: ["rev-parse", "--path-format=absolute", "--show-toplevel"],
    label: "Git repository-root capture"
  }), "Git repository-root capture");
  const canonicalReportedRoot = await resolveDirectDirectoryPath({
    path: reportedRoot,
    label: "Git-reported repository root",
  });
  if (!sameFilesystemPath(canonicalRepositoryRoot, canonicalReportedRoot)) {
    throw new Error("repositoryRoot must be the exact Git worktree root");
  }
  const localConfiguration = await captureSafeLocalGitConfiguration(provisionalContext);
  const objectFormat = decodeSingleLine(await runGit({
    ...provisionalContext,
    args: ["rev-parse", "--show-object-format=storage"],
    label: "Git object-format capture"
  }), "Git object-format capture");
  if (!Object.hasOwn(OBJECT_ID_LENGTH, objectFormat)) {
    throw new Error(`Unsupported Git object format: ${objectFormat}`);
  }
  const context = { ...provisionalContext, objectFormat };
  await assertCompleteRepository(context);
  const gitMetadata = await captureSafeGitMetadata(context);
  await assertRemote(context, expectedRemote);
  await assertNoHiddenIndexEntries(context);
  return {
    context,
    executableCapture,
    expectedRemote,
    gitMetadata,
    localConfiguration,
  };
}

function executableBinding(pathValue, capture) {
  return {
    path: pathValue,
    sha256: capture.sha256,
    sizeBytes: capture.sizeBytes
  };
}

async function assertContextStable(prepared) {
  const {
    context,
    executableCapture,
    expectedRemote,
    gitMetadata,
    localConfiguration,
  } = prepared;
  const secondExecutableCapture = await captureBoundFile({
    anchorPath: path.parse(context.executablePath).root,
    hardLinkPolicy: HARD_LINK_POLICY.ALLOW_STABLE_IDENTITY,
    path: context.executablePath,
    label: "Git executable",
    maxBytes: CONTRACT_VALUES.limits.maxGitExecutableBytes,
  });
  assertSameFileCapture(executableCapture, secondExecutableCapture, "Git executable");
  const objectFormat = decodeSingleLine(await runGit({
    ...context,
    args: ["rev-parse", "--show-object-format=storage"],
    label: "Final Git object-format capture"
  }), "Final Git object-format capture");
  if (objectFormat !== context.objectFormat) {
    throw new Error("Git object format changed during identity capture");
  }
  const finalLocalConfiguration = await captureSafeLocalGitConfiguration(context);
  if (!localConfiguration.equals(finalLocalConfiguration)) {
    throw new Error("Repository-local Git configuration changed during identity capture");
  }
  await assertCompleteRepository(context);
  const finalGitMetadata = await captureSafeGitMetadata(context);
  if (canonicalJson(gitMetadata) !== canonicalJson(finalGitMetadata)) {
    throw new Error("Git metadata policy state changed during identity capture");
  }
  await assertRemote(context, expectedRemote);
  await assertNoHiddenIndexEntries(context);
}

function normalizeCapturedAt(capturedAt) {
  if (typeof capturedAt !== "string" || new Date(capturedAt).toISOString() !== capturedAt) {
    throw new Error("capturedAt is required and must be an exact UTC ISO-8601 timestamp");
  }
  return capturedAt;
}

export function candidateSnapshotDigest(identity) {
  const common = {
    gitExecutable: identity.gitExecutable,
    gitObjectFormat: identity.gitObjectFormat,
    mode: identity.mode,
    repositoryId: identity.repositoryId,
    repositoryRemote: identity.repositoryRemote
  };
  if (identity.mode === MODE.COMMITTED) {
    return canonicalDigest({
      ...common,
      baseCommit: identity.baseCommit,
      candidateCommit: identity.candidateCommit,
      candidateGitTree: identity.candidateGitTree,
      mergeBaseCommit: identity.mergeBaseCommit
    });
  }
  if (identity.mode === MODE.DIRTY) {
    return canonicalDigest({
      ...common,
      baseCommit: identity.baseCommit,
      mergeBaseCommit: identity.mergeBaseCommit,
      trackedPatchDigest: identity.trackedPatchDigest,
      untrackedFiles: identity.untrackedFiles,
      untrackedFilesManifestDigest: identity.untrackedFilesManifestDigest
    });
  }
  throw new Error(`Unsupported candidate identity mode: ${String(identity.mode)}`);
}

export function identityId(identity) {
  return canonicalDigest(withoutKey(identity, "identityId"));
}

export function assertIdentitySemantics(identity, label = "candidate identity") {
  if (identity.schemaVersion !== CONTRACT_VALUES.candidateIdentitySchemaVersion) {
    throw new Error(
      `${label} must use candidate identity schema version ${CONTRACT_VALUES.candidateIdentitySchemaVersion}`,
    );
  }
  assertNonEmptyString(identity.repositoryId, `${label}.repositoryId`);
  validateRemoteInput(identity.repositoryRemote);
  assertAbsolutePath(identity.gitExecutable.path, `${label}.gitExecutable.path`);
  if (!Object.hasOwn(OBJECT_ID_LENGTH, identity.gitObjectFormat)) {
    throw new Error(`${label}.gitObjectFormat is unsupported`);
  }
  assertObjectId(identity.baseCommit, identity.gitObjectFormat, `${label}.baseCommit`);
  assertObjectId(identity.mergeBaseCommit, identity.gitObjectFormat, `${label}.mergeBaseCommit`);
  if (identity.mode === MODE.COMMITTED) {
    assertObjectId(identity.candidateCommit, identity.gitObjectFormat, `${label}.candidateCommit`);
    assertObjectId(identity.candidateGitTree, identity.gitObjectFormat, `${label}.candidateGitTree`);
  }
  if (identity.identityId !== identityId(identity)) {
    throw new Error(`${label} identityId does not match its canonical fields`);
  }
  if (identity.candidateSnapshotDigest !== candidateSnapshotDigest(identity)) {
    throw new Error(`${label} candidateSnapshotDigest does not match its identity fields`);
  }
  const paths = identity.untrackedFiles.map(({ path: repositoryPath }) => repositoryPath);
  if (new Set(paths).size !== paths.length) {
    throw new Error(`${label} contains duplicate untracked file paths`);
  }
  if (canonicalJson(paths) !== canonicalJson([...paths].sort(compareText))) {
    throw new Error(`${label} untracked files must be sorted by repository path`);
  }
  if (identity.mode === MODE.DIRTY) {
    if (identity.untrackedFilesManifestDigest !== canonicalDigest(identity.untrackedFiles)) {
      throw new Error(`${label} untrackedFilesManifestDigest is invalid`);
    }
    if (identity.trackedPatchDigest === EMPTY_BYTES_SHA256 && identity.untrackedFiles.length === 0) {
      throw new Error(`${label} DIRTY_WORKTREE must bind a tracked patch or untracked file`);
    }
  }
  return identity;
}

export async function captureCandidateIdentity({
  repositoryRoot,
  gitExecutablePath,
  expectedGitExecutableSha256,
  repositoryId,
  repositoryRemote,
  mode,
  baseCommit,
  candidateCommit,
  capturedAt
}) {
  assertNonEmptyString(repositoryId, "repositoryId");
  if (mode !== MODE.COMMITTED && mode !== MODE.DIRTY) {
    throw new Error("mode must explicitly be COMMITTED_GIT or DIRTY_WORKTREE");
  }
  const prepared = await prepareContext({
    repositoryRoot,
    gitExecutablePath,
    expectedGitExecutableSha256,
    repositoryRemote,
  });
  const { context, executableCapture, expectedRemote } = prepared;
  assertObjectId(baseCommit, context.objectFormat, "baseCommit");
  await assertCommit(context, baseCommit, "Base commit");

  let fields;
  if (mode === MODE.COMMITTED) {
    assertObjectId(candidateCommit, context.objectFormat, "candidateCommit");
    await assertCommit(context, candidateCommit, "Candidate commit");
    const firstTree = await resolveCommitTree(context, candidateCommit);
    const firstMergeBase = await resolveMergeBase(context, baseCommit, candidateCommit);
    await assertObjectIntegrity(
      context,
      [baseCommit, candidateCommit, firstTree, firstMergeBase],
      "Committed candidate Git object integrity verification (first capture)"
    );
    await assertCommit(context, baseCommit, "Base commit (second capture)");
    await assertCommit(context, candidateCommit, "Candidate commit (second capture)");
    const secondTree = await resolveCommitTree(context, candidateCommit);
    const secondMergeBase = await resolveMergeBase(context, baseCommit, candidateCommit);
    await assertObjectIntegrity(
      context,
      [baseCommit, candidateCommit, secondTree, secondMergeBase],
      "Committed candidate Git object integrity verification (second capture)"
    );
    if (firstTree !== secondTree || firstMergeBase !== secondMergeBase) {
      throw new Error("Committed Git identity changed during the double capture");
    }
    fields = {
      baseCommit,
      mergeBaseCommit: firstMergeBase,
      candidateCommit,
      candidateGitTree: firstTree,
      trackedPatchDigest: null,
      untrackedFilesManifestDigest: null,
      untrackedFiles: [],
      dirty: false
    };
  } else {
    if (candidateCommit !== undefined) {
      throw new Error("candidateCommit must be omitted for DIRTY_WORKTREE capture");
    }
    const first = await captureDirtyState(context, baseCommit);
    const second = await captureDirtyState(context, baseCommit);
    assertSameDirtyState(first, second);
    const untrackedFiles = first.untracked.map(({ manifestEntry }) => manifestEntry);
    fields = {
      baseCommit,
      mergeBaseCommit: first.mergeBaseCommit,
      candidateCommit: null,
      candidateGitTree: null,
      trackedPatchDigest: sha256(first.trackedPatch),
      untrackedFilesManifestDigest: canonicalDigest(untrackedFiles),
      untrackedFiles,
      dirty: true
    };
  }
  await assertContextStable(prepared);

  const identity = {
    schemaVersion: CONTRACT_VALUES.candidateIdentitySchemaVersion,
    identityId: "",
    mode,
    repositoryId,
    repositoryRemote: expectedRemote,
    gitExecutable: executableBinding(context.executablePath, executableCapture),
    gitObjectFormat: context.objectFormat,
    ...fields,
    candidateSnapshotDigest: "",
    capturedAt: normalizeCapturedAt(capturedAt)
  };
  identity.candidateSnapshotDigest = candidateSnapshotDigest(identity);
  identity.identityId = identityId(identity);
  return assertIdentitySemantics(identity);
}

export async function verifyCandidateIdentity({
  identity,
  repositoryRoot,
  gitExecutablePath,
  expectedGitExecutableSha256,
  repositoryId,
  repositoryRemote,
  requireWorktreeMatch
}) {
  if (typeof requireWorktreeMatch !== "boolean") {
    throw new Error("requireWorktreeMatch must be explicitly true or false");
  }
  assertIdentitySemantics(identity, "Candidate identity");
  if (identity.repositoryId !== repositoryId) {
    throw new Error("Candidate identity repositoryId does not match the explicitly expected repositoryId");
  }
  const expectedRemote = validateRemoteInput(repositoryRemote);
  if (canonicalJson(identity.repositoryRemote) !== canonicalJson(expectedRemote)) {
    throw new Error("Candidate identity remote does not match the explicitly expected remote");
  }
  const recaptured = await captureCandidateIdentity({
    repositoryRoot,
    gitExecutablePath,
    expectedGitExecutableSha256,
    repositoryId,
    repositoryRemote: expectedRemote,
    mode: identity.mode,
    baseCommit: identity.baseCommit,
    ...(identity.mode === MODE.COMMITTED ? { candidateCommit: identity.candidateCommit } : {}),
    capturedAt: identity.capturedAt
  });
  if (canonicalJson(recaptured) !== canonicalJson(identity)) {
    throw new Error("Candidate identity does not exactly match the live Git repository state");
  }
  if (requireWorktreeMatch && identity.mode === MODE.COMMITTED) {
    const prepared = await prepareContext({
      repositoryRoot,
      gitExecutablePath,
      expectedGitExecutableSha256,
      repositoryRemote: expectedRemote,
    });
    const first = await captureDirtyState(prepared.context, identity.candidateCommit);
    const second = await captureDirtyState(prepared.context, identity.candidateCommit);
    assertSameDirtyState(first, second);
    await assertContextStable(prepared);
    if (
      first.headCommit !== identity.candidateCommit
      || first.trackedPatch.length !== 0
      || first.untracked.length !== 0
    ) {
      throw new Error(
        "A COMMITTED_GIT candidate role requires HEAD and the non-ignored live worktree "
        + "to exactly match candidateCommit"
      );
    }
  }
  return identity;
}

export async function collectCandidateSnapshotMaterial({
  identity,
  repositoryRoot,
  gitExecutablePath,
  expectedGitExecutableSha256,
  repositoryRemote,
}) {
  assertIdentitySemantics(identity, "Candidate snapshot material identity");
  const prepared = await prepareContext({
    repositoryRoot,
    gitExecutablePath,
    expectedGitExecutableSha256,
    repositoryRemote,
  });
  const { context } = prepared;
  let trackedPatch;
  let untrackedFiles;
  if (identity.mode === MODE.DIRTY) {
    const first = await captureDirtyState(context, identity.baseCommit);
    const second = await captureDirtyState(context, identity.baseCommit);
    assertSameDirtyState(first, second);
    const manifest = first.untracked.map(({ manifestEntry }) => manifestEntry);
    if (
      sha256(first.trackedPatch) !== identity.trackedPatchDigest
      || canonicalDigest(manifest) !== identity.untrackedFilesManifestDigest
      || canonicalJson(manifest) !== canonicalJson(identity.untrackedFiles)
      || first.mergeBaseCommit !== identity.mergeBaseCommit
    ) {
      throw new Error("Candidate snapshot material does not match the bound dirty-worktree identity");
    }
    trackedPatch = first.trackedPatch;
    untrackedFiles = first.untracked.map(({ capture, manifestEntry }) => ({
      path: manifestEntry.path,
      bytes: capture.bytes,
      sha256: manifestEntry.sha256,
      sizeBytes: manifestEntry.sizeBytes,
    }));
  } else {
    await assertAttributeFreeBase(context, identity.baseCommit);
    const patchArguments = deterministicPatchArguments({
      baseCommit: identity.baseCommit,
      candidateCommit: identity.candidateCommit,
    });
    const first = await runGit({
      ...context,
      args: patchArguments,
      label: "Committed candidate reconstruction patch capture",
    });
    const second = await runGit({
      ...context,
      args: patchArguments,
      label: "Committed candidate reconstruction patch recapture",
    });
    if (!first.equals(second)) {
      throw new Error("Committed candidate reconstruction patch changed during capture");
    }
    trackedPatch = first;
    untrackedFiles = [];
  }
  await assertContextStable(prepared);
  return { trackedPatch, untrackedFiles };
}

export async function readIdentity({ anchorPath, identityPath, schema, label }) {
  let identity;
  try {
    const bytes = await captureStableRegularFile({
      anchorPath,
      hardLinkPolicy: HARD_LINK_POLICY.REJECT,
      path: identityPath,
      label,
      maxBytes: CONTRACT_VALUES.limits.maxCandidateIdentityBytes,
    });
    identity = JSON.parse(UTF8_DECODER.decode(bytes));
  } catch (error) {
    if (error instanceof SyntaxError) {
      throw new Error(`${label} is not valid JSON: ${error.message}`);
    }
    if (error instanceof TypeError) {
      throw new Error(`${label} is not valid UTF-8`);
    }
    throw error;
  }
  assertContract(schema, identity, label);
  return assertIdentitySemantics(identity, label);
}
