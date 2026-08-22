import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { TextDecoder } from "node:util";
import { canonicalDigest, canonicalJsonByteLength } from "./canonical.mjs";
import { assertAnalysisSemantics } from "./analysis-semantics.mjs";
import { assertAnalysisSchemaProjections } from "./contracts/projections.mjs";
import {
  ACTION_TYPE,
  CANDIDATE_STATE,
  CHANGE_KIND_BY_STATUS,
  CHANGE_STATUS,
  CLASSIFICATION,
  INVENTORY_CLASSIFICATION,
  LIMITS,
  PUBLICATION_TRIGGER_KIND,
  REASON,
  SUPPORTED_CHANGE_STATUS,
  TOOL_VERSION
} from "./constants.mjs";
import {
  assertObjectGraphIntegrity,
  assertRepositoryRoot,
  assertTreeObjectsAvailable,
  findMergeBase,
  GitReadError,
  readBlob,
  readChanges,
  readTreeEntries,
  resolveCommit
} from "./git-reader.mjs";
import { resolveImpactRoutes } from "./impact-graph.mjs";
import {
  assertRepositoryPath,
  parsePolicyBytes,
  pathMatches
} from "./policy.mjs";
import { assertSchema } from "./schema-validator.mjs";

const ANALYSIS_SCHEMA_PATH = fileURLToPath(
  new URL("./contracts/analysis.schema.json", import.meta.url)
);
const ANALYSIS_UTF8_DECODER = new TextDecoder("utf-8", { fatal: true });
const SUPPORTED_FILE_MODES = new Set(["100644", "100755"]);

function compareText(left, right) {
  return left < right ? -1 : left > right ? 1 : 0;
}

function sortedUnique(values) {
  return [...new Set(values)].sort(compareText);
}

function compareCanonical(left, right) {
  return compareText(JSON.stringify(left), JSON.stringify(right));
}

function sortedUniqueObjects(values) {
  const byIdentity = new Map();
  for (const value of values) {
    byIdentity.set(JSON.stringify(value), value);
  }
  return [...byIdentity.values()].sort(compareCanonical);
}

function sortErrors(errors) {
  return [...errors].sort((left, right) =>
    compareText(left.code, right.code)
    || compareText(left.detail, right.detail)
    || compareText(left.paths.join("\u0000"), right.paths.join("\u0000"))
  );
}

function policyError(code, detail, { paths = [], ruleIds = [] } = {}) {
  return {
    code,
    detail,
    paths: sortedUnique(paths),
    ruleIds: sortedUnique(ruleIds)
  };
}

class BoundedPolicyErrors extends Array {
  static get [Symbol.species]() {
    return Array;
  }

  constructor() {
    super();
    this.totalDetailBytes = 0;
    this.totalSerializedBytes = 0;
    this.limitSignaled = false;
  }

  signalLimit() {
    if (this.limitSignaled) {
      return;
    }
    this.limitSignaled = true;
    if (this.length >= LIMITS.maxPolicyErrors) {
      super.pop();
    }
    super.push(policyError(
      REASON.ANALYSIS_LIMIT_EXCEEDED,
      "Policy error diagnostics exceeded canonical output bounds"
    ));
  }

  push(...candidates) {
    for (const candidate of candidates) {
      if (this.limitSignaled) {
        continue;
      }
      const detailBytes = Buffer.byteLength(candidate.detail, "utf8");
      const serializedBytes = canonicalJsonByteLength(candidate);
      if (this.length >= LIMITS.maxPolicyErrors
          || detailBytes > LIMITS.maxPolicyErrorDetailBytesPerError
          || this.totalDetailBytes + detailBytes > LIMITS.maxPolicyErrorDetailBytes
          || this.totalSerializedBytes + serializedBytes > LIMITS.maxPolicyErrorBytes) {
        this.signalLimit();
        continue;
      }
      super.push(candidate);
      this.totalDetailBytes += detailBytes;
      this.totalSerializedBytes += serializedBytes;
    }
    return this.length;
  }
}

class AnalysisLimitError extends Error {
  constructor(message) {
    super(message);
    this.name = "AnalysisLimitError";
  }
}

class AnalysisOutputBudget {
  constructor(initialValue) {
    this.usedBytes = canonicalJsonByteLength(initialValue);
  }

