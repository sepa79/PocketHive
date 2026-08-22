import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import {
  mkdir,
  mkdtemp,
  readFile,
  realpath,
  rm,
  stat,
  writeFile,
} from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";

import { canonicalDigest, canonicalJson, sha256 } from "../docs-impact/canonical.mjs";
import { assembleReview } from "./assembler.mjs";
import {
  candidateSnapshotDigest,
  captureCandidateIdentity,
  identityId,
} from "./identity.mjs";
import {
  collectToolIdentity,
  evidenceReceiptId,
  verifyBundleDirectory,
} from "./manifest.mjs";
import { loadReviewProfiles } from "./profile.mjs";

const ROOT = await realpath(path.resolve("."));
const IGNORED_ROOT = path.join(ROOT, ".test-results", "completed-work-review");
const REQUEST_SCHEMA = path.join(
  ROOT,
  "tools/completed-work-review/contracts/review-request.schema.json",
);
const PRODUCER_REGISTRY_SCHEMA = path.join(
  ROOT,
  "tools/completed-work-review/contracts/producer-registry.schema.json",
);
const PROFILE_PATH = path.join(ROOT, "docs/ci/completed-work-review-profiles.json");
const PROFILE_SCHEMA = path.join(ROOT, "docs/ci/completed-work-review-profiles.schema.json");
const TOOL_FILES_PATH = path.join(
  ROOT,
  "tools/completed-work-review/contracts/tool-files.json",
);
const REPOSITORY_ID = "sepa79/PocketHive";
const REPOSITORY_REMOTE_NAME = "origin";

function commandOutput(command, argumentsList, cwd = ROOT) {
  const result = spawnSync(command, argumentsList, {
    cwd,
    encoding: "utf8",
    maxBuffer: 32 * 1024 * 1024,
    shell: false,
    stdio: ["ignore", "pipe", "pipe"],
    windowsHide: true,
  });
  if (result.error) throw result.error;
  if (result.status !== 0) {
    throw new Error(
      `${command} ${argumentsList.join(" ")} failed (${result.status}): `
      + `${(result.stderr || result.stdout).trim()}`,
    );
  }
  return result.stdout.trim();
}

async function discoverGitExecutable() {
  if (process.platform === "win32") {
    for (const candidate of [
      process.env.GIT_TEST_EXECUTABLE,
      "C:\\Program Files\\Git\\mingw64\\bin\\git.exe",
    ]) {
      if (typeof candidate !== "string" || !path.isAbsolute(candidate)) continue;
      const resolved = await realpath(candidate).catch(() => null);
      if (resolved !== null) return resolved;
    }
    throw new Error(
      "Set GIT_TEST_EXECUTABLE to the explicit Git-for-Windows mingw64/bin/git.exe path",
    );
  }
  const command = process.platform === "win32" ? "where.exe" : "which";
  const query = process.platform === "win32" ? "git.exe" : "git";
  const located = commandOutput(command, [query], ROOT).split(/\r?\n/u)[0];
  if (!path.isAbsolute(located)) throw new Error("Git discovery did not return an absolute path");
  return realpath(located);
}

const GIT_EXECUTABLE = await discoverGitExecutable();
const GIT_EXECUTABLE_SHA256 = sha256(await readFile(GIT_EXECUTABLE));
const REPOSITORY_REMOTE = Object.freeze({
  name: REPOSITORY_REMOTE_NAME,
  url: commandOutput(GIT_EXECUTABLE, ["remote", "get-url", REPOSITORY_REMOTE_NAME]),
});
const HEAD_COMMIT = commandOutput(GIT_EXECUTABLE, ["rev-parse", "HEAD"]);
const PROFILE_STATE = await loadReviewProfiles({
  anchorPath: ROOT,
  profilesPath: PROFILE_PATH,
  schemaPath: PROFILE_SCHEMA,
});
const TOOL_FILES_DOCUMENT = JSON.parse(await readFile(TOOL_FILES_PATH, "utf8"));
const TOOL_IDENTITY = await collectToolIdentity({
  repositoryRoot: ROOT,
  toolFilePaths: TOOL_FILES_DOCUMENT.files,
  runtimePackageSpecifications: TOOL_FILES_DOCUMENT.runtimePackages,
});

const INITIAL_CAPTURED_AT = new Date().toISOString();

const PRODUCER_DIGESTS = Object.freeze({
  "score-producer": "1".repeat(64),
  "validation-producer": "2".repeat(64),
  "automation-producer": "3".repeat(64),
  "docs-impact-producer": "4".repeat(64),
  "review-producer": "5".repeat(64),
});

function toRepositoryPath(absolutePath) {
  return path.relative(ROOT, absolutePath).split(path.sep).join("/");
}

async function writeJson(filePath, value) {
  await mkdir(path.dirname(filePath), { recursive: true });
  await writeFile(filePath, `${JSON.stringify(value, null, 2)}\n`, "utf8");
}

function unlock(description, kind = "EXTERNAL_EVIDENCE", evidenceKinds = ["MEASURED"]) {
  return { kind, description, requiredEvidenceKinds: evidenceKinds };
}

function gateOutcome(gateId, status = "PASS") {
  return {
    gateId,
    status,
    checkId: `${gateId}-check`,
    checkContractDigest: canonicalDigest({ gateId, contract: "test-contract-v1" }),
    configurationDigest: canonicalDigest({ gateId, configuration: "test-configuration-v1" }),
  };
}

function makeReceipt({
  evidenceId,
  kind,
  subject,
  subjectIdentityRef,
  profileDigest,
  producerId,
  executionKind,
  adapter,
  officialIngress,
  gateOutcomes = [],
  scoreAttestations = [],
  searchDiscovery = null,
  independentReview = null,
  findingApprovals = [],
  createdAt,
}) {
  const receipt = {
    schemaVersion: 1,
    receiptId: "0".repeat(64),
    evidenceId,
    kind,
    subject,
    subjectIdentityRef,
    profileDigest,
    producer: {
      id: producerId,
      version: "1.0.0",
      digest: PRODUCER_DIGESTS[producerId],
    },
    execution: {
      kind: executionKind,
      adapter,
      entrypoint: gateOutcomes.length > 0 ? "test-adapter" : null,
      arguments: [],
      officialIngress,
    },
    status: "PASS",
    summary: `${evidenceId} passed for the exact bound test candidate.`,
    claims: {
      gateOutcomes,
      scoreAttestations,
      searchDiscovery,
      independentReview,
      findingApprovals,
    },
    artifacts: [],
    observations: gateOutcomes.length > 0
      ? [{ id: "check-result", label: "Check result", value: true, unit: null }]
      : [],
    createdAt,
  };
  receipt.receiptId = evidenceReceiptId(receipt);
  return receipt;
}

