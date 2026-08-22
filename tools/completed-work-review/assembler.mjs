import { dirname, isAbsolute, relative, resolve, sep } from "node:path";
import { TextDecoder } from "node:util";

import { canonicalDigest, canonicalJson, sha256 } from "../docs-impact/canonical.mjs";
import {
  assertCandidateIdentitySchemaProjections,
  assertEvidenceReceiptSchemaProjections,
  assertProducerRegistrySchemaProjections,
  assertReviewRequestSchemaProjections,
  assertReviewResultSchemaProjections,
} from "./contracts/projections.mjs";
import {
  BLOCKER_SEVERITY,
  BLOCKER_STATUS,
  CANONICAL_REPOSITORY_PATHS,
  CANDIDATE_IDENTITY_MODE,
  CANDIDATE_VERIFICATION_TARGET,
  CONFIDENCE_LABEL,
  DISCOVERY_STATUS,
  DOCS_IMPACT_AUTHORITY,
  EVIDENCE_ADAPTER,
  EVIDENCE_KIND,
  EVIDENCE_STATUS,
  EVIDENCE_SUBJECT,
  EVALUATOR_EXECUTION_PROVENANCE_METHOD,
  EVALUATOR_EXECUTION_PROVENANCE_STATEMENT,
  EVALUATOR_EXECUTION_PROVENANCE_STATUS,
  EXTERNAL_AUTHORITY_STATUS,
  FINDING_DISPOSITION,
  FRESHNESS_STATUS,
  GATE_ID,
  GATE_STATUS,
  IDENTITY_MATCH_STATUS,
  PRODUCER_REGISTRY_AUTHORITY,
  PROVENANCE_BLOCKER_ID,
  UNLOCK_KIND,
} from "./contracts/constants.mjs";
import {
  collectCandidateSnapshotMaterial,
  readIdentity,
  verifyCandidateIdentity,
} from "./identity.mjs";
import {
  assertDirectPathSnapshot,
  assertPathAbsent,
  captureDirectDirectorySnapshot,
  captureStableRegularFile,
  createDirectoryUnderSnapshot,
  filesystemRoot,
  HARD_LINK_POLICY,
  isPathInside,
  resolveDirectDirectoryPath,
  resolveDirectRegularFilePath,
  sameFilesystemPath,
  writeNewFileUnderSnapshot,
} from "./file-safety.mjs";
import {
  assertCheckIgnorePath,
  runGitSync,
  verifyGitExecutableAdapter,
} from "./git-command.mjs";
import {
  buildEvidenceManifest,
  bundleChecksumText,
  bundleDigest,
  canonicalFile,
  collectToolIdentity,
  readRepositoryFile,
  readEvidenceReceipt,
  resolveRepositoryFile,
  verifyBundleDirectory,
} from "./manifest.mjs";
import { CONTRACT_VALUES, assertContract, loadReviewProfiles } from "./profile.mjs";
import {
  buildFindingReadiness,
  buildGates,
  assertReadinessSemantics,
  readinessVerdict,
} from "./readiness.mjs";
import { renderHtml } from "./render-html.mjs";
import { renderMarkdown } from "./render-markdown.mjs";
import { assertScorecardSemantics, buildScorecard, SCORE_STATUS } from "./scorecard.mjs";

const UTF8_DECODER = new TextDecoder("utf-8", { fatal: true });
const MAX_JSON_BYTES = 16 * 1024 * 1024;
const MAX_EVALUATION_CLOCK_SKEW_MS = 5 * 60 * 1000;
const ABSENT_IDENTITY = "NONE";

function compareText(left, right) {
  return left < right ? -1 : left > right ? 1 : 0;
}

function validTimestamp(value, label) {
  const time = Date.parse(value);
  const normalized = typeof value === "string" && value.includes(".")
    ? value
    : typeof value === "string" ? value.replace(/Z$/u, ".000Z") : value;
  if (!Number.isFinite(time) || new Date(time).toISOString() !== normalized) {
    throw new Error(`${label} must be a real canonical UTC timestamp`);
  }
  return time;
}

function repositoryPath(repositoryRoot, value, label) {
  if (typeof value !== "string" || value.length === 0 || isAbsolute(value)) {
    throw new Error(`${label} must be an explicit repository-relative path`);
  }
  const path = resolve(repositoryRoot, value);
  if (!isPathInside(repositoryRoot, path)) throw new Error(`${label} escapes the repository root`);
  return path;
}

async function repositoryFilePath(repositoryRoot, value, label) {
  repositoryPath(repositoryRoot, value, label);
  return resolveRepositoryFile(repositoryRoot, value, label);
}

function assertCanonicalPath(repositoryRoot, actualPath, repositoryPathValue, label) {
  const expectedPath = resolve(repositoryRoot, repositoryPathValue);
  if (!sameFilesystemPath(actualPath, expectedPath)) {
    throw new Error(`${label} must use the canonical repository path ${repositoryPathValue}`);
  }
}

async function readJson(path, label, anchorPath) {
  try {
    const bytes = await captureStableRegularFile({
      anchorPath,
      hardLinkPolicy: HARD_LINK_POLICY.REJECT,
      path,
      label,
      maxBytes: MAX_JSON_BYTES,
    });
    return JSON.parse(UTF8_DECODER.decode(bytes));
  } catch (error) {
    if (error instanceof SyntaxError) throw new Error(`${label} is not valid JSON: ${error.message}`);
    if (error instanceof TypeError) throw new Error(`${label} is not valid UTF-8`);
    throw error;
  }
}

async function readJsonDocument(path, label, anchorPath) {
  const bytes = await captureStableRegularFile({
    anchorPath,
    hardLinkPolicy: HARD_LINK_POLICY.REJECT,
    path,
    label,
    maxBytes: MAX_JSON_BYTES,
  });
  let value;
  try {
    value = JSON.parse(UTF8_DECODER.decode(bytes));
  } catch (error) {
    if (error instanceof SyntaxError) throw new Error(`${label} is not valid JSON: ${error.message}`);
    if (error instanceof TypeError) throw new Error(`${label} is not valid UTF-8`);
    throw error;
  }
  return { bytes, value };
}

async function assertRepositoryRoot(input, gitExecutable, expectedGitExecutableSha256) {
  if (typeof input !== "string" || !isAbsolute(input)) {
    throw new Error("--repo must be an explicit absolute path");
  }
  const root = await resolveDirectDirectoryPath({ path: input, label: "Repository root" });
  const verifiedGitExecutable = await verifyGitExecutableAdapter({
    gitExecutablePath: gitExecutable,
    expectedGitExecutableSha256,
  });
  const canonicalGitExecutable = verifiedGitExecutable.executablePath;
  if (isPathInside(root, canonicalGitExecutable) || sameFilesystemPath(root, canonicalGitExecutable)) {
    throw new Error("Git executable must exist outside the repository under review");
  }
  const gitRoot = await resolveDirectDirectoryPath({
    path: runGitSync({
    repositoryRoot: root,
    gitExecutable: canonicalGitExecutable,
    argumentsList: ["rev-parse", "--show-toplevel"],
    literalPathspecs: true,
    }).stdout.trim(),
    label: "Git top-level directory",
  });
  if (!sameFilesystemPath(root, gitRoot)) throw new Error(`--repo must be the Git top level: ${root}`);
  return { gitExecutable: canonicalGitExecutable, repositoryRoot: root };
}

function assertUnique(values, label) {
  if (new Set(values).size !== values.length) throw new Error(`${label} must be unique`);
}

