import { dirname, isAbsolute, relative, resolve, sep } from "node:path";
import { fileURLToPath } from "node:url";

import { canonicalJson } from "../docs-impact/canonical.mjs";
import { assembleReview } from "./assembler.mjs";
import {
  CANDIDATE_IDENTITY_MODE,
  CONTRACT_VALUES,
} from "./contracts/constants.mjs";
import {
  assertCheckIgnorePath,
  runGitSync,
  verifyGitExecutableAdapter,
} from "./git-command.mjs";
import { captureCandidateIdentity } from "./identity.mjs";
import {
  assertDirectPathSnapshot,
  assertPathAbsent,
  captureDirectDirectorySnapshot,
  isPathInside,
  resolveDirectDirectoryPath,
  writeNewFileUnderSnapshot,
} from "./file-safety.mjs";
import { verifyBundleDirectory } from "./manifest.mjs";

function parseNamedArguments(argv, { allowed, pathArguments, required }) {
  const supplied = new Map();
  for (let index = 1; index < argv.length; index += 1) {
    const argument = argv[index];
    if (!allowed.has(argument)) throw new Error(`Unknown argument: ${argument}`);
    if (supplied.has(argument)) throw new Error(`Duplicate argument: ${argument}`);
    const value = argv[index + 1];
    if (!value || value.startsWith("--")) throw new Error(`${argument} requires a value`);
    supplied.set(argument, value);
    index += 1;
  }
  for (const name of required) {
    if (!supplied.has(name)) throw new Error(`${name} is required`);
  }
  for (const name of pathArguments) {
    if (supplied.has(name) && !isAbsolute(supplied.get(name))) {
      throw new Error(`${name} must be an explicit absolute path`);
    }
  }
  return supplied;
}

function expectedGitExecutableSha256(supplied) {
  const value = supplied.get("--git-executable-sha256");
  if (!/^[a-f0-9]{64}$/u.test(value ?? "")) {
    throw new Error("--git-executable-sha256 must be an explicit lowercase SHA-256 digest");
  }
  return value;
}

function parseArguments(argv) {
  if (argv[0] === "verify") {
    if (argv.length !== 5 || argv[1] !== "--bundle" || !isAbsolute(argv[2])
      || argv[3] !== "--expected-digest" || !/^[a-f0-9]{64}$/u.test(argv[4])) {
      throw new Error(
        "verify requires exactly --bundle <absolute-directory> --expected-digest <sha256>",
      );
    }
    return { command: "verify", bundleDirectory: resolve(argv[2]), expectedDigest: argv[4] };
  }
  if (argv[0] === "capture-identity") {
    const pathArguments = new Set(["--repo", "--git-executable", "--output"]);
    const common = new Set([
      ...pathArguments,
      "--repository-id",
      "--remote-name",
      "--remote-url",
      "--mode",
      "--base-commit",
      "--captured-at",
      "--git-executable-sha256",
    ]);
    const allowed = new Set([...common, "--candidate-commit"]);
    const supplied = parseNamedArguments(argv, { allowed, pathArguments, required: common });
    const mode = supplied.get("--mode");
    if (!CONTRACT_VALUES.candidateIdentityMode.includes(mode)) {
      throw new Error("--mode must explicitly be COMMITTED_GIT or DIRTY_WORKTREE");
    }
    if (mode === CANDIDATE_IDENTITY_MODE.COMMITTED_GIT && !supplied.has("--candidate-commit")) {
      throw new Error("--candidate-commit is required for COMMITTED_GIT");
    }
    if (mode === CANDIDATE_IDENTITY_MODE.DIRTY_WORKTREE && supplied.has("--candidate-commit")) {
      throw new Error("--candidate-commit is forbidden for DIRTY_WORKTREE");
    }
    return {
      command: "capture-identity",
      repositoryRoot: resolve(supplied.get("--repo")),
      gitExecutable: resolve(supplied.get("--git-executable")),
      expectedGitExecutableSha256: expectedGitExecutableSha256(supplied),
      outputPath: resolve(supplied.get("--output")),
      repositoryId: supplied.get("--repository-id"),
      repositoryRemote: {
        name: supplied.get("--remote-name"),
        url: supplied.get("--remote-url"),
      },
      mode,
      baseCommit: supplied.get("--base-commit"),
      candidateCommit: supplied.get("--candidate-commit"),
      capturedAt: supplied.get("--captured-at"),
    };
  }
  if (argv[0] !== "assemble") {
    throw new Error("Explicit command required: capture-identity, assemble, or verify");
  }
  const pathArguments = new Set([
    "--repo",
    "--request",
    "--request-schema",
    "--producer-registry",
    "--producer-registry-schema",
    "--git-executable",
  ]);
  const stringArguments = new Set([
    "--repository-id",
    "--remote-name",
    "--remote-url",
    "--evaluation-time",
    "--git-executable-sha256",
    "--producer-registry-digest",
    "--candidate-identity-id",
    "--baseline-identity-id",
    "--deployment-identity-id",
  ]);
  const allowed = new Set([...pathArguments, ...stringArguments]);
  const supplied = parseNamedArguments(argv, { allowed, pathArguments, required: allowed });
  return {
    command: "assemble",
    repositoryRoot: resolve(supplied.get("--repo")),
    requestPath: resolve(supplied.get("--request")),
    requestSchemaPath: resolve(supplied.get("--request-schema")),
    producerRegistryPath: resolve(supplied.get("--producer-registry")),
    producerRegistrySchemaPath: resolve(supplied.get("--producer-registry-schema")),
    gitExecutable: resolve(supplied.get("--git-executable")),
    expectedGitExecutableSha256: expectedGitExecutableSha256(supplied),
    repositoryId: supplied.get("--repository-id"),
    repositoryRemote: {
      name: supplied.get("--remote-name"),
      url: supplied.get("--remote-url"),
    },
    evaluationTime: supplied.get("--evaluation-time"),
    producerRegistryDigest: supplied.get("--producer-registry-digest"),
    expectedCandidateIdentityId: supplied.get("--candidate-identity-id"),
    expectedBaselineIdentityId: supplied.get("--baseline-identity-id"),
    expectedDeploymentIdentityId: supplied.get("--deployment-identity-id"),
  };
}