function producerPolicies(receipts) {
  const grouped = new Map();
  for (const receipt of receipts) {
    const existing = grouped.get(receipt.producer.id) ?? {
      id: receipt.producer.id,
      digest: receipt.producer.digest,
      adapters: new Set(),
      executionKinds: new Set(),
      evidenceKinds: new Set(),
      gateChecks: new Map(),
      reviewerIds: new Set(),
    };
    assert.equal(existing.digest, receipt.producer.digest);
    existing.adapters.add(receipt.execution.adapter);
    existing.executionKinds.add(receipt.execution.kind);
    existing.evidenceKinds.add(receipt.kind);
    for (const outcome of receipt.claims.gateOutcomes) {
      const gateCheck = {
        gateId: outcome.gateId,
        checkId: outcome.checkId,
        checkContractDigest: outcome.checkContractDigest,
        configurationDigest: outcome.configurationDigest,
      };
      existing.gateChecks.set(canonicalJson(gateCheck), gateCheck);
    }
    if (receipt.claims.independentReview !== null) {
      existing.reviewerIds.add(receipt.claims.independentReview.reviewerId);
    }
    grouped.set(existing.id, existing);
  }
  return [...grouped.values()].map((producer) => ({
    id: producer.id,
    digest: producer.digest,
    adapters: [...producer.adapters].sort(),
    executionKinds: [...producer.executionKinds].sort(),
    evidenceKinds: [...producer.evidenceKinds].sort(),
    gateChecks: [...producer.gateChecks.values()].sort(
      (left, right) => canonicalJson(left).localeCompare(canonicalJson(right)),
    ),
    reviewerIds: [...producer.reviewerIds].sort(),
  })).sort((left, right) => left.id.localeCompare(right.id));
}

function makeRegistry({ authority, profileId, profileDigest, receipts, generatedAt }) {
  return {
    schemaVersion: 1,
    authority,
    profileId,
    profileDigest,
    toolSourceDigest: TOOL_IDENTITY.toolSourceDigest,
    producers: producerPolicies(receipts),
    receiptAuthorizations: receipts.map((receipt) => ({
      evidenceId: receipt.evidenceId,
      receiptId: receipt.receiptId,
      producerId: receipt.producer.id,
    })).sort((left, right) => left.evidenceId.localeCompare(right.evidenceId)),
    generatedAt,
    statement: authority === "OPERATOR_SUPPLIED"
      ? "External test operator authorizes only the exact listed receipts."
      : "Repository-contained registry is candidate supplied and cannot establish trust.",
  };
}

async function liveIdentities() {
  const dirty = commandOutput(
    GIT_EXECUTABLE,
    ["status", "--porcelain=v1", "--untracked-files=all"],
  ).length > 0;
  const candidate = await captureCandidateIdentity({
    repositoryRoot: ROOT,
    gitExecutablePath: GIT_EXECUTABLE,
    expectedGitExecutableSha256: GIT_EXECUTABLE_SHA256,
    repositoryId: REPOSITORY_ID,
    repositoryRemote: REPOSITORY_REMOTE,
    mode: dirty ? "DIRTY_WORKTREE" : "COMMITTED_GIT",
    baseCommit: HEAD_COMMIT,
    ...(dirty ? {} : { candidateCommit: HEAD_COMMIT }),
    capturedAt: INITIAL_CAPTURED_AT,
  });
  const baseline = await captureCandidateIdentity({
    repositoryRoot: ROOT,
    gitExecutablePath: GIT_EXECUTABLE,
    expectedGitExecutableSha256: GIT_EXECUTABLE_SHA256,
    repositoryId: REPOSITORY_ID,
    repositoryRemote: REPOSITORY_REMOTE,
    mode: "COMMITTED_GIT",
    baseCommit: HEAD_COMMIT,
    candidateCommit: HEAD_COMMIT,
    capturedAt: INITIAL_CAPTURED_AT,
  });
  return { baseline, candidate };
}

const LIVE_IDENTITIES = await liveIdentities();

function retimeIdentity(identity, capturedAt) {
  const retimed = { ...structuredClone(identity), capturedAt, identityId: "0".repeat(64) };
  retimed.identityId = identityId(retimed);
  return retimed;
}

