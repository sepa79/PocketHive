import { CONTRACT_VALUES } from './contracts/constants.mjs';

const COMPARISON_MARKERS = Object.freeze({
  IMPROVED: '🟢 ▲ Improved',
  DECREASED: '🔴 ▼ Decreased',
  UNCHANGED: '🟣 = Unchanged',
  UNVERIFIED: '🟠 ? Unverified',
});

const READINESS_MARKERS = Object.freeze({
  READY: '🟢 Ready',
  NOT_READY: '🔴 Not ready',
});

const GATE_MARKERS = Object.freeze({
  VERIFIED: '🟢 Verified',
  FAILED: '🔴 Failed',
  NOT_VERIFIED: '🟠 Not verified',
});

const SCORE_STATUS_MARKERS = Object.freeze({
  VERIFIED: '🟢 Verified score evidence',
  NOT_VERIFIED: '🟠 Submitted only — not verified',
});

const FRESHNESS_MARKERS = Object.freeze({
  FRESH: '🟢 Fresh',
  STALE: '🔴 Stale',
  NOT_VERIFIED: '🟠 Not verified',
});

const IDENTITY_MATCH_MARKERS = Object.freeze({
  MATCH: '🟢 Match',
  MISMATCH: '🔴 Mismatch',
  NOT_VERIFIED: '🟠 Not verified',
});

const INDEPENDENT_REVIEW_MARKERS = Object.freeze({
  VERIFIED: '🟢 Verified',
  NOT_VERIFIED: '🟠 Not verified',
});

const SEARCH_DISCOVERY_MARKERS = Object.freeze({
  CHANGED_SYNCHRONIZED: '🟢 Changed - synchronized',
  CHANGED_ACTION_REQUIRED: '🔴 Changed - action required',
  NO_MATERIAL_CHANGE: '🟣 No material change',
  NOT_APPLICABLE: '⚪ N/A',
  UNVERIFIED: '🟠 Unverified',
});

const CONFIDENCE_LABELS = Object.freeze({
  HIGH: 'High',
  MEDIUM: 'Medium',
  LOW: 'Low',
});

const ENUM_LABELS = Object.freeze({
  profile: Object.freeze({
    POCKETHIVE_DOCUMENTATION_V1: 'PocketHive documentation',
    POCKETHIVE_DOCS_AUTOMATION_V1: 'PocketHive documentation automation',
  }),
  scoringMethod: Object.freeze({
    ANCHORED_RUBRIC_V1: 'Anchored rubric v1',
  }),
  verdictScope: Object.freeze({
    LOCAL_CANDIDATE: 'Local candidate',
    MERGE: 'Merge',
    PUBLICATION: 'Publication',
    DEPLOYMENT: 'Deployment',
  }),
  candidateVerificationTarget: Object.freeze({
    LIVE_WORKTREE: 'Live worktree',
    GIT_OBJECT: 'Historical Git object',
  }),
  docsImpactAuthority: Object.freeze({
    NOT_SUPPLIED: 'Not supplied',
    INFORMATIONAL_UNVERIFIED: 'Informational and unverified',
    PROTECTED_CONTROLLER_VERIFIED: 'Protected controller verified',
  }),
  producerRegistryAuthority: Object.freeze({
    CANDIDATE_UNVERIFIED: 'Candidate-controlled and unverified',
    OPERATOR_SUPPLIED: 'Supplied explicitly by the operator',
  }),
  evaluatorExecutionProvenanceStatus: Object.freeze({
    NOT_VERIFIED: 'Executed evaluator source not verified',
  }),
  evaluatorExecutionProvenanceMethod: Object.freeze({
    POST_LOAD_FILESYSTEM_SNAPSHOT: 'Post-load filesystem snapshot',
  }),
  externalAuthority: Object.freeze({
    NOT_GRANTED: 'Not granted',
    EXTERNALLY_VERIFIED: 'Externally verified',
  }),
  passKind: Object.freeze({
    NOVICE: 'Novice pass',
    EXPERT: 'Expert pass',
    UX: 'User-experience pass',
    SECURITY: 'Security pass',
  }),
  dimensionKind: Object.freeze({ SCORE: 'Score' }),
  direction: Object.freeze({ HIGHER_IS_BETTER: 'Higher is better' }),
  severity: Object.freeze({ MATERIAL: 'Material', NON_MATERIAL: 'Non-material' }),
  blockerStatus: Object.freeze({ OPEN: 'Open', RESOLVED: 'Resolved' }),
  unlockKind: Object.freeze({
    COMMAND: 'Run a command',
    IMPLEMENTATION: 'Implementation change',
    CONFIGURATION: 'Configuration change',
    HUMAN_APPROVAL: 'Human approval',
    EXTERNAL_EVIDENCE: 'External evidence',
  }),
  evidenceKind: Object.freeze({
    MEASURED: 'Measured evidence',
    REVIEWER_JUDGMENT: 'Reviewer judgment',
  }),
  regressionDisposition: Object.freeze({
    FIXED: 'Fixed',
    ACCEPTED_TRADE_OFF: 'Accepted trade-off',
    BLOCKER: 'Blocker',
  }),
});

function valueFrom(map, key, kind) {
  if (!Object.hasOwn(map, key)) {
    throw new TypeError(`Unsupported ${kind}: ${String(key)}`);
  }
  return map[key];
}

function contractValue(value, allowedValues, kind) {
  if (!allowedValues.includes(value)) {
    throw new TypeError(`Unsupported ${kind}: ${String(value)}`);
  }
  return value;
}

function requiredRecord(value, label) {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    throw new TypeError(`${label} must be an object`);
  }
  return value;
}

function requiredArray(value, label) {
  if (!Array.isArray(value)) throw new TypeError(`${label} must be an array`);
  return value;
}

