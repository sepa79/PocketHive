import { execFileSync } from "node:child_process";
import { realpathSync } from "node:fs";
import path from "node:path";
import { TextDecoder } from "node:util";
import { LIMITS } from "./constants.mjs";

const UTF8_DECODER = new TextDecoder("utf-8", { fatal: true });
const HEX_OBJECT_ID = /^[a-f0-9]+$/iu;
const OBJECT_FORMAT = Object.freeze({
  SHA1: "sha1",
  SHA256: "sha256"
});
const OBJECT_ID_LENGTH = Object.freeze({
  [OBJECT_FORMAT.SHA1]: 40,
  [OBJECT_FORMAT.SHA256]: 64
});
const GIT_ENVIRONMENT_ALLOWLIST = new Set([
  "COMSPEC",
  "LANG",
  "LC_ALL",
  "PATH",
  "PATHEXT",
  "SYSTEMDRIVE",
  "SYSTEMROOT",
  "TEMP",
  "TMP",
  "TMPDIR",
  "WINDIR"
]);

export class GitReadError extends Error {
  constructor(message) {
    super(message);
    this.name = "GitReadError";
  }
}

export function boundedGitErrorDetail(stderr) {
  const bytes = Buffer.isBuffer(stderr)
    ? stderr
    : Buffer.from(String(stderr ?? ""), "utf8");
  const bounded = bytes.length > LIMITS.maxGitErrorDetailBytes
    ? bytes.subarray(0, LIMITS.maxGitErrorDetailBytes)
    : bytes;
  const detail = bounded.toString("utf8").trim();
  return bytes.length > LIMITS.maxGitErrorDetailBytes
    ? `${detail}${detail ? " " : ""}[Git stderr omitted beyond ${LIMITS.maxGitErrorDetailBytes} bytes]`
    : detail;
}

function gitEnvironment() {
  const environment = {};
  for (const [key, value] of Object.entries(process.env)) {
    if (value !== undefined && GIT_ENVIRONMENT_ALLOWLIST.has(key.toUpperCase())) {
      environment[key] = value;
    }
  }
  return {
    ...environment,
    GIT_ATTR_NOSYSTEM: "1",
    GIT_CONFIG_GLOBAL: process.platform === "win32" ? "NUL" : "/dev/null",
    GIT_CONFIG_NOSYSTEM: "1",
    GIT_NO_LAZY_FETCH: "1",
    GIT_NO_REPLACE_OBJECTS: "1",
    GIT_OPTIONAL_LOCKS: "0",
    GIT_TERMINAL_PROMPT: "0",
    LANG: "C",
    LC_ALL: "C"
  };
}

function trustedGitExecutable(repoRoot, gitExecutable) {
  if (typeof gitExecutable !== "string" || !path.isAbsolute(gitExecutable)) {
    throw new GitReadError("Git executable must be an explicit absolute path");
  }
  let resolvedRepository;
  let resolvedExecutable;
  try {
    resolvedRepository = realpathSync(repoRoot);
    resolvedExecutable = realpathSync(gitExecutable);
  } catch (error) {
    throw new GitReadError(`Git executable or repository path cannot be resolved: ${error.message}`);
  }
  const relative = path.relative(resolvedRepository, resolvedExecutable);
  if (relative === "" || (!relative.startsWith("..") && !path.isAbsolute(relative))) {
    throw new GitReadError("Git executable must be outside the analyzed repository");
  }
  return resolvedExecutable;
}

function runGit(
  repoRoot,
  gitExecutable,
  args,
  { encoding = "buffer", allowFailure = false, input } = {}
) {
  const executable = trustedGitExecutable(repoRoot, gitExecutable);
  try {
    return execFileSync(executable, args, {
      cwd: repoRoot,
      encoding,
      env: gitEnvironment(),
      input,
      maxBuffer: LIMITS.gitMaxBufferBytes,
      stdio: [input === undefined ? "ignore" : "pipe", "pipe", "pipe"],
      timeout: LIMITS.gitCommandTimeoutMs,
      windowsHide: true
    });
  } catch (error) {
    if (error.code === "ETIMEDOUT") {
      throw new GitReadError(`git ${args[0]} exceeded ${LIMITS.gitCommandTimeoutMs} ms`);
    }
    if (allowFailure) {
      return null;
    }
    const stderr = boundedGitErrorDetail(error.stderr);
    throw new GitReadError(`git ${args[0]} failed${stderr ? `: ${stderr}` : ""}`);
  }
}

export function readObjectFormat(repoRoot, gitExecutable) {
  const objectFormat = runGit(
    repoRoot,
    gitExecutable,
    ["rev-parse", "--show-object-format=storage"],
    { encoding: "utf8" }
  ).trim().toLowerCase();
  if (!Object.hasOwn(OBJECT_ID_LENGTH, objectFormat)) {
    throw new GitReadError(`Git object format is unsupported: ${objectFormat || "<empty>"}`);
  }
  return objectFormat;
}