function buildReceipts({ profile, profileDigest, candidate, baseline, includeBaseline, createdAt }) {
  const scoreRefs = [];
  const receipts = [];
  if (includeBaseline) {
    receipts.push(makeReceipt({
      evidenceId: "baseline-scores",
      kind: "REVIEWER_JUDGMENT",
      subject: "BASELINE",
      subjectIdentityRef: baseline.identityId,
      profileDigest,
      producerId: "score-producer",
      executionKind: "AUTOMATED_CHECK",
      adapter: "MANUAL_INSPECTION",
      officialIngress: false,
      createdAt,
      gateOutcomes: [gateOutcome("required-score-evidence")],
      scoreAttestations: profile.dimensions.map(({ id }) => ({
        dimensionId: id,
        side: "BASELINE",
        score: 7.0,
      })),
    }));
    scoreRefs.push("baseline-scores");
  }
  receipts.push(makeReceipt({
    evidenceId: "current-scores",
    kind: "REVIEWER_JUDGMENT",
    subject: "REVIEW",
    subjectIdentityRef: candidate.identityId,
    profileDigest,
    producerId: "score-producer",
    executionKind: "AUTOMATED_CHECK",
    adapter: "MANUAL_INSPECTION",
    officialIngress: false,
    createdAt,
    gateOutcomes: [gateOutcome("required-score-evidence")],
    scoreAttestations: profile.dimensions.map(({ id }) => ({
      dimensionId: id,
      side: "CURRENT",
      score: 8.0,
    })),
  }));
  scoreRefs.push("current-scores");

  const verificationEvidenceId = profile.kind === "DOCUMENTATION"
    ? "documentation-validation"
    : "automation-tests";
  receipts.push(makeReceipt({
    evidenceId: verificationEvidenceId,
    kind: "MEASURED",
    subject: "CANDIDATE",
    subjectIdentityRef: candidate.identityId,
    profileDigest,
    producerId: profile.kind === "DOCUMENTATION"
      ? "validation-producer"
      : "automation-producer",
    executionKind: "AUTOMATED_CHECK",
    adapter: profile.kind === "DOCUMENTATION" ? "DOCS_VALIDATION" : "NODE_TEST",
    officialIngress: profile.kind === "DOCUMENTATION",
    createdAt,
    gateOutcomes: profile.kind === "DOCUMENTATION"
      ? [gateOutcome("documentation-validation"), gateOutcome("search-ai-discovery")]
      : [gateOutcome("focused-tests"), gateOutcome("schema-projection-tests")],
    searchDiscovery: profile.kind === "DOCUMENTATION"
      ? { status: "NO_MATERIAL_CHANGE" }
      : null,
  }));
  receipts.push(makeReceipt({
    evidenceId: "docs-impact-evidence",
    kind: "MEASURED",
    subject: "CANDIDATE",
    subjectIdentityRef: candidate.identityId,
    profileDigest,
    producerId: "docs-impact-producer",
    executionKind: "AUTOMATED_CHECK",
    adapter: "DOCS_IMPACT",
    officialIngress: false,
    createdAt,
    gateOutcomes: [gateOutcome("docs-impact-disposition")],
  }));

  const reviewRefs = [];
  for (const passKind of profile.independentReviewPasses) {
    const evidenceId = `review-${passKind.toLowerCase()}`;
    receipts.push(makeReceipt({
      evidenceId,
      kind: "REVIEWER_JUDGMENT",
      subject: "REVIEW",
      subjectIdentityRef: candidate.identityId,
      profileDigest,
      producerId: "review-producer",
      executionKind: "INDEPENDENT_REVIEW",
      adapter: "INDEPENDENT_REVIEW",
      officialIngress: false,
      createdAt,
      gateOutcomes: [gateOutcome("independent-review")],
      independentReview: {
        reviewerId: `reviewer-${passKind.toLowerCase()}`,
        passKind,
      },
    }));
    reviewRefs.push(evidenceId);
  }
  return { receipts, reviewRefs, scoreRefs, verificationEvidenceId };
}

function gateInput(id, evidenceRefs) {
  return {
    id,
    summary: `${id} must satisfy its exact typed policy.`,
    evidenceRefs,
    unlock: unlock(`Supply exact trusted evidence for ${id}.`),
  };
}

function gateEvidence(gateId, evidence) {
  switch (gateId) {
    case "candidate-identity":
    case "baseline-identity":
      return [];
    case "required-score-evidence":
      return evidence.scoreRefs;
    case "documentation-validation":
    case "focused-tests":
    case "schema-projection-tests":
      return [evidence.verificationEvidenceId];
    case "docs-impact-disposition":
      return ["docs-impact-evidence"];
    case "search-ai-discovery":
      return [evidence.verificationEvidenceId];
    case "independent-review":
      return evidence.reviewRefs;
    default:
      throw new Error(`Test fixture has no policy for gate ${gateId}`);
  }
}

async function createFixture({
  profileId = "POCKETHIVE_DOCUMENTATION_V1",
  includeBaseline = true,
  registryInsideRepository = false,
} = {}) {
  await mkdir(IGNORED_ROOT, { recursive: true });
  const repositoryFixtureRoot = await mkdtemp(path.join(IGNORED_ROOT, "assembler-"));
  const externalRoot = await mkdtemp(path.join(os.tmpdir(), "pockethive-review-registry-"));
  const evaluatedAt = new Date().toISOString();
  const identities = {
    baseline: retimeIdentity(LIVE_IDENTITIES.baseline, evaluatedAt),
    candidate: retimeIdentity(LIVE_IDENTITIES.candidate, evaluatedAt),
  };
  const profile = structuredClone(PROFILE_STATE.profilesById.get(profileId));
  const candidatePath = path.join(repositoryFixtureRoot, "candidate-identity.json");
  const baselinePath = path.join(repositoryFixtureRoot, "baseline-identity.json");
  await writeJson(candidatePath, identities.candidate);
  if (includeBaseline) await writeJson(baselinePath, identities.baseline);

  const evidence = buildReceipts({
    profile,
    profileDigest: PROFILE_STATE.configDigest,
    candidate: identities.candidate,
    baseline: identities.baseline,
    includeBaseline,
    createdAt: evaluatedAt,
  });
  const receiptPaths = new Map();
  for (const receipt of evidence.receipts) {
    const receiptPath = path.join(repositoryFixtureRoot, `${receipt.evidenceId}.json`);
    receiptPaths.set(receipt.evidenceId, receiptPath);
    await writeJson(receiptPath, receipt);
  }

  const registryPath = registryInsideRepository
    ? path.join(repositoryFixtureRoot, "producer-registry.json")
    : path.join(externalRoot, "producer-registry.json");
  const registry = makeRegistry({
    authority: registryInsideRepository ? "CANDIDATE_UNVERIFIED" : "OPERATOR_SUPPLIED",
    profileId,
    profileDigest: PROFILE_STATE.configDigest,
    receipts: evidence.receipts,
    generatedAt: evaluatedAt,
  });
  await writeJson(registryPath, registry);

  const outputDirectory = path.join(repositoryFixtureRoot, "output");
  const requestPath = path.join(repositoryFixtureRoot, "request.json");
  const request = {
    schemaVersion: 1,
    profileId,
    verdictScope: "LOCAL_CANDIDATE",
    candidateVerificationTarget: "LIVE_WORKTREE",
    paths: {
      profiles: "docs/ci/completed-work-review-profiles.json",
      profileSchema: "docs/ci/completed-work-review-profiles.schema.json",
      candidateIdentitySchema: "tools/completed-work-review/contracts/candidate-identity.schema.json",
      evidenceReceiptSchema: "tools/completed-work-review/contracts/evidence-receipt.schema.json",
      reviewResultSchema: "tools/completed-work-review/contracts/review-result.schema.json",
      candidateIdentity: toRepositoryPath(candidatePath),
      baselineIdentity: includeBaseline ? toRepositoryPath(baselinePath) : null,
      deploymentIdentity: null,
      evidenceReceipts: evidence.receipts.map(({ evidenceId }) => (
        toRepositoryPath(receiptPaths.get(evidenceId))
      )),
      outputDirectory: toRepositoryPath(outputDirectory),
    },
    approvedReferenceEvidenceRefs: [],
    dimensions: profile.dimensions.map(({ id }) => ({
      id,
      baseline: includeBaseline ? 7.0 : null,
      current: 8.0,
      evidenceRefs: evidence.scoreRefs,
      notes: `${id} uses exact baseline and current receipt attestations.`,
    })),
    gateInputs: profile.requiredGates.map(({ id }) => (
      gateInput(id, gateEvidence(id, evidence))
    )),
    docsImpact: {
      authority: "INFORMATIONAL_UNVERIFIED",
      analysisEvidenceRef: "docs-impact-evidence",
      statement: "Documentation impact is explicitly informational in schema v1.",
    },
    searchDiscovery: profile.kind === "DOCUMENTATION"
      ? {
        status: "NO_MATERIAL_CHANGE",
        statement: "The controlled fixture has no material search or AI discovery change.",
        evidenceRefs: [evidence.verificationEvidenceId],
        actions: [],
      }
      : {
        status: "NOT_APPLICABLE",
        statement: "This internal automation fixture has no public web surface.",
        evidenceRefs: [],
        actions: [],
      },
    publicationBoundary: {
      maximumVerifiedScope: "LOCAL_CANDIDATE",
      mergeAuthority: "NOT_GRANTED",
      publicationAuthority: "NOT_GRANTED",
      deploymentAuthority: "NOT_GRANTED",
      authorityEvidenceRefs: [],
      statement: "This test review grants no merge, publication, or deployment authority.",
    },
    confidence: {
      label: "HIGH",
      value: 0.90,
      basisEvidenceRefs: [evidence.verificationEvidenceId, evidence.reviewRefs[0]],
      limitations: ["The v1 documentation-impact controller remains unavailable."],
    },
    blockers: [],
    regressions: [],
    remainingGaps: [],
  };
  await writeJson(requestPath, request);

  return {
    baselinePath,
    candidatePath,
    evidence,
    evaluatedAt,
    externalRoot,
    identities,
    outputDirectory,
    profile,
    receiptPaths,
    registry,
    registryInsideRepository,
    registryPath,
    repositoryFixtureRoot,
    request,
    requestPath,
  };
}