async function loadProducerRegistry({
  repositoryRoot,
  registryPath,
  registrySchemaPath,
  profileId,
  profileDigest,
  evaluatedAt,
  expectedRegistryDigest,
}) {
  if (typeof registryPath !== "string" || typeof registrySchemaPath !== "string"
    || !isAbsolute(registryPath) || !isAbsolute(registrySchemaPath)) {
    throw new Error("Producer registry and schema paths must be explicit absolute paths");
  }
  const [resolvedRegistryPath, resolvedSchemaPath] = await Promise.all([
    resolveDirectRegularFilePath({ path: registryPath, label: "Producer registry" }),
    resolveDirectRegularFilePath({
      anchorPath: repositoryRoot,
      path: registrySchemaPath,
      label: "Producer registry schema",
    }),
  ]);
  assertCanonicalPath(
    repositoryRoot,
    resolvedSchemaPath,
    CANONICAL_REPOSITORY_PATHS.producerRegistrySchema,
    "Producer registry schema",
  );
  const [{ bytes, value: registry }, registrySchema] = await Promise.all([
    readJsonDocument(
      resolvedRegistryPath,
      "Producer registry",
      filesystemRoot(resolvedRegistryPath),
    ),
    readJson(resolvedSchemaPath, "Producer registry schema", repositoryRoot),
  ]);
  if (registrySchema.$id !== CONTRACT_VALUES.schemaIds.producerRegistry) {
    throw new Error(`Unexpected producer registry schema identity: ${registrySchema.$id}`);
  }
  assertProducerRegistrySchemaProjections(registrySchema);
  assertContract(registrySchema, registry, "Producer registry");
  if (!/^[a-f0-9]{64}$/u.test(expectedRegistryDigest)
    || canonicalDigest(registry) !== expectedRegistryDigest) {
    throw new Error("Producer registry does not match the explicitly expected canonical digest");
  }
  const insideRepository = isPathInside(repositoryRoot, resolvedRegistryPath);
  if (insideRepository && registry.authority !== PRODUCER_REGISTRY_AUTHORITY.CANDIDATE_UNVERIFIED) {
    throw new Error("A repository-contained producer registry can only be CANDIDATE_UNVERIFIED");
  }
  if (!insideRepository && registry.authority !== PRODUCER_REGISTRY_AUTHORITY.OPERATOR_SUPPLIED) {
    throw new Error("An external producer registry must declare OPERATOR_SUPPLIED authority");
  }
  if (registry.profileId !== profileId || registry.profileDigest !== profileDigest) {
    throw new Error("Producer registry does not pin the exact selected profile and digest");
  }
  const generatedTime = validTimestamp(registry.generatedAt, "Producer registry generatedAt");
  if (generatedTime > validTimestamp(evaluatedAt, "evaluatedAt")) {
    throw new Error("Producer registry was generated after evaluatedAt");
  }
  assertUnique(registry.producers.map(({ id }) => id), "Producer registry IDs");
  assertUnique(
    registry.receiptAuthorizations.map(({ evidenceId }) => evidenceId),
    "Producer registry authorized evidence IDs",
  );
  assertUnique(
    registry.receiptAuthorizations.map(({ receiptId }) => receiptId),
    "Producer registry authorized receipt IDs",
  );
  const producerIds = new Set(registry.producers.map(({ id }) => id));
  for (const authorization of registry.receiptAuthorizations) {
    if (!producerIds.has(authorization.producerId)) {
      throw new Error(
        `Receipt authorization ${authorization.evidenceId} references unknown producer ${authorization.producerId}`,
      );
    }
  }
  return {
    bytes,
    insideRepository,
    path: resolvedRegistryPath,
    registry,
  };
}

async function loadToolManifest(repositoryRoot) {
  const manifestPath = await repositoryFilePath(
    repositoryRoot,
    CANONICAL_REPOSITORY_PATHS.toolManifest,
    "Completed-work tool manifest",
  );
  const manifest = await readJson(manifestPath, "Completed-work tool manifest", repositoryRoot);
  if (
    Object.keys(manifest).sort(compareText).join(",") !== "files,runtimePackages,schemaVersion"
    || manifest.schemaVersion !== 1
    || !Array.isArray(manifest.files)
    || manifest.files.length === 0
    || !Array.isArray(manifest.runtimePackages)
  ) {
    throw new Error("Completed-work tool manifest must be the closed schema-v1 shape");
  }
  assertUnique(manifest.files, "Completed-work tool manifest paths");
  if (canonicalJson(manifest.files) !== canonicalJson([...manifest.files].sort(compareText))) {
    throw new Error("Completed-work tool manifest paths must be sorted");
  }
  if (!manifest.files.includes(CANONICAL_REPOSITORY_PATHS.toolManifest)) {
    throw new Error("Completed-work tool manifest must bind itself");
  }
  for (const [index, repositoryPathValue] of manifest.files.entries()) {
    await repositoryFilePath(repositoryRoot, repositoryPathValue, `Tool manifest path ${index}`);
  }
  for (const [index, specification] of manifest.runtimePackages.entries()) {
    if (specification === null || typeof specification !== "object" || Array.isArray(specification)
      || Object.keys(specification).sort(compareText).join(",") !== "name,repositoryPath,version"
      || typeof specification.name !== "string" || specification.name.length === 0
      || typeof specification.version !== "string" || specification.version.length === 0
      || typeof specification.repositoryPath !== "string" || specification.repositoryPath.length === 0) {
      throw new Error(`Runtime package specification ${index} is invalid`);
    }
    repositoryPath(repositoryRoot, specification.repositoryPath, `Runtime package specification ${index}`);
  }
  assertUnique(manifest.runtimePackages.map(({ name }) => name), "Runtime package names");
  return manifest;
}

function assertEvidenceReferences(request, receiptsById) {
  const known = new Set(receiptsById.keys());
  const referenceGroups = [
    ["approvedReferenceEvidenceRefs", request.approvedReferenceEvidenceRefs],
    ["searchDiscovery.evidenceRefs", request.searchDiscovery.evidenceRefs],
    ["publicationBoundary.authorityEvidenceRefs", request.publicationBoundary.authorityEvidenceRefs],
    ["confidence.basisEvidenceRefs", request.confidence.basisEvidenceRefs],
    ...request.dimensions.map((item) => [`dimension ${item.id}`, item.evidenceRefs]),
    ...request.gateInputs.map((item) => [`gate ${item.id}`, item.evidenceRefs]),
    ...request.blockers.map((item) => [`blocker ${item.id}`, item.evidenceRefs]),
    ...request.regressions.map((item) => [`regression ${item.id}`, item.evidenceRefs]),
    ...request.remainingGaps.map((item) => [`gap ${item.id}`, item.evidenceRefs]),
  ];
  if (request.docsImpact.analysisEvidenceRef !== null) {
    referenceGroups.push(["docsImpact.analysisEvidenceRef", [request.docsImpact.analysisEvidenceRef]]);
  }
  for (const [label, references] of referenceGroups) {
    for (const reference of references) {
      if (!known.has(reference)) throw new Error(`${label} references unknown evidence ${reference}`);
    }
  }
  if (request.docsImpact.authority === DOCS_IMPACT_AUTHORITY.NOT_SUPPLIED
    && request.docsImpact.analysisEvidenceRef !== null) {
    throw new Error("NOT_SUPPLIED documentation impact cannot cite an analysis receipt");
  }
  if (request.docsImpact.authority === DOCS_IMPACT_AUTHORITY.INFORMATIONAL_UNVERIFIED
    && request.docsImpact.analysisEvidenceRef === null) {
    throw new Error("INFORMATIONAL_UNVERIFIED documentation impact requires an analysis receipt");
  }
  if (request.docsImpact.authority === DOCS_IMPACT_AUTHORITY.PROTECTED_CONTROLLER_VERIFIED) {
    throw new Error("PROTECTED_CONTROLLER_VERIFIED is rejected in schema v1 because no trusted adapter exists");
  }
  if (request.docsImpact.analysisEvidenceRef !== null) {
    const receipt = receiptsById.get(request.docsImpact.analysisEvidenceRef);
    if (receipt.execution.adapter !== EVIDENCE_ADAPTER.DOCS_IMPACT) {
      throw new Error("Documentation-impact evidence must use the explicit DOCS_IMPACT adapter");
    }
  }
  for (const [authority, label] of [
    [request.publicationBoundary.mergeAuthority, "merge"],
    [request.publicationBoundary.publicationAuthority, "publication"],
    [request.publicationBoundary.deploymentAuthority, "deployment"],
  ]) {
    if (authority === EXTERNAL_AUTHORITY_STATUS.EXTERNALLY_VERIFIED
      && request.publicationBoundary.authorityEvidenceRefs.length === 0) {
      throw new Error(`${label} authority requires external authority evidence`);
    }
  }
}

