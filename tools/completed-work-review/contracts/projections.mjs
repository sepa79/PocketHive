import { readFileSync } from "node:fs";
import { TextDecoder } from "node:util";

const UTF8_DECODER = new TextDecoder("utf-8", { fatal: true });

function deepFreeze(value) {
  if (value !== null && typeof value === "object" && !Object.isFrozen(value)) {
    for (const child of Object.values(value)) deepFreeze(child);
    Object.freeze(value);
  }
  return value;
}

export const CONTRACT_VALUES = deepFreeze(JSON.parse(UTF8_DECODER.decode(
  readFileSync(new URL("./values.json", import.meta.url))
)));

const CANONICAL_REPOSITORY_PATH_KEYS = Object.freeze([
  "candidateIdentitySchema",
  "decisionReceiptSchema",
  "evidenceReceiptSchema",
  "producerRegistrySchema",
  "profileSchema",
  "profiles",
  "reviewRequestSchema",
  "reviewResultSchema",
  "toolManifest",
]);

export function assertCanonicalRepositoryPathValues() {
  const paths = CONTRACT_VALUES.canonicalRepositoryPaths;
  const keys = Object.keys(paths ?? {}).sort();
  if (JSON.stringify(keys) !== JSON.stringify(CANONICAL_REPOSITORY_PATH_KEYS)) {
    throw new Error("canonicalRepositoryPaths must expose the exact closed path-key set");
  }
  const values = Object.values(paths);
  if (new Set(values).size !== values.length) {
    throw new Error("canonicalRepositoryPaths values must be unique");
  }
  for (const [key, value] of Object.entries(paths)) {
    if (typeof value !== "string" || value.length === 0 || value.includes("\\")
      || value.startsWith("/")
      || value.split("/").some((segment) => segment === "" || segment === "." || segment === "..")) {
      throw new Error(`canonicalRepositoryPaths.${key} must be a safe repository-relative path`);
    }
  }
  return true;
}

assertCanonicalRepositoryPathValues();

function compareText(left, right) {
  return left < right ? -1 : left > right ? 1 : 0;
}

function assertProjection(label, projected, valuesKey) {
  const expected = [...CONTRACT_VALUES[valuesKey]].sort(compareText);
  const actual = Array.isArray(projected) ? [...projected].sort(compareText) : [];
  if (JSON.stringify(actual) !== JSON.stringify(expected)) {
    throw new Error(
      `${label} must project canonical ${valuesKey} values from `
      + "tools/completed-work-review/contracts/values.json"
    );
  }
}

function assertConstSet(label, projected, valuesKey) {
  assertProjection(label, projected.map((value) => value?.const), valuesKey);
}

function assertLimit(label, projected, limitKey) {
  if (projected !== CONTRACT_VALUES.limits[limitKey]) {
    throw new Error(
      `${label} must project canonical limit ${limitKey} from `
      + "tools/completed-work-review/contracts/values.json"
    );
  }
}

function assertVersion(label, projected) {
  if (projected !== CONTRACT_VALUES.schemaVersion) {
    throw new Error(`${label} must be exactly ${CONTRACT_VALUES.schemaVersion}`);
  }
}

function assertSchemaId(label, projected, schemaKey) {
  if (projected !== CONTRACT_VALUES.schemaIds[schemaKey]) {
    throw new Error(`${label} must be exactly ${CONTRACT_VALUES.schemaIds[schemaKey]}`);
  }
}

export function assertClosedObjectSchemas(schema, path = "$") {
  if (schema?.type === "object" && schema.additionalProperties !== false) {
    throw new Error(`${path} must set additionalProperties to false`);
  }
  for (const [key, child] of Object.entries(schema?.properties ?? {})) {
    assertClosedObjectSchemas(child, `${path}.properties.${key}`);
  }
  for (const [key, child] of Object.entries(schema?.$defs ?? {})) {
    assertClosedObjectSchemas(child, `${path}.$defs.${key}`);
  }
  if (schema?.items && typeof schema.items === "object") {
    assertClosedObjectSchemas(schema.items, `${path}.items`);
  }
  for (const [index, child] of (schema?.oneOf ?? []).entries()) {
    assertClosedObjectSchemas(child, `${path}.oneOf[${index}]`);
  }
}