async function rebuildRegistry(fixture) {
  fixture.registry = makeRegistry({
    authority: fixture.registryInsideRepository ? "CANDIDATE_UNVERIFIED" : "OPERATOR_SUPPLIED",
    profileId: fixture.request.profileId,
    profileDigest: PROFILE_STATE.configDigest,
    receipts: fixture.evidence.receipts,
    generatedAt: fixture.evaluatedAt,
  });
  await writeJson(fixture.registryPath, fixture.registry);
}

async function mutateReceipt(fixture, evidenceId, mutate) {
  const receipt = fixture.evidence.receipts.find((item) => item.evidenceId === evidenceId);
  assert.ok(receipt, `Missing receipt ${evidenceId}`);
  mutate(receipt);
  receipt.receiptId = "0".repeat(64);
  receipt.receiptId = evidenceReceiptId(receipt);
  await writeJson(fixture.receiptPaths.get(evidenceId), receipt);
  await rebuildRegistry(fixture);
  return receipt;
}

async function runFixture(fixture, overrides = {}) {
  await writeJson(fixture.requestPath, fixture.request);
  await writeJson(fixture.registryPath, fixture.registry);
  return assembleReview({
    repositoryRoot: ROOT,
    requestPath: fixture.requestPath,
    requestSchemaPath: REQUEST_SCHEMA,
    producerRegistryPath: fixture.registryPath,
    producerRegistrySchemaPath: PRODUCER_REGISTRY_SCHEMA,
    gitExecutable: GIT_EXECUTABLE,
    expectedGitExecutableSha256: GIT_EXECUTABLE_SHA256,
    repositoryId: REPOSITORY_ID,
    repositoryRemote: REPOSITORY_REMOTE,
    evaluationTime: fixture.evaluatedAt,
    producerRegistryDigest: canonicalDigest(fixture.registry),
    expectedCandidateIdentityId: fixture.identities.candidate.identityId,
    expectedBaselineIdentityId: fixture.request.paths.baselineIdentity === null
      ? "NONE"
      : fixture.identities.baseline.identityId,
    expectedDeploymentIdentityId: "NONE",
    ...overrides,
  });
}

async function withFixture(options, action) {
  const fixture = await createFixture(options);
  try {
    return await action(fixture);
  } finally {
    await rm(fixture.repositoryFixtureRoot, { recursive: true, force: true });
    await rm(fixture.externalRoot, { recursive: true, force: true });
  }
}

function gateStatus(review, gateId) {
  return review.gates.find(({ id }) => id === gateId)?.status;
}

