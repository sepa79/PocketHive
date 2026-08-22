import {
  BLOCKER_SEVERITY,
  BLOCKER_STATUS,
  CONTRACT_VALUES,
  DISCOVERY_STATUS,
  DOCS_IMPACT_AUTHORITY,
  EVIDENCE_ADAPTER,
  EVIDENCE_KIND,
  EVIDENCE_STATUS,
  EVIDENCE_SUBJECT,
  EXECUTION_KIND,
  FINDING_APPROVAL_KIND,
  FINDING_DISPOSITION,
  GATE_EVALUATOR,
  GATE_ID,
  GATE_OUTCOME_STATUS,
  GATE_STATUS,
  IDENTITY_MATCH_STATUS,
  INDEPENDENT_REVIEW_STATUS,
  OFFICIAL_INGRESS_POLICY,
  READINESS_VERDICT,
  SCORE_SIDE,
  UNLOCK_KIND,
} from "./contracts/constants.mjs";
import { canonicalDigest } from "../docs-impact/canonical.mjs";

const RECEIPT_FAILURE_STATUSES = new Set([
  EVIDENCE_STATUS.FAIL,
  EVIDENCE_STATUS.ERROR,
  EVIDENCE_STATUS.TIMEOUT,
]);

function compareText(left, right) {
  return left < right ? -1 : left > right ? 1 : 0;
}

function sameSet(left, right) {
  return JSON.stringify([...left].sort(compareText)) === JSON.stringify([...right].sort(compareText));
}

function assertKnown(value, values, label) {
  if (!values.includes(value)) throw new Error(`Unknown ${label}: ${String(value)}`);
}

function assertFreshnessPolicy(freshness) {
  if (!freshness || !Number.isInteger(freshness.maxAgeSeconds) || freshness.maxAgeSeconds < 1) {
    throw new Error("Freshness maxAgeSeconds must be an explicit positive integer");
  }
  for (const field of [
    "requireCandidateIdentityMatch",
    "requireToolDigestMatch",
    "requireProfileDigestMatch",
  ]) {
    if (typeof freshness[field] !== "boolean") {
      throw new Error(`Freshness ${field} must be an explicit boolean`);
    }
  }
}

