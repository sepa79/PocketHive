import { readdir } from "node:fs/promises";
import { relative, resolve, sep } from "node:path";
import { TextDecoder } from "node:util";

import { canonicalDigest, canonicalJson, sha256 } from "../docs-impact/canonical.mjs";
import {
  EVIDENCE_ADAPTER,
  EVIDENCE_KIND,
  EVALUATOR_EXECUTION_PROVENANCE_METHOD,
  EVALUATOR_EXECUTION_PROVENANCE_STATEMENT,
  EVALUATOR_EXECUTION_PROVENANCE_STATUS,
  EXECUTION_KIND,
} from "./contracts/constants.mjs";
import {
  assertDirectPathSnapshot,
  captureDirectDirectorySnapshot,
  captureStableRegularFile,
  filesystemRoot,
  HARD_LINK_POLICY,
  isPathInside,
  resolveDirectDirectoryPath,
  resolveDirectRegularFilePath,
} from "./file-safety.mjs";
import { assertContract, CONTRACT_VALUES } from "./profile.mjs";

const UTF8_DECODER = new TextDecoder("utf-8", { fatal: true });
const MAX_RECEIPT_BYTES = 16 * 1024 * 1024;
const MAX_AGGREGATE_RECEIPT_BYTES = 128 * 1024 * 1024;
const MAX_ARTIFACT_BYTES = 64 * 1024 * 1024;
const MAX_AGGREGATE_ARTIFACT_BYTES = 512 * 1024 * 1024;
const MAX_BUNDLE_FILE_BYTES = 128 * 1024 * 1024;
const MAX_AGGREGATE_BUNDLE_BYTES = 1024 * 1024 * 1024;
const MAX_BUNDLE_FILES = 20_000;
const MAX_BUNDLE_ENTRIES = 25_000;
const MAX_BUNDLE_DEPTH = 16;
const MAX_RUNTIME_PACKAGE_FILES = 4096;
const MAX_RUNTIME_PACKAGE_BYTES = 64 * 1024 * 1024;
const MAX_REPOSITORY_FILE_BYTES = 128 * 1024 * 1024;

function compareText(left, right) {
  return left < right ? -1 : left > right ? 1 : 0;
}

function withoutKey(value, key) {
  return Object.fromEntries(Object.entries(value).filter(([candidate]) => candidate !== key));
}

export function postLoadEvaluatorExecutionProvenance() {
  return {
    status: EVALUATOR_EXECUTION_PROVENANCE_STATUS.NOT_VERIFIED,
    method: EVALUATOR_EXECUTION_PROVENANCE_METHOD.POST_LOAD_FILESYSTEM_SNAPSHOT,
    executedSourceDigest: null,
    controllerAttestationRef: null,
    statement: EVALUATOR_EXECUTION_PROVENANCE_STATEMENT,
  };
}

export function evidenceReceiptId(receipt) {
  return canonicalDigest(withoutKey(receipt, "receiptId"));
}

export function assertEvidenceReceiptSemantics(receipt, label = "evidence receipt") {
  if (receipt.receiptId !== evidenceReceiptId(receipt)) {
    throw new Error(`${label} receiptId does not match its canonical fields`);
  }
  const artifactIds = receipt.artifacts.map(({ id }) => id);
  if (new Set(artifactIds).size !== artifactIds.length) {
    throw new Error(`${label} artifact IDs must be unique`);
  }
  const observationIds = receipt.observations.map(({ id }) => id);
  if (new Set(observationIds).size !== observationIds.length) {
    throw new Error(`${label} observation IDs must be unique`);
  }
  if (receipt.execution.adapter === EVIDENCE_ADAPTER.INDEPENDENT_REVIEW) {
    if (receipt.execution.kind !== EXECUTION_KIND.INDEPENDENT_REVIEW
      || receipt.kind !== EVIDENCE_KIND.REVIEWER_JUDGMENT) {
      throw new Error(`${label} INDEPENDENT_REVIEW adapter requires reviewer-judgment independent execution`);
    }
  }
  return receipt;
}