test("assembles deterministic identity-, tool-, profile-, registry-, and receipt-bound output", async () => {
  await withFixture({}, async (fixture) => {
    const first = await runFixture(fixture);
    assert.equal(first.review.profileDigest, PROFILE_STATE.configDigest);
    assert.equal(first.review.trustControl.producerRegistryAuthority, "OPERATOR_SUPPLIED");
    assert.equal(first.review.trustControl.toolSourceDigest, TOOL_IDENTITY.toolSourceDigest);
    assert.deepEqual(
      first.review.trustControl.evaluatorExecutionProvenance,
      TOOL_IDENTITY.evaluatorExecutionProvenance,
    );
    assert.equal(first.review.freshness.toolSourceSnapshotMatch, "MATCH");
    assert.equal(first.review.freshness.status, "FRESH");
    assert.equal(first.review.freshness.evaluatedAt, fixture.evaluatedAt);
    assert.equal(first.review.generatedAt, fixture.evaluatedAt);
    assert.equal(first.review.comparisonStatus, "IMPROVED");
    assert.deepEqual(first.review.overall, {
      baseline: 7.0,
      current: 8.0,
      delta: 1.0,
      comparisonStatus: "IMPROVED",
    });
    assert.equal(gateStatus(first.review, "required-score-evidence"), "VERIFIED");
    assert.equal(gateStatus(first.review, "documentation-validation"), "VERIFIED");
    assert.equal(gateStatus(first.review, "independent-review"), "VERIFIED");
    assert.equal(gateStatus(first.review, "docs-impact-disposition"), "NOT_VERIFIED");
    assert.equal(first.review.readinessVerdict, "NOT_READY");
    assert.equal(first.review.confidence.label, "LOW");
    assert.equal(first.review.confidence.value, 0.4);
    assert.match(first.review.confidence.limitations.join(" "), /docs-impact-disposition/u);
    assert.match(first.review.confidence.limitations.join(" "), /execution provenance is not verified/u);
    assert.deepEqual(
      first.review.blockers.filter(({ id }) => id === "evaluator-execution-provenance-unverified")
        .map(({ severity, status }) => ({ severity, status })),
      [{ severity: "MATERIAL", status: "OPEN" }],
    );
    assert.deepEqual(first.review.submittedConfidence, fixture.request.confidence);
    assert.deepEqual(first.review.independentReview, {
      status: "VERIFIED",
      reviewerIds: ["reviewer-expert", "reviewer-novice", "reviewer-ux"],
      passKinds: ["EXPERT", "NOVICE", "UX"],
      evidenceRefs: ["review-expert", "review-novice", "review-ux"],
    });
    const bundledRequest = JSON.parse(await readFile(
      path.join(fixture.outputDirectory, "inputs", "review-request.json"),
      "utf8",
    ));
    const bundledRegistry = JSON.parse(await readFile(
      path.join(fixture.outputDirectory, "inputs", "producer-registry.json"),
      "utf8",
    ));
    const evidenceManifest = JSON.parse(await readFile(
      path.join(fixture.outputDirectory, "evidence-manifest.json"),
      "utf8",
    ));
    assert.deepEqual(bundledRequest, fixture.request);
    assert.deepEqual(bundledRegistry, fixture.registry);
    assert.equal(
      evidenceManifest.trustControl.reviewRequest.canonicalDigest,
      canonicalDigest(fixture.request),
    );
    assert.equal(
      evidenceManifest.trustControl.producerRegistry.canonicalDigest,
      canonicalDigest(fixture.registry),
    );
    assert.deepEqual(
      evidenceManifest.tool.evaluatorExecutionProvenance,
      TOOL_IDENTITY.evaluatorExecutionProvenance,
    );
    const verifiedBundle = await verifyBundleDirectory(fixture.outputDirectory, first.bundleDigest);
    assert.equal(verifiedBundle.bundleDigest, first.bundleDigest);
    assert.ok(verifiedBundle.fileCount > 17, "Offline reconstruction material was not bundled");
    const reconstruction = JSON.parse(await readFile(
      path.join(fixture.outputDirectory, "subject", "candidate", "reconstruction.json"),
      "utf8",
    ));
    assert.equal(reconstruction.identityId, fixture.identities.candidate.identityId);
    assert.equal(reconstruction.trackedPatch.sha256, fixture.identities.candidate.trackedPatchDigest);
    assert.deepEqual(
      reconstruction.evaluatorExecutionProvenance,
      TOOL_IDENTITY.evaluatorExecutionProvenance,
    );
    await stat(path.join(
      fixture.outputDirectory,
      "reviewer",
      "source",
      "tools",
      "completed-work-review",
      "assembler.mjs",
    ));

    await rm(fixture.outputDirectory, { recursive: true, force: true });
    const second = await runFixture(fixture);
    assert.equal(second.review.reviewId, first.review.reviewId);
    assert.equal(second.review.evidenceManifestDigest, first.review.evidenceManifestDigest);
    assert.equal(second.bundleDigest, first.bundleDigest);
  });
});

test("candidate input cannot resolve or replace the generated execution-provenance blocker", async () => {
  await withFixture({}, async (fixture) => {
    fixture.request.blockers.push({
      id: "evaluator-execution-provenance-unverified",
      severity: "MATERIAL",
      status: "RESOLVED",
      summary: "Candidate input attempts to resolve evaluator provenance.",
      evidenceRefs: ["review-expert"],
      unlock: unlock(
        "Candidate input cannot attest the evaluator that consumes it.",
        "EXTERNAL_EVIDENCE",
        ["REVIEWER_JUDGMENT"],
      ),
    });
    await assert.rejects(runFixture(fixture), /Final blocker IDs must be unique/u);
  });
});

test("external registry and identity anchors must match their exact canonical values", async (t) => {
  await t.test("Git executable digest", async () => {
    await withFixture({}, async (fixture) => {
      await assert.rejects(
        runFixture(fixture, { expectedGitExecutableSha256: "f".repeat(64) }),
        /does not match the externally expected SHA-256 digest/u,
      );
    });
  });

  await t.test("dirty candidate verification target", async () => {
    await withFixture({}, async (fixture) => {
      fixture.request.candidateVerificationTarget = "GIT_OBJECT";
      await assert.rejects(
        runFixture(fixture),
        /DIRTY_WORKTREE candidates require LIVE_WORKTREE verification/u,
      );
    });
  });

  await t.test("producer registry digest", async () => {
    await withFixture({}, async (fixture) => {
      await assert.rejects(
        runFixture(fixture, { producerRegistryDigest: "f".repeat(64) }),
        /does not match the explicitly expected canonical digest/u,
      );
    });
  });

  await t.test("candidate identity ID", async () => {
    await withFixture({}, async (fixture) => {
      await assert.rejects(
        runFixture(fixture, { expectedCandidateIdentityId: "f".repeat(64) }),
        /does not match the explicitly expected candidate identity ID/u,
      );
    });
  });

  await t.test("baseline identity ID", async () => {
    await withFixture({}, async (fixture) => {
      await assert.rejects(
        runFixture(fixture, { expectedBaselineIdentityId: "NONE" }),
        /does not match its explicitly expected identity ID/u,
      );
    });
  });
});

test("baselineRequired profiles treat honest current-only evidence as UNVERIFIED and NOT_READY", async (t) => {
  for (const profileId of ["POCKETHIVE_DOCUMENTATION_V1", "POCKETHIVE_DOCS_AUTOMATION_V1"]) {
    await t.test(profileId, async () => {
      await withFixture({ profileId, includeBaseline: false }, async (fixture) => {
        assert.equal(fixture.profile.baselineRequired, true);
        const assembled = await runFixture(fixture);
        assert.equal(assembled.review.identityRefs.baselineIdentityId, null);
        assert.equal(assembled.review.comparisonStatus, "UNVERIFIED");
        assert.equal(assembled.review.overall.baseline, null);
        assert.equal(assembled.review.overall.current, null);
        assert.equal(assembled.review.overall.delta, null);
        assert.deepEqual(assembled.review.submittedOverall, {
          baseline: null,
          current: 8.0,
          delta: null,
          comparisonStatus: "UNVERIFIED",
        });
        assert.equal(gateStatus(assembled.review, "baseline-identity"), "NOT_VERIFIED");
        assert.equal(gateStatus(assembled.review, "required-score-evidence"), "NOT_VERIFIED");
        assert.equal(assembled.review.readinessVerdict, "NOT_READY");
      });
    });
  }

  await withFixture({}, async (fixture) => {
    fixture.request.paths.baselineIdentity = null;
    await assert.rejects(runFixture(fixture), /requires explicit null baseline scores/u);
  });
});