function requiredString(value, label) {
  if (typeof value !== 'string' || value.length === 0) {
    throw new TypeError(`${label} must be a non-empty string`);
  }
  return value;
}

function assertReceiptProjection(receipt) {
  requiredRecord(receipt, 'Evidence receipt');
  if (receipt.schemaVersion !== CONTRACT_VALUES.schemaVersion) {
    throw new TypeError(`Unsupported evidence receipt schema version: ${String(receipt.schemaVersion)}`);
  }
  requiredString(receipt.receiptId, 'Evidence receipt receiptId');
  requiredString(receipt.evidenceId, 'Evidence receipt evidenceId');
  contractValue(receipt.kind, CONTRACT_VALUES.evidenceKind, 'evidence kind');
  contractValue(receipt.subject, CONTRACT_VALUES.evidenceSubject, 'evidence subject');
  requiredString(receipt.subjectIdentityRef, 'Evidence receipt subject identity');
  requiredString(receipt.profileDigest, 'Evidence receipt profile digest');
  requiredString(receipt.createdAt, 'Evidence receipt createdAt');
  contractValue(receipt.status, CONTRACT_VALUES.evidenceStatus, 'evidence status');
  requiredString(receipt.summary, 'Evidence receipt summary');

  const producer = requiredRecord(receipt.producer, 'Evidence receipt producer');
  requiredString(producer.id, 'Evidence receipt producer ID');
  requiredString(producer.version, 'Evidence receipt producer version');
  requiredString(producer.digest, 'Evidence receipt producer digest');

  const execution = requiredRecord(receipt.execution, 'Evidence receipt execution');
  contractValue(execution.kind, CONTRACT_VALUES.executionKind, 'execution kind');
  contractValue(execution.adapter, CONTRACT_VALUES.evidenceAdapter, 'evidence adapter');
  if (execution.entrypoint !== null) requiredString(execution.entrypoint, 'Evidence receipt entrypoint');
  requiredArray(execution.arguments, 'Evidence receipt arguments');
  if (!execution.arguments.every((argument) => typeof argument === 'string')) {
    throw new TypeError('Evidence receipt arguments must contain strings');
  }
  if (typeof execution.officialIngress !== 'boolean') {
    throw new TypeError('Evidence receipt officialIngress must be boolean');
  }

  const claims = requiredRecord(receipt.claims, 'Evidence receipt claims');
  for (const outcome of requiredArray(claims.gateOutcomes, 'Evidence receipt gate outcomes')) {
    requiredRecord(outcome, 'Gate outcome');
    contractValue(outcome.gateId, CONTRACT_VALUES.gateId, 'gate outcome ID');
    contractValue(outcome.status, CONTRACT_VALUES.gateOutcomeStatus, 'gate outcome status');
    requiredString(outcome.checkId, 'Gate outcome check ID');
    requiredString(outcome.checkContractDigest, 'Gate outcome check-contract digest');
    requiredString(outcome.configurationDigest, 'Gate outcome configuration digest');
  }
  for (const attestation of requiredArray(claims.scoreAttestations, 'Evidence receipt score attestations')) {
    requiredRecord(attestation, 'Score attestation');
    requiredString(attestation.dimensionId, 'Score attestation dimension ID');
    contractValue(attestation.side, CONTRACT_VALUES.scoreSide, 'score attestation side');
    if (typeof attestation.score !== 'number' || !Number.isFinite(attestation.score)) {
      throw new TypeError('Score attestation score must be finite');
    }
  }
  if (claims.searchDiscovery !== null) {
    const searchDiscovery = requiredRecord(claims.searchDiscovery, 'Search discovery claim');
    contractValue(searchDiscovery.status, CONTRACT_VALUES.discoveryStatus, 'search discovery status');
  }
  if (claims.independentReview !== null) {
    const independentReview = requiredRecord(claims.independentReview, 'Independent review claim');
    requiredString(independentReview.reviewerId, 'Independent reviewer ID');
    contractValue(independentReview.passKind, CONTRACT_VALUES.independentReviewPass, 'independent review pass');
  }
  for (const approval of requiredArray(claims.findingApprovals, 'Evidence receipt finding approvals')) {
    requiredRecord(approval, 'Finding approval');
    requiredString(approval.findingId, 'Finding approval ID');
    requiredString(approval.findingDigest, 'Finding approval digest');
    contractValue(approval.kind, CONTRACT_VALUES.findingApprovalKind, 'finding approval kind');
  }

  for (const observation of requiredArray(receipt.observations, 'Evidence receipt observations')) {
    requiredRecord(observation, 'Evidence observation');
    requiredString(observation.id, 'Evidence observation ID');
    requiredString(observation.label, 'Evidence observation label');
    if (!['string', 'number', 'boolean'].includes(typeof observation.value) && observation.value !== null) {
      throw new TypeError('Evidence observation value must be a scalar or null');
    }
    if (observation.unit !== null && typeof observation.unit !== 'string') {
      throw new TypeError('Evidence observation unit must be a string or null');
    }
  }
  for (const artifact of requiredArray(receipt.artifacts, 'Evidence receipt artifacts')) {
    requiredRecord(artifact, 'Evidence artifact');
    requiredString(artifact.id, 'Evidence artifact ID');
    contractValue(artifact.kind, CONTRACT_VALUES.artifactKind, 'artifact kind');
    requiredString(artifact.repositoryPath, 'Evidence artifact repository path');
    requiredString(artifact.sha256, 'Evidence artifact digest');
    if (!Number.isInteger(artifact.sizeBytes) || artifact.sizeBytes < 0) {
      throw new TypeError('Evidence artifact sizeBytes must be a non-negative integer');
    }
  }
}