function producerPolicyMatchesReceipt(policy, receipt) {
  return policy.digest === receipt.producer.digest
    && policy.adapters.includes(receipt.execution.adapter)
    && policy.executionKinds.includes(receipt.execution.kind)
    && policy.evidenceKinds.includes(receipt.kind)
    && receipt.claims.gateOutcomes.every((outcome) => policy.gateChecks.some(
      (authorized) => canonicalJson(authorized) === canonicalJson({
        gateId: outcome.gateId,
        checkId: outcome.checkId,
        checkContractDigest: outcome.checkContractDigest,
        configurationDigest: outcome.configurationDigest,
      }),
    ))
    && (receipt.claims.independentReview === null
      || policy.reviewerIds.includes(receipt.claims.independentReview.reviewerId));
}

function receiptTrust({ receiptsById, producerRegistry, identities, profileDigest, evaluatedAt, maxAgeSeconds }) {
  const producerIds = producerRegistry.producers.map(({ id }) => id);
  assertUnique(producerIds, "Producer registry IDs");
  const producers = new Map(producerRegistry.producers.map((producer) => [producer.id, producer]));
  const authorizations = new Map(
    producerRegistry.receiptAuthorizations.map((authorization) => [authorization.evidenceId, authorization]),
  );
  const registryTrusted = producerRegistry.authority === PRODUCER_REGISTRY_AUTHORITY.OPERATOR_SUPPLIED;
  const evaluatedTime = validTimestamp(evaluatedAt, "evaluatedAt");
  const registryGeneratedTime = validTimestamp(producerRegistry.generatedAt, "Producer registry generatedAt");
  const receipts = [...receiptsById.values()];
  if (registryTrusted) {
    const actualAuthorizations = receipts.map((receipt) => ({
      evidenceId: receipt.evidenceId,
      receiptId: receipt.receiptId,
      producerId: receipt.producer.id,
    })).sort((left, right) => compareText(left.evidenceId, right.evidenceId));
    const expectedAuthorizations = [...producerRegistry.receiptAuthorizations]
      .sort((left, right) => compareText(left.evidenceId, right.evidenceId));
    if (canonicalJson(actualAuthorizations) !== canonicalJson(expectedAuthorizations)) {
      throw new Error("Operator registry must authorize the exact complete receipt set");
    }
  }
  const profileMatches = receipts.map((receipt) => receipt.profileDigest === profileDigest);
  const profileIdentityMatch = profileMatches.every(Boolean)
    ? IDENTITY_MATCH_STATUS.MATCH
    : IDENTITY_MATCH_STATUS.MISMATCH;

  const producerStates = receipts.map((receipt) => {
    const policy = producers.get(receipt.producer.id);
    const authorization = authorizations.get(receipt.evidenceId);
    if (!registryTrusted || policy === undefined || authorization === undefined) {
      return IDENTITY_MATCH_STATUS.NOT_VERIFIED;
    }
    return authorization.receiptId === receipt.receiptId
      && authorization.producerId === receipt.producer.id
      && producerPolicyMatchesReceipt(policy, receipt)
      ? IDENTITY_MATCH_STATUS.MATCH
      : IDENTITY_MATCH_STATUS.MISMATCH;
  });
  const producerAuthorizationMatch = producerStates.includes(IDENTITY_MATCH_STATUS.MISMATCH)
    ? IDENTITY_MATCH_STATUS.MISMATCH
    : producerStates.includes(IDENTITY_MATCH_STATUS.NOT_VERIFIED) || producerStates.length === 0
      ? IDENTITY_MATCH_STATUS.NOT_VERIFIED
      : IDENTITY_MATCH_STATUS.MATCH;

  const expectedIdentityFor = (receipt) => {
    if (receipt.subject === EVIDENCE_SUBJECT.BASELINE) return identities.baseline?.identityId ?? null;
    if (receipt.subject === EVIDENCE_SUBJECT.DEPLOYMENT) return identities.deployment?.identityId ?? null;
    return identities.candidate.identityId;
  };
  const subjectCapturedTimeFor = (receipt) => {
    if (receipt.subject === EVIDENCE_SUBJECT.BASELINE) {
      return identities.baseline === null
        ? null
        : validTimestamp(identities.baseline.capturedAt, "Baseline identity capturedAt");
    }
    if (receipt.subject === EVIDENCE_SUBJECT.DEPLOYMENT) {
      return identities.deployment === null
        ? null
        : validTimestamp(identities.deployment.capturedAt, "Deployment identity capturedAt");
    }
    return validTimestamp(identities.candidate.capturedAt, "Candidate identity capturedAt");
  };
  const candidateReceipts = receipts.filter((receipt) => [
    EVIDENCE_SUBJECT.CANDIDATE,
    EVIDENCE_SUBJECT.REVIEW,
  ].includes(receipt.subject));
  const identityStates = candidateReceipts.map((receipt) => receipt.subjectIdentityRef === expectedIdentityFor(receipt));
  const allReceiptIdentityStates = receipts.map(
    (receipt) => receipt.subjectIdentityRef === expectedIdentityFor(receipt),
  );
  const candidateIdentityMatch = identityStates.length === 0
    ? IDENTITY_MATCH_STATUS.NOT_VERIFIED
    : identityStates.every(Boolean) ? IDENTITY_MATCH_STATUS.MATCH : IDENTITY_MATCH_STATUS.MISMATCH;

  let oldestTime = null;
  const trustById = new Map();
  for (const receipt of receipts) {
    const createdTime = validTimestamp(receipt.createdAt, `Evidence ${receipt.evidenceId} createdAt`);
    if (createdTime > evaluatedTime) {
      throw new Error(`Evidence ${receipt.evidenceId} was created after evaluatedAt`);
    }
    const subjectCapturedTime = subjectCapturedTimeFor(receipt);
    if (registryTrusted && (subjectCapturedTime === null || createdTime < subjectCapturedTime)) {
      throw new Error(`Evidence ${receipt.evidenceId} predates its bound subject identity capture`);
    }
    if (registryTrusted && createdTime > registryGeneratedTime) {
      throw new Error(`Evidence ${receipt.evidenceId} was created after the producer registry`);
    }
    oldestTime = oldestTime === null ? createdTime : Math.min(oldestTime, createdTime);
    const producerPolicy = producers.get(receipt.producer.id);
    const authorization = authorizations.get(receipt.evidenceId);
    const producerState = !registryTrusted || producerPolicy === undefined || authorization === undefined
      ? IDENTITY_MATCH_STATUS.NOT_VERIFIED
      : authorization.receiptId === receipt.receiptId
        && authorization.producerId === receipt.producer.id
        && producerPolicyMatchesReceipt(producerPolicy, receipt)
        ? IDENTITY_MATCH_STATUS.MATCH
        : IDENTITY_MATCH_STATUS.MISMATCH;
    const ageSeconds = Math.floor((evaluatedTime - createdTime) / 1000);
    const identityMatches = receipt.subjectIdentityRef === expectedIdentityFor(receipt);
    trustById.set(receipt.evidenceId, {
      ageSeconds,
      identityMatches,
      producerState,
      profileMatches: receipt.profileDigest === profileDigest,
      usable: identityMatches
        && producerState === IDENTITY_MATCH_STATUS.MATCH
        && receipt.profileDigest === profileDigest
        && subjectCapturedTime !== null
        && createdTime >= subjectCapturedTime
        && createdTime <= registryGeneratedTime
        && ageSeconds <= maxAgeSeconds,
    });
  }
  const oldestEvidenceAt = oldestTime === null ? null : new Date(oldestTime).toISOString();
  const stale = [...trustById.values()].some(({ ageSeconds }) => ageSeconds > maxAgeSeconds);
  const freshnessStatus = candidateIdentityMatch !== IDENTITY_MATCH_STATUS.MATCH
    || !allReceiptIdentityStates.every(Boolean)
    || producerAuthorizationMatch !== IDENTITY_MATCH_STATUS.MATCH
    || profileIdentityMatch !== IDENTITY_MATCH_STATUS.MATCH
    ? FRESHNESS_STATUS.NOT_VERIFIED
    : stale ? FRESHNESS_STATUS.STALE : FRESHNESS_STATUS.FRESH;
  return {
    trustById,
    freshness: {
      status: freshnessStatus,
      evaluatedAt,
      oldestEvidenceAt,
      maxAgeSeconds,
      candidateIdentityMatch,
      producerAuthorizationMatch,
      toolSourceSnapshotMatch: IDENTITY_MATCH_STATUS.MATCH,
      profileIdentityMatch,
    },
  };
}