export async function readEvidenceReceipt({ path, repositoryPath, repositoryRoot, schema }) {
  let receipt;
  let bytes;
  try {
    bytes = await captureStableRegularFile({
      anchorPath: repositoryRoot,
      hardLinkPolicy: HARD_LINK_POLICY.REJECT,
      path,
      label: `Evidence receipt ${repositoryPath}`,
      maxBytes: MAX_RECEIPT_BYTES,
    });
    receipt = JSON.parse(UTF8_DECODER.decode(bytes));
  } catch (error) {
    if (error instanceof SyntaxError) {
      throw new Error(`Evidence receipt ${repositoryPath} is not valid JSON: ${error.message}`);
    }
    if (error instanceof TypeError) {
      throw new Error(`Evidence receipt ${repositoryPath} is not valid UTF-8`);
    }
    throw error;
  }
  assertContract(schema, receipt, `Evidence receipt ${repositoryPath}`);
  assertEvidenceReceiptSemantics(receipt, `Evidence receipt ${repositoryPath}`);
  return { bytes, receipt, repositoryPath };
}

export async function readRepositoryFile(
  repositoryRoot,
  repositoryPath,
  label,
  maxBytes = MAX_REPOSITORY_FILE_BYTES,
) {
  const resolvedPath = resolve(repositoryRoot, repositoryPath);
  if (!isPathInside(repositoryRoot, resolvedPath)) {
    throw new Error(`${label} escapes the repository root`);
  }
  return captureStableRegularFile({
    anchorPath: repositoryRoot,
    hardLinkPolicy: HARD_LINK_POLICY.REJECT,
    path: resolvedPath,
    label,
    maxBytes,
  });
}

export async function resolveRepositoryFile(repositoryRoot, repositoryPath, label) {
  const root = await resolveDirectDirectoryPath({
    path: repositoryRoot,
    label: "Repository root",
  });
  const lexicalPath = resolve(root, repositoryPath);
  if (!isPathInside(root, lexicalPath)) throw new Error(`${label} escapes the repository root`);
  return resolveDirectRegularFilePath({ anchorPath: root, path: lexicalPath, label });
}

export async function verifyArtifact(repositoryRoot, evidenceId, artifact) {
  if (artifact.sizeBytes > MAX_ARTIFACT_BYTES) {
    throw new Error(`${evidenceId}/${artifact.id} exceeds the ${MAX_ARTIFACT_BYTES}-byte artifact limit`);
  }
  const bytes = await readRepositoryFile(
    repositoryRoot,
    artifact.repositoryPath,
    `${evidenceId}/${artifact.id} artifact`,
  );
  if (bytes.byteLength !== artifact.sizeBytes) {
    throw new Error(`${evidenceId}/${artifact.id} artifact size does not match ${artifact.repositoryPath}`);
  }
  const digest = sha256(bytes);
  if (digest !== artifact.sha256) {
    throw new Error(`${evidenceId}/${artifact.id} artifact digest does not match ${artifact.repositoryPath}`);
  }
  return { bytes, ...artifact };
}