function prepareEvidenceReceipts(review, evidenceReceipts) {
  const references = requiredArray(review.evidenceRefs, 'Review evidence references');
  const receipts = requiredArray(evidenceReceipts, 'Evidence receipt collection');
  const referenceIds = references.map(({ evidenceId }) => evidenceId);
  const receiptIds = receipts.map(({ evidenceId }) => evidenceId);
  if (new Set(referenceIds).size !== referenceIds.length) {
    throw new TypeError('Review evidence reference IDs must be unique');
  }
  if (new Set(receiptIds).size !== receiptIds.length) {
    throw new TypeError('Evidence receipt IDs must be unique');
  }
  if (references.length !== receipts.length) {
    throw new TypeError('Evidence receipt collection must match the complete review evidence registry');
  }
  const receiptsById = new Map(receipts.map((receipt) => {
    assertReceiptProjection(receipt);
    return [receipt.evidenceId, receipt];
  }));
  return references.map((reference) => {
    requiredRecord(reference, 'Review evidence reference');
    requiredString(reference.evidenceId, 'Review evidence ID');
    requiredString(reference.receiptDigest, 'Review evidence receipt digest');
    requiredString(reference.repositoryPath, 'Review evidence repository path');
    const receipt = receiptsById.get(reference.evidenceId);
    if (receipt === undefined || receipt.receiptId !== reference.receiptDigest) {
      throw new TypeError(`Evidence receipt ${reference.evidenceId} does not match the review registry`);
    }
    return { reference, receipt };
  });
}

function stableDisplay(value) {
  if (value === null) {
    return 'Not supplied';
  }
  if (typeof value === 'string') {
    return value;
  }
  if (typeof value === 'number' || typeof value === 'boolean') {
    return String(value);
  }
  throw new TypeError('Lists and objects require an explicit projection');
}

function escapeMarkdown(value) {
  return stableDisplay(value)
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('\\', '&#92;')
    .replaceAll('|', '&#124;')
    .replaceAll('`', '&#96;')
    .replaceAll('*', '&#42;')
    .replaceAll('_', '&#95;')
    .replaceAll('[', '&#91;')
    .replaceAll(']', '&#93;')
    .replaceAll('\r\n', '<br>')
    .replaceAll('\r', '<br>')
    .replaceAll('\n', '<br>');
}

function formatScore(value) {
  return value === null ? 'N/V' : value.toFixed(1);
}

function formatSignedDelta(value) {
  const normalized = Object.is(value, -0) ? 0 : value;
  return `${normalized >= 0 ? '+' : ''}${normalized.toFixed(1)}`;
}

function comparisonMarker(status, delta) {
  const marker = valueFrom(COMPARISON_MARKERS, status, 'comparison status');
  if (status === 'UNVERIFIED') {
    return `${marker} (N/V - Unverified)`;
  }
  if (typeof delta !== 'number' || !Number.isFinite(delta)) {
    throw new TypeError(`Comparison status ${status} requires a finite delta`);
  }
  return `${marker} (${formatSignedDelta(delta)})`;
}

function assertScoreQuarantine(review) {
  let hasUnverifiedScore = false;
  for (const dimension of review.dimensions) {
    valueFrom(SCORE_STATUS_MARKERS, dimension.scoreStatus, 'score status');
    if (dimension.scoreStatus !== 'NOT_VERIFIED') continue;
    hasUnverifiedScore = true;
    if (dimension.baseline !== null || dimension.current !== null || dimension.delta !== null
      || dimension.comparisonStatus !== 'UNVERIFIED') {
      throw new TypeError(
        `Dimension ${dimension.id} has unverified score evidence but exposes canonical scores`,
      );
    }
  }
  if (hasUnverifiedScore
    && (review.overall.baseline !== null || review.overall.current !== null
      || review.overall.delta !== null || review.overall.comparisonStatus !== 'UNVERIFIED'
      || review.comparisonStatus !== 'UNVERIFIED')) {
    throw new TypeError('Unverified dimension scores require a quarantined canonical Overall');
  }
}

function assertEvaluatorExecutionProvenance(trustControl) {
  const control = requiredRecord(trustControl, 'Trust control');
  const provenance = requiredRecord(
    control.evaluatorExecutionProvenance,
    'Evaluator execution provenance',
  );
  contractValue(
    provenance.status,
    CONTRACT_VALUES.evaluatorExecutionProvenanceStatus,
    'evaluator execution provenance status',
  );
  contractValue(
    provenance.method,
    CONTRACT_VALUES.evaluatorExecutionProvenanceMethod,
    'evaluator execution provenance method',
  );
  if (provenance.executedSourceDigest !== null) {
    throw new TypeError('Unverified evaluator executedSourceDigest must be null');
  }
  if (provenance.controllerAttestationRef !== null) {
    throw new TypeError('Unverified evaluator controllerAttestationRef must be null');
  }
  if (provenance.statement !== CONTRACT_VALUES.evaluatorExecutionProvenanceStatement) {
    throw new TypeError('Evaluator execution provenance statement must be canonical');
  }
  return provenance;
}

function markdownEnum(value, labels, kind) {
  return `${escapeMarkdown(valueFrom(labels, value, kind))} (\`${escapeMarkdown(value)}\`)`;
}

function markedEnum(markerMap, value, kind) {
  return `${valueFrom(markerMap, value, kind)}; raw \`${escapeMarkdown(value)}\``;
}

function comparisonWithRaw(status, delta) {
  return `${comparisonMarker(status, delta)}; raw \`${escapeMarkdown(status)}\``;
}

function submittedComparisonWithRaw(status, delta) {
  return `⚠ Submitted only / not a canonical delta — ${comparisonWithRaw(status, delta)}`;
}

function booleanWithRaw(value) {
  return `${value ? 'Yes' : 'No'} (\`${String(value)}\`)`;
}

function tableList(items, formatter = escapeMarkdown) {
  return items.length === 0
    ? 'None'
    : items.map((item) => `• ${formatter(item)}`).join('<br>');
}