function assertSubmittedConfidence(confidence) {
  const minimum = CONTRACT_VALUES.confidencePolicy.submittedBandMinimumByLabel;
  const expected = confidence.value >= minimum[CONFIDENCE_LABEL.HIGH]
    ? CONFIDENCE_LABEL.HIGH
    : confidence.value >= minimum[CONFIDENCE_LABEL.MEDIUM]
      ? CONFIDENCE_LABEL.MEDIUM
      : CONFIDENCE_LABEL.LOW;
  if (confidence.label !== expected) {
    throw new Error(`Confidence ${confidence.value} requires label ${expected}, not ${confidence.label}`);
  }
}

function buildCanonicalConfidence({
  gates,
  freshness,
  trustById,
  evaluatorExecutionProvenance,
}) {
  if (evaluatorExecutionProvenance.status
      !== EVALUATOR_EXECUTION_PROVENANCE_STATUS.NOT_VERIFIED
    || evaluatorExecutionProvenance.method
      !== EVALUATOR_EXECUTION_PROVENANCE_METHOD.POST_LOAD_FILESYSTEM_SNAPSHOT
    || evaluatorExecutionProvenance.executedSourceDigest !== null
    || evaluatorExecutionProvenance.controllerAttestationRef !== null
    || evaluatorExecutionProvenance.statement !== EVALUATOR_EXECUTION_PROVENANCE_STATEMENT) {
    throw new Error("Schema v1 requires the fixed unverified evaluator execution provenance state");
  }
  const referencedEvidence = new Set(gates.flatMap(({ evidenceRefs }) => evidenceRefs));
  const basisEvidenceRefs = [...referencedEvidence]
    .filter((reference) => trustById.get(reference)?.usable === true)
    .sort(compareText);
  const unverifiedGateIds = gates
    .filter(({ status }) => status === GATE_STATUS.NOT_VERIFIED)
    .map(({ id }) => id)
    .sort(compareText);
  const scoreGate = gates.find(({ id }) => id === GATE_ID["required-score-evidence"]);
  const scoreVerified = scoreGate?.status === GATE_STATUS.VERIFIED;
  const label = CONFIDENCE_LABEL.LOW;
  const limitations = [];
  if (freshness.status !== FRESHNESS_STATUS.FRESH) {
    limitations.push(`Evidence freshness is ${freshness.status}.`);
  }
  if (basisEvidenceRefs.length === 0) {
    limitations.push("No gate-referenced evidence is both externally authorized and identity-bound.");
  }
  if (!scoreVerified) {
    limitations.push("Required score evidence is not verified.");
  }
  if (unverifiedGateIds.length > 0) {
    limitations.push(`Required gates remain unverified: ${unverifiedGateIds.join(", ")}.`);
  }
  limitations.push("Evaluator execution provenance is not verified.");
  return {
    label,
    value: CONTRACT_VALUES.confidencePolicy.canonicalValueByLabel[label],
    basisEvidenceRefs,
    limitations: [...new Set(limitations)].sort(compareText),
  };
}

function generatedFindingState(request, dimensions) {
  const regressions = request.regressions.map((item) => ({ ...item, evidenceRefs: [...item.evidenceRefs].sort(compareText) }));
  const blockers = request.blockers.map((item) => ({ ...item, evidenceRefs: [...item.evidenceRefs].sort(compareText) }));
  const gaps = request.remainingGaps.map((item) => ({ ...item, evidenceRefs: [...item.evidenceRefs].sort(compareText) }));
  const dimensionIds = new Set(dimensions.map(({ id }) => id));
  for (const finding of [...regressions, ...gaps]) {
    if (finding.dimensionId !== null && !dimensionIds.has(finding.dimensionId)) {
      throw new Error(`${finding.id} references unknown dimension ${finding.dimensionId}`);
    }
  }
  assertUnique([...blockers, ...regressions, ...gaps].map(({ id }) => id), "Finding IDs");

  for (const dimension of dimensions) {
    if (dimension.required && dimension.current === null) {
      blockers.push({
        id: `missing-current-${dimension.id}`,
        severity: BLOCKER_SEVERITY.MATERIAL,
        status: BLOCKER_STATUS.OPEN,
        summary: `${dimension.label} is required but has no current score.`,
        evidenceRefs: [...dimension.evidenceRefs],
        unlock: {
          kind: UNLOCK_KIND.EXTERNAL_EVIDENCE,
          description: `Supply bound current evidence and a displayed score for ${dimension.label}.`,
          requiredEvidenceKinds: [EVIDENCE_KIND.MEASURED, EVIDENCE_KIND.REVIEWER_JUDGMENT],
        },
      });
    }
    if (dimension.required && dimension.comparisonStatus === "DECREASED") {
      const matching = regressions.filter((regression) => regression.dimensionId === dimension.id);
      if (matching.length === 0) {
        regressions.push({
          id: `decrease-${dimension.id}`,
          dimensionId: dimension.id,
          severity: BLOCKER_SEVERITY.MATERIAL,
          summary: `${dimension.label} decreased and has no accepted trade-off.`,
          evidenceRefs: [...dimension.evidenceRefs],
          disposition: FINDING_DISPOSITION.BLOCKER,
        });
      }
      if (!matching.some(
        (regression) => regression.disposition === FINDING_DISPOSITION.ACCEPTED_TRADE_OFF,
      )) {
        blockers.push({
          id: `unaccepted-decrease-${dimension.id}`,
          severity: BLOCKER_SEVERITY.MATERIAL,
          status: BLOCKER_STATUS.OPEN,
          summary: `${dimension.label} decreased without an explicit ACCEPTED_TRADE_OFF regression.`,
          evidenceRefs: [...dimension.evidenceRefs],
          unlock: {
            kind: UNLOCK_KIND.HUMAN_APPROVAL,
            description: `Record an explicit ACCEPTED_TRADE_OFF regression for ${dimension.label} or restore the score.`,
            requiredEvidenceKinds: [EVIDENCE_KIND.REVIEWER_JUDGMENT],
          },
        });
      }
    }
  }
  if ([DISCOVERY_STATUS.CHANGED_ACTION_REQUIRED, DISCOVERY_STATUS.UNVERIFIED]
    .includes(request.searchDiscovery.status)) {
    blockers.push({
      id: request.searchDiscovery.status === DISCOVERY_STATUS.CHANGED_ACTION_REQUIRED
        ? "search-discovery-action-required"
        : "search-discovery-unverified",
      severity: BLOCKER_SEVERITY.MATERIAL,
      status: BLOCKER_STATUS.OPEN,
      summary: request.searchDiscovery.statement,
      evidenceRefs: [...request.searchDiscovery.evidenceRefs].sort(compareText),
      unlock: {
        kind: request.searchDiscovery.status === DISCOVERY_STATUS.CHANGED_ACTION_REQUIRED
          ? UNLOCK_KIND.IMPLEMENTATION
          : UNLOCK_KIND.EXTERNAL_EVIDENCE,
        description: request.searchDiscovery.actions.length > 0
          ? request.searchDiscovery.actions.join(" ")
          : "Supply bound search and AI discovery impact evidence.",
        requiredEvidenceKinds: [EVIDENCE_KIND.MEASURED],
      },
    });
  }
  assertUnique([...blockers, ...regressions, ...gaps].map(({ id }) => id), "Generated and supplied finding IDs");
  return {
    blockers: blockers.sort((left, right) => compareText(left.id, right.id)),
    regressions: regressions.sort((left, right) => compareText(left.id, right.id)),
    remainingGaps: gaps.sort((left, right) => compareText(left.id, right.id)),
  };
}