  consume(value, label) {
    const bytes = canonicalJsonByteLength(value);
    if (this.usedBytes + bytes > LIMITS.maxAnalysisBytes) {
      throw new AnalysisLimitError(
        `${label} exceeds canonical analysis budget ${LIMITS.maxAnalysisBytes} bytes`
      );
    }
    this.usedBytes += bytes;
  }
}

function matchesForPath(rules, repositoryPath, field) {
  return rules.filter((rule) =>
    rule[field].some((pattern) => pathMatches(pattern, repositoryPath))
  );
}

export function mapGitStatusToChangeKind(status) {
  return CHANGE_KIND_BY_STATUS[status] ?? null;
}

export { resolveImpactRoutes } from "./impact-graph.mjs";

export function findPathIdentityCollisions(paths) {
  const identities = new Map();
  for (const repositoryPath of paths) {
    const identity = repositoryPath.normalize("NFC").toLowerCase();
    const existing = identities.get(identity) ?? new Set();
    existing.add(repositoryPath);
    identities.set(identity, existing);
  }
  return [...identities.values()]
    .filter((pathsForIdentity) => pathsForIdentity.size > 1)
    .map(sortedUnique)
    .sort((left, right) => compareText(left.join("\u0000"), right.join("\u0000")));
}

export function findDuplicatePaths(paths) {
  const seen = new Set();
  const duplicates = new Set();
  for (const repositoryPath of paths) {
    if (seen.has(repositoryPath)) {
      duplicates.add(repositoryPath);
    }
    seen.add(repositoryPath);
  }
  return sortedUnique(duplicates);
}

function inspectPathIdentity(entries, treeLabel, errors) {
  for (const duplicate of findDuplicatePaths(entries.map((entry) => entry.path))) {
    errors.push(policyError(
      REASON.PATH_IDENTITY_COLLISION,
      `${treeLabel} contains duplicate exact path ${duplicate}`,
      { paths: [duplicate] }
    ));
  }
  const safePaths = [];
  for (const entry of entries) {
    try {
      assertRepositoryPath(entry.path, `${treeLabel} path`);
      safePaths.push(entry.path);
    } catch (error) {
      errors.push(policyError(REASON.INVALID_REPOSITORY_PATH, error.message, { paths: [entry.path] }));
      continue;
    }
    if (!SUPPORTED_FILE_MODES.has(entry.mode) || entry.type !== "blob") {
      errors.push(policyError(
        REASON.UNSUPPORTED_GIT_MODE,
        `${treeLabel} path ${entry.path} uses unsupported mode/type ${entry.mode}/${entry.type}`,
        { paths: [entry.path] }
      ));
    }
  }
  for (const collision of findPathIdentityCollisions(safePaths)) {
    errors.push(policyError(
      REASON.PATH_IDENTITY_COLLISION,
      `${treeLabel} contains paths that collide by case or Unicode identity: ${collision.join(", ")}`,
      { paths: collision }
    ));
  }
}

function inspectInventory(entries, treeLabel, policy, errors) {
  const classifications = new Map();
  for (const entry of entries) {
    const matches = matchesForPath(policy.inventoryRules, entry.path, "paths");
    if (matches.length === 0) {
      errors.push(policyError(
        REASON.UNMAPPED_INVENTORY_PATH,
        `${treeLabel} path is not covered by the repository inventory`,
        { paths: [entry.path] }
      ));
      continue;
    }
    if (matches.length > 1) {
      errors.push(policyError(
        REASON.AMBIGUOUS_INVENTORY_MAPPING,
        `${treeLabel} path matches more than one inventory rule`,
        { paths: [entry.path], ruleIds: matches.map((rule) => rule.id) }
      ));
      continue;
    }
    classifications.set(entry.path, matches[0]);
  }
  return classifications;
}