function requireFullObjectId(value, objectFormat, label) {
  const requiredLength = OBJECT_ID_LENGTH[objectFormat];
  if (
    typeof value !== "string"
    || value.length !== requiredLength
    || !HEX_OBJECT_ID.test(value)
  ) {
    throw new GitReadError(
      `${label} must be a full ${requiredLength}-character ${objectFormat} Git object ID`
    );
  }
  return value.toLowerCase();
}

function splitNul(buffer) {
  const values = [];
  let start = 0;
  for (let index = 0; index < buffer.length; index += 1) {
    if (buffer[index] === 0) {
      values.push(buffer.subarray(start, index));
      start = index + 1;
    }
  }
  if (start !== buffer.length) {
    throw new GitReadError("Git returned a non-terminated NUL-delimited record");
  }
  return values.filter((value) => value.length > 0);
}

function decodePath(buffer) {
  try {
    return UTF8_DECODER.decode(buffer);
  } catch {
    throw new GitReadError("Git path is not valid UTF-8");
  }
}

export function assertRepositoryRoot(repoRoot, gitExecutable) {
  const requested = path.resolve(repoRoot);
  const reported = runGit(
    requested,
    gitExecutable,
    ["rev-parse", "--show-toplevel"],
    { encoding: "utf8" }
  ).trim();
  const normalize = (value) => process.platform === "win32" ? path.resolve(value).toLowerCase() : path.resolve(value);
  if (normalize(requested) !== normalize(reported)) {
    throw new GitReadError(`--repo must name the Git top level exactly: ${reported}`);
  }
  const shallowState = runGit(
    requested,
    gitExecutable,
    ["rev-parse", "--is-shallow-repository"],
    { encoding: "utf8" }
  ).trim().toLowerCase();
  if (shallowState === "true") {
    throw new GitReadError("Shallow repositories are not supported for documentation impact analysis");
  }
  if (shallowState !== "false") {
    throw new GitReadError(`Git returned an unsupported shallow-repository state: ${shallowState || "<empty>"}`);
  }
  readObjectFormat(requested, gitExecutable);
  return requested;
}

export function resolveCommit(repoRoot, objectId, gitExecutable) {
  const objectFormat = readObjectFormat(repoRoot, gitExecutable);
  const requestedObjectId = requireFullObjectId(objectId, objectFormat, "Base and head");
  const objectType = runGit(
    repoRoot,
    gitExecutable,
    ["cat-file", "-t", requestedObjectId],
    { encoding: "utf8" }
  ).trim();
  if (objectType !== "commit") {
    throw new GitReadError(`Base and head must identify commit objects, not ${objectType}`);
  }
  const resolved = runGit(
    repoRoot,
    gitExecutable,
    ["rev-parse", "--verify", requestedObjectId],
    { encoding: "utf8" }
  ).trim().toLowerCase();
  const fullResolved = requireFullObjectId(
    resolved,
    objectFormat,
    `Resolved commit for ${requestedObjectId}`
  );
  if (fullResolved !== requestedObjectId) {
    throw new GitReadError("Git changed an explicit commit identity during resolution");
  }
  return fullResolved;
}

export function assertObjectGraphIntegrity(repoRoot, commitShas, gitExecutable) {
  const objectFormat = readObjectFormat(repoRoot, gitExecutable);
  const commits = [...new Set(commitShas.map((commitSha) =>
    requireFullObjectId(commitSha, objectFormat, "Integrity root commit")
  ))].sort();
  if (commits.length === 0) {
    throw new GitReadError("At least one integrity root commit is required");
  }
  runGit(repoRoot, gitExecutable, [
    "fsck",
    "--strict",
    "--full",
    "--no-dangling",
    "--no-reflogs",
    "--no-progress",
    ...commits
  ]);
}

export function findMergeBase(repoRoot, baseSha, headSha, gitExecutable) {
  const objectFormat = readObjectFormat(repoRoot, gitExecutable);
  const fullBaseSha = requireFullObjectId(baseSha, objectFormat, "Base");
  const fullHeadSha = requireFullObjectId(headSha, objectFormat, "Head");
  const value = runGit(repoRoot, gitExecutable, ["merge-base", fullBaseSha, fullHeadSha], {
    encoding: "utf8",
    allowFailure: true
  });
  const mergeBase = value?.trim().toLowerCase();
  if (!mergeBase) {
    throw new GitReadError("Base and head do not have an available merge base");
  }
  return requireFullObjectId(mergeBase, objectFormat, "Merge base");
}

