import { spawnSync } from "node:child_process";
import path from "node:path";

import { sha256 } from "../docs-impact/canonical.mjs";
import { CONTRACT_VALUES } from "./contracts/constants.mjs";
import {
  captureStableRegularFile,
  filesystemRoot,
  HARD_LINK_POLICY,
  resolveDirectRegularFilePath,
} from "./file-safety.mjs";

const GIT_COMMAND_TIMEOUT_MS = 30_000;
const MAX_GIT_OUTPUT_BYTES = 8 * 1024 * 1024;
const NULL_DEVICE = process.platform === "win32" ? "NUL" : "/dev/null";
const SHA256_PATTERN = /^[a-f0-9]{64}$/u;
const GIT_FOR_WINDOWS_CORE_LAYOUT = Object.freeze({
  executableName: "git.exe",
  parentDirectory: "bin",
  distributionDirectories: Object.freeze(new Set(["mingw32", "mingw64"])),
});

function assertGitForWindowsCorePath(executablePath) {
  if (process.platform !== "win32") return;
  const executableDirectory = path.win32.dirname(executablePath);
  const distributionDirectory = path.win32.dirname(executableDirectory);
  const isCore = (
    path.win32.basename(executablePath).toLowerCase()
      === GIT_FOR_WINDOWS_CORE_LAYOUT.executableName
    && path.win32.basename(executableDirectory).toLowerCase()
      === GIT_FOR_WINDOWS_CORE_LAYOUT.parentDirectory
    && GIT_FOR_WINDOWS_CORE_LAYOUT.distributionDirectories.has(
      path.win32.basename(distributionDirectory).toLowerCase(),
    )
  );
  if (!isCore) {
    throw new Error(
      "gitExecutablePath must identify a Git-for-Windows mingw32/bin or mingw64/bin core executable",
    );
  }
}

export async function verifyGitExecutableAdapter({
  gitExecutablePath,
  expectedGitExecutableSha256,
}) {
  if (typeof gitExecutablePath !== "string" || !path.isAbsolute(gitExecutablePath)) {
    throw new Error("gitExecutablePath must be an explicit absolute path");
  }
  if (!SHA256_PATTERN.test(expectedGitExecutableSha256 ?? "")) {
    throw new Error("expectedGitExecutableSha256 must be an explicit lowercase SHA-256 digest");
  }
  const executablePath = await resolveDirectRegularFilePath({
    path: gitExecutablePath,
    label: "Git executable",
  });
  assertGitForWindowsCorePath(executablePath);
  const bytes = await captureStableRegularFile({
    anchorPath: filesystemRoot(executablePath),
    hardLinkPolicy: HARD_LINK_POLICY.ALLOW_STABLE_IDENTITY,
    path: executablePath,
    label: "Git executable",
    maxBytes: CONTRACT_VALUES.limits.maxGitExecutableBytes,
  });
  const actualSha256 = sha256(bytes);
  if (actualSha256 !== expectedGitExecutableSha256) {
    throw new Error("Git executable does not match the externally expected SHA-256 digest");
  }
  return Object.freeze({
    executablePath,
    capture: Object.freeze({
      bytes,
      sha256: actualSha256,
      sizeBytes: bytes.byteLength,
    }),
  });
}

export function gitEnvironment() {
  const environment = Object.fromEntries(
    Object.entries(process.env).filter(([key]) => !key.toUpperCase().startsWith("GIT_")),
  );
  return {
    ...environment,
    GIT_ATTR_NOSYSTEM: "1",
    GIT_CONFIG_GLOBAL: NULL_DEVICE,
    GIT_CONFIG_NOSYSTEM: "1",
    GIT_NO_LAZY_FETCH: "1",
    GIT_NO_REPLACE_OBJECTS: "1",
    GIT_OPTIONAL_LOCKS: "0",
    GIT_TERMINAL_PROMPT: "0",
    LANG: "C",
    LC_ALL: "C",
  };
}

export function gitHardeningArguments({ literalPathspecs }) {
  if (typeof literalPathspecs !== "boolean") {
    throw new Error("Git literalPathspecs must be explicitly true or false");
  }
  return [
    "--no-pager",
    ...(literalPathspecs ? ["--literal-pathspecs"] : []),
    "--no-lazy-fetch",
    "--no-optional-locks",
    "--no-replace-objects",
    "-c",
    "core.quotepath=false",
    "-c",
    "core.fsmonitor=false",
    "-c",
    `core.excludesFile=${NULL_DEVICE}`,
    "-c",
    `core.attributesFile=${NULL_DEVICE}`,
    "-c",
    "core.autocrlf=false",
    "-c",
    "core.safecrlf=false",
    "-c",
    "core.fscache=false",
    "-c",
    "core.commitGraph=false",
    "-c",
    "core.ignoreStat=false",
    "-c",
    "core.ignoreCase=false",
    "-c",
    `core.fileMode=${process.platform === "win32" ? "false" : "true"}`,
    "-c",
    "core.trustctime=true",
    "-c",
    "core.checkStat=default",
    "-c",
    "core.untrackedCache=false",
    "-c",
    `fsck.skipList=${NULL_DEVICE}`,
    "-c",
    "diff.autoRefreshIndex=false",
    "-c",
    "diff.suppressBlankEmpty=false",
    "-c",
    "gc.auto=0",
    "-c",
    "color.ui=false",
  ];
}

export function runGitSync({
  repositoryRoot,
  gitExecutable,
  argumentsList,
  acceptedStatuses = [0],
  literalPathspecs,
  label,
}) {
  if (!Array.isArray(acceptedStatuses) || acceptedStatuses.length === 0) {
    throw new Error("Git accepted statuses must be an explicit non-empty array");
  }
  const globalArguments = [
    ...gitHardeningArguments({ literalPathspecs }),
    "-C",
    repositoryRoot,
  ];
  const result = spawnSync(gitExecutable, [
    ...globalArguments,
    ...argumentsList,
  ], {
    encoding: "utf8",
    env: gitEnvironment(),
    maxBuffer: MAX_GIT_OUTPUT_BYTES,
    stdio: ["ignore", "pipe", "pipe"],
    timeout: GIT_COMMAND_TIMEOUT_MS,
    windowsHide: true,
  });
  const commandLabel = label ?? `Git ${argumentsList.join(" ")}`;
  if (result.error) throw new Error(`${commandLabel} failed to start: ${result.error.message}`);
  if (!acceptedStatuses.includes(result.status)) {
    throw new Error(
      `${commandLabel} failed (exit ${result.status}): ${(result.stderr || result.stdout).trim()}`,
    );
  }
  return result;
}

export function assertCheckIgnorePath(repositoryPath) {
  if (typeof repositoryPath !== "string" || repositoryPath.length === 0) {
    throw new Error("Git check-ignore path must be an explicit non-empty repository path");
  }
  if (repositoryPath.startsWith(":") || /[?*[]/u.test(repositoryPath)) {
    throw new Error(`Git check-ignore path contains unsupported pathspec syntax: ${repositoryPath}`);
  }
  return repositoryPath;
}