function inspectImpactNodes(entries, treeLabel, policy, inventory, errors) {
  const nodes = new Map();
  for (const entry of entries) {
    const inventoryRule = inventory.get(entry.path);
    if (!inventoryRule) {
      continue;
    }
    const matches = matchesForPath(policy.impactNodes, entry.path, "sourcePaths");
    if (inventoryRule.classification === INVENTORY_CLASSIFICATION.MATERIAL) {
      if (matches.length === 0) {
        errors.push(policyError(
          REASON.UNMAPPED_IMPACT_NODE,
          `${treeLabel} MATERIAL path is not mapped to an impact node`,
          { paths: [entry.path] }
        ));
        continue;
      }
      if (matches.length > 1) {
        errors.push(policyError(
          REASON.AMBIGUOUS_IMPACT_NODE_MAPPING,
          `${treeLabel} MATERIAL path matches more than one impact node`,
          { paths: [entry.path], ruleIds: matches.map((node) => node.id) }
        ));
        continue;
      }
    } else if (inventoryRule.classification === INVENTORY_CLASSIFICATION.DOCUMENTATION) {
      if (matches.length > 1) {
        errors.push(policyError(
          REASON.AMBIGUOUS_IMPACT_NODE_MAPPING,
          `${treeLabel} DOCUMENTATION path matches more than one impact node`,
          { paths: [entry.path], ruleIds: matches.map((node) => node.id) }
        ));
        continue;
      }
    } else if (matches.length > 0) {
      errors.push(policyError(
        REASON.NO_DOC_IMPACT_NODE_MAPPING,
        `${treeLabel} NO_DOC_IMPACT path must not map to an impact node`,
        { paths: [entry.path], ruleIds: matches.map((node) => node.id) }
      ));
      continue;
    }
    if (matches.length === 1) {
      nodes.set(entry.path, matches[0]);
    }
  }
  return nodes;
}

function buildTrustedToolDigest(policy, baseEntries, errors) {
  const manifest = [];
  for (const pattern of [...policy.trustedToolPaths].sort(compareText)) {
    const entries = baseEntries
      .filter((entry) => pathMatches(pattern, entry.path))
      .map(({ mode, objectId, path: repositoryPath }) => ({
        mode,
        objectId,
        path: repositoryPath
      }))
      .sort((left, right) => compareText(left.path, right.path));
    if (entries.length === 0) {
      errors.push(policyError(
        REASON.TRUSTED_TOOL_PATH_MISSING,
        `Protected base does not contain trusted tool path ${pattern}`,
        { paths: [pattern] }
      ));
    }
    manifest.push({ pattern, entries });
  }
  return canonicalDigest(manifest);
}

function inspectRequiredBaseDocuments(policy, baseEntries, errors) {
  const basePaths = new Set(baseEntries.map((entry) => entry.path));
  for (const document of policy.documents) {
    if (!basePaths.has(document.path)) {
      errors.push(policyError(
        REASON.DOCUMENT_BASE_MISSING,
        `Document ${document.id} requires base presence at ${document.path}`,
        { paths: [document.path], ruleIds: [document.id] }
      ));
    }
  }
}

function inspectRequiredHeadDocuments(policy, headEntries, errors) {
  const headPaths = new Set(headEntries.map((entry) => entry.path));
  for (const document of policy.documents) {
    if (!headPaths.has(document.path)) {
      errors.push(policyError(
        REASON.DOCUMENT_HEAD_MISSING,
        `Document ${document.id} requires candidate-head presence at ${document.path}`,
        { paths: [document.path], ruleIds: [document.id] }
      ));
    }
  }
}

function makeIdentity(
  policy,
  policyPath,
  policyDigest,
  toolDigest,
  requestedBase,
  requestedHead,
  resolved
) {
  return {
    repositoryId: policy.repositoryId,
    requestedBase: requestedBase.toLowerCase(),
    requestedHead: requestedHead.toLowerCase(),
    baseSha: resolved.baseSha,
    headSha: resolved.headSha,
    mergeBaseSha: resolved.mergeBaseSha,
    policyPath,
    policyDigest,
    toolDigest
  };
}

function deduplicateErrors(errors) {
  const byIdentity = new Map();
  for (const error of errors) {
    const normalized = {
      ...error,
      paths: sortedUnique(error.paths),
      ruleIds: sortedUnique(error.ruleIds)
    };
    byIdentity.set(JSON.stringify(normalized), normalized);
  }
  return sortErrors(byIdentity.values());
}

function outputPublicationBindings(publicationById, document) {
  return [...document.publicationBindings]
    .sort((left, right) => compareText(left.publicationId, right.publicationId))
    .map((binding) => {
      const publication = publicationById.get(binding.publicationId);
      return {
        publicationId: publication.id,
        kind: publication.kind,
        locatorKind: publication.locatorKind,
        contentRoot: publication.contentRoot,
        artifactSelector: publication.artifactSelector,
        producerPaths: sortedUnique(publication.producerPaths),
        contentInputPaths: sortedUnique(publication.contentInputPaths),
        artifactPath: binding.artifactPath,
        checkIds: sortedUnique(publication.checkIds),
        ownerIds: sortedUnique(publication.ownerIds)
      };
    });
}