export function assertReceiptClaimSemantics(receipt) {
  assertKnown(receipt.kind, CONTRACT_VALUES.evidenceKind, "evidence kind");
  assertKnown(receipt.subject, CONTRACT_VALUES.evidenceSubject, "evidence subject");
  assertKnown(receipt.execution?.kind, CONTRACT_VALUES.executionKind, "execution kind");
  assertKnown(receipt.execution?.adapter, CONTRACT_VALUES.evidenceAdapter, "evidence adapter");
  assertKnown(receipt.status, CONTRACT_VALUES.evidenceStatus, "evidence status");
  if (typeof receipt.execution?.officialIngress !== "boolean") {
    throw new Error(`Evidence ${receipt.evidenceId} must declare officialIngress explicitly`);
  }
  if (!receipt.claims || !Array.isArray(receipt.claims.gateOutcomes)
    || !Array.isArray(receipt.claims.scoreAttestations)
    || !Array.isArray(receipt.claims.findingApprovals)
    || !(receipt.claims.searchDiscovery === null
      || (receipt.claims.searchDiscovery && typeof receipt.claims.searchDiscovery === "object"))
    || !(receipt.claims.independentReview === null
      || (receipt.claims.independentReview && typeof receipt.claims.independentReview === "object"))) {
    throw new Error(`Evidence ${receipt.evidenceId} must declare the closed claims object`);
  }

  const gateOutcomeIds = new Set();
  for (const outcome of receipt.claims.gateOutcomes) {
    assertKnown(outcome.gateId, CONTRACT_VALUES.gateId, "gate outcome ID");
    if (!CONTRACT_VALUES.gateOutcomeStatus.includes(outcome.status)) {
      throw new Error(`Evidence ${receipt.evidenceId} has unknown gate outcome status ${outcome.status}`);
    }
    if (gateOutcomeIds.has(outcome.gateId)) {
      throw new Error(`Evidence ${receipt.evidenceId} repeats gate outcome ${outcome.gateId}`);
    }
    gateOutcomeIds.add(outcome.gateId);
  }
  if (receipt.status === EVIDENCE_STATUS.PASS && receipt.claims.gateOutcomes.length > 0) {
    if (receipt.execution.entrypoint === null) {
      throw new Error(`Passing gate evidence ${receipt.evidenceId} requires an explicit entrypoint`);
    }
    if (receipt.artifacts.length === 0 && receipt.observations.length === 0) {
      throw new Error(`Passing gate evidence ${receipt.evidenceId} requires an artifact or observation`);
    }
  }

  const scoreClaimKeys = new Set();
  for (const claim of receipt.claims.scoreAttestations) {
    assertKnown(claim.side, CONTRACT_VALUES.scoreSide, "score side");
    const key = `${claim.dimensionId}:${claim.side}`;
    if (scoreClaimKeys.has(key)) {
      throw new Error(`Evidence ${receipt.evidenceId} repeats score claim ${key}`);
    }
    scoreClaimKeys.add(key);
    if (claim.side === SCORE_SIDE.BASELINE && receipt.subject !== EVIDENCE_SUBJECT.BASELINE) {
      throw new Error(`Baseline score claim ${key} requires a BASELINE receipt subject`);
    }
    if (claim.side === SCORE_SIDE.CURRENT
      && ![EVIDENCE_SUBJECT.CANDIDATE, EVIDENCE_SUBJECT.REVIEW].includes(receipt.subject)) {
      throw new Error(`Current score claim ${key} requires a CANDIDATE or REVIEW receipt subject`);
    }
  }

  if (receipt.claims.independentReview !== null) {
    assertKnown(
      receipt.claims.independentReview.passKind,
      CONTRACT_VALUES.independentReviewPass,
      "independent review pass",
    );
    if (receipt.subject !== EVIDENCE_SUBJECT.REVIEW
      || receipt.kind !== EVIDENCE_KIND.REVIEWER_JUDGMENT
      || receipt.execution.kind !== EXECUTION_KIND.INDEPENDENT_REVIEW
      || receipt.execution.adapter !== EVIDENCE_ADAPTER.INDEPENDENT_REVIEW
      || receipt.execution.officialIngress !== false) {
      throw new Error(
        `Independent review claim in ${receipt.evidenceId} requires REVIEW subject, `
        + "REVIEWER_JUDGMENT kind, INDEPENDENT_REVIEW execution and adapter, and forbidden ingress",
      );
    }
    if (!gateOutcomeIds.has(GATE_ID["independent-review"])) {
      throw new Error(`Independent review claim in ${receipt.evidenceId} requires an independent-review gate outcome`);
    }
  }

  if (receipt.claims.scoreAttestations.length > 0
    && !gateOutcomeIds.has(GATE_ID["required-score-evidence"])) {
    throw new Error(`Score attestations in ${receipt.evidenceId} require a required-score-evidence gate outcome`);
  }
  if (receipt.claims.searchDiscovery !== null) {
    assertKnown(
      receipt.claims.searchDiscovery.status,
      CONTRACT_VALUES.discoveryStatus,
      "search discovery claim status",
    );
    if (!gateOutcomeIds.has(GATE_ID["search-ai-discovery"])) {
      throw new Error(`Search discovery claim in ${receipt.evidenceId} requires a search-ai-discovery gate outcome`);
    }
  }

  const approvalKeys = new Set();
  for (const claim of receipt.claims.findingApprovals) {
    assertKnown(claim.kind, CONTRACT_VALUES.findingApprovalKind, "finding approval kind");
    const key = `${claim.findingId}:${claim.kind}`;
    if (approvalKeys.has(key)) {
      throw new Error(`Evidence ${receipt.evidenceId} repeats finding approval ${key}`);
    }
    approvalKeys.add(key);
    if (receipt.claims.independentReview === null) {
      throw new Error(`Finding approval ${key} requires a named independent reviewer claim`);
    }
  }
  return true;
}

function trustUsable(trust, freshness) {
  if (!trust || !Number.isInteger(trust.ageSeconds) || trust.ageSeconds < 0) return false;
  if (trust.ageSeconds > freshness.maxAgeSeconds) return false;
  if (freshness.requireCandidateIdentityMatch && trust.identityMatches !== true) return false;
  if (freshness.requireToolDigestMatch
    && trust.producerState !== IDENTITY_MATCH_STATUS.MATCH) return false;
  if (freshness.requireProfileDigestMatch && trust.profileMatches !== true) return false;
  return true;
}