export function assertProfileSchemaProjections(schema) {
  assertVersion("profile schemaVersion", schema.properties?.schemaVersion?.const);
  const profile = schema.properties?.profiles?.items?.properties;
  const dimension = profile?.dimensions?.items?.properties;
  const gate = profile?.requiredGates?.items?.properties;
  assertProjection("profile id", profile?.id?.enum, "profileId");
  assertProjection("profile scope", profile?.scope?.enum, "verdictScope");
  assertProjection("profile kind", profile?.kind?.enum, "profileKind");
  assertProjection("profile scoring method", profile?.scoringMethod?.enum, "scoringMethod");
  assertProjection("profile dimension kind", dimension?.kind?.enum, "dimensionKind");
  assertProjection("profile score direction", dimension?.direction?.enum, "scoreDirection");
  assertProjection(
    "profile score anchor values",
    dimension?.scoreAnchors?.items?.properties?.score?.enum,
    "scoreAnchorValues"
  );
  if (dimension?.scoreAnchors?.minItems !== CONTRACT_VALUES.scoreAnchorValues.length
    || dimension?.scoreAnchors?.maxItems !== CONTRACT_VALUES.scoreAnchorValues.length) {
    throw new Error("Profile score anchors must contain the complete canonical anchor set");
  }
  assertProjection("profile evidence kind", dimension?.evidenceKinds?.items?.enum, "evidenceKind");
  assertProjection("profile evidence adapter", schema.$defs?.evidenceAdapter?.enum, "evidenceAdapter");
  assertProjection("profile gate id", schema.$defs?.gateId?.enum, "gateId");
  if (dimension?.allowedAdapters?.items?.$ref !== "#/$defs/evidenceAdapter"
    || gate?.allowedAdapters?.items?.$ref !== "#/$defs/evidenceAdapter") {
    throw new Error("Profile dimension and gate adapter allowlists must reference the canonical evidenceAdapter");
  }
  if (gate?.id?.$ref !== "#/$defs/gateId") {
    throw new Error("Profile required gate IDs must reference the canonical gateId");
  }
  assertProjection("profile gate evaluator", gate?.evaluator?.enum, "gateEvaluator");
  assertProjection(
    "profile gate execution kind",
    gate?.allowedExecutionKinds?.items?.enum,
    "executionKind"
  );
  assertProjection(
    "profile gate evidence kind",
    gate?.allowedEvidenceKinds?.items?.enum,
    "evidenceKind"
  );
  assertProjection(
    "profile official ingress policy",
    gate?.officialIngressPolicy?.enum,
    "officialIngressPolicy"
  );
  assertProjection(
    "profile independent review pass",
    profile?.independentReviewPasses?.items?.enum,
    "independentReviewPass"
  );
  assertLimit("profile count", schema.properties?.profiles?.maxItems, "maxProfiles");
  assertLimit("profile dimension count", profile?.dimensions?.maxItems, "maxDimensionsPerProfile");
  assertLimit("profile gate count", profile?.requiredGates?.maxItems, "maxGateIdsPerProfile");
  assertLimit(
    "profile dimension adapter count",
    dimension?.allowedAdapters?.maxItems,
    "maxAllowedAdaptersPerPolicy"
  );
  assertLimit(
    "profile gate adapter count",
    gate?.allowedAdapters?.maxItems,
    "maxAllowedAdaptersPerPolicy"
  );
  assertLimit(
    "profile independent pass count",
    profile?.independentReviewPasses?.maxItems,
    "maxIndependentReviewPasses"
  );
  assertLimit(
    "profile dimension evidence kinds",
    dimension?.evidenceKinds?.maxItems,
    "maxEvidenceKindsPerDimension"
  );
  assertLimit("profile identifier", schema.$defs?.id?.maxLength, "maxIdentifierCharacters");
  assertLimit("profile text", profile?.description?.maxLength, "maxTextCharacters");
  assertClosedObjectSchemas(schema);
}