function buildDocumentationObligations(
  analysisId,
  policy,
  triggersByRuleId,
  changedByPath,
  outputBudget
) {
  const documentById = new Map(policy.documents.map((document) => [document.id, document]));
  const publicationById = new Map(
    policy.publications.map((publication) => [publication.id, publication])
  );
  const obligations = [];
  let totalTargets = 0;
  const rules = policy.documentationRules
    .filter((rule) => triggersByRuleId.has(rule.id))
    .sort((left, right) => compareText(left.id, right.id));
  for (const rule of rules) {
    const triggers = sortedUniqueObjects(triggersByRuleId.get(rule.id));
    const triggeringSourcePaths = new Set(triggers.map((trigger) => trigger.sourcePath));
    const targets = [];
    for (const target of [...rule.targets].sort((left, right) =>
      compareText(left.documentId, right.documentId)
    )) {
      totalTargets += 1;
      if (totalTargets > LIMITS.maxTotalObligationTargets) {
        throw new AnalysisLimitError(
          `Documentation targets exceed canonical limit ${LIMITS.maxTotalObligationTargets}`
        );
      }
      const document = documentById.get(target.documentId);
      const outputTarget = {
        documentId: document.id,
        path: document.path,
        role: document.role,
        publicationBindings: outputPublicationBindings(publicationById, document),
        basePresence: document.basePresence,
        ownerIds: sortedUnique(document.ownerIds),
        acceptedChangeKinds: sortedUnique(target.acceptedChangeKinds)
      };
      outputBudget.consume(outputTarget, `documentation target ${document.id}`);
      targets.push(outputTarget);
    }
    const acceptedTargetChanges = [];
    for (const target of targets) {
      const targetChange = changedByPath.get(target.path);
      if (targetChange
          && !triggeringSourcePaths.has(target.path)
          && target.acceptedChangeKinds.includes(targetChange.changeKind)) {
        const evidence = {
          documentId: target.documentId,
          path: target.path,
          changeKind: targetChange.changeKind
        };
        outputBudget.consume(evidence, `accepted target change ${target.documentId}`);
        acceptedTargetChanges.push(evidence);
      }
    }
    const action = {
      actionType: ACTION_TYPE.DOCUMENTATION,
      ruleId: rule.id,
      triggers,
      targets,
      acceptedTargetChanges,
      checkIds: sortedUnique(rule.checkIds),
      ownerIds: sortedUnique(rule.ownerIds),
      candidateState: acceptedTargetChanges.length === targets.length
        ? CANDIDATE_STATE.CANDIDATE_CHANGE_PRESENT
        : CANDIDATE_STATE.ACTION_REQUIRED
    };
    const obligation = {
      obligationId: canonicalDigest({ analysisId, ...action }),
      ...action
    };
    outputBudget.consume({
      obligationId: obligation.obligationId,
      actionType: obligation.actionType,
      ruleId: obligation.ruleId,
      checkIds: obligation.checkIds,
      ownerIds: obligation.ownerIds,
      candidateState: obligation.candidateState
    }, `documentation obligation ${rule.id}`);
    obligations.push(obligation);
  }
  return obligations;
}

