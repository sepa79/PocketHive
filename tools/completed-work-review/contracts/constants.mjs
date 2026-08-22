import { CONTRACT_VALUES } from "./projections.mjs";

function namedValues(values) {
  return Object.freeze(Object.fromEntries(values.map((value) => [value, value])));
}

export { CONTRACT_VALUES };
export const BLOCKER_SEVERITY = namedValues(CONTRACT_VALUES.blockerSeverity);
export const BLOCKER_STATUS = namedValues(CONTRACT_VALUES.blockerStatus);
export const CANONICAL_REPOSITORY_PATHS = Object.freeze({
  ...CONTRACT_VALUES.canonicalRepositoryPaths,
});
export const CANDIDATE_IDENTITY_MODE = namedValues(CONTRACT_VALUES.candidateIdentityMode);
export const CANDIDATE_VERIFICATION_TARGET = namedValues(CONTRACT_VALUES.candidateVerificationTarget);
export const COMPARISON_STATUS = namedValues(CONTRACT_VALUES.comparisonStatus);
export const CONFIDENCE_LABEL = namedValues(CONTRACT_VALUES.confidenceLabel);
export const DISCOVERY_STATUS = namedValues(CONTRACT_VALUES.discoveryStatus);
export const DOCS_IMPACT_AUTHORITY = namedValues(CONTRACT_VALUES.docsImpactAuthority);
export const EVIDENCE_ADAPTER = namedValues(CONTRACT_VALUES.evidenceAdapter);
export const EVIDENCE_KIND = namedValues(CONTRACT_VALUES.evidenceKind);
export const EVIDENCE_STATUS = namedValues(CONTRACT_VALUES.evidenceStatus);
export const EVIDENCE_SUBJECT = namedValues(CONTRACT_VALUES.evidenceSubject);
export const EVALUATOR_EXECUTION_PROVENANCE_METHOD = namedValues(
  CONTRACT_VALUES.evaluatorExecutionProvenanceMethod,
);
export const EVALUATOR_EXECUTION_PROVENANCE_STATEMENT =
  CONTRACT_VALUES.evaluatorExecutionProvenanceStatement;
export const EVALUATOR_EXECUTION_PROVENANCE_STATUS = namedValues(
  CONTRACT_VALUES.evaluatorExecutionProvenanceStatus,
);
export const EXECUTION_KIND = namedValues(CONTRACT_VALUES.executionKind);
export const EXTERNAL_AUTHORITY_STATUS = namedValues(CONTRACT_VALUES.externalAuthorityStatus);
export const FINDING_APPROVAL_KIND = namedValues(CONTRACT_VALUES.findingApprovalKind);
export const FINDING_DISPOSITION = namedValues(CONTRACT_VALUES.findingDisposition);
export const FRESHNESS_STATUS = namedValues(CONTRACT_VALUES.freshnessStatus);
export const GATE_EVALUATOR = namedValues(CONTRACT_VALUES.gateEvaluator);
export const GATE_ID = namedValues(CONTRACT_VALUES.gateId);
export const GATE_OUTCOME_STATUS = namedValues(CONTRACT_VALUES.gateOutcomeStatus);
export const GATE_STATUS = namedValues(CONTRACT_VALUES.gateStatus);
export const IDENTITY_MATCH_STATUS = namedValues(CONTRACT_VALUES.identityMatchStatus);
export const INDEPENDENT_REVIEW_STATUS = namedValues(CONTRACT_VALUES.independentReviewStatus);
export const OFFICIAL_INGRESS_POLICY = namedValues(CONTRACT_VALUES.officialIngressPolicy);
export const PRODUCER_REGISTRY_AUTHORITY = namedValues(CONTRACT_VALUES.producerRegistryAuthority);
export const PROVENANCE_BLOCKER_ID = CONTRACT_VALUES.provenanceBlockerId;
export const READINESS_VERDICT = namedValues(CONTRACT_VALUES.readinessVerdict);
export const SCORE_SIDE = namedValues(CONTRACT_VALUES.scoreSide);
export const SCORING_METHOD = namedValues(CONTRACT_VALUES.scoringMethod);
export const SCORE_VERIFICATION_STATUS = namedValues(CONTRACT_VALUES.scoreVerificationStatus);
export const UNLOCK_KIND = namedValues(CONTRACT_VALUES.unlockKind);
export const VERDICT_SCOPE = namedValues(CONTRACT_VALUES.verdictScope);