async function collectRuntimePackage(repositoryRoot, specification) {
  const root = await resolveDirectDirectoryPath({
    path: repositoryRoot,
    label: "Repository root",
  });
  const lexicalRoot = resolve(root, specification.repositoryPath);
  if (!isPathInside(root, lexicalRoot)) {
    throw new Error(`Runtime package ${specification.name} escapes the repository root`);
  }
  const packageLabel = `Runtime package ${specification.name}`;
  const packageRoot = await resolveDirectDirectoryPath({
    anchorPath: root,
    path: lexicalRoot,
    label: packageLabel,
  });
  const packageRootSnapshot = await captureDirectDirectorySnapshot({
    anchorPath: root,
    path: packageRoot,
    label: packageLabel,
  });
  const files = [];
  let aggregateBytes = 0;
  let packageJsonBytes = null;
  async function visit(directory, depth = 0) {
    if (depth > MAX_BUNDLE_DEPTH) {
      throw new Error(`${packageLabel} exceeds the maximum depth of ${MAX_BUNDLE_DEPTH}`);
    }
    const directorySnapshot = await captureDirectDirectorySnapshot({
      anchorPath: packageRoot,
      path: directory,
      label: `${packageLabel} directory`,
    });
    const entries = (await readdir(directory, { withFileTypes: true })).sort(
      (left, right) => compareText(left.name, right.name),
    );
    for (const entry of entries) {
      const absolutePath = resolve(directory, entry.name);
      if (entry.isSymbolicLink()) {
        throw new Error(`Runtime package ${specification.name} contains a link: ${entry.name}`);
      }
      if (entry.isDirectory()) {
        await visit(absolutePath, depth + 1);
        continue;
      }
      if (!entry.isFile()) {
        throw new Error(`Runtime package ${specification.name} contains an unsupported filesystem entry`);
      }
      if (files.length >= MAX_RUNTIME_PACKAGE_FILES) {
        throw new Error(`Runtime package ${specification.name} exceeds ${MAX_RUNTIME_PACKAGE_FILES} files`);
      }
      const bytes = await captureStableRegularFile({
        anchorPath: packageRoot,
        hardLinkPolicy: HARD_LINK_POLICY.REJECT,
        path: absolutePath,
        label: `${packageLabel} file ${entry.name}`,
        maxBytes: MAX_RUNTIME_PACKAGE_BYTES,
      });
      aggregateBytes += bytes.byteLength;
      if (aggregateBytes > MAX_RUNTIME_PACKAGE_BYTES) {
        throw new Error(`Runtime package ${specification.name} exceeds ${MAX_RUNTIME_PACKAGE_BYTES} bytes`);
      }
      const packagePath = relative(packageRoot, absolutePath).split(sep).join("/");
      files.push({
        packagePath,
        sha256: sha256(bytes),
        sizeBytes: bytes.byteLength,
      });
      if (packagePath === "package.json") packageJsonBytes = bytes;
    }
    const finalEntries = (await readdir(directory, { withFileTypes: true })).map((entry) => ({
      name: entry.name,
      kind: entry.isDirectory() ? "directory" : entry.isFile() ? "file" : "unsupported",
    })).sort((left, right) => compareText(left.name, right.name));
    const initialEntries = entries.map((entry) => ({
      name: entry.name,
      kind: entry.isDirectory() ? "directory" : entry.isFile() ? "file" : "unsupported",
    }));
    if (canonicalJson(initialEntries) !== canonicalJson(finalEntries)) {
      throw new Error(`${packageLabel} directory entries changed while they were captured`);
    }
    await assertDirectPathSnapshot(directorySnapshot);
    await assertDirectPathSnapshot(packageRootSnapshot);
  }
  await visit(packageRoot);
  const packageJsonEntry = files.find(({ packagePath }) => packagePath === "package.json");
  if (packageJsonEntry === undefined || packageJsonBytes === null) {
    throw new Error(`Runtime package ${specification.name} has no package.json`);
  }
  const packageJson = JSON.parse(UTF8_DECODER.decode(packageJsonBytes));
  if (packageJson.name !== specification.name || packageJson.version !== specification.version) {
    throw new Error(
      `Runtime package ${specification.name} must be exact version ${specification.version}`,
    );
  }
  return {
    name: specification.name,
    version: specification.version,
    repositoryPath: specification.repositoryPath,
    digest: canonicalDigest(files),
    files,
  };
}

export async function collectToolIdentity({ repositoryRoot, toolFilePaths, runtimePackageSpecifications }) {
  const sortedPaths = [...toolFilePaths].sort(compareText);
  if (new Set(sortedPaths).size !== sortedPaths.length) {
    throw new Error("Completed-work tool identity paths must be unique");
  }
  const toolFiles = [];
  const toolFileBytesByPath = new Map();
  let aggregateToolBytes = 0;
  for (const repositoryPath of sortedPaths) {
    const bytes = await readRepositoryFile(
      repositoryRoot,
      repositoryPath,
      `Completed-work tool file ${repositoryPath}`,
      CONTRACT_VALUES.limits.maxToolFileBytes,
    );
    aggregateToolBytes += bytes.byteLength;
    if (aggregateToolBytes > CONTRACT_VALUES.limits.maxAggregateToolBytes) {
      throw new Error(
        `Completed-work tool files exceed the ${CONTRACT_VALUES.limits.maxAggregateToolBytes}-byte aggregate limit`,
      );
    }
    toolFiles.push({ repositoryPath, sha256: sha256(bytes), sizeBytes: bytes.byteLength });
    toolFileBytesByPath.set(repositoryPath, bytes);
  }
  if (!Array.isArray(runtimePackageSpecifications)) {
    throw new Error("Runtime package specifications must be explicitly supplied");
  }
  const packageNames = runtimePackageSpecifications.map(({ name }) => name);
  if (new Set(packageNames).size !== packageNames.length) {
    throw new Error("Runtime package specifications must use unique names");
  }
  const runtimePackages = [];
  for (const specification of [...runtimePackageSpecifications].sort(
    (left, right) => compareText(left.name, right.name),
  )) {
    runtimePackages.push(await collectRuntimePackage(repositoryRoot, specification));
  }
  const nodeExecutablePath = await resolveDirectRegularFilePath({
    path: process.execPath,
    label: "Node executable",
  });
  const nodeExecutableBytes = await captureStableRegularFile({
    anchorPath: filesystemRoot(nodeExecutablePath),
    hardLinkPolicy: HARD_LINK_POLICY.ALLOW_STABLE_IDENTITY,
    path: nodeExecutablePath,
    label: "Node executable",
    maxBytes: CONTRACT_VALUES.limits.maxNodeExecutableBytes,
  });
  const runtime = {
    nodeVersion: process.version,
    platform: process.platform,
    architecture: process.arch,
    nodeExecutable: {
      path: nodeExecutablePath,
      sha256: sha256(nodeExecutableBytes),
      sizeBytes: nodeExecutableBytes.byteLength,
    },
  };
  const toolSourceDigest = canonicalDigest({ repositoryFiles: toolFiles, runtimePackages });
  return {
    toolFiles,
    toolFileBytesByPath,
    runtimePackages,
    toolSourceDigest,
    runtime,
    evaluatorExecutionProvenance: postLoadEvaluatorExecutionProvenance(),
    toolDigest: canonicalDigest({ sourceDigest: toolSourceDigest, runtime }),
  };
}