test("rejects a semantically self-consistent identity that does not match live Git and a mutated receipt", async () => {
  await withFixture({}, async (fixture) => {
    const forged = structuredClone(fixture.identities.candidate);
    if (forged.mode === "DIRTY_WORKTREE") {
      forged.trackedPatchDigest = forged.trackedPatchDigest === "f".repeat(64)
        ? "e".repeat(64)
        : "f".repeat(64);
    } else {
      forged.candidateGitTree = forged.candidateGitTree === "f".repeat(forged.candidateGitTree.length)
        ? "e".repeat(forged.candidateGitTree.length)
        : "f".repeat(forged.candidateGitTree.length);
    }
    forged.candidateSnapshotDigest = candidateSnapshotDigest(forged);
    forged.identityId = identityId(forged);
    await writeJson(fixture.candidatePath, forged);
    await assert.rejects(runFixture(fixture), /does not exactly match the live Git repository state/u);
  });

  await withFixture({}, async (fixture) => {
    const receiptPath = fixture.receiptPaths.get("documentation-validation");
    const forged = JSON.parse(await readFile(receiptPath, "utf8"));
    forged.summary = "Receipt content was changed without re-attesting its digest.";
    await writeJson(receiptPath, forged);
    await assert.rejects(runFixture(fixture), /receiptId does not match its canonical fields/u);
  });
});

test("receipt execution metadata rejects empty entrypoints and control characters", async (t) => {
  for (const [label, mutate] of [
    ["empty entrypoint", (receipt) => { receipt.execution.entrypoint = ""; }],
    ["entrypoint control character", (receipt) => { receipt.execution.entrypoint = "node\nmalicious"; }],
    ["argument control character", (receipt) => { receipt.execution.arguments = ["--test\u0000unsafe"]; }],
  ]) {
    await t.test(label, async () => {
      await withFixture({}, async (fixture) => {
        await mutateReceipt(fixture, "documentation-validation", mutate);
        await assert.rejects(runFixture(fixture), /Evidence receipt .* failed schema validation/u);
      });
    });
  }
});

test("receipt chronology is bound between subject capture and registry generation", async (t) => {
  await t.test("receipt before subject capture", async () => {
    await withFixture({}, async (fixture) => {
      await mutateReceipt(fixture, "documentation-validation", (receipt) => {
        receipt.createdAt = new Date(
          Date.parse(fixture.identities.candidate.capturedAt) - 1_000,
        ).toISOString();
      });
      await assert.rejects(
        runFixture(fixture),
        /predates its bound subject identity capture/u,
      );
    });
  });

  await t.test("receipt after registry generation", async () => {
    await withFixture({}, async (fixture) => {
      const receipt = fixture.evidence.receipts[0];
      fixture.registry.generatedAt = new Date(Date.parse(receipt.createdAt) - 1_000).toISOString();
      await assert.rejects(
        runFixture(fixture),
        /was created after the producer registry/u,
      );
    });
  });
});

test("repository-contained registry cannot establish trust and external registry bindings fail closed", async (t) => {
  await withFixture({ registryInsideRepository: true }, async (fixture) => {
    fixture.request.confidence = {
      label: "LOW",
      value: 0.50,
      basisEvidenceRefs: [],
      limitations: ["Candidate-contained producer authorization is not a trust root."],
    };
    const assembled = await runFixture(fixture);
    assert.equal(assembled.review.trustControl.producerRegistryAuthority, "CANDIDATE_UNVERIFIED");
    assert.equal(assembled.review.freshness.toolSourceSnapshotMatch, "MATCH");
    assert.equal(assembled.review.freshness.producerAuthorizationMatch, "NOT_VERIFIED");
    assert.equal(gateStatus(assembled.review, "documentation-validation"), "NOT_VERIFIED");
    assert.equal(gateStatus(assembled.review, "independent-review"), "NOT_VERIFIED");
    assert.equal(assembled.review.readinessVerdict, "NOT_READY");
  });

  for (const [label, mutate, expected] of [
    [
      "receipt authorization",
      (fixture) => {
        fixture.registry.receiptAuthorizations.find(
          ({ evidenceId }) => evidenceId === "documentation-validation",
        ).receiptId = "f".repeat(64);
      },
      /Operator registry must authorize the exact complete receipt set/u,
    ],
    [
      "profile digest",
      (fixture) => { fixture.registry.profileDigest = "f".repeat(64); },
      /does not pin the exact selected profile and digest/u,
    ],
    [
      "tool source digest",
      (fixture) => { fixture.registry.toolSourceDigest = "f".repeat(64); },
      /does not pin the exact completed-work tool source digest/u,
    ],
  ]) {
    await t.test(label, async () => {
      await withFixture({}, async (fixture) => {
        mutate(fixture);
        await assert.rejects(runFixture(fixture), expected);
      });
    });
  }
});