export function assertCandidateIdentitySchemaProjections(schema) {
  assertSchemaId("candidate identity schema id", schema.$id, "candidateIdentity");
  if (schema.properties?.schemaVersion?.const !== CONTRACT_VALUES.candidateIdentitySchemaVersion) {
    throw new Error(
      `candidate identity schemaVersion must be exactly ${CONTRACT_VALUES.candidateIdentitySchemaVersion}`
    );
  }
  assertProjection(
    "candidate identity mode",
    schema.properties?.mode?.enum,
    "candidateIdentityMode"
  );
  assertConstSet(
    "candidate identity mode branches",
    schema.oneOf?.map((branch) => branch.properties?.mode),
    "candidateIdentityMode"
  );
  assertProjection(
    "candidate identity Git object format",
    schema.properties?.gitObjectFormat?.enum,
    "gitObjectFormat"
  );
  for (const required of ["gitExecutable", "gitObjectFormat", "repositoryRemote"]) {
    if (!(schema.required ?? []).includes(required)) {
      throw new Error(`candidate identity schema must require ${required}`);
    }
  }
  assertLimit(
    "candidate identity untracked-file count",
    schema.properties?.untrackedFiles?.maxItems,
    "maxUntrackedFiles"
  );
  assertLimit(
    "candidate identity untracked-file bytes",
    schema.properties?.untrackedFiles?.items?.properties?.sizeBytes?.maximum,
    "maxUntrackedFileBytes"
  );
  assertClosedObjectSchemas(schema);
}

export function assertEvidenceReceiptSchemaProjections(schema) {
  assertSchemaId("evidence receipt schema id", schema.$id, "evidenceReceipt");
  assertVersion("evidence receipt schemaVersion", schema.properties?.schemaVersion?.const);
  assertProjection("evidence kind", schema.properties?.kind?.enum, "evidenceKind");
  assertProjection("evidence subject", schema.properties?.subject?.enum, "evidenceSubject");
  assertProjection(
    "evidence execution kind",
    schema.properties?.execution?.properties?.kind?.enum,
    "executionKind"
  );
  assertProjection(
    "evidence adapter",
    schema.properties?.execution?.properties?.adapter?.enum,
    "evidenceAdapter"
  );
  assertProjection("evidence status", schema.properties?.status?.enum, "evidenceStatus");
  assertProjection(
    "score attestation side",
    schema.properties?.claims?.properties?.scoreAttestations?.items?.properties?.side?.enum,
    "scoreSide"
  );
  assertProjection(
    "evidence gate outcome",
    schema.properties?.claims?.properties?.gateOutcomes?.items?.properties?.gateId?.enum,
    "gateId"
  );
  assertProjection(
    "evidence gate outcome status",
    schema.properties?.claims?.properties?.gateOutcomes?.items?.properties?.status?.enum,
    "gateOutcomeStatus"
  );
  assertProjection(
    "independent review claim pass",
    schema.properties?.claims?.properties?.independentReview?.properties?.passKind?.enum,
    "independentReviewPass"
  );
  assertProjection(
    "evidence search discovery status",
    schema.properties?.claims?.properties?.searchDiscovery?.properties?.status?.enum,
    "discoveryStatus"
  );
  assertProjection(
    "finding approval kind",
    schema.properties?.claims?.properties?.findingApprovals?.items?.properties?.kind?.enum,
    "findingApprovalKind"
  );
  assertProjection(
    "evidence artifact kind",
    schema.properties?.artifacts?.items?.properties?.kind?.enum,
    "artifactKind"
  );
  assertLimit(
    "evidence gate outcomes",
    schema.properties?.claims?.properties?.gateOutcomes?.maxItems,
    "maxGateOutcomesPerReceipt"
  );
  assertLimit(
    "evidence arguments",
    schema.properties?.execution?.properties?.arguments?.maxItems,
    "maxCommandArguments"
  );
  assertLimit(
    "evidence score attestations",
    schema.properties?.claims?.properties?.scoreAttestations?.maxItems,
    "maxScoreAttestationsPerReceipt"
  );
  assertLimit(
    "evidence finding approvals",
    schema.properties?.claims?.properties?.findingApprovals?.maxItems,
    "maxFindingApprovalsPerReceipt"
  );
  assertLimit(
    "evidence artifacts",
    schema.properties?.artifacts?.maxItems,
    "maxArtifactsPerReceipt"
  );
  assertLimit(
    "evidence observations",
    schema.properties?.observations?.maxItems,
    "maxObservationsPerReceipt"
  );
  assertClosedObjectSchemas(schema);
}