export async function buildEvidenceManifest({
  repositoryRoot,
  reviewRequest,
  reviewRequestBytes,
  profileId,
  profileDigest,
  verdictScope,
  identities,
  receiptFiles,
  toolIdentity,
  producerRegistry,
  producerRegistryBytes,
  generatedAt,
}) {
  const aggregateReceiptBytes = receiptFiles.reduce((sum, { bytes }) => sum + bytes.byteLength, 0);
  if (aggregateReceiptBytes > MAX_AGGREGATE_RECEIPT_BYTES) {
    throw new Error(`Evidence receipts exceed the ${MAX_AGGREGATE_RECEIPT_BYTES}-byte aggregate limit`);
  }
  const evidenceIds = receiptFiles.map(({ receipt }) => receipt.evidenceId);
  if (new Set(evidenceIds).size !== evidenceIds.length) {
    throw new Error("Evidence IDs must be unique across the review request");
  }
  const receiptIds = receiptFiles.map(({ receipt }) => receipt.receiptId);
  if (new Set(receiptIds).size !== receiptIds.length) {
    throw new Error("Evidence receipt IDs must be unique across the review request");
  }

  const {
    evaluatorExecutionProvenance,
    runtime,
    runtimePackages,
    toolDigest,
    toolFiles,
    toolSourceDigest,
  } = toolIdentity;
  if (producerRegistry.toolSourceDigest !== toolSourceDigest) {
    throw new Error("Producer registry does not pin the exact completed-work tool source digest");
  }

  const artifacts = [];
  const artifactBytesBySha256 = new Map();
  let aggregateArtifactBytes = 0;
  for (const { receipt } of receiptFiles) {
    for (const artifact of receipt.artifacts) {
      const verified = await verifyArtifact(repositoryRoot, receipt.evidenceId, artifact);
      aggregateArtifactBytes += verified.bytes.byteLength;
      if (aggregateArtifactBytes > MAX_AGGREGATE_ARTIFACT_BYTES) {
        throw new Error(`Evidence artifacts exceed the ${MAX_AGGREGATE_ARTIFACT_BYTES}-byte aggregate limit`);
      }
      const existing = artifactBytesBySha256.get(verified.sha256);
      if (existing !== undefined && !existing.equals(verified.bytes)) {
        throw new Error(`Artifact digest collision detected for ${verified.sha256}`);
      }
      artifactBytesBySha256.set(verified.sha256, verified.bytes);
      artifacts.push({
        evidenceId: receipt.evidenceId,
        artifactId: verified.id,
        kind: verified.kind,
        repositoryPath: verified.repositoryPath,
        bundlePath: `evidence/artifacts/${verified.sha256}`,
        sha256: verified.sha256,
        sizeBytes: verified.sizeBytes,
      });
    }
  }
  artifacts.sort((left, right) => compareText(
    `${left.evidenceId}/${left.artifactId}`,
    `${right.evidenceId}/${right.artifactId}`,
  ));

  const receipts = receiptFiles.map(({ bytes, receipt, repositoryPath }) => ({
    evidenceId: receipt.evidenceId,
    receiptId: receipt.receiptId,
    repositoryPath,
    bundlePath: `evidence/receipts/${receipt.receiptId}.json`,
    bundleSha256: sha256(Buffer.from(canonicalJson(receipt), "utf8")),
    rawFileSha256: sha256(bytes),
    sizeBytes: bytes.byteLength,
  })).sort((left, right) => compareText(left.evidenceId, right.evidenceId));

  const manifest = {
    schemaVersion: 1,
    profileId,
    profileDigest,
    verdictScope,
    identityRefs: {
      baselineIdentityId: identities.baseline?.identityId ?? null,
      candidateIdentityId: identities.candidate.identityId,
      deploymentIdentityId: identities.deployment?.identityId ?? null,
    },
    trustControl: {
      reviewRequest: {
        canonicalDigest: canonicalDigest(reviewRequest),
        rawFileSha256: sha256(reviewRequestBytes),
        bundlePath: "inputs/review-request.json",
        bundleSha256: sha256(canonicalFile(reviewRequest)),
      },
      producerRegistry: {
        authority: producerRegistry.authority,
        canonicalDigest: canonicalDigest(producerRegistry),
        rawFileSha256: sha256(producerRegistryBytes),
        bundlePath: "inputs/producer-registry.json",
        bundleSha256: sha256(canonicalFile(producerRegistry)),
      },
    },
    tool: {
      digest: toolDigest,
      sourceDigest: toolSourceDigest,
      evaluatorExecutionProvenance,
      files: toolFiles,
      runtimePackages,
      runtime,
    },
    receipts,
    artifacts,
    generatedAt,
  };
  return {
    artifacts,
    artifactBytesBySha256,
    evidenceManifest: manifest,
    evidenceManifestDigest: canonicalDigest(manifest),
    toolSourceDigest,
    toolDigest,
    receipts,
    receiptDigestById: new Map(receiptFiles.map(({ receipt }) => [receipt.evidenceId, receipt.receiptId])),
  };
}