function receiptAllowedByGate(receipt, gate) {
  assertKnown(gate.evaluator, CONTRACT_VALUES.gateEvaluator, "gate evaluator");
  assertKnown(gate.officialIngressPolicy, CONTRACT_VALUES.officialIngressPolicy, "official ingress policy");
  if (!gate.allowedAdapters.includes(receipt.execution.adapter)
    || !gate.allowedExecutionKinds.includes(receipt.execution.kind)
    || !gate.allowedEvidenceKinds.includes(receipt.kind)) {
    return false;
  }
  switch (gate.officialIngressPolicy) {
    case OFFICIAL_INGRESS_POLICY.REQUIRED:
      return receipt.execution.officialIngress === true;
    case OFFICIAL_INGRESS_POLICY.FORBIDDEN:
      return receipt.execution.officialIngress === false;
    case OFFICIAL_INGRESS_POLICY.EITHER_EXPLICIT:
      return typeof receipt.execution.officialIngress === "boolean";
    case OFFICIAL_INGRESS_POLICY.NO_EVIDENCE:
      return false;
    default:
      throw new Error(`Unknown official ingress policy: ${gate.officialIngressPolicy}`);
  }
}

export function refsStatus(evidenceRefs, receiptsById, trustById, gate, freshness) {
  assertFreshnessPolicy(freshness);
  if (!Array.isArray(evidenceRefs)) throw new Error("evidenceRefs must be an explicit array");
  if (evidenceRefs.length === 0) return GATE_STATUS.NOT_VERIFIED;
  const receipts = evidenceRefs.map((reference) => {
    const receipt = receiptsById.get(reference);
    if (!receipt) throw new Error(`Unknown evidence reference: ${reference}`);
    assertReceiptClaimSemantics(receipt);
    return receipt;
  });
  if (receipts.some((receipt) => RECEIPT_FAILURE_STATUSES.has(receipt.status))) {
    return GATE_STATUS.FAILED;
  }
  if (receipts.some((receipt) => receipt.status === EVIDENCE_STATUS.SKIP)) {
    return GATE_STATUS.NOT_VERIFIED;
  }
  if (receipts.some((receipt) => !receiptAllowedByGate(receipt, gate))) {
    return GATE_STATUS.NOT_VERIFIED;
  }
  if (evidenceRefs.some((reference) => !trustUsable(trustById.get(reference), freshness))) {
    return GATE_STATUS.NOT_VERIFIED;
  }
  if (receipts.some((receipt) => {
    const outcomes = receipt.claims.gateOutcomes.filter(({ gateId }) => gateId === gate.id);
    return outcomes.length !== 1 || outcomes[0].status !== receipt.status;
  })) {
    return GATE_STATUS.NOT_VERIFIED;
  }
  return receipts.every((receipt) => receipt.status === EVIDENCE_STATUS.PASS)
    ? GATE_STATUS.VERIFIED
    : GATE_STATUS.NOT_VERIFIED;
}

function dimensionEvidenceAllowed(definition, receipt) {
  return definition.allowedAdapters.includes(receipt.execution.adapter)
    && definition.evidenceKinds.includes(receipt.kind);
}