function buildPublicationValidations(
  analysisId,
  policy,
  changedByPath,
  outputBudget
) {
  const documentBindingByPublicationAndPath = new Map();
  for (const document of policy.documents) {
    for (const binding of document.publicationBindings) {
      documentBindingByPublicationAndPath.set(
        `${binding.publicationId}\u0000${document.path}`,
        {
          documentId: document.id,
          artifactPath: binding.artifactPath,
          ownerIds: document.ownerIds
        }
      );
    }
  }
  const validations = [];
  let totalPublicationTriggers = 0;
  const changes = [...changedByPath.entries()]
    .sort(([leftPath], [rightPath]) => compareText(leftPath, rightPath));
  for (const publication of [...policy.publications].sort((left, right) =>
    compareText(left.id, right.id)
  )) {
    const triggers = [];
    const matchedDocumentOwnerIds = [];
    for (const [changedPath, changed] of changes) {
      const triggerKinds = [];
      if (publication.contentInputPaths.some((pattern) => pathMatches(pattern, changedPath))) {
        triggerKinds.push(PUBLICATION_TRIGGER_KIND.CONTENT_INPUT);
      }
      if (publication.producerPaths.some((pattern) => pathMatches(pattern, changedPath))) {
        triggerKinds.push(PUBLICATION_TRIGGER_KIND.PRODUCER_INPUT);
      }
      const boundDocument = documentBindingByPublicationAndPath.get(
        `${publication.id}\u0000${changedPath}`
      );
      const documentBindings = [];
      if (boundDocument) {
        triggerKinds.push(PUBLICATION_TRIGGER_KIND.DOCUMENT_BINDING);
        documentBindings.push({
          documentId: boundDocument.documentId,
          artifactPath: boundDocument.artifactPath
        });
        matchedDocumentOwnerIds.push(...boundDocument.ownerIds);
      }
      if (triggerKinds.length === 0) {
        continue;
      }
      totalPublicationTriggers += 1;
      if (totalPublicationTriggers > LIMITS.maxTotalPublicationTriggers) {
        throw new AnalysisLimitError(
          `Publication triggers exceed canonical limit ${LIMITS.maxTotalPublicationTriggers}`
        );
      }
      const trigger = {
        path: changedPath,
        changeKind: changed.changeKind,
        triggerKinds: sortedUnique(triggerKinds),
        documentBindings: sortedUniqueObjects(documentBindings)
      };
      outputBudget.consume(
        trigger,
        `publication trigger ${publication.id}:${changedPath}`
      );
      triggers.push(trigger);
    }
    if (triggers.length === 0) {
      continue;
    }
    if (validations.length >= LIMITS.maxPublicationValidations) {
      throw new AnalysisLimitError(
        `Publication validations exceed canonical limit ${LIMITS.maxPublicationValidations}`
      );
    }
    const action = {
      actionType: ACTION_TYPE.PUBLICATION_VALIDATION,
      publicationId: publication.id,
      kind: publication.kind,
      locatorKind: publication.locatorKind,
      contentRoot: publication.contentRoot,
      artifactSelector: publication.artifactSelector,
      producerPaths: sortedUnique(publication.producerPaths),
      contentInputPaths: sortedUnique(publication.contentInputPaths),
      triggers,
      checkIds: sortedUnique(publication.checkIds),
      ownerIds: sortedUnique([...publication.ownerIds, ...matchedDocumentOwnerIds]),
      candidateState: CANDIDATE_STATE.ACTION_REQUIRED
    };
    const validation = {
      validationId: canonicalDigest({ analysisId, ...action }),
      ...action
    };
    outputBudget.consume({
      validationId: validation.validationId,
      actionType: validation.actionType,
      publicationId: validation.publicationId,
      kind: validation.kind,
      locatorKind: validation.locatorKind,
      contentRoot: validation.contentRoot,
      artifactSelector: validation.artifactSelector,
      producerPaths: validation.producerPaths,
      contentInputPaths: validation.contentInputPaths,
      checkIds: validation.checkIds,
      ownerIds: validation.ownerIds,
      candidateState: validation.candidateState
    }, `publication validation ${publication.id}`);
    validations.push(validation);
  }
  return validations;
}

function buildGovernanceReviews(analysisId, policy, governanceChangesByRuleId, outputBudget) {
  const reviews = [];
  const rules = policy.protectionRules
    .filter((rule) => governanceChangesByRuleId.has(rule.id))
    .sort((left, right) => compareText(left.id, right.id));
  for (const rule of rules) {
    const changes = sortedUniqueObjects(governanceChangesByRuleId.get(rule.id));
    const action = {
      actionType: ACTION_TYPE.GOVERNANCE_REVIEW,
      protectionRuleId: rule.id,
      kind: rule.kind,
      changes,
      ownerIds: sortedUnique(rule.ownerIds),
      candidateState: CANDIDATE_STATE.ACTION_REQUIRED
    };
    const review = {
      reviewId: canonicalDigest({ analysisId, ...action }),
      ...action
    };
    outputBudget.consume({
      reviewId: review.reviewId,
      actionType: review.actionType,
      protectionRuleId: review.protectionRuleId,
      kind: review.kind,
      ownerIds: review.ownerIds,
      candidateState: review.candidateState
    }, `governance review ${rule.id}`);
    reviews.push(review);
  }
  return reviews;
}