export function bundleDigest(entries) {
  return canonicalDigest([...entries].sort((left, right) => compareText(left.path, right.path)));
}

export function bundleChecksumText(entries) {
  const sorted = [...entries].sort((left, right) => compareText(left.path, right.path));
  const digest = bundleDigest(sorted);
  return `# bundleDigest ${digest}\n${sorted.map(({ path, sha256: digestValue }) => `${digestValue}  ${path}`).join("\n")}\n`;
}

export function canonicalFile(value) {
  return Buffer.from(canonicalJson(value), "utf8");
}

async function bundleFiles(
  rootSnapshot,
  directory = rootSnapshot.target,
  state = { entries: 0, files: 0 },
  depth = 0,
) {
  if (depth > MAX_BUNDLE_DEPTH) throw new Error(`Bundle exceeds the maximum depth of ${MAX_BUNDLE_DEPTH}`);
  const directorySnapshot = await captureDirectDirectorySnapshot({
    anchorPath: rootSnapshot.target,
    path: directory,
    label: "Bundle directory",
  });
  const files = [];
  const entries = (await readdir(directory, { withFileTypes: true })).sort(
    (left, right) => compareText(left.name, right.name),
  );
  for (const entry of entries) {
    state.entries += 1;
    if (state.entries > MAX_BUNDLE_ENTRIES) {
      throw new Error(`Bundle exceeds the maximum entry count of ${MAX_BUNDLE_ENTRIES}`);
    }
    const absolutePath = resolve(directory, entry.name);
    if (entry.isSymbolicLink()) throw new Error(`Bundle contains a symbolic link: ${entry.name}`);
    if (entry.isDirectory()) {
      files.push(...await bundleFiles(rootSnapshot, absolutePath, state, depth + 1));
    } else if (entry.isFile()) {
      state.files += 1;
      if (state.files > MAX_BUNDLE_FILES) {
        throw new Error(`Bundle exceeds the maximum file count of ${MAX_BUNDLE_FILES}`);
      }
      files.push(relative(rootSnapshot.target, absolutePath).split(sep).join("/"));
    } else {
      throw new Error(`Bundle contains an unsupported filesystem entry: ${entry.name}`);
    }
  }
  const finalEntries = (await readdir(directory, { withFileTypes: true })).map((entry) => ({
    name: entry.name,
    kind: entry.isDirectory() ? "directory" : entry.isFile() ? "file" : "unsupported",
  })).sort((left, right) => compareText(left.name, right.name));
  const initialEntries = entries.map((entry) => ({
    name: entry.name,
    kind: entry.isDirectory() ? "directory" : entry.isFile() ? "file" : "unsupported",
  }));
  if (canonicalJson(initialEntries) !== canonicalJson(finalEntries)) {
    throw new Error("Bundle directory entries changed while they were inspected");
  }
  await assertDirectPathSnapshot(directorySnapshot);
  await assertDirectPathSnapshot(rootSnapshot);
  return files.sort(compareText);
}