function scoreEvidenceStatus({ profile, gate, input, dimensions, receiptsById, trustById }) {
  const referencedByDimensions = [...new Set(dimensions.flatMap(({ evidenceRefs }) => evidenceRefs))];
  if (!sameSet(referencedByDimensions, input.evidenceRefs)) return GATE_STATUS.NOT_VERIFIED;
  const baseStatus = refsStatus(input.evidenceRefs, receiptsById, trustById, gate, profile.freshness);
  if (baseStatus !== GATE_STATUS.VERIFIED) return baseStatus;

  const definitionIds = new Set(profile.dimensions.map(({ id }) => id));
  for (const reference of input.evidenceRefs) {
    for (const claim of receiptsById.get(reference).claims.scoreAttestations) {
      if (!definitionIds.has(claim.dimensionId)) {
        throw new Error(`Evidence ${reference} claims unknown dimension ${claim.dimensionId}`);
      }
    }
  }

  for (const definition of profile.dimensions) {
    const dimension = dimensions.find(({ id }) => id === definition.id);
    if (!dimension) throw new Error(`Missing scorecard dimension ${definition.id}`);
    const receipts = dimension.evidenceRefs.map((reference) => receiptsById.get(reference));
    if (receipts.some((receipt) => !dimensionEvidenceAllowed(definition, receipt))) {
      return GATE_STATUS.NOT_VERIFIED;
    }
    const expectedScores = [
      [SCORE_SIDE.CURRENT, dimension.current, definition.required],
      [SCORE_SIDE.BASELINE, dimension.baseline, profile.baselineRequired],
    ];
    for (const [side, score, required] of expectedScores) {
      if (score === null) {
        if (required) return GATE_STATUS.NOT_VERIFIED;
        continue;
      }
      const attestations = receipts.flatMap((receipt) => receipt.claims.scoreAttestations
        .filter((claim) => claim.dimensionId === definition.id && claim.side === side)
        .map((claim) => ({ claim, receipt })));
      if (attestations.length !== 1 || attestations[0].claim.score !== score) {
        return GATE_STATUS.NOT_VERIFIED;
      }
    }
  }
  return GATE_STATUS.VERIFIED;
}

function independentReviewState({ profile, gate, input, receiptsById, trustById }) {
  const baseStatus = refsStatus(input.evidenceRefs, receiptsById, trustById, gate, profile.freshness);
  const claims = input.evidenceRefs.map((reference) => ({
    evidenceRef: reference,
    claim: receiptsById.get(reference)?.claims.independentReview ?? null,
  }));
  const claimed = claims.filter(({ claim }) => claim !== null);
  const reviewerIds = [...new Set(claimed.map(({ claim }) => claim.reviewerId))].sort(compareText);
  const claimedPassKinds = claimed.map(({ claim }) => claim.passKind).sort(compareText);
  const passKinds = [...new Set(claimedPassKinds)];
  const requiredPasses = [...profile.independentReviewPasses].sort(compareText);
  const exactPassReceipts = claimed.length === requiredPasses.length
    && passKinds.length === claimedPassKinds.length
    && reviewerIds.length === requiredPasses.length
    && sameSet(claimedPassKinds, requiredPasses);
  return {
    status: baseStatus === GATE_STATUS.VERIFIED && exactPassReceipts
      ? GATE_STATUS.VERIFIED
      : baseStatus === GATE_STATUS.FAILED ? GATE_STATUS.FAILED : GATE_STATUS.NOT_VERIFIED,
    reviewerIds,
    passKinds,
    evidenceRefs: claimed.map(({ evidenceRef }) => evidenceRef).sort(compareText),
  };
}

function identityStatus(input, identity, required) {
  if (input.evidenceRefs.length !== 0) return GATE_STATUS.NOT_VERIFIED;
  return identity !== null && identity !== undefined
    ? GATE_STATUS.VERIFIED
    : required ? GATE_STATUS.NOT_VERIFIED : GATE_STATUS.VERIFIED;
}