test("producer check and reviewer role anchors fail closed", async (t) => {
  await t.test("exact gate-check authorization", async () => {
    await withFixture({}, async (fixture) => {
      const producer = fixture.registry.producers.find(({ id }) => id === "validation-producer");
      producer.gateChecks = producer.gateChecks.filter(
        ({ gateId }) => gateId !== "documentation-validation",
      );
      const assembled = await runFixture(fixture);
      assert.equal(assembled.review.freshness.producerAuthorizationMatch, "MISMATCH");
      assert.equal(assembled.review.confidence.label, "LOW");
      assert.equal(gateStatus(assembled.review, "documentation-validation"), "NOT_VERIFIED");
    });
  });

  await t.test("check contract and configuration digests", async () => {
    await withFixture({}, async (fixture) => {
      const producer = fixture.registry.producers.find(({ id }) => id === "validation-producer");
      const check = producer.gateChecks.find(({ gateId }) => gateId === "documentation-validation");
      check.checkContractDigest = "f".repeat(64);
      check.configurationDigest = "e".repeat(64);
      const assembled = await runFixture(fixture);
      assert.equal(assembled.review.freshness.producerAuthorizationMatch, "MISMATCH");
      assert.equal(assembled.review.confidence.label, "LOW");
      assert.equal(gateStatus(assembled.review, "documentation-validation"), "NOT_VERIFIED");
    });
  });

  await t.test("reviewer ID authorization", async () => {
    await withFixture({}, async (fixture) => {
      const basisReviewId = fixture.request.confidence.basisEvidenceRefs.find(
        (evidenceId) => evidenceId.startsWith("review-"),
      );
      const basisReview = fixture.evidence.receipts.find(
        ({ evidenceId }) => evidenceId === basisReviewId,
      );
      const producer = fixture.registry.producers.find(({ id }) => id === "review-producer");
      producer.reviewerIds = producer.reviewerIds.filter(
        (reviewerId) => reviewerId !== basisReview.claims.independentReview.reviewerId,
      );
      const assembled = await runFixture(fixture);
      assert.equal(assembled.review.freshness.producerAuthorizationMatch, "MISMATCH");
      assert.equal(assembled.review.confidence.label, "LOW");
      assert.equal(gateStatus(assembled.review, "independent-review"), "NOT_VERIFIED");
    });
  });
});

test("approved references must themselves be externally authorized, exact, and fresh", async () => {
  await withFixture({}, async (fixture) => {
    fixture.request.approvedReferenceEvidenceRefs = ["current-scores"];
    fixture.registry.producers.find(({ id }) => id === "score-producer").digest = "f".repeat(64);
    await assert.rejects(
      runFixture(fixture),
      /Approved reference current-scores must be exact trusted, fresh, passing, and substantive evidence/u,
    );
  });
});

test("wrong gate adapter, execution kind, ingress, and arbitrary scores cannot satisfy readiness", async (t) => {
  for (const [label, mutate] of [
    ["adapter", (receipt) => { receipt.execution.adapter = "MANUAL_INSPECTION"; }],
    ["execution kind", (receipt) => { receipt.execution.kind = "INDEPENDENT_REVIEW"; }],
    ["official ingress", (receipt) => { receipt.execution.officialIngress = false; }],
  ]) {
    await t.test(label, async () => {
      await withFixture({}, async (fixture) => {
        await mutateReceipt(fixture, "documentation-validation", mutate);
        const assembled = await runFixture(fixture);
        assert.equal(assembled.review.freshness.toolSourceSnapshotMatch, "MATCH");
        assert.equal(gateStatus(assembled.review, "documentation-validation"), "NOT_VERIFIED");
        assert.equal(assembled.review.readinessVerdict, "NOT_READY");
      });
    });
  }

  await t.test("unattested arbitrary score", async () => {
    await withFixture({}, async (fixture) => {
      fixture.request.dimensions[0].current = 9.0;
      const assembled = await runFixture(fixture);
      assert.equal(assembled.review.dimensions[0].scoreStatus, "NOT_VERIFIED");
      assert.equal(assembled.review.dimensions[0].submittedCurrent, 9.0);
      assert.equal(assembled.review.dimensions[0].current, null);
      assert.deepEqual(assembled.review.overall, {
        baseline: null,
        current: null,
        delta: null,
        comparisonStatus: "UNVERIFIED",
      });
      assert.deepEqual(assembled.review.submittedOverall, {
        baseline: 7.0,
        current: 8.2,
        delta: 1.2,
        comparisonStatus: "IMPROVED",
      });
      assert.equal(gateStatus(assembled.review, "required-score-evidence"), "NOT_VERIFIED");
      assert.equal(assembled.review.readinessVerdict, "NOT_READY");
    });
  });
});

test("typed gate, discovery, and independent-review claims cannot be inferred from receipt prose", async (t) => {
  await t.test("gate outcome must match the receipt status", async () => {
    await withFixture({}, async (fixture) => {
      await mutateReceipt(fixture, "documentation-validation", (receipt) => {
        receipt.claims.gateOutcomes.find(
          ({ gateId }) => gateId === "documentation-validation",
        ).status = "FAIL";
      });
      const assembled = await runFixture(fixture);
      assert.equal(gateStatus(assembled.review, "documentation-validation"), "NOT_VERIFIED");
      assert.equal(gateStatus(assembled.review, "search-ai-discovery"), "VERIFIED");
    });
  });

  await t.test("search discovery claim must match the submitted disposition", async () => {
    await withFixture({}, async (fixture) => {
      await mutateReceipt(fixture, "documentation-validation", (receipt) => {
        receipt.claims.searchDiscovery.status = "UNVERIFIED";
      });
      const assembled = await runFixture(fixture);
      assert.equal(gateStatus(assembled.review, "documentation-validation"), "VERIFIED");
      assert.equal(gateStatus(assembled.review, "search-ai-discovery"), "NOT_VERIFIED");
    });
  });

  await t.test("independent-review pass kinds must be complete and non-duplicated", async () => {
    await withFixture({}, async (fixture) => {
      await mutateReceipt(fixture, "review-expert", (receipt) => {
        receipt.claims.independentReview.passKind = "NOVICE";
      });
      const assembled = await runFixture(fixture);
      assert.equal(gateStatus(assembled.review, "independent-review"), "NOT_VERIFIED");
      assert.deepEqual(assembled.review.independentReview.passKinds, ["NOVICE", "UX"]);
    });
  });
});