async function assertIdentityOutputLocation({ repositoryRoot, gitExecutable, outputPath }) {
  const root = await resolveDirectDirectoryPath({ path: repositoryRoot, label: "Repository root" });
  if (!isPathInside(root, outputPath)) throw new Error("Identity output must be inside the repository");
  const parentSnapshot = await captureDirectDirectorySnapshot({
    anchorPath: root,
    path: dirname(outputPath),
    label: "Identity output parent",
  });
  const repositoryPath = relative(root, outputPath).split(sep).join("/");
  assertCheckIgnorePath(repositoryPath);
  const tracked = runGitSync({
    repositoryRoot: root,
    gitExecutable,
    argumentsList: ["ls-files", "--error-unmatch", "--", repositoryPath],
    acceptedStatuses: [0, 1],
    literalPathspecs: true,
  });
  if (tracked.status === 0) throw new Error(`Identity output is tracked by Git: ${repositoryPath}`);
  const ignored = runGitSync({
    repositoryRoot: root,
    gitExecutable,
    argumentsList: ["check-ignore", "--no-index", "--quiet", "--", repositoryPath],
    acceptedStatuses: [0, 1],
    literalPathspecs: false,
  });
  if (ignored.status !== 0) throw new Error(`Identity output must be Git-ignored: ${repositoryPath}`);
  await assertPathAbsent(
    outputPath,
    `Identity output already exists and will not be overwritten: ${repositoryPath}`,
  );
  await assertDirectPathSnapshot(parentSnapshot);
  return { parentSnapshot, repositoryPath, root };
}

async function writeCapturedIdentity(options) {
  const verifiedGitExecutable = await verifyGitExecutableAdapter({
    gitExecutablePath: options.gitExecutable,
    expectedGitExecutableSha256: options.expectedGitExecutableSha256,
  });
  const location = await assertIdentityOutputLocation({
    ...options,
    gitExecutable: verifiedGitExecutable.executablePath,
  });
  const identity = await captureCandidateIdentity({
    repositoryRoot: location.root,
    gitExecutablePath: verifiedGitExecutable.executablePath,
    expectedGitExecutableSha256: options.expectedGitExecutableSha256,
    repositoryId: options.repositoryId,
    repositoryRemote: options.repositoryRemote,
    mode: options.mode,
    baseCommit: options.baseCommit,
    ...(options.mode === CANDIDATE_IDENTITY_MODE.COMMITTED_GIT
      ? { candidateCommit: options.candidateCommit }
      : {}),
    capturedAt: options.capturedAt,
  });
  await assertDirectPathSnapshot(location.parentSnapshot);
  await writeNewFileUnderSnapshot({
    rootSnapshot: location.parentSnapshot,
    path: options.outputPath,
    bytes: Buffer.from(canonicalJson(identity), "utf8"),
    label: `Candidate identity output ${location.repositoryPath}`,
  });
  return { identity, repositoryPath: location.repositoryPath };
}

export async function main(argv = process.argv.slice(2)) {
  const options = parseArguments(argv);
  if (options.command === "verify") {
    const verification = await verifyBundleDirectory(options.bundleDirectory, options.expectedDigest);
    process.stdout.write(canonicalJson({
      bundleDigest: verification.bundleDigest,
      fileCount: verification.fileCount,
      status: "EXPECTED_DIGEST_MATCH",
    }));
    return verification;
  }
  if (options.command === "capture-identity") {
    const captured = await writeCapturedIdentity(options);
    process.stdout.write(canonicalJson({
      candidateSnapshotDigest: captured.identity.candidateSnapshotDigest,
      identityId: captured.identity.identityId,
      mode: captured.identity.mode,
      outputPath: options.outputPath,
      repositoryPath: captured.repositoryPath,
      status: "CAPTURED",
    }));
    return captured;
  }
  const result = await assembleReview(options);
  process.stdout.write(canonicalJson({
    bundleDigest: result.bundleDigest,
    comparisonStatus: result.review.comparisonStatus,
    outputDirectory: result.outputDirectory,
    profileId: result.review.profileId,
    readinessVerdict: result.review.readinessVerdict,
    reviewId: result.review.reviewId,
    verdictScope: result.review.verdictScope,
  }));
  return result;
}

const invokedPath = process.argv[1] ? resolve(process.argv[1]) : "";
if (invokedPath === fileURLToPath(import.meta.url)) {
  main().catch((error) => {
    console.error(error.stack || error.message);
    process.exitCode = 1;
  });
}

export { parseArguments, writeCapturedIdentity };