function labelledList(label, items, formatter = escapeMarkdown) {
  return items.length === 0
    ? `- **${label}:** None.`
    : [`- **${label}:**`, ...items.map((item) => `  - ${formatter(item)}`)].join('\n');
}

function listOrNone(items, formatter) {
  return items.length === 0 ? 'None recorded.' : items.map(formatter).join('\n');
}

function identityLines(identityRefs) {
  return [
    `- **Change baseline identity ID:** ${escapeMarkdown(identityRefs.baselineIdentityId)}`,
    `- **Candidate identity ID:** ${escapeMarkdown(identityRefs.candidateIdentityId)}`,
    `- **Candidate verification target:** ${markdownEnum(identityRefs.candidateVerificationTarget, ENUM_LABELS.candidateVerificationTarget, 'candidate verification target')}`,
    labelledList('Approved reference evidence', identityRefs.approvedReferenceEvidenceRefs),
    `- **Deployment reference identity ID:** ${escapeMarkdown(identityRefs.deploymentIdentityId)}`,
  ].join('\n');
}

function validatedScoreAnchors(dimension) {
  requiredRecord(dimension, 'Dimension');
  if (typeof dimension.criterion !== 'string' || dimension.criterion.trim().length === 0) {
    throw new TypeError('Dimension criterion must be a non-empty string');
  }
  if (!Array.isArray(dimension.scoreAnchors)
    || dimension.scoreAnchors.length !== CONTRACT_VALUES.scoreAnchorValues.length) {
    throw new TypeError('Dimension score anchors must contain the complete canonical anchor set');
  }
  for (const [index, expectedScore] of CONTRACT_VALUES.scoreAnchorValues.entries()) {
    const anchor = dimension.scoreAnchors[index];
    requiredRecord(anchor, `Dimension score anchor ${index}`);
    if (anchor.score !== expectedScore
      || typeof anchor.description !== 'string'
      || anchor.description.trim().length === 0) {
      throw new TypeError('Dimension score anchors must match the ordered canonical values and descriptions');
    }
  }
  return dimension.scoreAnchors;
}

function dimensionContract(dimension) {
  const scoreAnchors = validatedScoreAnchors(dimension)
    .map((anchor) => `${escapeMarkdown(anchor.score)} — ${escapeMarkdown(anchor.description)}`)
    .join('<br>');
  return [
    `ID: \`${escapeMarkdown(dimension.id)}\``,
    `Kind: ${markdownEnum(dimension.kind, ENUM_LABELS.dimensionKind, 'dimension kind')}`,
    `Direction: ${markdownEnum(dimension.direction, ENUM_LABELS.direction, 'score direction')}`,
    `Criterion: ${escapeMarkdown(dimension.criterion)}`,
    `Score anchors: ${scoreAnchors}`,
    `Required: ${booleanWithRaw(dimension.required)}`,
    `Weight: ${escapeMarkdown(dimension.weight)}%`,
    `Score evidence: ${markedEnum(SCORE_STATUS_MARKERS, dimension.scoreStatus, 'score status')}`,
  ].join('<br>');
}

function submittedScoresTable(review, hasBaseline) {
  const dimensionRows = review.dimensions.map((dimension) => {
    const scoreStatus = markedEnum(SCORE_STATUS_MARKERS, dimension.scoreStatus, 'score status');
    if (!hasBaseline) {
      return `| ${escapeMarkdown(dimension.label)} | ${scoreStatus} | ${formatScore(dimension.submittedCurrent)} | ${submittedComparisonWithRaw(dimension.submittedComparisonStatus, dimension.submittedDelta)} |`;
    }
    return `| ${escapeMarkdown(dimension.label)} | ${scoreStatus} | ${formatScore(dimension.submittedBaseline)} | ${formatScore(dimension.submittedCurrent)} | ${submittedComparisonWithRaw(dimension.submittedComparisonStatus, dimension.submittedDelta)} |`;
  });
  const overallStatus = review.dimensions.every(({ scoreStatus }) => scoreStatus === 'VERIFIED')
    ? 'All dimension submissions corroborated; canonical Overall above remains authoritative.'
    : 'One or more dimension submissions lack trusted score evidence; this aggregate is not a score.';
  if (!hasBaseline) {
    return [
      '| Submitted input | Score-evidence state | Submitted candidate | Submitted comparison |',
      '| --- | --- | ---: | --- |',
      ...dimensionRows,
      `| **Submitted Overall input** | **${escapeMarkdown(overallStatus)}** | **${formatScore(review.submittedOverall.current)}** | **${submittedComparisonWithRaw(review.submittedOverall.comparisonStatus, review.submittedOverall.delta)}** |`,
    ].join('\n');
  }
  return [
    '| Submitted input | Score-evidence state | Submitted baseline | Submitted candidate | Submitted comparison |',
    '| --- | --- | ---: | ---: | --- |',
    ...dimensionRows,
    `| **Submitted Overall input** | **${escapeMarkdown(overallStatus)}** | **${formatScore(review.submittedOverall.baseline)}** | **${formatScore(review.submittedOverall.current)}** | **${submittedComparisonWithRaw(review.submittedOverall.comparisonStatus, review.submittedOverall.delta)}** |`,
  ].join('\n');
}

function dimensionEvidence(dimension) {
  return `${tableList(dimension.evidenceRefs)}<br>**Notes:** ${escapeMarkdown(dimension.notes)}`;
}