function evaluateGate({
  gate,
  input,
  profile,
  identities,
  dimensions,
  docsImpact,
  searchDiscovery,
  receiptsById,
  trustById,
}) {
  switch (gate.evaluator) {
    case GATE_EVALUATOR.CANDIDATE_IDENTITY:
      return { status: identityStatus(input, identities.candidate, true) };
    case GATE_EVALUATOR.BASELINE_IDENTITY:
      return { status: identityStatus(input, identities.baseline, profile.baselineRequired) };
    case GATE_EVALUATOR.SCORE_ATTESTATIONS:
      return { status: scoreEvidenceStatus({ profile, gate, input, dimensions, receiptsById, trustById }) };
    case GATE_EVALUATOR.EVIDENCE_SET:
      return { status: refsStatus(input.evidenceRefs, receiptsById, trustById, gate, profile.freshness) };
    case GATE_EVALUATOR.DOCS_IMPACT: {
      if (docsImpact.authority === DOCS_IMPACT_AUTHORITY.PROTECTED_CONTROLLER_VERIFIED) {
        throw new Error("PROTECTED_CONTROLLER_VERIFIED is unavailable in schema v1: no trusted adapter exists");
      }
      const status = input.evidenceRefs.length === 0
        ? GATE_STATUS.NOT_VERIFIED
        : refsStatus(input.evidenceRefs, receiptsById, trustById, gate, profile.freshness);
      return { status: status === GATE_STATUS.FAILED ? status : GATE_STATUS.NOT_VERIFIED };
    }
    case GATE_EVALUATOR.SEARCH_DISCOVERY:
      if (searchDiscovery.status === DISCOVERY_STATUS.CHANGED_ACTION_REQUIRED) {
        return { status: GATE_STATUS.FAILED };
      }
      if (searchDiscovery.status === DISCOVERY_STATUS.UNVERIFIED) {
        return { status: GATE_STATUS.NOT_VERIFIED };
      }
      {
        const status = refsStatus(input.evidenceRefs, receiptsById, trustById, gate, profile.freshness);
        if (status !== GATE_STATUS.VERIFIED) return { status };
        const claims = input.evidenceRefs
          .map((reference) => receiptsById.get(reference).claims.searchDiscovery)
          .filter((claim) => claim !== null);
        return {
          status: claims.length === 1 && claims[0].status === searchDiscovery.status
            ? GATE_STATUS.VERIFIED
            : GATE_STATUS.NOT_VERIFIED,
        };
      }
    case GATE_EVALUATOR.INDEPENDENT_REVIEW: {
      const independentReview = independentReviewState({ profile, gate, input, receiptsById, trustById });
      return { status: independentReview.status, independentReview };
    }
    default:
      throw new Error(`Unknown gate evaluator: ${String(gate.evaluator)}`);
  }
}

function assertGatePolicy(gate) {
  assertKnown(gate.evaluator, CONTRACT_VALUES.gateEvaluator, "gate evaluator");
  assertKnown(gate.officialIngressPolicy, CONTRACT_VALUES.officialIngressPolicy, "official ingress policy");
  if (gate.evaluator !== CONTRACT_VALUES.gateEvaluatorById[gate.id]) {
    throw new Error(`Gate ${gate.id} must use evaluator ${CONTRACT_VALUES.gateEvaluatorById[gate.id]}`);
  }
  for (const adapter of gate.allowedAdapters) {
    assertKnown(adapter, CONTRACT_VALUES.evidenceAdapter, "gate evidence adapter");
  }
  for (const executionKind of gate.allowedExecutionKinds) {
    assertKnown(executionKind, CONTRACT_VALUES.executionKind, "gate execution kind");
  }
  for (const evidenceKind of gate.allowedEvidenceKinds) {
    assertKnown(evidenceKind, CONTRACT_VALUES.evidenceKind, "gate evidence kind");
  }
}

export function buildGates({
  profile,
  gateInputs,
  identities,
  dimensions,
  docsImpact,
  searchDiscovery,
  receiptsById,
  trustById,
}) {
  assertFreshnessPolicy(profile.freshness);
  const ids = gateInputs.map(({ id }) => id);
  if (new Set(ids).size !== ids.length) throw new Error("Gate input IDs must be unique");
  const expected = profile.requiredGates.map(({ id }) => id);
  if (!sameSet(ids, expected)) {
    throw new Error(`Gate inputs must declare every ${profile.id} required gate exactly once`);
  }
  for (const receipt of receiptsById.values()) assertReceiptClaimSemantics(receipt);
  const inputsById = new Map(gateInputs.map((input) => [input.id, input]));
  const generatedBlockers = [];
  let independentReview = {
    status: GATE_STATUS.NOT_VERIFIED,
    reviewerIds: [],
    passKinds: [],
    evidenceRefs: [],
  };
  const gates = profile.requiredGates.map((gate) => {
    assertGatePolicy(gate);
    const input = inputsById.get(gate.id);
    const result = evaluateGate({
      gate,
      input,
      profile,
      identities,
      dimensions,
      docsImpact,
      searchDiscovery,
      receiptsById,
      trustById,
    });
    if (result.independentReview) independentReview = result.independentReview;
    const blockerId = `gate-${gate.id}`;
    if (result.status !== GATE_STATUS.VERIFIED) {
      generatedBlockers.push({
        id: blockerId,
        severity: BLOCKER_SEVERITY.MATERIAL,
        status: BLOCKER_STATUS.OPEN,
        summary: `${input.summary} Required gate status: ${result.status}.`,
        evidenceRefs: [...input.evidenceRefs].sort(compareText),
        unlock: input.unlock,
      });
    }
    return {
      id: gate.id,
      required: true,
      status: result.status,
      summary: input.summary,
      evidenceRefs: [...input.evidenceRefs].sort(compareText),
      blockerRefs: result.status === GATE_STATUS.VERIFIED ? [] : [blockerId],
    };
  });
  return {
    gates,
    generatedBlockers,
    independentReviewStatus: independentReview.status === GATE_STATUS.VERIFIED
      ? INDEPENDENT_REVIEW_STATUS.VERIFIED
      : INDEPENDENT_REVIEW_STATUS.NOT_VERIFIED,
    independentReview,
  };
}

