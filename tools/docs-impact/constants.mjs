import { readFileSync } from "node:fs";
import { TextDecoder } from "node:util";

export const TOOL_VERSION = "0.3.0";

const UTF8_DECODER = new TextDecoder("utf-8", { fatal: true });

export const CONTRACT_VALUES = Object.freeze(JSON.parse(UTF8_DECODER.decode(
  readFileSync(new URL("./contracts/values.json", import.meta.url))
)));

function namedValues(values) {
  return Object.freeze(Object.fromEntries(values.map((value) => [value, value])));
}

export const ACTION_TYPE = namedValues(CONTRACT_VALUES.actionType);
export const ACTION_TYPE_BY_COLLECTION = Object.freeze({ ...CONTRACT_VALUES.actionTypeByCollection });
export const CANDIDATE_STATE = namedValues(CONTRACT_VALUES.candidateState);
export const CHANGE_KIND = namedValues(CONTRACT_VALUES.changeKind);
export const CHANGE_KIND_BY_STATUS = Object.freeze({ ...CONTRACT_VALUES.changeKindByStatus });
export const CHANGE_STATUS = namedValues(CONTRACT_VALUES.changeStatus);
export const CLASSIFICATION = namedValues(CONTRACT_VALUES.classification);
export const COMPONENT_KIND = namedValues(CONTRACT_VALUES.componentKind);
export const DOCUMENT_BASE_PRESENCE = namedValues(CONTRACT_VALUES.documentBasePresence);
export const DOCUMENT_ROLE = namedValues(CONTRACT_VALUES.documentRole);
export const IMPACT_DEPTH = namedValues(CONTRACT_VALUES.impactDepth);
export const IMPACT_RELATION = namedValues(CONTRACT_VALUES.impactRelation);
export const INVENTORY_CLASSIFICATION = namedValues(CONTRACT_VALUES.inventoryClassification);
export const LIMITS = Object.freeze({ ...CONTRACT_VALUES.limits });
export const PROPAGATION_DECISION = namedValues(CONTRACT_VALUES.propagationDecision);
export const PROTECTION_KIND = namedValues(CONTRACT_VALUES.protectionKind);
export const PUBLICATION = namedValues(CONTRACT_VALUES.publication);
export const PUBLICATION_LOCATOR = namedValues(CONTRACT_VALUES.publicationLocator);
export const PUBLICATION_TRIGGER_KIND = namedValues(CONTRACT_VALUES.publicationTriggerKind);
export const SUPPORTED_CHANGE_STATUS = Object.freeze(new Set(CONTRACT_VALUES.supportedChangeStatus));

const mappedStatuses = Object.keys(CHANGE_KIND_BY_STATUS).sort();
const supportedStatuses = [...SUPPORTED_CHANGE_STATUS].sort();
if (JSON.stringify(mappedStatuses) !== JSON.stringify(supportedStatuses)
    || Object.values(CHANGE_KIND_BY_STATUS).some((kind) => !Object.hasOwn(CHANGE_KIND, kind))) {
  throw new Error("changeKindByStatus must map every supported Git status to one canonical change kind");
}

export const REASON = Object.freeze({
  AMBIGUOUS_IMPACT_NODE_MAPPING: "AMBIGUOUS_IMPACT_NODE_MAPPING",
  AMBIGUOUS_INVENTORY_MAPPING: "AMBIGUOUS_INVENTORY_MAPPING",
  ANALYSIS_LIMIT_EXCEEDED: "ANALYSIS_LIMIT_EXCEEDED",
  DOCUMENT_BASE_MISSING: "DOCUMENT_BASE_MISSING",
  DOCUMENT_HEAD_MISSING: "DOCUMENT_HEAD_MISSING",
  DOCUMENTATION_OBLIGATION_IDENTIFIED: "DOCUMENTATION_OBLIGATION_IDENTIFIED",
  GIT_IDENTITY_ERROR: "GIT_IDENTITY_ERROR",
  GOVERNANCE_REVIEW_REQUIRED: "GOVERNANCE_REVIEW_REQUIRED",
  INVALID_REPOSITORY_PATH: "INVALID_REPOSITORY_PATH",
  NO_ACTION_REQUIRED: "NO_ACTION_REQUIRED",
  NO_DOC_IMPACT_NODE_MAPPING: "NO_DOC_IMPACT_NODE_MAPPING",
  PATH_IDENTITY_COLLISION: "PATH_IDENTITY_COLLISION",
  PUBLICATION_VALIDATION_REQUIRED: "PUBLICATION_VALIDATION_REQUIRED",
  TRUSTED_TOOL_PATH_MISSING: "TRUSTED_TOOL_PATH_MISSING",
  UNMAPPED_IMPACT_NODE: "UNMAPPED_IMPACT_NODE",
  UNMAPPED_INVENTORY_PATH: "UNMAPPED_INVENTORY_PATH",
  UNSUPPORTED_GIT_CHANGE: "UNSUPPORTED_GIT_CHANGE",
  UNSUPPORTED_GIT_MODE: "UNSUPPORTED_GIT_MODE"
});