function reviewId(review) {
  const { reviewId: ignored, ...payload } = review;
  return canonicalDigest(payload);
}

export function assertReviewSemantics({ review, profile, evidenceIds }) {
  if (review.reviewId !== reviewId(review)) throw new Error("reviewId does not match the canonical review result");
  if (review.scoringMethod !== profile.scoringMethod) {
    throw new Error("Review scoringMethod does not match the bound profile");
  }
  assertScorecardSemantics(
    profile,
    review.dimensions,
    review.overall,
    review.submittedOverall,
    review.comparisonStatus,
  );
  assertReadinessSemantics(review);
  const provenanceBlockers = review.blockers.filter(({ id }) => id === PROVENANCE_BLOCKER_ID);
  if (provenanceBlockers.length !== 1
    || provenanceBlockers[0].severity !== BLOCKER_SEVERITY.MATERIAL
    || provenanceBlockers[0].status !== BLOCKER_STATUS.OPEN
    || review.confidence.label !== CONFIDENCE_LABEL.LOW
    || review.confidence.value !== CONTRACT_VALUES.confidencePolicy.canonicalValueByLabel.LOW) {
    throw new Error("Unverified evaluator execution provenance must remain a material blocker with LOW confidence");
  }
  const known = new Set(evidenceIds);
  for (const reference of review.evidenceRefs) {
    if (!known.has(reference.evidenceId)) throw new Error(`Review evidence registry contains unknown ${reference.evidenceId}`);
  }
  return true;
}

async function assertOutputLocation(
  repositoryRoot,
  outputDirectory,
  gitExecutable,
  expectedGitExecutableSha256,
) {
  if (!isPathInside(repositoryRoot, outputDirectory)) {
    throw new Error("Output directory must be inside the repository");
  }
  const verifiedGitExecutable = await verifyGitExecutableAdapter({
    gitExecutablePath: gitExecutable,
    expectedGitExecutableSha256,
  });
  const parentSnapshot = await captureDirectDirectorySnapshot({
    anchorPath: repositoryRoot,
    path: dirname(outputDirectory),
    label: "Output parent",
  });
  const relativePath = relative(repositoryRoot, outputDirectory).split(sep).join("/");
  assertCheckIgnorePath(relativePath);
  const tracked = runGitSync({
    repositoryRoot,
    gitExecutable: verifiedGitExecutable.executablePath,
    argumentsList: ["ls-files", "--error-unmatch", "--", relativePath],
    acceptedStatuses: [0, 1],
    literalPathspecs: true,
  });
  if (tracked.status === 0) throw new Error(`Output directory is tracked by Git: ${relativePath}`);
  const ignored = runGitSync({
    repositoryRoot,
    gitExecutable: verifiedGitExecutable.executablePath,
    argumentsList: ["check-ignore", "--no-index", "--quiet", "--", relativePath],
    acceptedStatuses: [0, 1],
    literalPathspecs: false,
  });
  if (ignored.status !== 0) throw new Error(`Output directory must be Git-ignored: ${relativePath}`);
  await assertPathAbsent(
    outputDirectory,
    `Output directory already exists and will not be overwritten: ${relativePath}`,
  );
  await assertDirectPathSnapshot(parentSnapshot);
  return parentSnapshot;
}

async function writeBundle({
  outputDirectory,
  review,
  scorecard,
  readiness,
  manifestState,
  identities,
  receiptFiles,
  reviewRequest,
  producerRegistry,
  candidateSnapshotMaterial,
  toolIdentity,
  outputParentSnapshot,
}) {
  const files = new Map();
  if (toolIdentity.runtimePackages.length !== 0) {
    throw new Error("Offline bundle reconstruction requires runtime-package bytes, which schema v1 does not support");
  }
  files.set("candidate-identity.json", canonicalFile(identities.candidate));
  if (identities.baseline !== null) files.set("baseline-identity.json", canonicalFile(identities.baseline));
  if (identities.deployment !== null) files.set("deployment-identity.json", canonicalFile(identities.deployment));
  files.set("evidence-manifest.json", canonicalFile(manifestState.evidenceManifest));
  files.set("review.json", canonicalFile(review));
  files.set("scorecard.json", canonicalFile(scorecard));
  files.set("readiness.json", canonicalFile(readiness));
  files.set("inputs/review-request.json", canonicalFile(reviewRequest));
  files.set("inputs/producer-registry.json", canonicalFile(producerRegistry));
  files.set("subject/candidate/tracked.patch", candidateSnapshotMaterial.trackedPatch);
  files.set("subject/candidate/reconstruction.json", canonicalFile({
    schemaVersion: 1,
    identityId: identities.candidate.identityId,
    mode: identities.candidate.mode,
    baseCommit: identities.candidate.baseCommit,
    candidateCommit: identities.candidate.candidateCommit,
    trackedPatch: {
      path: "subject/candidate/tracked.patch",
      sha256: sha256(candidateSnapshotMaterial.trackedPatch),
      sizeBytes: candidateSnapshotMaterial.trackedPatch.byteLength,
    },
    untrackedFiles: candidateSnapshotMaterial.untrackedFiles.map((untracked) => ({
      path: untracked.path,
      bundlePath: `subject/candidate/untracked/${untracked.path}`,
      sha256: untracked.sha256,
      sizeBytes: untracked.sizeBytes,
    })),
    reviewerSourceRoot: "reviewer/source",
    reviewerSourceDigest: toolIdentity.toolSourceDigest,
    evaluatorExecutionProvenance: toolIdentity.evaluatorExecutionProvenance,
  }));
  for (const untracked of candidateSnapshotMaterial.untrackedFiles) {
    files.set(`subject/candidate/untracked/${untracked.path}`, untracked.bytes);
  }
  for (const [repositoryPathValue, bytes] of toolIdentity.toolFileBytesByPath) {
    files.set(`reviewer/source/${repositoryPathValue}`, bytes);
  }
  const evidenceReceipts = receiptFiles.map(({ receipt }) => receipt);
  files.set("report.md", Buffer.from(renderMarkdown(review, evidenceReceipts), "utf8"));
  files.set("index.html", Buffer.from(renderHtml(review, evidenceReceipts), "utf8"));
  for (const { receipt } of receiptFiles) {
    files.set(`evidence/receipts/${receipt.receiptId}.json`, canonicalFile(receipt));
    for (const artifact of receipt.artifacts) {
      const key = `evidence/artifacts/${artifact.sha256}`;
      if (!files.has(key)) {
        const verifiedBytes = manifestState.artifactBytesBySha256.get(artifact.sha256);
        if (verifiedBytes === undefined) {
          throw new Error(`Verified artifact bytes are unavailable for ${artifact.id}`);
        }
        files.set(key, verifiedBytes);
      }
    }
  }
  const entries = [...files.entries()].map(([path, bytes]) => ({ path, sha256: sha256(bytes) }));
  const digest = bundleDigest(entries);
  files.set("bundle.sha256", Buffer.from(bundleChecksumText(entries), "utf8"));

  await assertDirectPathSnapshot(outputParentSnapshot);
  try {
    await assertDirectPathSnapshot(outputParentSnapshot);
    const outputSnapshot = await createDirectoryUnderSnapshot({
      rootSnapshot: outputParentSnapshot,
      path: outputDirectory,
      label: "Completed-work bundle output directory",
    });
    const directories = new Set();
    for (const path of files.keys()) {
      let directory = dirname(path);
      while (directory !== ".") {
        directories.add(directory);
        directory = dirname(directory);
      }
    }
    const sortedDirectories = [...directories].sort((left, right) => {
      const depthDelta = left.split(/[\\/]/u).length - right.split(/[\\/]/u).length;
      return depthDelta === 0 ? compareText(left, right) : depthDelta;
    });
    for (const path of sortedDirectories) {
      await createDirectoryUnderSnapshot({
        rootSnapshot: outputSnapshot,
        path: resolve(outputDirectory, path),
        label: `Bundle output directory ${path}`,
      });
    }
    for (const [path, bytes] of [...files.entries()].sort(([left], [right]) => compareText(left, right))) {
      const destination = resolve(outputDirectory, path);
      if (!isPathInside(outputDirectory, destination)) throw new Error(`Unsafe bundle output path: ${path}`);
      await writeNewFileUnderSnapshot({
        rootSnapshot: outputSnapshot,
        path: destination,
        bytes,
        label: `Bundle output file ${path}`,
      });
    }
    await assertDirectPathSnapshot(outputSnapshot);
    await assertDirectPathSnapshot(outputParentSnapshot);
  } catch (error) {
    throw new Error(
      `${error.message}; fail-closed cleanup retained output path ${outputDirectory}`,
      { cause: error },
    );
  }
  return { bundleDigest: digest, files: [...files.keys()].sort(compareText) };
}