function approvalReceiptUsable(receipt, trust, freshness) {
  if (!trustUsable(trust, freshness) || receipt.status !== EVIDENCE_STATUS.PASS) return false;
  const reviewOutcomes = receipt.claims.gateOutcomes.filter(
    ({ gateId }) => gateId === GATE_ID["independent-review"],
  );
  return receipt.subject === EVIDENCE_SUBJECT.REVIEW
    && receipt.kind === EVIDENCE_KIND.REVIEWER_JUDGMENT
    && receipt.execution.kind === EXECUTION_KIND.INDEPENDENT_REVIEW
    && receipt.execution.adapter === EVIDENCE_ADAPTER.INDEPENDENT_REVIEW
    && receipt.execution.officialIngress === false
    && receipt.claims.independentReview !== null
    && reviewOutcomes.length === 1
    && reviewOutcomes[0].status === GATE_OUTCOME_STATUS.PASS;
}

function approvalEvidenceRefs(finding, approvalKind, receiptsById, trustById, freshness) {
  return finding.evidenceRefs.filter((reference) => {
    const receipt = receiptsById.get(reference);
    if (!receipt) return false;
    assertReceiptClaimSemantics(receipt);
    return approvalReceiptUsable(receipt, trustById.get(reference), freshness)
      && receipt.claims.findingApprovals.some(
        (claim) => claim.findingId === finding.id
          && claim.findingDigest === canonicalDigest(finding)
          && claim.kind === approvalKind,
      );
  }).sort(compareText);
}

function generatedFindingBlocker(id, summary, evidenceRefs, unlockKind, requiredEvidenceKinds) {
  if (id.length > CONTRACT_VALUES.limits.maxIdentifierCharacters) {
    throw new Error(`Generated finding blocker ID exceeds ${CONTRACT_VALUES.limits.maxIdentifierCharacters} characters: ${id}`);
  }
  return {
    id,
    severity: BLOCKER_SEVERITY.MATERIAL,
    status: BLOCKER_STATUS.OPEN,
    summary,
    evidenceRefs: [...evidenceRefs].sort(compareText),
    unlock: {
      kind: unlockKind,
      description: summary,
      requiredEvidenceKinds,
    },
  };
}