function comparisonTable(review, hasBaseline) {
  if (!hasBaseline) {
    return [
      '| Dimension | Contract | Candidate / Current | Status | Evidence and notes |',
      '| --- | --- | ---: | --- | --- |',
      ...review.dimensions.map((dimension) =>
        `| ${escapeMarkdown(dimension.label)} | ${dimensionContract(dimension)} | ${formatScore(dimension.current)} | ${comparisonWithRaw(dimension.comparisonStatus, dimension.delta)} | ${dimensionEvidence(dimension)} |`,
      ),
    ].join('\n');
  }
  return [
    '| Dimension | Contract | Baseline / Production | Candidate / Current | Delta | Evidence and notes |',
    '| --- | --- | ---: | ---: | --- | --- |',
    ...review.dimensions.map((dimension) =>
      `| ${escapeMarkdown(dimension.label)} | ${dimensionContract(dimension)} | ${formatScore(dimension.baseline)} | ${formatScore(dimension.current)} | ${comparisonWithRaw(dimension.comparisonStatus, dimension.delta)} | ${dimensionEvidence(dimension)} |`,
    ),
  ].join('\n');
}

function readinessTable(review) {
  const openMaterial = review.blockers.filter(
    (blocker) => blocker.severity === 'MATERIAL' && blocker.status === 'OPEN',
  ).length;
  const readiness = markedEnum(READINESS_MARKERS, review.readinessVerdict, 'readiness verdict');
  return [
    '| Readiness gate | Status | Required | Evidence | Blockers |',
    '| --- | --- | --- | --- | --- |',
    ...review.gates.map((gate) =>
      `| \`${escapeMarkdown(gate.id)}\` — ${escapeMarkdown(gate.summary)} | ${markedEnum(GATE_MARKERS, gate.status, 'gate status')} | ${booleanWithRaw(gate.required)} | ${tableList(gate.evidenceRefs)} | ${tableList(gate.blockerRefs)} |`,
    ),
    `| **Overall readiness** | **${readiness}** | **${booleanWithRaw(true)}** | **${openMaterial} open material blocker${openMaterial === 1 ? '' : 's'}** | **See blocker drill-down** |`,
  ].join('\n');
}

function overallTable(review, hasBaseline) {
  const marker = comparisonWithRaw(review.overall.comparisonStatus, review.overall.delta);
  if (!hasBaseline) {
    return [
      '| Dimension | Candidate / Current | Status | Evidence |',
      '| --- | ---: | --- | --- |',
      `| **Overall** | **${formatScore(review.overall.current)}** | **${marker}** | **Bound evidence manifest ${escapeMarkdown(review.evidenceManifestDigest)}** |`,
    ].join('\n');
  }
  return [
    '| Dimension | Baseline / Production | Candidate / Current | Delta | Evidence |',
    '| --- | ---: | ---: | --- | --- |',
    `| **Overall** | **${formatScore(review.overall.baseline)}** | **${formatScore(review.overall.current)}** | **${marker}** | **Bound evidence manifest ${escapeMarkdown(review.evidenceManifestDigest)}** |`,
  ].join('\n');
}

function evidenceList(label, values, formatter = escapeMarkdown) {
  return labelledList(label, values, formatter);
}

function blockerBlock(blocker) {
  return [
    `- **${escapeMarkdown(blocker.id)} — ${escapeMarkdown(blocker.summary)}**`,
    `  - **Severity:** ${markdownEnum(blocker.severity, ENUM_LABELS.severity, 'blocker severity')}`,
    `  - **Status:** ${markdownEnum(blocker.status, ENUM_LABELS.blockerStatus, 'blocker status')}`,
    ...evidenceList('Evidence', blocker.evidenceRefs).split('\n').map((line) => `  ${line}`),
    `  - **Unlock kind:** ${markdownEnum(blocker.unlock.kind, ENUM_LABELS.unlockKind, 'unlock kind')}`,
    `  - **Unlock condition:** ${escapeMarkdown(blocker.unlock.description)}`,
    ...evidenceList(
      'Required evidence kinds',
      blocker.unlock.requiredEvidenceKinds,
      (kind) => markdownEnum(kind, ENUM_LABELS.evidenceKind, 'evidence kind'),
    ).split('\n').map((line) => `  ${line}`),
  ].join('\n');
}

function dimensionReference(dimensionId) {
  return dimensionId === null
    ? 'Cross-cutting (not bound to one dimension)'
    : `\`${escapeMarkdown(dimensionId)}\``;
}

function regressionBlock(regression) {
  return [
    `- **${escapeMarkdown(regression.id)} — ${escapeMarkdown(regression.summary)}**`,
    `  - **Dimension:** ${dimensionReference(regression.dimensionId)}`,
    `  - **Severity:** ${markdownEnum(regression.severity, ENUM_LABELS.severity, 'regression severity')}`,
    `  - **Disposition:** ${markdownEnum(regression.disposition, ENUM_LABELS.regressionDisposition, 'regression disposition')}`,
    ...evidenceList('Evidence', regression.evidenceRefs).split('\n').map((line) => `  ${line}`),
  ].join('\n');
}

function gapBlock(gap) {
  return [
    `- **${escapeMarkdown(gap.id)} — ${escapeMarkdown(gap.summary)}**`,
    `  - **Dimension:** ${dimensionReference(gap.dimensionId)}`,
    `  - **Severity:** ${markdownEnum(gap.severity, ENUM_LABELS.severity, 'gap severity')}`,
    ...evidenceList('Evidence', gap.evidenceRefs).split('\n').map((line) => `  ${line}`),
    `  - **Unlock kind:** ${markdownEnum(gap.unlock.kind, ENUM_LABELS.unlockKind, 'unlock kind')}`,
    `  - **Unlock condition:** ${escapeMarkdown(gap.unlock.description)}`,
    ...evidenceList(
      'Required evidence kinds',
      gap.unlock.requiredEvidenceKinds,
      (kind) => markdownEnum(kind, ENUM_LABELS.evidenceKind, 'evidence kind'),
    ).split('\n').map((line) => `  ${line}`),
  ].join('\n');
}