export async function classifyRepository({
  repoRoot,
  gitExecutable,
  repositoryId,
  base,
  head,
  policyPath
}) {
  assertRepositoryPath(policyPath, "policy path");
  const analysisSchema = JSON.parse(ANALYSIS_UTF8_DECODER.decode(
    await readFile(ANALYSIS_SCHEMA_PATH)
  ));
  assertAnalysisSchemaProjections(analysisSchema);
  const repositoryRoot = assertRepositoryRoot(repoRoot, gitExecutable);
  const resolved = {
    baseSha: resolveCommit(repositoryRoot, base, gitExecutable),
    headSha: resolveCommit(repositoryRoot, head, gitExecutable),
    mergeBaseSha: null
  };
  assertObjectGraphIntegrity(
    repositoryRoot,
    [resolved.baseSha, resolved.headSha],
    gitExecutable
  );
  const { policy, policyDigest } = await parsePolicyBytes(
    readBlob(repositoryRoot, resolved.baseSha, policyPath, gitExecutable)
  );
  if (repositoryId !== policy.repositoryId) {
    throw new Error(
      `Explicit repository ID ${JSON.stringify(repositoryId)} does not match policy repository ID ${JSON.stringify(policy.repositoryId)}`
    );
  }

  const errors = new BoundedPolicyErrors();
  let baseEntries = [];
  let baseInventory = new Map();
  let baseImpactNodes = new Map();
  let mergeBaseInventory = new Map();
  let mergeBaseImpactNodes = new Map();
  let toolDigest = canonicalDigest([]);

  try {
    resolved.mergeBaseSha = findMergeBase(
      repositoryRoot,
      resolved.baseSha,
      resolved.headSha,
      gitExecutable
    );
  } catch (error) {
    if (!(error instanceof GitReadError)) {
      throw error;
    }
    errors.push(policyError(REASON.GIT_IDENTITY_ERROR, error.message));
  }

  try {
    baseEntries = readTreeEntries(repositoryRoot, resolved.baseSha, gitExecutable);
    assertTreeObjectsAvailable(repositoryRoot, baseEntries, gitExecutable);
    inspectPathIdentity(baseEntries, "base", errors);
    baseInventory = inspectInventory(baseEntries, "base", policy, errors);
    baseImpactNodes = inspectImpactNodes(
      baseEntries,
      "base",
      policy,
      baseInventory,
      errors
    );
    inspectRequiredBaseDocuments(policy, baseEntries, errors);
    toolDigest = buildTrustedToolDigest(policy, baseEntries, errors);
    if (resolved.mergeBaseSha === resolved.baseSha) {
      mergeBaseInventory = baseInventory;
      mergeBaseImpactNodes = baseImpactNodes;
    } else if (resolved.mergeBaseSha) {
      const mergeBaseEntries = readTreeEntries(
        repositoryRoot,
        resolved.mergeBaseSha,
        gitExecutable
      );
      assertTreeObjectsAvailable(repositoryRoot, mergeBaseEntries, gitExecutable);
      inspectPathIdentity(mergeBaseEntries, "merge base", errors);
      mergeBaseInventory = inspectInventory(mergeBaseEntries, "merge base", policy, errors);
      mergeBaseImpactNodes = inspectImpactNodes(
        mergeBaseEntries,
        "merge base",
        policy,
        mergeBaseInventory,
        errors
      );
    }
  } catch (error) {
    if (!(error instanceof GitReadError)) {
      throw error;
    }
    errors.push(policyError(REASON.GIT_IDENTITY_ERROR, error.message));
  }

  const identity = makeIdentity(
    policy,
    policyPath,
    policyDigest,
    toolDigest,
    base,
    head,
    resolved
  );
  const analysisId = canonicalDigest({
    schemaVersion: 2,
    toolVersion: TOOL_VERSION,
    ...identity
  });
  const outputBudget = new AnalysisOutputBudget({
    schemaVersion: 2,
    toolVersion: TOOL_VERSION,
    analysisId,
    identity
  });
  let changes = [];
  let documentationObligations = [];
  let publicationValidations = [];
  let governanceReviews = [];
  const triggersByRuleId = new Map();
  const governanceChangesByRuleId = new Map();

  if (errors.length === 0) {
    try {
      const headEntries = readTreeEntries(repositoryRoot, resolved.headSha, gitExecutable);
      assertTreeObjectsAvailable(repositoryRoot, headEntries, gitExecutable);
      inspectPathIdentity(headEntries, "head", errors);
      inspectRequiredHeadDocuments(policy, headEntries, errors);
      const headInventory = inspectInventory(headEntries, "head", policy, errors);
      const headImpactNodes = inspectImpactNodes(
        headEntries,
        "head",
        policy,
        headInventory,
        errors
      );
      const rawChanges = readChanges(
        repositoryRoot,
        resolved.mergeBaseSha,
        resolved.headSha,
        gitExecutable
      ).sort((left, right) =>
        compareText(left.path, right.path) || compareText(left.status, right.status)
      );
      const changedByPath = new Map();
      let totalImpactRoutes = 0;
      let totalGovernanceMatches = 0;
      let totalTriggers = 0;
      let limitExceeded = false;

      changeLoop: for (const change of rawChanges) {
        let pathIsSafe = true;
        try {
          assertRepositoryPath(change.path, "changed path");
        } catch (error) {
          pathIsSafe = false;
          errors.push(policyError(REASON.INVALID_REPOSITORY_PATH, error.message, {
            paths: [change.path]
          }));
        }
        const changeKind = mapGitStatusToChangeKind(change.status);
        if (!SUPPORTED_CHANGE_STATUS.has(change.status) || !changeKind) {
          errors.push(policyError(
            REASON.UNSUPPORTED_GIT_CHANGE,
            `Changed path uses unsupported Git status ${change.status}`,
            { paths: [change.path] }
          ));
        } else {
          changedByPath.set(change.path, { changeKind });
        }
        const inventoryRule = change.status === CHANGE_STATUS.D
          ? mergeBaseInventory.get(change.path)
          : headInventory.get(change.path);
        const impactNode = change.status === CHANGE_STATUS.D
          ? mergeBaseImpactNodes.get(change.path)
          : headImpactNodes.get(change.path);

        if (!inventoryRule) {
          errors.push(policyError(
            REASON.UNMAPPED_INVENTORY_PATH,
            "Changed path does not have one inventory classification",
            { paths: [change.path] }
          ));
        }

        let routes = impactNode && changeKind && pathIsSafe
          ? resolveImpactRoutes(policy, impactNode.id, changeKind)
          : [];
        totalImpactRoutes += routes.length;
        if (totalImpactRoutes > LIMITS.maxTotalImpactRoutes) {
          errors.push(policyError(
            REASON.ANALYSIS_LIMIT_EXCEEDED,
            `Impact routes exceed canonical limit ${LIMITS.maxTotalImpactRoutes}`
          ));
          routes = [];
          limitExceeded = true;
          break;
        }
        if (changeKind && pathIsSafe) {
          for (const protectionRule of policy.protectionRules) {
            if (protectionRule.paths.some((pattern) => pathMatches(pattern, change.path))) {
              totalGovernanceMatches += 1;
              if (totalGovernanceMatches > LIMITS.maxTotalGovernanceMatches) {
                errors.push(policyError(
                  REASON.ANALYSIS_LIMIT_EXCEEDED,
                  `Governance matches exceed canonical limit ${LIMITS.maxTotalGovernanceMatches}`
                ));
                limitExceeded = true;
                break changeLoop;
              }
              const matched = governanceChangesByRuleId.get(protectionRule.id) ?? [];
              const governanceEvidence = { path: change.path, changeKind };
              outputBudget.consume(
                governanceEvidence,
                `governance evidence ${protectionRule.id}`
              );
              matched.push(governanceEvidence);
              governanceChangesByRuleId.set(protectionRule.id, matched);
            }
          }
          for (const route of routes) {
            for (const documentationRule of policy.documentationRules) {
              if (!documentationRule.impactNodeIds.includes(route.impactNodeId)
                  || !documentationRule.impactDepths.includes(route.impactDepth)
                  || !documentationRule.sourceChangeKinds.includes(changeKind)) {
                continue;
              }
              totalTriggers += 1;
              if (totalTriggers > LIMITS.maxTotalTriggers) {
                errors.push(policyError(
                  REASON.ANALYSIS_LIMIT_EXCEEDED,
                  `Documentation triggers exceed canonical limit ${LIMITS.maxTotalTriggers}`
                ));
                limitExceeded = true;
                break changeLoop;
              }
              const triggers = triggersByRuleId.get(documentationRule.id) ?? [];
              const trigger = {
                sourcePath: change.path,
                sourceChangeKind: changeKind,
                impactNodeId: route.impactNodeId,
                impactDepth: route.impactDepth,
                viaEdgeIds: route.viaEdgeIds
              };
              outputBudget.consume(trigger, `documentation trigger ${documentationRule.id}`);
              triggers.push(trigger);
              triggersByRuleId.set(documentationRule.id, triggers);
            }
          }
        }

        const outputChange = {
          path: change.path,
          status: change.status,
          changeKind,
          oldMode: change.oldMode,
          newMode: change.newMode,
          inventoryRuleId: inventoryRule?.id ?? null,
          inventoryClassification: inventoryRule?.classification ?? null,
          impactNodeId: impactNode?.id ?? null,
          componentId: impactNode?.componentId ?? null,
          reachableImpactNodes: routes
        };
        outputBudget.consume(outputChange, `changed path ${change.path}`);
        changes.push(outputChange);
      }

      if (limitExceeded) {
        changes = [];
      } else {
        documentationObligations = buildDocumentationObligations(
          analysisId,
          policy,
          triggersByRuleId,
          changedByPath,
          outputBudget
        );
        publicationValidations = buildPublicationValidations(
          analysisId,
          policy,
          changedByPath,
          outputBudget
        );
        governanceReviews = buildGovernanceReviews(
          analysisId,
          policy,
          governanceChangesByRuleId,
          outputBudget
        );
      }
    } catch (error) {
      if (error instanceof AnalysisLimitError) {
        errors.push(policyError(REASON.ANALYSIS_LIMIT_EXCEEDED, error.message));
        changes = [];
        documentationObligations = [];
        publicationValidations = [];
        governanceReviews = [];
      } else if (!(error instanceof GitReadError)) {
        throw error;
      } else {
        errors.push(policyError(REASON.GIT_IDENTITY_ERROR, error.message));
      }
    }
  }

  let finalErrors = deduplicateErrors(errors);
  if (finalErrors.length > 0) {
    documentationObligations = [];
    publicationValidations = [];
    governanceReviews = [];
  }
  const actionReasonCodes = [];
  if (documentationObligations.length > 0) {
    actionReasonCodes.push(REASON.DOCUMENTATION_OBLIGATION_IDENTIFIED);
  }
  if (publicationValidations.length > 0) {
    actionReasonCodes.push(REASON.PUBLICATION_VALIDATION_REQUIRED);
  }
  if (governanceReviews.length > 0) {
    actionReasonCodes.push(REASON.GOVERNANCE_REVIEW_REQUIRED);
  }
  const hasActions = documentationObligations.length > 0
    || publicationValidations.length > 0
    || governanceReviews.length > 0;
  let classification = finalErrors.length > 0
    ? CLASSIFICATION.POLICY_ERROR
    : hasActions
      ? CLASSIFICATION.ACTION_REQUIRED
      : CLASSIFICATION.NO_ACTION_REQUIRED;
  let reasonCodes = finalErrors.length > 0
    ? sortedUnique([...finalErrors.map((error) => error.code), ...actionReasonCodes])
    : hasActions
      ? sortedUnique(actionReasonCodes)
      : [REASON.NO_ACTION_REQUIRED];
  let analysis = {
    schemaVersion: 2,
    toolVersion: TOOL_VERSION,
    analysisId,
    identity,
    classification,
    reasonCodes,
    policyErrors: finalErrors,
    changes,
    documentationObligations,
    publicationValidations,
    governanceReviews
  };
  if (canonicalJsonByteLength(analysis) > LIMITS.maxAnalysisBytes) {
    finalErrors = [policyError(
      REASON.ANALYSIS_LIMIT_EXCEEDED,
      `Canonical analysis exceeds ${LIMITS.maxAnalysisBytes} bytes`
    )];
    changes = [];
    documentationObligations = [];
    publicationValidations = [];
    governanceReviews = [];
    classification = CLASSIFICATION.POLICY_ERROR;
    reasonCodes = sortedUnique(finalErrors.map((error) => error.code));
    analysis = {
      ...analysis,
      classification,
      reasonCodes,
      policyErrors: finalErrors,
      changes,
      documentationObligations,
      publicationValidations,
      governanceReviews
    };
  }
  assertSchema(analysisSchema, analysis, "Documentation impact analysis");
  assertAnalysisSemantics(analysis);
  return analysis;
}