export function buildFindingReadiness({
  blockers,
  regressions,
  remainingGaps,
  receiptsById,
  trustById,
  freshness,
}) {
  assertFreshnessPolicy(freshness);
  const generatedBlockers = [];
  const approvals = [];

  for (const blocker of blockers) {
    if (blocker.status !== BLOCKER_STATUS.RESOLVED) continue;
    const evidenceRefs = approvalEvidenceRefs(
      blocker,
      FINDING_APPROVAL_KIND.RESOLVED_BLOCKER,
      receiptsById,
      trustById,
      freshness,
    );
    if (evidenceRefs.length === 0) {
      generatedBlockers.push(generatedFindingBlocker(
        `approval-resolved-${blocker.id}`,
        `Resolved blocker ${blocker.id} lacks a trusted RESOLVED_BLOCKER approval claim.`,
        blocker.evidenceRefs,
        UNLOCK_KIND.HUMAN_APPROVAL,
        [EVIDENCE_KIND.REVIEWER_JUDGMENT],
      ));
    } else {
      approvals.push({
        findingId: blocker.id,
        kind: FINDING_APPROVAL_KIND.RESOLVED_BLOCKER,
        evidenceRefs,
      });
    }
  }

  for (const regression of regressions) {
    if (regression.disposition === FINDING_DISPOSITION.BLOCKER) {
      generatedBlockers.push(generatedFindingBlocker(
        `blocking-regression-${regression.id}`,
        `Regression ${regression.id} is explicitly classified as BLOCKER.`,
        regression.evidenceRefs,
        UNLOCK_KIND.IMPLEMENTATION,
        [EVIDENCE_KIND.MEASURED],
      ));
      continue;
    }
    if (regression.disposition === FINDING_DISPOSITION.FIXED) {
      const evidenceRefs = approvalEvidenceRefs(
        regression,
        FINDING_APPROVAL_KIND.RESOLVED_REGRESSION,
        receiptsById,
        trustById,
        freshness,
      );
      if (evidenceRefs.length === 0) {
        generatedBlockers.push(generatedFindingBlocker(
          `approval-fixed-${regression.id}`,
          `Fixed regression ${regression.id} lacks a trusted RESOLVED_REGRESSION approval claim.`,
          regression.evidenceRefs,
          UNLOCK_KIND.HUMAN_APPROVAL,
          [EVIDENCE_KIND.REVIEWER_JUDGMENT],
        ));
      } else {
        approvals.push({
          findingId: regression.id,
          kind: FINDING_APPROVAL_KIND.RESOLVED_REGRESSION,
          evidenceRefs,
        });
      }
      continue;
    }
    if (regression.disposition !== FINDING_DISPOSITION.ACCEPTED_TRADE_OFF) continue;
    const evidenceRefs = approvalEvidenceRefs(
      regression,
      FINDING_APPROVAL_KIND.ACCEPTED_TRADE_OFF,
      receiptsById,
      trustById,
      freshness,
    );
    if (evidenceRefs.length === 0) {
      generatedBlockers.push(generatedFindingBlocker(
        `approval-tradeoff-${regression.id}`,
        `Accepted trade-off ${regression.id} lacks a trusted ACCEPTED_TRADE_OFF approval claim.`,
        regression.evidenceRefs,
        UNLOCK_KIND.HUMAN_APPROVAL,
        [EVIDENCE_KIND.REVIEWER_JUDGMENT],
      ));
    } else {
      approvals.push({
        findingId: regression.id,
        kind: FINDING_APPROVAL_KIND.ACCEPTED_TRADE_OFF,
        evidenceRefs,
      });
    }
  }

  for (const gap of remainingGaps) {
    if (gap.severity !== BLOCKER_SEVERITY.MATERIAL) continue;
    generatedBlockers.push(generatedFindingBlocker(
      `material-gap-${gap.id}`,
      `Material remaining gap ${gap.id} blocks readiness until it is removed by verified work.`,
      gap.evidenceRefs,
      gap.unlock.kind,
      gap.unlock.requiredEvidenceKinds,
    ));
  }

  return {
    generatedBlockers: generatedBlockers.sort((left, right) => compareText(left.id, right.id)),
    approvals: approvals.sort((left, right) => compareText(left.findingId, right.findingId)),
  };
}

export function readinessVerdict(gates, blockers) {
  return gates.some((gate) => gate.required && gate.status !== GATE_STATUS.VERIFIED)
    || blockers.some((blocker) => blocker.severity === BLOCKER_SEVERITY.MATERIAL
      && blocker.status === BLOCKER_STATUS.OPEN)
    ? READINESS_VERDICT.NOT_READY
    : READINESS_VERDICT.READY;
}

export function assertReadinessSemantics(review) {
  const blockerIds = new Set(review.blockers.map(({ id }) => id));
  for (const gate of review.gates) {
    for (const blockerRef of gate.blockerRefs) {
      if (!blockerIds.has(blockerRef)) {
        throw new Error(`Gate ${gate.id} references unknown blocker ${blockerRef}`);
      }
    }
  }
  const hasUnrepresentedBlockingFinding = review.remainingGaps.some(
    (gap) => gap.severity === BLOCKER_SEVERITY.MATERIAL,
  ) || review.regressions.some(
    (regression) => regression.disposition === FINDING_DISPOSITION.BLOCKER,
  );
  const expected = hasUnrepresentedBlockingFinding
    ? READINESS_VERDICT.NOT_READY
    : readinessVerdict(review.gates, review.blockers);
  if (review.readinessVerdict !== expected) {
    throw new Error(`Review readiness ${review.readinessVerdict} is inconsistent; expected ${expected}`);
  }
  return true;
}

export { GATE_STATUS };