function publicationBoundaryLines(boundary) {
  return [
    `- **Maximum verified scope:** ${markdownEnum(boundary.maximumVerifiedScope, ENUM_LABELS.verdictScope, 'verdict scope')}`,
    `- **Merge authority:** ${markdownEnum(boundary.mergeAuthority, ENUM_LABELS.externalAuthority, 'external authority status')}`,
    `- **Publication authority:** ${markdownEnum(boundary.publicationAuthority, ENUM_LABELS.externalAuthority, 'external authority status')}`,
    `- **Deployment authority:** ${markdownEnum(boundary.deploymentAuthority, ENUM_LABELS.externalAuthority, 'external authority status')}`,
    labelledList('Authority evidence', boundary.authorityEvidenceRefs),
    `- **Statement:** ${escapeMarkdown(boundary.statement)}`,
  ].join('\n');
}

function searchDiscoveryLines(searchDiscovery) {
  const status = markedEnum(
    SEARCH_DISCOVERY_MARKERS,
    searchDiscovery.status,
    'search and AI discovery status',
  );
  return [
    `- **Status:** ${status}`,
    `- **Statement:** ${escapeMarkdown(searchDiscovery.statement)}`,
    labelledList('Evidence', searchDiscovery.evidenceRefs),
    labelledList('Actions', searchDiscovery.actions),
    '- **External-outcome boundary:** Local evidence cannot prove indexing, ranking, citation, recommendation, traffic, or model inclusion.',
  ].join('\n');
}

function evidenceRegistry(evidenceRefs) {
  if (evidenceRefs.length === 0) return 'None recorded.';
  return [
    '| Evidence ID | Receipt digest | Repository path |',
    '| --- | --- | --- |',
    ...evidenceRefs.map((reference) =>
      `| \`${escapeMarkdown(reference.evidenceId)}\` | \`${escapeMarkdown(reference.receiptDigest)}\` | \`${escapeMarkdown(reference.repositoryPath)}\` |`,
    ),
  ].join('\n');
}

function receiptGateOutcomes(outcomes) {
  if (outcomes.length === 0) return 'None recorded.';
  return [
    '| Gate ID | Status | Check ID | Check-contract digest | Configuration digest |',
    '| --- | --- | --- | --- | --- |',
    ...outcomes.map((outcome) =>
      `| \`${escapeMarkdown(outcome.gateId)}\` | \`${escapeMarkdown(outcome.status)}\` | \`${escapeMarkdown(outcome.checkId)}\` | \`${escapeMarkdown(outcome.checkContractDigest)}\` | \`${escapeMarkdown(outcome.configurationDigest)}\` |`,
    ),
  ].join('\n');
}

function receiptScoreAttestations(attestations) {
  if (attestations.length === 0) return 'None recorded.';
  return [
    '| Dimension ID | Side | Score |',
    '| --- | --- | ---: |',
    ...attestations.map((attestation) =>
      `| \`${escapeMarkdown(attestation.dimensionId)}\` | \`${escapeMarkdown(attestation.side)}\` | ${attestation.score.toFixed(1)} |`,
    ),
  ].join('\n');
}

function receiptFindingApprovals(approvals) {
  if (approvals.length === 0) return 'None recorded.';
  return [
    '| Finding ID | Finding digest | Approval kind |',
    '| --- | --- | --- |',
    ...approvals.map((approval) =>
      `| \`${escapeMarkdown(approval.findingId)}\` | \`${escapeMarkdown(approval.findingDigest)}\` | \`${escapeMarkdown(approval.kind)}\` |`,
    ),
  ].join('\n');
}

function receiptObservations(observations) {
  if (observations.length === 0) return 'None recorded.';
  return [
    '| Observation ID | Label | Value | Unit |',
    '| --- | --- | --- | --- |',
    ...observations.map((observation) =>
      `| \`${escapeMarkdown(observation.id)}\` | ${escapeMarkdown(observation.label)} | ${escapeMarkdown(observation.value)} | ${escapeMarkdown(observation.unit)} |`,
    ),
  ].join('\n');
}

function receiptArtifacts(artifacts) {
  if (artifacts.length === 0) return 'None recorded.';
  return [
    '| Artifact ID | Kind | Repository path | SHA-256 | Size bytes |',
    '| --- | --- | --- | --- | ---: |',
    ...artifacts.map((artifact) =>
      `| \`${escapeMarkdown(artifact.id)}\` | \`${escapeMarkdown(artifact.kind)}\` | \`${escapeMarkdown(artifact.repositoryPath)}\` | \`${escapeMarkdown(artifact.sha256)}\` | ${escapeMarkdown(artifact.sizeBytes)} |`,
    ),
  ].join('\n');
}

function receiptClaimLines(claims) {
  const searchDiscovery = claims.searchDiscovery === null
    ? 'None.'
    : `Status \`${escapeMarkdown(claims.searchDiscovery.status)}\`.`;
  const independentReview = claims.independentReview === null
    ? 'None.'
    : `Reviewer \`${escapeMarkdown(claims.independentReview.reviewerId)}\`; pass \`${escapeMarkdown(claims.independentReview.passKind)}\`.`;
  return [
    `- **Search and AI discovery claim:** ${searchDiscovery}`,
    `- **Independent-review claim:** ${independentReview}`,
  ].join('\n');
}