export async function assembleReview({
  repositoryRoot: repositoryInput,
  requestPath,
  requestSchemaPath,
  producerRegistryPath,
  producerRegistrySchemaPath,
  gitExecutable,
  expectedGitExecutableSha256,
  repositoryId,
  repositoryRemote,
  evaluationTime,
  producerRegistryDigest,
  expectedCandidateIdentityId,
  expectedBaselineIdentityId,
  expectedDeploymentIdentityId,
}) {
  if (typeof gitExecutable !== "string" || !isAbsolute(gitExecutable)) {
    throw new Error("Git executable must be an explicit absolute path");
  }
  if (typeof repositoryId !== "string" || repositoryId.length === 0) {
    throw new Error("Repository ID must be an explicit non-empty string");
  }
  if (repositoryRemote === null || typeof repositoryRemote !== "object") {
    throw new Error("Repository remote must be explicitly supplied");
  }
  for (const [label, value, allowAbsent] of [
    ["expectedCandidateIdentityId", expectedCandidateIdentityId, false],
    ["expectedBaselineIdentityId", expectedBaselineIdentityId, true],
    ["expectedDeploymentIdentityId", expectedDeploymentIdentityId, true],
  ]) {
    if (!(allowAbsent && value === ABSENT_IDENTITY) && !/^[a-f0-9]{64}$/u.test(value ?? "")) {
      throw new Error(`${label} must be an explicit SHA-256 identity ID${allowAbsent ? ` or ${ABSENT_IDENTITY}` : ""}`);
    }
  }
  const evaluationTimeMs = validTimestamp(evaluationTime, "evaluationTime");
  if (Math.abs(Date.now() - evaluationTimeMs) > MAX_EVALUATION_CLOCK_SKEW_MS) {
    throw new Error("evaluationTime must be within five minutes of the trusted process clock");
  }
  const repositoryState = await assertRepositoryRoot(
    repositoryInput,
    gitExecutable,
    expectedGitExecutableSha256,
  );
  const repositoryRoot = repositoryState.repositoryRoot;
  const verifiedGitExecutable = repositoryState.gitExecutable;
  if (typeof requestPath !== "string" || typeof requestSchemaPath !== "string"
    || !isAbsolute(requestPath) || !isAbsolute(requestSchemaPath)) {
    throw new Error("Request and request-schema paths must be explicit absolute paths");
  }
  const [resolvedRequestPath, resolvedRequestSchemaPath] = await Promise.all([
    resolveDirectRegularFilePath({
      anchorPath: repositoryRoot,
      path: requestPath,
      label: "Review request",
    }),
    resolveDirectRegularFilePath({
      anchorPath: repositoryRoot,
      path: requestSchemaPath,
      label: "Review request schema",
    }),
  ]);
  if (!isPathInside(repositoryRoot, resolvedRequestPath)) {
    throw new Error("Review request must be a direct, non-link file inside the repository");
  }
  const requestRepositoryPath = relative(repositoryRoot, resolvedRequestPath).split(sep).join("/");
  await repositoryFilePath(repositoryRoot, requestRepositoryPath, "Review request");
  assertCanonicalPath(
    repositoryRoot,
    resolvedRequestSchemaPath,
    CANONICAL_REPOSITORY_PATHS.reviewRequestSchema,
    "Review request schema",
  );
  const [requestDocument, requestSchema] = await Promise.all([
    readJsonDocument(resolvedRequestPath, "Review request", repositoryRoot),
    readJson(resolvedRequestSchemaPath, "Review request schema", repositoryRoot),
  ]);
  const request = requestDocument.value;
  if (requestSchema.$id !== CONTRACT_VALUES.schemaIds.reviewRequest) {
    throw new Error(`Unexpected request schema identity: ${requestSchema.$id}`);
  }
  assertReviewRequestSchemaProjections(requestSchema);
  assertContract(requestSchema, request, "Completed-work review request");
  const pathEntries = await Promise.all(Object.entries(request.paths).map(async ([key, value]) => {
    if (key === "outputDirectory") return [key, repositoryPath(repositoryRoot, value, key)];
    if (Array.isArray(value)) {
      return [key, await Promise.all(value.map((item, index) => (
        repositoryFilePath(repositoryRoot, item, `${key} item ${index}`)
      )))];
    }
    if (value === null) return [key, null];
    return [key, await repositoryFilePath(repositoryRoot, value, key)];
  }));
  const paths = Object.fromEntries(pathEntries);
  for (const key of [
    "profiles",
    "profileSchema",
    "candidateIdentitySchema",
    "evidenceReceiptSchema",
    "reviewResultSchema",
  ]) {
    assertCanonicalPath(repositoryRoot, paths[key], CANONICAL_REPOSITORY_PATHS[key], key);
  }
  const [candidateIdentitySchema, evidenceSchema, resultSchema] = await Promise.all([
    readJson(paths.candidateIdentitySchema, "Candidate identity schema", repositoryRoot),
    readJson(paths.evidenceReceiptSchema, "Evidence receipt schema", repositoryRoot),
    readJson(paths.reviewResultSchema, "Review result schema", repositoryRoot),
  ]);
  assertCandidateIdentitySchemaProjections(candidateIdentitySchema);
  assertEvidenceReceiptSchemaProjections(evidenceSchema);
  assertReviewResultSchemaProjections(resultSchema);
  const loaded = await loadReviewProfiles({
    anchorPath: repositoryRoot,
    profilesPath: paths.profiles,
    schemaPath: paths.profileSchema,
  });
  const profile = loaded.profilesById.get(request.profileId);
  if (!profile) throw new Error(`Unknown completed-work profile: ${request.profileId}`);
  if (profile.scope !== request.verdictScope) {
    throw new Error(`${request.profileId} requires verdict scope ${profile.scope}, not ${request.verdictScope}`);
  }
  const maximumScopeIndex = CONTRACT_VALUES.verdictScope.indexOf(request.publicationBoundary.maximumVerifiedScope);
  const verdictScopeIndex = CONTRACT_VALUES.verdictScope.indexOf(request.verdictScope);
  if (maximumScopeIndex < 0 || verdictScopeIndex < 0 || maximumScopeIndex > verdictScopeIndex) {
    throw new Error("Publication boundary cannot exceed the requested verdict scope");
  }
  if ([
    request.publicationBoundary.mergeAuthority,
    request.publicationBoundary.publicationAuthority,
    request.publicationBoundary.deploymentAuthority,
  ].includes(EXTERNAL_AUTHORITY_STATUS.EXTERNALLY_VERIFIED)) {
    throw new Error("Schema v1 local profiles do not accept external merge, publication, or deployment authority");
  }
  assertSubmittedConfidence(request.confidence);
  const toolManifest = await loadToolManifest(repositoryRoot);
  const toolIdentity = await collectToolIdentity({
    repositoryRoot,
    toolFilePaths: toolManifest.files,
    runtimePackageSpecifications: toolManifest.runtimePackages,
  });
  const producerRegistryState = await loadProducerRegistry({
    repositoryRoot,
    registryPath: producerRegistryPath,
    registrySchemaPath: producerRegistrySchemaPath,
    profileId: request.profileId,
    profileDigest: loaded.configDigest,
    evaluatedAt: evaluationTime,
    expectedRegistryDigest: producerRegistryDigest,
  });
  if (producerRegistryState.registry.toolSourceDigest !== toolIdentity.toolSourceDigest) {
    throw new Error("Producer registry does not pin the exact completed-work tool source digest");
  }

  const [candidate, baseline, deployment] = await Promise.all([
    readIdentity({ anchorPath: repositoryRoot, identityPath: paths.candidateIdentity, schema: candidateIdentitySchema, label: "Candidate identity" }),
    paths.baselineIdentity === null ? null : readIdentity({ anchorPath: repositoryRoot, identityPath: paths.baselineIdentity, schema: candidateIdentitySchema, label: "Baseline identity" }),
    paths.deploymentIdentity === null ? null : readIdentity({ anchorPath: repositoryRoot, identityPath: paths.deploymentIdentity, schema: candidateIdentitySchema, label: "Deployment identity" }),
  ]);
  if (candidate.mode === CANDIDATE_IDENTITY_MODE.DIRTY_WORKTREE
    && request.candidateVerificationTarget !== CANDIDATE_VERIFICATION_TARGET.LIVE_WORKTREE) {
    throw new Error("DIRTY_WORKTREE candidates require LIVE_WORKTREE verification");
  }
  const requireCandidateWorktreeMatch = request.candidateVerificationTarget
    === CANDIDATE_VERIFICATION_TARGET.LIVE_WORKTREE;
  await Promise.all([
    [candidate, requireCandidateWorktreeMatch],
    [baseline, false],
    [deployment, false],
  ].filter(([identity]) => identity !== null).map(([identity, requireWorktreeMatch]) => (
    verifyCandidateIdentity({
      identity,
      repositoryRoot,
      gitExecutablePath: verifiedGitExecutable,
      expectedGitExecutableSha256,
      repositoryId,
      repositoryRemote,
      requireWorktreeMatch,
    })
  )));
  for (const identity of [baseline, deployment].filter(Boolean)) {
    if (identity.repositoryId !== candidate.repositoryId) throw new Error("All identities must use the same repositoryId");
  }
  const identities = { candidate, baseline, deployment };
  if (candidate.identityId !== expectedCandidateIdentityId) {
    throw new Error("Candidate identity does not match the explicitly expected candidate identity ID");
  }
  for (const [label, identity, expected] of [
    ["Baseline", baseline, expectedBaselineIdentityId],
    ["Deployment", deployment, expectedDeploymentIdentityId],
  ]) {
    if ((identity === null && expected !== ABSENT_IDENTITY)
      || (identity !== null && identity.identityId !== expected)) {
      throw new Error(`${label} identity does not match its explicitly expected identity ID`);
    }
  }
  if (baseline !== null) {
    if (baseline.mode !== CANDIDATE_IDENTITY_MODE.COMMITTED_GIT) {
      throw new Error("Baseline identity must be a committed Git snapshot");
    }
    if (candidate.baseCommit !== baseline.candidateCommit
      || candidate.mergeBaseCommit !== baseline.candidateCommit) {
      throw new Error("Candidate identity must link its base and merge base to the exact baseline commit");
    }
  }
  if (baseline === null && request.dimensions.some(({ baseline: score }) => score !== null)) {
    throw new Error("A missing baseline identity requires explicit null baseline scores and an UNVERIFIED comparison");
  }
  for (const [label, identity] of Object.entries(identities)) {
    if (identity === null) continue;
    const capturedTime = validTimestamp(identity.capturedAt, `${label} identity capturedAt`);
    if (capturedTime > evaluationTimeMs) {
      throw new Error(`${label} identity capturedAt must not be after evaluationTime`);
    }
    if (label === "candidate"
      && requireCandidateWorktreeMatch
      && evaluationTimeMs - capturedTime > MAX_EVALUATION_CLOCK_SKEW_MS) {
      throw new Error("Live candidate identity capturedAt must be within five minutes of evaluationTime");
    }
  }

  const receiptFiles = await Promise.all(paths.evidenceReceipts.map((path, index) => readEvidenceReceipt({
    path,
    repositoryPath: request.paths.evidenceReceipts[index],
    repositoryRoot,
    schema: evidenceSchema,
  })));
  const receiptsById = new Map(receiptFiles.map(({ receipt }) => [receipt.evidenceId, receipt]));
  if (receiptsById.size !== receiptFiles.length) throw new Error("Evidence IDs must be unique");
  assertEvidenceReferences(request, receiptsById);

  const manifestState = await buildEvidenceManifest({
    repositoryRoot,
    reviewRequest: request,
    reviewRequestBytes: requestDocument.bytes,
    profileId: request.profileId,
    profileDigest: loaded.configDigest,
    verdictScope: request.verdictScope,
    identities,
    receiptFiles,
    toolIdentity,
    producerRegistry: producerRegistryState.registry,
    producerRegistryBytes: producerRegistryState.bytes,
    generatedAt: evaluationTime,
  });
  const trust = receiptTrust({
    receiptsById,
    producerRegistry: producerRegistryState.registry,
    identities,
    profileDigest: loaded.configDigest,
    evaluatedAt: evaluationTime,
    maxAgeSeconds: profile.freshness.maxAgeSeconds,
  });
  for (const reference of request.approvedReferenceEvidenceRefs) {
    const receipt = receiptsById.get(reference);
    if (trust.trustById.get(reference)?.usable !== true
      || receipt.status !== EVIDENCE_STATUS.PASS
      || receipt.execution.entrypoint === null
      || (receipt.artifacts.length === 0 && receipt.observations.length === 0)) {
      throw new Error(
        `Approved reference ${reference} must be exact trusted, fresh, passing, and substantive evidence`,
      );
    }
  }
  const submittedScorecardState = buildScorecard(
    profile,
    request.dimensions,
    { scoreStatus: SCORE_STATUS.VERIFIED },
  );
  const gateState = buildGates({
    profile,
    gateInputs: request.gateInputs,
    identities,
    dimensions: submittedScorecardState.dimensions,
    docsImpact: request.docsImpact,
    searchDiscovery: request.searchDiscovery,
    receiptsById,
    trustById: trust.trustById,
  });
  const scoreGate = gateState.gates.find(({ id }) => id === GATE_ID["required-score-evidence"]);
  if (scoreGate === undefined) throw new Error("Profile has no required-score-evidence gate");
  const scorecardState = buildScorecard(profile, request.dimensions, {
    scoreStatus: scoreGate.status === GATE_STATUS.VERIFIED
      ? SCORE_STATUS.VERIFIED
      : SCORE_STATUS.NOT_VERIFIED,
  });
  const confidence = buildCanonicalConfidence({
    gates: gateState.gates,
    freshness: trust.freshness,
    trustById: trust.trustById,
    evaluatorExecutionProvenance: toolIdentity.evaluatorExecutionProvenance,
  });
  const findingState = generatedFindingState(request, scorecardState.dimensions);
  const findingReadiness = buildFindingReadiness({
    blockers: findingState.blockers,
    regressions: findingState.regressions,
    remainingGaps: findingState.remainingGaps,
    receiptsById,
    trustById: trust.trustById,
    freshness: profile.freshness,
  });
  const blockers = [
    ...findingState.blockers,
    ...gateState.generatedBlockers,
    ...findingReadiness.generatedBlockers,
    {
      id: PROVENANCE_BLOCKER_ID,
      severity: BLOCKER_SEVERITY.MATERIAL,
      status: BLOCKER_STATUS.OPEN,
      summary: "Executed evaluator source is not verified by the local review process.",
      evidenceRefs: [],
      unlock: {
        kind: UNLOCK_KIND.EXTERNAL_EVIDENCE,
        description: "Run the evaluator under an approved protected pre-load controller and supply its externally verifiable execution attestation.",
        requiredEvidenceKinds: [EVIDENCE_KIND.MEASURED],
      },
    },
  ]
    .sort((left, right) => compareText(left.id, right.id));
  assertUnique(blockers.map(({ id }) => id), "Final blocker IDs");
  const evidenceRefs = manifestState.receipts.map((receipt) => ({
    evidenceId: receipt.evidenceId,
    receiptDigest: receipt.receiptId,
    repositoryPath: receipt.repositoryPath,
  }));
  await verifyCandidateIdentity({
    identity: candidate,
    repositoryRoot,
    gitExecutablePath: verifiedGitExecutable,
    expectedGitExecutableSha256,
    repositoryId,
    repositoryRemote,
    requireWorktreeMatch: requireCandidateWorktreeMatch,
  });
  const candidateSnapshotMaterial = await collectCandidateSnapshotMaterial({
    identity: candidate,
    repositoryRoot,
    gitExecutablePath: verifiedGitExecutable,
    expectedGitExecutableSha256,
    repositoryRemote,
  });
  const review = {
    schemaVersion: 1,
    reviewId: "0".repeat(64),
    profileId: request.profileId,
    profileDigest: loaded.configDigest,
    scoringMethod: profile.scoringMethod,
    evidenceManifestDigest: manifestState.evidenceManifestDigest,
    trustControl: {
      producerRegistryAuthority: producerRegistryState.registry.authority,
      producerRegistryDigest: canonicalDigest(producerRegistryState.registry),
      toolSourceDigest: manifestState.toolSourceDigest,
      toolDigest: manifestState.toolDigest,
      evaluatorExecutionProvenance: toolIdentity.evaluatorExecutionProvenance,
    },
    verdictScope: request.verdictScope,
    identityRefs: {
      baselineIdentityId: baseline?.identityId ?? null,
      candidateIdentityId: candidate.identityId,
      candidateVerificationTarget: request.candidateVerificationTarget,
      approvedReferenceEvidenceRefs: [...request.approvedReferenceEvidenceRefs].sort(compareText),
      deploymentIdentityId: deployment?.identityId ?? null,
    },
    docsImpact: request.docsImpact,
    searchDiscovery: {
      ...request.searchDiscovery,
      evidenceRefs: [...request.searchDiscovery.evidenceRefs].sort(compareText),
      actions: [...request.searchDiscovery.actions].sort(compareText),
    },
    comparisonStatus: scorecardState.comparisonStatus,
    readinessVerdict: readinessVerdict(gateState.gates, blockers),
    publicationBoundary: {
      ...request.publicationBoundary,
      authorityEvidenceRefs: [...request.publicationBoundary.authorityEvidenceRefs].sort(compareText),
    },
    confidence,
    submittedConfidence: {
      ...request.confidence,
      basisEvidenceRefs: [...request.confidence.basisEvidenceRefs].sort(compareText),
      limitations: [...request.confidence.limitations].sort(compareText),
    },
    freshness: trust.freshness,
    independentReview: {
      status: gateState.independentReviewStatus,
      reviewerIds: [...gateState.independentReview.reviewerIds],
      passKinds: [...gateState.independentReview.passKinds],
      evidenceRefs: [...gateState.independentReview.evidenceRefs],
    },
    dimensions: scorecardState.dimensions,
    overall: scorecardState.overall,
    submittedOverall: scorecardState.submittedOverall,
    gates: gateState.gates,
    blockers,
    regressions: findingState.regressions,
    remainingGaps: findingState.remainingGaps,
    evidenceRefs,
    generatedAt: evaluationTime,
  };
  review.reviewId = reviewId(review);
  assertContract(resultSchema, review, "Completed-work review result");
  assertReviewSemantics({ review, profile, evidenceIds: receiptsById.keys() });

  const scorecard = {
    schemaVersion: 1,
    profileId: review.profileId,
    profileDigest: review.profileDigest,
    scoringMethod: review.scoringMethod,
    dimensions: review.dimensions,
    overall: review.overall,
    submittedOverall: review.submittedOverall,
    comparisonStatus: review.comparisonStatus,
  };
  const readiness = {
    schemaVersion: 1,
    reviewId: review.reviewId,
    verdictScope: review.verdictScope,
    readinessVerdict: review.readinessVerdict,
    gates: review.gates,
    blockers: review.blockers,
    publicationBoundary: review.publicationBoundary,
  };
  const outputParentSnapshot = await assertOutputLocation(
    repositoryRoot,
    paths.outputDirectory,
    verifiedGitExecutable,
    expectedGitExecutableSha256,
  );
  const bundle = await writeBundle({
    outputDirectory: paths.outputDirectory,
    review,
    scorecard,
    readiness,
    manifestState,
    identities,
    receiptFiles,
    reviewRequest: request,
    producerRegistry: producerRegistryState.registry,
    candidateSnapshotMaterial,
    toolIdentity,
    outputParentSnapshot,
  });
  try {
    const verifiedBundle = await verifyBundleDirectory(paths.outputDirectory, bundle.bundleDigest);
    if (verifiedBundle.bundleDigest !== bundle.bundleDigest) {
      throw new Error("Written bundle verification did not reproduce the assembled digest");
    }
  } catch (error) {
    throw new Error(
      `${error.message}; fail-closed cleanup retained published output ${paths.outputDirectory}`,
      { cause: error },
    );
  }
  return {
    bundleDigest: bundle.bundleDigest,
    outputDirectory: paths.outputDirectory,
    review,
    scorecard,
    readiness,
  };
}

export { reviewId };