export async function verifyBundleDirectory(bundleDirectory, expectedDigest) {
  if (typeof expectedDigest !== "string" || !/^[a-f0-9]{64}$/u.test(expectedDigest)) {
    throw new Error("Bundle verification requires an explicit expected SHA-256 bundle digest");
  }
  const rootSnapshot = await captureDirectDirectorySnapshot({
    path: bundleDirectory,
    label: "Bundle path",
  });
  const root = rootSnapshot.target;
  const checksumBytes = await captureStableRegularFile({
    anchorPath: root,
    hardLinkPolicy: HARD_LINK_POLICY.REJECT,
    path: resolve(root, "bundle.sha256"),
    label: "Bundle checksum file",
    maxBytes: 16 * 1024 * 1024,
  });
  const checksumText = UTF8_DECODER.decode(checksumBytes);
  const lines = checksumText.split("\n");
  const header = /^# bundleDigest ([a-f0-9]{64})$/u.exec(lines.shift() ?? "");
  if (header === null) throw new Error("Bundle checksum header is invalid");
  if (lines.at(-1) === "") lines.pop();
  const expected = new Map();
  for (const line of lines) {
    const match = /^([a-f0-9]{64})  (.+)$/u.exec(line);
    if (match === null) throw new Error(`Invalid bundle checksum line: ${line}`);
    const path = match[2];
    const absolutePath = resolve(root, path);
    if (!isPathInside(root, absolutePath) || path.includes("\\")) throw new Error(`Unsafe bundle checksum path: ${path}`);
    if (expected.has(path)) throw new Error(`Duplicate bundle checksum path: ${path}`);
    expected.set(path, match[1]);
  }
  if (expected.size > MAX_BUNDLE_FILES) {
    throw new Error(`Bundle checksum exceeds the maximum file count of ${MAX_BUNDLE_FILES}`);
  }
  const actualPaths = (await bundleFiles(rootSnapshot)).filter((path) => path !== "bundle.sha256");
  if (canonicalJson([...expected.keys()].sort(compareText)) !== canonicalJson(actualPaths)) {
    throw new Error("Bundle file set does not match bundle.sha256");
  }
  const entries = [];
  let aggregateBytes = 0;
  for (const path of actualPaths) {
    const absolutePath = resolve(root, path);
    const bytes = await captureStableRegularFile({
      anchorPath: root,
      hardLinkPolicy: HARD_LINK_POLICY.REJECT,
      path: absolutePath,
      label: `Bundle file ${path}`,
      maxBytes: MAX_BUNDLE_FILE_BYTES,
    });
    aggregateBytes += bytes.byteLength;
    if (aggregateBytes > MAX_AGGREGATE_BUNDLE_BYTES) {
      throw new Error(`Bundle exceeds the ${MAX_AGGREGATE_BUNDLE_BYTES}-byte aggregate limit`);
    }
    const digest = sha256(bytes);
    if (digest !== expected.get(path)) throw new Error(`Bundle file digest mismatch: ${path}`);
    entries.push({ path, sha256: digest });
  }
  const finalPaths = (await bundleFiles(rootSnapshot)).filter((path) => path !== "bundle.sha256");
  if (canonicalJson(finalPaths) !== canonicalJson(actualPaths)) {
    throw new Error("Bundle file set changed while the bundle was verified");
  }
  await assertDirectPathSnapshot(rootSnapshot);
  const digest = bundleDigest(entries);
  if (digest !== header[1]) throw new Error("Bundle digest does not match its checksum entries");
  if (digest !== expectedDigest) throw new Error("Bundle digest does not match the externally expected digest");
  return { bundleDigest: digest, fileCount: entries.length };
}