function receiptDrillDown(projections) {
  if (projections.length === 0) return 'None recorded.';
  return projections.map(({ reference, receipt }) => [
    `### Receipt \`${escapeMarkdown(receipt.evidenceId)}\``,
    '',
    `- **Registry repository path:** \`${escapeMarkdown(reference.repositoryPath)}\``,
    `- **Receipt digest:** \`${escapeMarkdown(receipt.receiptId)}\``,
    `- **Receipt schema version:** ${escapeMarkdown(receipt.schemaVersion)}`,
    `- **Evidence kind:** \`${escapeMarkdown(receipt.kind)}\``,
    `- **Status:** \`${escapeMarkdown(receipt.status)}\``,
    `- **Summary:** ${escapeMarkdown(receipt.summary)}`,
    `- **Subject:** \`${escapeMarkdown(receipt.subject)}\``,
    `- **Bound subject identity ID:** \`${escapeMarkdown(receipt.subjectIdentityRef)}\``,
    `- **Profile digest:** \`${escapeMarkdown(receipt.profileDigest)}\``,
    `- **Created at:** ${escapeMarkdown(receipt.createdAt)}`,
    `- **Producer ID:** \`${escapeMarkdown(receipt.producer.id)}\``,
    `- **Producer version:** ${escapeMarkdown(receipt.producer.version)}`,
    `- **Producer digest:** \`${escapeMarkdown(receipt.producer.digest)}\``,
    '',
    '#### Execution',
    '',
    `- **Execution kind:** \`${escapeMarkdown(receipt.execution.kind)}\``,
    `- **Adapter:** \`${escapeMarkdown(receipt.execution.adapter)}\``,
    `- **Entrypoint:** ${receipt.execution.entrypoint === null ? 'Not supplied.' : `\`${escapeMarkdown(receipt.execution.entrypoint)}\``}`,
    `- **Official ingress:** ${booleanWithRaw(receipt.execution.officialIngress)}`,
    labelledList('Arguments in exact order', receipt.execution.arguments, (argument) => `\`${escapeMarkdown(argument)}\``),
    '',
    '#### Exact gate outcomes',
    '',
    receiptGateOutcomes(receipt.claims.gateOutcomes),
    '',
    '#### Score attestations',
    '',
    receiptScoreAttestations(receipt.claims.scoreAttestations),
    '',
    '#### Other claims',
    '',
    receiptClaimLines(receipt.claims),
    '',
    '##### Finding approvals',
    '',
    receiptFindingApprovals(receipt.claims.findingApprovals),
    '',
    '#### Observations',
    '',
    receiptObservations(receipt.observations),
    '',
    '#### Artifacts',
    '',
    receiptArtifacts(receipt.artifacts),
  ].join('\n')).join('\n\n');
}

function confidenceLines(confidence) {
  const label = markdownEnum(confidence.label, CONFIDENCE_LABELS, 'confidence label');
  if (typeof confidence.value !== 'number' || !Number.isFinite(confidence.value)) {
    throw new TypeError('Confidence value must be finite');
  }
  return [
    `**${label} (${confidence.value.toFixed(2)}).**`,
    labelledList('Basis evidence', confidence.basisEvidenceRefs),
    labelledList('Limitations', confidence.limitations),
  ].join('\n');
}