export function readTreeEntries(repoRoot, commitSha, gitExecutable) {
  const objectFormat = readObjectFormat(repoRoot, gitExecutable);
  const fullCommitSha = requireFullObjectId(commitSha, objectFormat, "Tree commit");
  const output = runGit(
    repoRoot,
    gitExecutable,
    ["ls-tree", "-r", "-z", "--full-tree", fullCommitSha]
  );
  const records = splitNul(output);
  if (records.length > LIMITS.maxTreeEntries) {
    throw new GitReadError(`Git tree exceeds ${LIMITS.maxTreeEntries} entries`);
  }
  return records.map((record) => {
    const separator = record.indexOf(9);
    if (separator < 0) {
      throw new GitReadError("Git tree record is malformed");
    }
    const metadata = record.subarray(0, separator).toString("ascii");
    const match = /^(\d{6}) (blob|commit) ([a-f0-9]+)$/u.exec(metadata);
    if (!match) {
      throw new GitReadError(`Git tree metadata is unsupported: ${metadata}`);
    }
    return {
      mode: match[1],
      type: match[2],
      objectId: requireFullObjectId(match[3], objectFormat, "Tree entry"),
      path: decodePath(record.subarray(separator + 1))
    };
  });
}

export function assertTreeObjectsAvailable(repoRoot, entries, gitExecutable) {
  const objectFormat = readObjectFormat(repoRoot, gitExecutable);
  const expectedTypes = new Map();
  for (const entry of entries) {
    const objectId = requireFullObjectId(entry.objectId, objectFormat, "Tree object");
    const existingType = expectedTypes.get(objectId);
    if (existingType && existingType !== entry.type) {
      throw new GitReadError(`Tree object ${objectId} has conflicting expected types`);
    }
    expectedTypes.set(objectId, entry.type);
  }
  const objectIds = [...expectedTypes.keys()].sort();
  if (objectIds.length === 0) {
    return;
  }
  const output = runGit(
    repoRoot,
    gitExecutable,
    ["cat-file", "--batch-check=%(objectname) %(objecttype)"],
    { encoding: "utf8", input: `${objectIds.join("\n")}\n` }
  );
  const records = output.trimEnd().split(/\r?\n/u);
  if (records.length !== objectIds.length) {
    throw new GitReadError("Git object availability response length is inconsistent");
  }
  for (let index = 0; index < objectIds.length; index += 1) {
    const expectedObjectId = objectIds[index];
    const match = /^([a-f0-9]+) (blob|commit|missing)$/u.exec(records[index]);
    if (!match || match[1] !== expectedObjectId) {
      throw new GitReadError(`Git object availability response is malformed: ${records[index]}`);
    }
    const expectedType = expectedTypes.get(expectedObjectId);
    if (match[2] !== expectedType) {
      throw new GitReadError(
        `Tree object ${expectedObjectId} is unavailable or has type ${match[2]}; expected ${expectedType}`
      );
    }
  }
}

export function readBlob(repoRoot, commitSha, repositoryPath, gitExecutable) {
  const objectFormat = readObjectFormat(repoRoot, gitExecutable);
  const fullCommitSha = requireFullObjectId(commitSha, objectFormat, "Blob commit");
  return runGit(
    repoRoot,
    gitExecutable,
    ["cat-file", "blob", `${fullCommitSha}:${repositoryPath}`]
  );
}

export function readChanges(repoRoot, mergeBaseSha, headSha, gitExecutable) {
  const objectFormat = readObjectFormat(repoRoot, gitExecutable);
  const fullMergeBaseSha = requireFullObjectId(mergeBaseSha, objectFormat, "Diff merge base");
  const fullHeadSha = requireFullObjectId(headSha, objectFormat, "Diff head");
  const output = runGit(repoRoot, gitExecutable, [
    "diff",
    "--raw",
    "-z",
    "--no-abbrev",
    "--no-renames",
    "--no-ext-diff",
    "--no-textconv",
    fullMergeBaseSha,
    fullHeadSha,
    "--"
  ]);
  const records = splitNul(output);
  if (records.length % 2 !== 0) {
    throw new GitReadError("Git raw diff contained an incomplete record");
  }
  if (records.length / 2 > LIMITS.maxChanges) {
    throw new GitReadError(`Git diff exceeds ${LIMITS.maxChanges} changed paths`);
  }
  const changes = [];
  for (let index = 0; index < records.length; index += 2) {
    const metadata = records[index].toString("ascii");
    const match = /^:(\d{6}) (\d{6}) ([a-f0-9]+) ([a-f0-9]+) ([A-Z])$/u.exec(metadata);
    if (!match) {
      throw new GitReadError(`Git raw diff metadata is unsupported: ${metadata}`);
    }
    requireFullObjectId(match[3], objectFormat, "Diff old object");
    requireFullObjectId(match[4], objectFormat, "Diff new object");
    changes.push({
      oldMode: match[1],
      newMode: match[2],
      status: match[5],
      path: decodePath(records[index + 1])
    });
  }
  return changes;
}