test("material gaps, BLOCKER regressions, and unapproved claims create explicit material blockers", async () => {
  await withFixture({}, async (fixture) => {
    fixture.request.blockers.push({
      id: "resolved-risk",
      severity: "MATERIAL",
      status: "RESOLVED",
      summary: "The request claims this risk was resolved.",
      evidenceRefs: ["review-novice"],
      unlock: unlock("Approve the exact resolved blocker.", "HUMAN_APPROVAL", ["REVIEWER_JUDGMENT"]),
    });
    fixture.request.regressions.push(
      {
        id: "tradeoff-risk",
        dimensionId: fixture.request.dimensions[0].id,
        severity: "MATERIAL",
        summary: "The request claims this trade-off was accepted.",
        evidenceRefs: ["review-novice"],
        disposition: "ACCEPTED_TRADE_OFF",
      },
      {
        id: "blocking-risk",
        dimensionId: fixture.request.dimensions[1].id,
        severity: "NON_MATERIAL",
        summary: "This regression is explicitly a blocker.",
        evidenceRefs: ["documentation-validation"],
        disposition: "BLOCKER",
      },
    );
    fixture.request.remainingGaps.push({
      id: "unfinished-risk",
      dimensionId: fixture.request.dimensions[2].id,
      severity: "MATERIAL",
      summary: "Material work remains unfinished.",
      evidenceRefs: ["documentation-validation"],
      unlock: unlock("Finish and verify the missing work.", "IMPLEMENTATION"),
    });
    const assembled = await runFixture(fixture);
    const blockerIds = assembled.review.blockers.map(({ id }) => id);
    for (const expected of [
      "approval-resolved-resolved-risk",
      "approval-tradeoff-tradeoff-risk",
      "blocking-regression-blocking-risk",
      "material-gap-unfinished-risk",
    ]) {
      assert.ok(blockerIds.includes(expected), `Expected blocker ${expected}`);
    }
    assert.equal(assembled.review.readinessVerdict, "NOT_READY");
  });
});

test("bundle verification rejects mutated content and undeclared extra files", async () => {
  await withFixture({}, async (fixture) => {
    const assembled = await runFixture(fixture);
    await assert.rejects(
      verifyBundleDirectory(fixture.outputDirectory),
      /requires an explicit expected SHA-256 bundle digest/u,
    );
    await assert.rejects(
      verifyBundleDirectory(fixture.outputDirectory, "f".repeat(64)),
      /does not match the externally expected digest/u,
    );
    const reviewPath = path.join(fixture.outputDirectory, "review.json");
    const original = await readFile(reviewPath);
    await writeFile(reviewPath, Buffer.concat([original, Buffer.from(" ", "utf8")]));
    await assert.rejects(
      verifyBundleDirectory(fixture.outputDirectory, assembled.bundleDigest),
      /Bundle file digest mismatch: review.json/u,
    );
    await writeFile(reviewPath, original);
    assert.equal(
      (await verifyBundleDirectory(fixture.outputDirectory, assembled.bundleDigest)).bundleDigest,
      assembled.bundleDigest,
    );
    await writeFile(path.join(fixture.outputDirectory, "undeclared-extra.txt"), "extra", "utf8");
    await assert.rejects(
      verifyBundleDirectory(fixture.outputDirectory, assembled.bundleDigest),
      /Bundle file set does not match bundle.sha256/u,
    );
  });
});

test("output safety rejects unignored, escaping, and pre-existing destinations", async (t) => {
  await t.test("unignored", async () => {
    await withFixture({}, async (fixture) => {
      const output = `tools/completed-work-review/unignored-output-${process.pid}`;
      assert.equal(await stat(path.join(ROOT, output)).catch(() => null), null);
      fixture.request.paths.outputDirectory = output;
      await assert.rejects(runFixture(fixture), /must be Git-ignored/u);
    });
  });
  await t.test("escaping", async () => {
    await withFixture({}, async (fixture) => {
      fixture.request.paths.outputDirectory = "../escaped-output";
      await assert.rejects(runFixture(fixture), /does not match|escapes the repository root/u);
    });
  });
  await t.test("pre-existing", async () => {
    await withFixture({}, async (fixture) => {
      await mkdir(fixture.outputDirectory, { recursive: true });
      await assert.rejects(runFixture(fixture), /already exists and will not be overwritten/u);
    });
  });
});

function localImportSpecifiers(source) {
  const specifiers = [];
  const staticPattern = /\b(?:import|export)\s+(?:[^"'()]*?\s+from\s+)?["']([^"']+)["']/gu;
  const dynamicPattern = /\bimport\s*\(\s*["']([^"']+)["']\s*\)/gu;
  for (const pattern of [staticPattern, dynamicPattern]) {
    for (const match of source.matchAll(pattern)) {
      if (match[1].startsWith(".")) specifiers.push(match[1]);
    }
  }
  return specifiers;
}

async function importClosure(entrypoints) {
  const pending = [...entrypoints];
  const visited = new Set();
  while (pending.length > 0) {
    const repositoryPath = pending.pop();
    if (visited.has(repositoryPath)) continue;
    visited.add(repositoryPath);
    const absolutePath = path.join(ROOT, ...repositoryPath.split("/"));
    const source = await readFile(absolutePath, "utf8");
    for (const specifier of localImportSpecifiers(source)) {
      const imported = await realpath(path.resolve(path.dirname(absolutePath), specifier));
      const importedRepositoryPath = toRepositoryPath(imported);
      if (importedRepositoryPath.startsWith("../")) {
        throw new Error(`${repositoryPath} imports repository-external source ${specifier}`);
      }
      pending.push(importedRepositoryPath);
    }
  }
  return [...visited].sort();
}

test("tool-files manifest is sorted, self-bound, and covers the production local import closure", async () => {
  const files = TOOL_FILES_DOCUMENT.files;
  assert.equal(TOOL_FILES_DOCUMENT.schemaVersion, 1);
  assert.deepEqual(TOOL_FILES_DOCUMENT.runtimePackages, []);
  assert.deepEqual(TOOL_IDENTITY.runtimePackages, []);
  await assert.rejects(
    collectToolIdentity({ repositoryRoot: ROOT, toolFilePaths: files }),
    /Runtime package specifications must be explicitly supplied/u,
  );
  assert.deepEqual(files, [...files].sort());
  assert.equal(new Set(files).size, files.length);
  assert.ok(files.includes("tools/completed-work-review/contracts/tool-files.json"));
  for (const repositoryPath of files) {
    assert.equal((await stat(path.join(ROOT, ...repositoryPath.split("/")))).isFile(), true);
  }
  const closure = await importClosure([
    "tools/completed-work-review/assembler.mjs",
    "tools/completed-work-review/cli.mjs",
    "tools/completed-work-review/identity.mjs",
    "tools/completed-work-review/manifest.mjs",
  ]);
  const missing = closure.filter((repositoryPath) => !files.includes(repositoryPath));
  assert.deepEqual(missing, [], `Tool manifest omitted imported source: ${missing.join(", ")}`);
  assert.deepEqual(
    TOOL_IDENTITY.toolFiles.map(({ repositoryPath }) => repositoryPath),
    files,
  );
});