export function renderMarkdown(review, evidenceReceipts) {
  assertScoreQuarantine(review);
  const executionProvenance = assertEvaluatorExecutionProvenance(review.trustControl);
  const receiptProjections = prepareEvidenceReceipts(review, evidenceReceipts);
  const hasBaseline = review.identityRefs.baselineIdentityId !== null;
  const comparisonSummary = markedEnum(COMPARISON_MARKERS, review.comparisonStatus, 'comparison status');
  const readinessSummary = markedEnum(READINESS_MARKERS, review.readinessVerdict, 'readiness verdict');
  const freshness = markedEnum(FRESHNESS_MARKERS, review.freshness.status, 'freshness status');
  const independentReview = markedEnum(
    INDEPENDENT_REVIEW_MARKERS,
    review.independentReview.status,
    'independent review status',
  );

  return [
    '# Completed Work Review',
    '',
    `**Overall result:** ${readinessSummary}; comparison ${comparisonSummary}; scope ${markdownEnum(review.verdictScope, ENUM_LABELS.verdictScope, 'verdict scope')}.`,
    '',
    `- **Schema version:** ${escapeMarkdown(review.schemaVersion)}`,
    `- **Review ID:** ${escapeMarkdown(review.reviewId)}`,
    `- **Generated at:** ${escapeMarkdown(review.generatedAt)}`,
    `- **Profile:** ${markdownEnum(review.profileId, ENUM_LABELS.profile, 'profile')} — digest ${escapeMarkdown(review.profileDigest)}`,
    `- **Scoring method:** ${markdownEnum(review.scoringMethod, ENUM_LABELS.scoringMethod, 'scoring method')}`,
    identityLines(review.identityRefs),
    '',
    '## Marker legend',
    '',
    `- ${COMPARISON_MARKERS.IMPROVED} (\`IMPROVED\`)`,
    `- ${COMPARISON_MARKERS.DECREASED} (\`DECREASED\`)`,
    `- ${COMPARISON_MARKERS.UNCHANGED} (\`UNCHANGED\`)`,
    `- ${COMPARISON_MARKERS.UNVERIFIED} (\`UNVERIFIED\`)`,
    '',
    '## Comparison',
    '',
    hasBaseline
      ? 'Canonical baseline and candidate scores use the same bound profile and trusted evidence contract. Unverified score evidence remains N/V.'
      : 'The change baseline was explicitly not supplied. This is a current-only review; no baseline score or delta is inferred.',
    '',
    comparisonTable(review, hasBaseline),
    '',
    '### Submitted score inputs — audit only',
    '',
    '> **Not canonical scores.** These values came from the review request and are shown only for audit. They do not contribute to the canonical comparison, Overall, readiness, or verdict unless trusted score attestations verify them. Use the canonical table above for every completion claim.',
    '',
    submittedScoresTable(review, hasBaseline),
    '',
    '## Evidence',
    '',
    `- **Manifest digest:** ${escapeMarkdown(review.evidenceManifestDigest)}`,
    `- **Producer registry authority:** ${markdownEnum(review.trustControl.producerRegistryAuthority, ENUM_LABELS.producerRegistryAuthority, 'producer registry authority')}`,
    `- **Producer registry digest:** ${escapeMarkdown(review.trustControl.producerRegistryDigest)}`,
    `- **Review tool source snapshot digest:** ${escapeMarkdown(review.trustControl.toolSourceDigest)}`,
    `- **Review tool digest:** ${escapeMarkdown(review.trustControl.toolDigest)}`,
    `- **Executed evaluator source status:** ${markdownEnum(executionProvenance.status, ENUM_LABELS.evaluatorExecutionProvenanceStatus, 'evaluator execution provenance status')}`,
    `- **Evaluator source capture method:** ${markdownEnum(executionProvenance.method, ENUM_LABELS.evaluatorExecutionProvenanceMethod, 'evaluator execution provenance method')}`,
    `- **Executed evaluator source digest:** ${escapeMarkdown(executionProvenance.executedSourceDigest)}`,
    `- **Controller attestation reference:** ${escapeMarkdown(executionProvenance.controllerAttestationRef)}`,
    `- **Evaluator execution provenance statement:** ${escapeMarkdown(executionProvenance.statement)}`,
    '- **Measured:** automated observations and checks identified as measured in the bound evidence receipts.',
    '- **Reviewer judgment:** qualitative observations identified as reviewer judgment in the bound evidence receipts.',
    '- Evidence kinds are not inferred from identifiers by this renderer; the bound receipts remain authoritative.',
    `- **Freshness:** ${freshness}`,
    `- **Freshness evaluated at:** ${escapeMarkdown(review.freshness.evaluatedAt)}`,
    `- **Oldest bound evidence at:** ${escapeMarkdown(review.freshness.oldestEvidenceAt)}`,
    `- **Maximum evidence age:** ${escapeMarkdown(review.freshness.maxAgeSeconds)} seconds`,
    `- **Candidate evidence subject match:** ${markedEnum(IDENTITY_MATCH_MARKERS, review.freshness.candidateIdentityMatch, 'identity match status')}`,
    `- **Producer authorization match:** ${markedEnum(IDENTITY_MATCH_MARKERS, review.freshness.producerAuthorizationMatch, 'identity match status')}`,
    `- **Tool source snapshot match:** ${markedEnum(IDENTITY_MATCH_MARKERS, review.freshness.toolSourceSnapshotMatch, 'identity match status')}`,
    `- **Profile identity match:** ${markedEnum(IDENTITY_MATCH_MARKERS, review.freshness.profileIdentityMatch, 'identity match status')}`,
    '- **Identity terminology:** the candidate identity object is validated separately; “candidate evidence subject match” only states whether bound receipts name that validated identity.',
    `- **Independent review status:** ${independentReview}`,
    labelledList('Independent reviewer IDs', review.independentReview.reviewerIds),
    labelledList(
      'Independent pass kinds',
      review.independentReview.passKinds,
      (kind) => markdownEnum(kind, ENUM_LABELS.passKind, 'independent review pass kind'),
    ),
    labelledList('Independent-review evidence', review.independentReview.evidenceRefs),
    `- **Documentation-impact authority:** ${markdownEnum(review.docsImpact.authority, ENUM_LABELS.docsImpactAuthority, 'documentation-impact authority')}`,
    `- **Documentation-impact analysis evidence:** ${escapeMarkdown(review.docsImpact.analysisEvidenceRef)}`,
    `- **Documentation-impact statement:** ${escapeMarkdown(review.docsImpact.statement)}`,
    '',
    '### Bound evidence registry',
    '',
    evidenceRegistry(review.evidenceRefs),
    '',
    '### Receipt drill-down',
    '',
    'Receipt files are the canonical evidence source. The fields below are deterministic projections of the exact validated receipts bound by the registry above.',
    '',
    receiptDrillDown(receiptProjections),
    '',
    '## Search and AI discovery impact',
    '',
    searchDiscoveryLines(review.searchDiscovery),
    '',
    '## Readiness',
    '',
    readinessTable(review),
    '',
    '### Blocker drill-down',
    '',
    listOrNone(review.blockers, blockerBlock),
    '',
    '## Overall',
    '',
    overallTable(review, hasBaseline),
    '',
    '## Regressions',
    '',
    listOrNone(review.regressions, regressionBlock),
    '',
    '## Remaining gaps',
    '',
    listOrNone(review.remainingGaps, gapBlock),
    '',
    '## Verdict',
    '',
    `**${readinessSummary}.** This is a ${markdownEnum(review.verdictScope, ENUM_LABELS.verdictScope, 'verdict scope')} readiness verdict. The separate comparison status is ${comparisonSummary}.`,
    '',
    '## Confidence',
    '',
    '### Canonical evidence confidence',
    '',
    confidenceLines(review.confidence),
    '',
    '### Submitted confidence — audit only',
    '',
    '> This is the request-submitted confidence assertion. It is not the canonical evidence confidence and does not override it.',
    '',
    confidenceLines(review.submittedConfidence),
    '',
    '## Publication boundary',
    '',
    publicationBoundaryLines(review.publicationBoundary),
    '',
    'Use the pull request host’s native review controls for approval, requested changes, or deferral. This report records evidence only and never records a repository decision.',
    '',
    'This review is advisory evidence only. It does not authorize a commit, merge, publication, deployment, or production mutation.',
    '',
  ].join('\n');
}

export {
  COMPARISON_MARKERS,
  CONFIDENCE_LABELS,
  ENUM_LABELS,
  FRESHNESS_MARKERS,
  GATE_MARKERS,
  IDENTITY_MATCH_MARKERS,
  INDEPENDENT_REVIEW_MARKERS,
  READINESS_MARKERS,
  SEARCH_DISCOVERY_MARKERS,
  SCORE_STATUS_MARKERS,
  assertEvaluatorExecutionProvenance,
  assertScoreQuarantine,
  comparisonMarker,
  prepareEvidenceReceipts,
  stableDisplay,
  validatedScoreAnchors,
  valueFrom,
};