export function assertReviewResultSchemaProjections(schema) {
  assertSchemaId("review result schema id", schema.$id, "reviewResult");
  assertVersion("review result schemaVersion", schema.properties?.schemaVersion?.const);
  assertProjection("review profile id", schema.properties?.profileId?.enum, "profileId");
  assertProjection("review verdict scope", schema.properties?.verdictScope?.enum, "verdictScope");
  assertProjection("review scoring method", schema.properties?.scoringMethod?.enum, "scoringMethod");
  assertProjection(
    "review candidate verification target",
    schema.properties?.identityRefs?.properties?.candidateVerificationTarget?.enum,
    "candidateVerificationTarget"
  );
  assertProjection(
    "review maximum verified scope",
    schema.properties?.publicationBoundary?.properties?.maximumVerifiedScope?.enum,
    "verdictScope"
  );
  assertProjection(
    "review comparison status",
    schema.$defs?.comparisonStatus?.enum,
    "comparisonStatus"
  );
  assertProjection(
    "review readiness verdict",
    schema.properties?.readinessVerdict?.enum,
    "readinessVerdict"
  );
  assertProjection("review gate status", schema.$defs?.gate?.properties?.status?.enum, "gateStatus");
  assertProjection("review blocker severity", schema.$defs?.severity?.enum, "blockerSeverity");
  assertProjection("review blocker status", schema.$defs?.blocker?.properties?.status?.enum, "blockerStatus");
  assertProjection(
    "review regression disposition",
    schema.$defs?.regression?.properties?.disposition?.enum,
    "findingDisposition"
  );
  assertProjection("review unlock kind", schema.$defs?.unlock?.properties?.kind?.enum, "unlockKind");
  assertProjection("review confidence label", schema.$defs?.confidence?.properties?.label?.enum, "confidenceLabel");
  if (schema.properties?.confidence?.$ref !== "#/$defs/confidence"
    || schema.properties?.submittedConfidence?.$ref !== "#/$defs/confidence") {
    throw new Error("Canonical and submitted review confidence must share the closed confidence definition");
  }
  assertProjection("review freshness status", schema.properties?.freshness?.properties?.status?.enum, "freshnessStatus");
  assertProjection("review identity match", schema.$defs?.identityMatchStatus?.enum, "identityMatchStatus");
  assertProjection("review docs-impact authority", schema.properties?.docsImpact?.properties?.authority?.enum, "docsImpactAuthority");
  assertProjection(
    "review search and AI discovery status",
    schema.properties?.searchDiscovery?.properties?.status?.enum,
    "discoveryStatus"
  );
  assertProjection("review external authority", schema.$defs?.externalAuthorityStatus?.enum, "externalAuthorityStatus");
  assertProjection(
    "review producer registry authority",
    schema.properties?.trustControl?.properties?.producerRegistryAuthority?.enum,
    "producerRegistryAuthority"
  );
  const trustControl = schema.properties?.trustControl;
  const executionProvenance = trustControl?.properties?.evaluatorExecutionProvenance;
  if (!(trustControl?.required ?? []).includes("evaluatorExecutionProvenance")) {
    throw new Error("Review trust control must require evaluatorExecutionProvenance");
  }
  assertProjection(
    "review evaluator execution provenance status",
    executionProvenance?.properties?.status?.enum,
    "evaluatorExecutionProvenanceStatus"
  );
  assertProjection(
    "review evaluator execution provenance method",
    executionProvenance?.properties?.method?.enum,
    "evaluatorExecutionProvenanceMethod"
  );
  if (executionProvenance?.properties?.executedSourceDigest?.type !== "null"
    || executionProvenance?.properties?.controllerAttestationRef?.type !== "null") {
    throw new Error("Review evaluator execution provenance must keep unverified attestations null");
  }
  if (executionProvenance?.properties?.statement?.const
    !== CONTRACT_VALUES.evaluatorExecutionProvenanceStatement) {
    throw new Error("Review evaluator execution provenance statement must be canonical");
  }
  const freshness = schema.properties?.freshness;
  if (!(freshness?.required ?? []).includes("toolSourceSnapshotMatch")
    || Object.hasOwn(freshness?.properties ?? {}, "toolSourceMatch")) {
    throw new Error("Review freshness must expose only toolSourceSnapshotMatch");
  }
  assertProjection("review dimension kind", schema.$defs?.dimensionResult?.properties?.kind?.enum, "dimensionKind");
  assertProjection("review score direction", schema.$defs?.dimensionResult?.properties?.direction?.enum, "scoreDirection");
  assertProjection(
    "review score verification status",
    schema.$defs?.dimensionResult?.properties?.scoreStatus?.enum,
    "scoreVerificationStatus"
  );
  assertProjection(
    "review score anchor values",
    schema.$defs?.scoreAnchor?.properties?.score?.enum,
    "scoreAnchorValues"
  );
  assertProjection(
    "review independent status",
    schema.properties?.independentReview?.properties?.status?.enum,
    "independentReviewStatus"
  );
  assertProjection(
    "review independent pass",
    schema.properties?.independentReview?.properties?.passKinds?.items?.enum,
    "independentReviewPass"
  );
  assertProjection(
    "review unlock evidence kinds",
    schema.$defs?.unlock?.properties?.requiredEvidenceKinds?.items?.enum,
    "evidenceKind"
  );
  assertLimit("review dimensions", schema.properties?.dimensions?.maxItems, "maxDimensionsPerProfile");
  assertLimit("review evidence references", schema.properties?.evidenceRefs?.maxItems, "maxEvidenceReferences");
  assertLimit("review blockers", schema.properties?.blockers?.maxItems, "maxBlockers");
  assertLimit("review gates", schema.properties?.gates?.maxItems, "maxGates");
  assertClosedObjectSchemas(schema);
}

export function assertReviewRequestSchemaProjections(schema) {
  assertSchemaId("review request schema id", schema.$id, "reviewRequest");
  assertVersion("review request schemaVersion", schema.properties?.schemaVersion?.const);
  assertProjection("review request profile id", schema.properties?.profileId?.enum, "profileId");
  assertProjection("review request verdict scope", schema.properties?.verdictScope?.enum, "verdictScope");
  assertProjection(
    "review request candidate verification target",
    schema.properties?.candidateVerificationTarget?.enum,
    "candidateVerificationTarget"
  );
  assertProjection(
    "review request documentation-impact authority",
    schema.properties?.docsImpact?.properties?.authority?.enum,
    "docsImpactAuthority"
  );
  assertProjection(
    "review request search and AI discovery status",
    schema.properties?.searchDiscovery?.properties?.status?.enum,
    "discoveryStatus"
  );
  assertProjection(
    "review request external authority status",
    schema.$defs?.externalAuthorityStatus?.enum,
    "externalAuthorityStatus"
  );
  assertProjection("review request blocker severity", schema.$defs?.severity?.enum, "blockerSeverity");
  assertProjection(
    "review request regression disposition",
    schema.$defs?.regression?.properties?.disposition?.enum,
    "findingDisposition"
  );
  assertProjection("review request unlock kind", schema.$defs?.unlock?.properties?.kind?.enum, "unlockKind");
  assertProjection(
    "review request unlock evidence kind",
    schema.$defs?.unlock?.properties?.requiredEvidenceKinds?.items?.enum,
    "evidenceKind"
  );
  assertClosedObjectSchemas(schema);
}

export function assertProducerRegistrySchemaProjections(schema) {
  assertSchemaId("producer registry schema id", schema.$id, "producerRegistry");
  assertVersion("producer registry schemaVersion", schema.properties?.schemaVersion?.const);
  assertProjection("producer registry profile id", schema.properties?.profileId?.enum, "profileId");
  assertProjection(
    "producer registry authority",
    schema.properties?.authority?.enum,
    "producerRegistryAuthority"
  );
  const producer = schema.properties?.producers?.items?.properties;
  assertProjection("producer registry adapter", producer?.adapters?.items?.enum, "evidenceAdapter");
  assertProjection(
    "producer registry execution kind",
    producer?.executionKinds?.items?.enum,
    "executionKind"
  );
  assertProjection("producer registry evidence kind", producer?.evidenceKinds?.items?.enum, "evidenceKind");
  assertProjection(
    "producer registry gate-check ID",
    producer?.gateChecks?.items?.properties?.gateId?.enum,
    "gateId"
  );
  assertLimit(
    "producer registry gate checks",
    producer?.gateChecks?.maxItems,
    "maxGateChecksPerProducer"
  );
  assertLimit(
    "producer registry receipt authorizations",
    schema.properties?.receiptAuthorizations?.maxItems,
    "maxReceiptAuthorizations"
  );
  assertClosedObjectSchemas(schema);
}

export function assertDecisionReceiptSchemaProjections(schema) {
  assertSchemaId("decision receipt schema id", schema.$id, "decisionReceipt");
  assertVersion("decision receipt schemaVersion", schema.properties?.schemaVersion?.const);
  assertClosedObjectSchemas(schema);
}
