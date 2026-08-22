import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { TextDecoder } from "node:util";
import { parseDocument } from "yaml";
import { canonicalDigest } from "./canonical.mjs";
import { assertPolicySchemaProjections } from "./contracts/projections.mjs";
import {
  CHANGE_KIND,
  IMPACT_DEPTH,
  INVENTORY_CLASSIFICATION,
  LIMITS,
  PUBLICATION,
  PUBLICATION_LOCATOR,
  PROTECTION_KIND
} from "./constants.mjs";
import { resolveImpactRoutes } from "./impact-graph.mjs";
import { assertSchema } from "./schema-validator.mjs";

const POLICY_SCHEMA_PATH = fileURLToPath(
  new URL("../../docs/ci/docs-impact-map.schema.json", import.meta.url)
);

const POLICY_UTF8_DECODER = new TextDecoder("utf-8", { fatal: true });
const WINDOWS_RESERVED_NAME = /^(?:CON|PRN|AUX|NUL|CLOCK\$|CONIN\$|CONOUT\$|COM(?:[1-9]|[\u00b9\u00b2\u00b3])|LPT(?:[1-9]|[\u00b9\u00b2\u00b3]))$/iu;
const CONTROL_CHARACTER = /[\u0000-\u001f\u007f]/u;
const FORMAT_CHARACTER = /\p{Cf}/u;
const WINDOWS_INVALID_CHARACTER = /[<>:"|?*]/u;

export function assertRepositoryPath(value, label = "repository path") {
  if (typeof value !== "string" || value.length === 0) {
    throw new Error(`${label} must be a non-empty string`);
  }
  if (value !== value.normalize("NFC")) {
    throw new Error(`${label} must use Unicode NFC: ${JSON.stringify(value)}`);
  }
  if (Buffer.byteLength(value, "utf8") > LIMITS.maxPathUtf8Bytes) {
    throw new Error(`${label} exceeds ${LIMITS.maxPathUtf8Bytes} UTF-8 bytes`);
  }
  if (value.startsWith("/") || value.includes("\\") || CONTROL_CHARACTER.test(value)
      || FORMAT_CHARACTER.test(value)
      || WINDOWS_INVALID_CHARACTER.test(value)) {
    throw new Error(`${label} is not a safe Git POSIX path: ${JSON.stringify(value)}`);
  }
  const segments = value.split("/");
  if (segments.some((segment) => segment.length === 0 || segment === "." || segment === "..")) {
    throw new Error(`${label} contains an empty or traversal segment: ${JSON.stringify(value)}`);
  }
  for (const segment of segments) {
    if (segment.toLowerCase() === ".git") {
      throw new Error(`${label} contains a reserved Git metadata segment: ${JSON.stringify(value)}`);
    }
    if (Buffer.byteLength(segment, "utf8") > LIMITS.maxPathSegmentUtf8Bytes) {
      throw new Error(
        `${label} contains a segment over ${LIMITS.maxPathSegmentUtf8Bytes} UTF-8 bytes`
      );
    }
    if (segment.endsWith(".") || segment.endsWith(" ")) {
      throw new Error(`${label} is ambiguous on Windows: ${JSON.stringify(value)}`);
    }
    const stem = segment.split(".", 1)[0];
    if (WINDOWS_RESERVED_NAME.test(stem)) {
      throw new Error(`${label} uses a Windows reserved name: ${JSON.stringify(value)}`);
    }
  }
  return value;
}

export function assertDocsSiteRoute(value, label = "documentation site route") {
  if (typeof value !== "string" || value.length === 0) {
    throw new Error(`${label} must be a non-empty string`);
  }
  if (value !== value.normalize("NFC")) {
    throw new Error(`${label} must use Unicode NFC: ${JSON.stringify(value)}`);
  }
  if (Buffer.byteLength(value, "utf8") > LIMITS.maxPathUtf8Bytes) {
    throw new Error(`${label} exceeds ${LIMITS.maxPathUtf8Bytes} UTF-8 bytes`);
  }
  if (!value.startsWith("/") || value.includes("\\") || value.includes("?")
      || value.includes("#") || value.includes("*") || CONTROL_CHARACTER.test(value)
      || FORMAT_CHARACTER.test(value)) {
    throw new Error(`${label} is not a safe absolute route: ${JSON.stringify(value)}`);
  }
  if (value !== "/" && value.endsWith("/")) {
    throw new Error(`${label} must omit a trailing slash except for the root route`);
  }
  const segments = value === "/" ? [] : value.slice(1).split("/");
  if (segments.some((segment) => segment.length === 0 || segment === "." || segment === "..")) {
    throw new Error(`${label} contains an empty or traversal segment: ${JSON.stringify(value)}`);
  }
  for (const segment of segments) {
    if (!/^[A-Za-z0-9._~-]+$/u.test(segment)) {
      throw new Error(`${label} contains a non-canonical route segment: ${JSON.stringify(value)}`);
    }
    if (Buffer.byteLength(segment, "utf8") > LIMITS.maxPathSegmentUtf8Bytes) {
      throw new Error(
        `${label} contains a segment over ${LIMITS.maxPathSegmentUtf8Bytes} UTF-8 bytes`
      );
    }
  }
  return value;
}

function assertBoundedDescriptor(value, label) {
  if (typeof value !== "string" || value.length === 0) {
    throw new Error(`${label} must be a non-empty string`);
  }
  if (value !== value.normalize("NFC") || CONTROL_CHARACTER.test(value)
      || FORMAT_CHARACTER.test(value)) {
    throw new Error(`${label} must be normalized text without control or format characters`);
  }
  if (Buffer.byteLength(value, "utf8") > LIMITS.maxPathUtf8Bytes) {
    throw new Error(`${label} exceeds ${LIMITS.maxPathUtf8Bytes} UTF-8 bytes`);
  }
  return value;
}

export function assertPathPattern(pattern, label = "path pattern") {
  const base = pattern.endsWith("/**") ? pattern.slice(0, -3) : pattern;
  if (base.includes("*")) {
    throw new Error(`${label} may use only a terminal /** wildcard: ${pattern}`);
  }
  assertRepositoryPath(base, label);
  return pattern;
}

export function pathMatches(pattern, repositoryPath) {
  if (pattern.endsWith("/**")) {
    const prefix = pattern.slice(0, -3);
    return repositoryPath.startsWith(`${prefix}/`);
  }
  return repositoryPath === pattern;
}

export function patternsOverlap(left, right) {
  const leftPrefix = left.endsWith("/**");
  const rightPrefix = right.endsWith("/**");
  const leftBase = leftPrefix ? left.slice(0, -3) : left;
  const rightBase = rightPrefix ? right.slice(0, -3) : right;
  if (!leftPrefix && !rightPrefix) {
    return leftBase === rightBase;
  }
  if (leftPrefix && rightPrefix) {
    return leftBase === rightBase
      || leftBase.startsWith(`${rightBase}/`)
      || rightBase.startsWith(`${leftBase}/`);
  }
  const exact = leftPrefix ? rightBase : leftBase;
  const prefix = leftPrefix ? leftBase : rightBase;
  return exact.startsWith(`${prefix}/`);
}

export function patternContains(container, candidate) {
  const containerIsPrefix = container.endsWith("/**");
  const candidateIsPrefix = candidate.endsWith("/**");
  const containerBase = containerIsPrefix ? container.slice(0, -3) : container;
  const candidateBase = candidateIsPrefix ? candidate.slice(0, -3) : candidate;
  if (!containerIsPrefix) {
    return !candidateIsPrefix && containerBase === candidateBase;
  }
  return (candidateIsPrefix && candidateBase === containerBase)
    || candidateBase.startsWith(`${containerBase}/`);
}

function compareText(left, right) {
  return left < right ? -1 : left > right ? 1 : 0;
}

function assertUniqueIds(values, label) {
  const seen = new Set();
  for (const value of values) {
    if (seen.has(value.id)) {
      throw new Error(`${label} contains duplicate id ${value.id}`);
    }
    seen.add(value.id);
  }
}

function assertNoRuleOverlap(rules, pathField, label) {
  for (let leftIndex = 0; leftIndex < rules.length; leftIndex += 1) {
    for (let rightIndex = leftIndex + 1; rightIndex < rules.length; rightIndex += 1) {
      for (const leftPattern of rules[leftIndex][pathField]) {
        for (const rightPattern of rules[rightIndex][pathField]) {
          if (patternsOverlap(leftPattern, rightPattern)) {
            throw new Error(
              `${label} rules ${rules[leftIndex].id} and ${rules[rightIndex].id} overlap at ${leftPattern} / ${rightPattern}`
            );
          }
        }
      }
    }
  }
}

function rulesContainingPattern(rules, pathField, pattern) {
  return rules.filter((rule) =>
    rule[pathField].some((container) => patternContains(container, pattern))
  );
}

function requireReferences(references, knownIds, label) {
  for (const reference of references) {
    if (!knownIds.has(reference)) {
      throw new Error(`${label} references unknown id ${reference}`);
    }
  }
}

function assertCompleteEdgeDecisions(edge) {
  const counts = new Map(Object.values(CHANGE_KIND).map((kind) => [kind, 0]));
  for (const decision of edge.decisions) {
    for (const kind of decision.changeKinds) {
      counts.set(kind, (counts.get(kind) ?? 0) + 1);
    }
  }
  const missing = [];
  const repeated = [];
  for (const [kind, count] of counts) {
    if (count === 0) {
      missing.push(kind);
    } else if (count > 1) {
      repeated.push(kind);
    }
  }
  if (missing.length > 0 || repeated.length > 0) {
    throw new Error(
      `impact edge ${edge.id} decisions must cover every change kind exactly once; missing=${missing.sort(compareText).join(",") || "none"}; repeated=${repeated.sort(compareText).join(",") || "none"}`
    );
  }
}

function assertAcyclicImpactGraph(policy) {
  const outgoing = new Map(policy.impactNodes.map((node) => [node.id, []]));
  const inDegree = new Map(policy.impactNodes.map((node) => [node.id, 0]));
  for (const edge of policy.impactEdges) {
    outgoing.get(edge.fromNodeId).push(edge.toNodeId);
    inDegree.set(edge.toNodeId, inDegree.get(edge.toNodeId) + 1);
  }
  for (const targets of outgoing.values()) {
    targets.sort(compareText);
  }
  const queue = [...inDegree]
    .filter(([, degree]) => degree === 0)
    .map(([nodeId]) => nodeId)
    .sort(compareText);
  const longestDepth = new Map(policy.impactNodes.map((node) => [node.id, 0]));
  let visited = 0;
  while (queue.length > 0) {
    const nodeId = queue.shift();
    visited += 1;
    for (const targetId of outgoing.get(nodeId)) {
      longestDepth.set(
        targetId,
        Math.max(longestDepth.get(targetId), longestDepth.get(nodeId) + 1)
      );
      const remaining = inDegree.get(targetId) - 1;
      inDegree.set(targetId, remaining);
      if (remaining === 0) {
        queue.push(targetId);
        queue.sort(compareText);
      }
    }
  }
  if (visited !== policy.impactNodes.length) {
    throw new Error("impactEdges must form a directed acyclic graph");
  }
  const graphDepth = Math.max(0, ...longestDepth.values());
  if (graphDepth > LIMITS.maxTraversalDepth) {
    throw new Error(
      `impact graph depth ${graphDepth} exceeds canonical limit ${LIMITS.maxTraversalDepth}`
    );
  }
}

export function assertPolicySemantics(policy) {
  for (const pattern of policy.trustedToolPaths) {
    assertPathPattern(pattern, "trusted tool path");
  }
  for (const collection of [
    "owners",
    "checks",
    "publications",
    "inventoryRules",
    "components",
    "impactNodes",
    "impactEdges",
    "documents",
    "documentationRules",
    "protectionRules"
  ]) {
    assertUniqueIds(policy[collection], collection);
  }
  assertNoRuleOverlap(policy.inventoryRules, "paths", "inventory");
  assertNoRuleOverlap(policy.impactNodes, "sourcePaths", "impact node");

  for (const rule of policy.inventoryRules) {
    for (const pattern of rule.paths) {
      assertPathPattern(pattern, `inventory rule ${rule.id}`);
    }
  }
  for (const trustedToolPattern of policy.trustedToolPaths) {
    if (rulesContainingPattern(policy.inventoryRules, "paths", trustedToolPattern).length !== 1) {
      throw new Error(
        `trusted tool path ${trustedToolPattern} must be contained by exactly one inventory rule`
      );
    }
  }

  const knownComponentIds = new Set(policy.components.map((component) => component.id));
  const knownImpactNodeIds = new Set(policy.impactNodes.map((node) => node.id));
  const knownDocumentIds = new Set(policy.documents.map((document) => document.id));
  const publicationById = new Map(
    policy.publications.map((publication) => [publication.id, publication])
  );
  const knownPublicationIds = new Set(publicationById.keys());
  const knownOwnerIds = new Set(policy.owners.map((owner) => owner.id));
  const knownCheckIds = new Set(policy.checks.map((check) => check.id));

  for (const publication of policy.publications) {
    requireReferences(
      publication.checkIds,
      knownCheckIds,
      `publication ${publication.id} checks`
    );
    requireReferences(
      publication.ownerIds,
      knownOwnerIds,
      `publication ${publication.id} owners`
    );
    const allowedLocatorKinds = {
      [PUBLICATION.REPOSITORY]: [PUBLICATION_LOCATOR.REPOSITORY_PATH],
      [PUBLICATION.DOCS_SITE]: [PUBLICATION_LOCATOR.DOCUSAURUS_ROUTE],
      [PUBLICATION.PACKAGE]: [
        PUBLICATION_LOCATOR.ARCHIVE_ENTRY,
        PUBLICATION_LOCATOR.NPM_PACKAGE_PATH,
        PUBLICATION_LOCATOR.VSIX_EXTENSION_PATH,
        PUBLICATION_LOCATOR.CLASSPATH_RESOURCE,
        PUBLICATION_LOCATOR.IMAGE_FS_PATH
      ],
      [PUBLICATION.CLIENT_CONFIG]: [PUBLICATION_LOCATOR.CLIENT_CONFIG_PATH]
    }[publication.kind];
    if (!allowedLocatorKinds.includes(publication.locatorKind)) {
      throw new Error(
        `publication ${publication.id} kind ${publication.kind} cannot use locator ${publication.locatorKind}`
      );
    }
    if (publication.contentRoot !== "/") {
      assertRepositoryPath(publication.contentRoot, `publication ${publication.id} content root`);
    }
    assertBoundedDescriptor(
      publication.artifactSelector,
      `publication ${publication.id} artifact selector`
    );
    if (publication.kind !== PUBLICATION.REPOSITORY && publication.producerPaths.length === 0) {
      throw new Error(`publication ${publication.id} must declare at least one producer path`);
    }
    for (const producerPath of publication.producerPaths) {
      assertPathPattern(producerPath, `publication ${publication.id} producer path`);
      if (rulesContainingPattern(policy.inventoryRules, "paths", producerPath).length !== 1) {
        throw new Error(
          `publication ${publication.id} producer path ${producerPath} must be contained by exactly one inventory rule`
        );
      }
    }
    for (const contentInputPath of publication.contentInputPaths) {
      assertPathPattern(
        contentInputPath,
        `publication ${publication.id} content input path`
      );
      if (rulesContainingPattern(
        policy.inventoryRules,
        "paths",
        contentInputPath
      ).length !== 1) {
        throw new Error(
          `publication ${publication.id} content input path ${contentInputPath} must be contained by exactly one inventory rule`
        );
      }
    }
  }
  for (const component of policy.components) {
    requireReferences(component.ownerIds, knownOwnerIds, `component ${component.id}`);
  }
  for (const node of policy.impactNodes) {
    requireReferences([node.componentId], knownComponentIds, `impact node ${node.id}`);
    const evaluatedKinds = new Set(node.evaluateChangeKinds);
    const noDocumentationKinds = new Set(node.noDocumentationChangeKinds);
    const repeatedKinds = [...evaluatedKinds].filter((kind) => noDocumentationKinds.has(kind));
    const allKinds = new Set([...evaluatedKinds, ...noDocumentationKinds]);
    const missingKinds = Object.values(CHANGE_KIND).filter((kind) => !allKinds.has(kind));
    if (repeatedKinds.length > 0 || missingKinds.length > 0) {
      throw new Error(
        `impact node ${node.id} must partition every change kind exactly once between evaluateChangeKinds and noDocumentationChangeKinds; missing=${missingKinds.sort(compareText).join(",") || "none"}; repeated=${repeatedKinds.sort(compareText).join(",") || "none"}`
      );
    }
    for (const pattern of node.sourcePaths) {
      assertPathPattern(pattern, `impact node ${node.id}`);
      const inventoryMatches = rulesContainingPattern(policy.inventoryRules, "paths", pattern);
      if (inventoryMatches.length !== 1) {
        throw new Error(
          `impact node ${node.id} path ${pattern} must be contained by exactly one inventory rule`
        );
      }
      if (inventoryMatches[0].classification === INVENTORY_CLASSIFICATION.NO_DOC_IMPACT) {
        throw new Error(
          `impact node ${node.id} path ${pattern} cannot belong to NO_DOC_IMPACT inventory`
        );
      }
    }
  }
  for (const component of policy.components) {
    if (!policy.impactNodes.some((node) => node.componentId === component.id)) {
      throw new Error(`component ${component.id} must own at least one impact node`);
    }
  }
  for (const rule of policy.inventoryRules.filter(
    (candidate) => candidate.classification === INVENTORY_CLASSIFICATION.MATERIAL
  )) {
    for (const pattern of rule.paths) {
      const coveringNodes = policy.impactNodes.filter((node) =>
        node.sourcePaths.some((nodePattern) => patternContains(nodePattern, pattern))
      );
      if (coveringNodes.length !== 1) {
        throw new Error(
          `MATERIAL inventory path ${pattern} must be contained by exactly one impact node`
        );
      }
    }
  }

  const edgeIdentities = new Set();
  for (const edge of policy.impactEdges) {
    requireReferences(
      [edge.fromNodeId, edge.toNodeId],
      knownImpactNodeIds,
      `impact edge ${edge.id}`
    );
    if (edge.fromNodeId === edge.toNodeId) {
      throw new Error(`impact edge ${edge.id} cannot reference the same source and target node`);
    }
    const identity = `${edge.fromNodeId}\u0000${edge.toNodeId}`;
    if (edgeIdentities.has(identity)) {
      throw new Error(
        `impact edge ${edge.id} duplicates connection from ${edge.fromNodeId} to ${edge.toNodeId}`
      );
    }
    edgeIdentities.add(identity);
    assertCompleteEdgeDecisions(edge);
  }
  assertAcyclicImpactGraph(policy);

  const documentPaths = new Set();
  const publicationArtifactOwners = new Map();
  for (const document of policy.documents) {
    requireReferences(document.ownerIds, knownOwnerIds, `document ${document.id}`);
    const publicationIds = document.publicationBindings.map(
      (binding) => binding.publicationId
    );
    if (new Set(publicationIds).size !== publicationIds.length) {
      throw new Error(
        `document ${document.id} must bind each publication id at most once`
      );
    }
    requireReferences(
      publicationIds,
      knownPublicationIds,
      `document ${document.id} publications`
    );
    assertRepositoryPath(document.path, `document ${document.id} path`);
    if (documentPaths.has(document.path)) {
      throw new Error(`documents contain duplicate path ${document.path}`);
    }
    documentPaths.add(document.path);
    for (const binding of document.publicationBindings) {
      const publication = publicationById.get(binding.publicationId);
      if (!publication.contentInputPaths.some((pattern) => pathMatches(pattern, document.path))) {
        throw new Error(
          `document ${document.id} publication ${binding.publicationId} source path ${document.path} must be covered by a publication content input path`
        );
      }
      const artifactPathLabel =
        `document ${document.id} publication ${binding.publicationId} artifact path`;
      if (publication.locatorKind === PUBLICATION_LOCATOR.DOCUSAURUS_ROUTE
          || publication.locatorKind === PUBLICATION_LOCATOR.IMAGE_FS_PATH) {
        assertDocsSiteRoute(binding.artifactPath, artifactPathLabel);
      } else {
        assertRepositoryPath(binding.artifactPath, artifactPathLabel);
      }
      if (publication.kind === PUBLICATION.REPOSITORY
          && binding.artifactPath !== document.path) {
        throw new Error(
          `document ${document.id} REPOSITORY publication ${binding.publicationId} artifact path must equal document path ${document.path}`
        );
      }
      const artifactIdentity = `${binding.publicationId}\u0000${binding.artifactPath.toLowerCase()}`;
      const existingDocumentId = publicationArtifactOwners.get(artifactIdentity);
      if (existingDocumentId) {
        throw new Error(
          `documents ${existingDocumentId} and ${document.id} bind ambiguous publication artifact ${binding.publicationId}:${binding.artifactPath}`
        );
      }
      publicationArtifactOwners.set(artifactIdentity, document.id);
    }
    const inventoryMatches = rulesContainingPattern(
      policy.inventoryRules,
      "paths",
      document.path
    );
    if (inventoryMatches.length !== 1
        || inventoryMatches[0].classification === INVENTORY_CLASSIFICATION.NO_DOC_IMPACT) {
      throw new Error(
        `document ${document.id} path ${document.path} must belong to DOCUMENTATION or MATERIAL inventory`
      );
    }
  }

  for (const rule of policy.documentationRules) {
    requireReferences(rule.impactNodeIds, knownImpactNodeIds, `documentation rule ${rule.id}`);
    requireReferences(rule.ownerIds, knownOwnerIds, `documentation rule ${rule.id}`);
    requireReferences(rule.checkIds, knownCheckIds, `documentation rule ${rule.id}`);
    const targetDocumentIds = rule.targets.map((target) => target.documentId);
    if (new Set(targetDocumentIds).size !== targetDocumentIds.length) {
      throw new Error(`documentation rule ${rule.id} contains duplicate target document ids`);
    }
    requireReferences(targetDocumentIds, knownDocumentIds, `documentation rule ${rule.id}`);
    if (rule.impactDepths.includes(IMPACT_DEPTH.SELF)) {
      for (const impactNodeId of rule.impactNodeIds) {
        const impactNode = policy.impactNodes.find((node) => node.id === impactNodeId);
        const contradictoryKinds = rule.sourceChangeKinds.filter((kind) =>
          impactNode.noDocumentationChangeKinds.includes(kind)
        );
        if (contradictoryKinds.length > 0) {
          throw new Error(
            `documentation rule ${rule.id} SELF sourceChangeKinds contradict impact node ${impactNodeId} noDocumentationChangeKinds ${contradictoryKinds.sort(compareText).join(",")}`
          );
        }
      }
    }
    const ruleOwnerIds = new Set(rule.ownerIds);
    const ruleCheckIds = new Set(rule.checkIds);
    for (const impactNodeId of rule.impactNodeIds) {
      const impactNode = policy.impactNodes.find((node) => node.id === impactNodeId);
      const component = policy.components.find((candidate) => candidate.id === impactNode.componentId);
      const missingComponentOwnerIds = component.ownerIds.filter(
        (ownerId) => !ruleOwnerIds.has(ownerId)
      );
      if (missingComponentOwnerIds.length > 0) {
        throw new Error(
          `documentation rule ${rule.id} must include impact component ${component.id} owners ${missingComponentOwnerIds.sort(compareText).join(",")}`
        );
      }
    }
    for (const targetDocumentId of targetDocumentIds) {
      const document = policy.documents.find((candidate) => candidate.id === targetDocumentId);
      const missingOwnerIds = document.ownerIds.filter((ownerId) => !ruleOwnerIds.has(ownerId));
      if (missingOwnerIds.length > 0) {
        throw new Error(
          `documentation rule ${rule.id} must include target document ${targetDocumentId} owners ${missingOwnerIds.sort(compareText).join(",")}`
        );
      }
      for (const { publicationId } of document.publicationBindings) {
        const publication = publicationById.get(publicationId);
        const missingPublicationOwnerIds = publication.ownerIds.filter(
          (ownerId) => !ruleOwnerIds.has(ownerId)
        );
        if (missingPublicationOwnerIds.length > 0) {
          throw new Error(
            `documentation rule ${rule.id} must include target document ${targetDocumentId} publication ${publicationId} owners ${missingPublicationOwnerIds.sort(compareText).join(",")}`
          );
        }
        const missingPublicationCheckIds = publication.checkIds.filter(
          (checkId) => !ruleCheckIds.has(checkId)
        );
        if (missingPublicationCheckIds.length > 0) {
          throw new Error(
            `documentation rule ${rule.id} must include target document ${targetDocumentId} publication ${publicationId} checks ${missingPublicationCheckIds.sort(compareText).join(",")}`
          );
        }
      }
    }
  }

  for (const node of policy.impactNodes) {
    for (const changeKind of node.evaluateChangeKinds) {
      const routes = resolveImpactRoutes(policy, node.id, changeKind);
      const matchingRules = policy.documentationRules.filter((rule) =>
        routes.some((route) =>
          rule.impactNodeIds.includes(route.impactNodeId)
          && rule.impactDepths.includes(route.impactDepth)
          && rule.sourceChangeKinds.includes(changeKind)
        )
      );
      if (matchingRules.length === 0) {
        throw new Error(
          `impact node ${node.id} evaluated change kind ${changeKind} must reach at least one matching documentation rule`
        );
      }
      for (const rule of matchingRules) {
        for (const target of rule.targets) {
          const document = policy.documents.find(
            (candidate) => candidate.id === target.documentId
          );
          if (node.sourcePaths.some((pattern) => pathMatches(pattern, document.path))) {
            throw new Error(
              `impact node ${node.id} evaluated change kind ${changeKind} triggers unsatisfiable documentation rule ${rule.id} targeting its own source ${document.path}`
            );
          }
        }
      }
    }
  }

  for (const rule of policy.protectionRules) {
    requireReferences(rule.ownerIds, knownOwnerIds, `protection rule ${rule.id}`);
    for (const pattern of rule.paths) {
      assertPathPattern(pattern, `protection rule ${rule.id}`);
      if (rulesContainingPattern(policy.inventoryRules, "paths", pattern).length !== 1) {
        throw new Error(
          `protection rule ${rule.id} path ${pattern} must be contained by exactly one inventory rule`
        );
      }
    }
  }
  for (const trustedToolPattern of policy.trustedToolPaths) {
    const controlRules = policy.protectionRules.filter((rule) =>
      rule.kind === PROTECTION_KIND.CONTROL
      && rule.paths.some((pattern) => patternContains(pattern, trustedToolPattern))
    );
    if (controlRules.length === 0) {
      throw new Error(
        `trusted tool path ${trustedToolPattern} must be contained by at least one CONTROL protection rule`
      );
    }
  }
}

export async function parsePolicyBytes(policyBytes, schemaPath = POLICY_SCHEMA_PATH) {
  const schemaBytes = await readFile(schemaPath);
  const normalizedPolicyBytes = Buffer.isBuffer(policyBytes)
    ? policyBytes
    : Buffer.from(policyBytes);
  if (normalizedPolicyBytes.length > LIMITS.maxPolicyBytes) {
    throw new Error(`Documentation impact policy exceeds ${LIMITS.maxPolicyBytes} bytes`);
  }
  let policyText;
  try {
    policyText = POLICY_UTF8_DECODER.decode(normalizedPolicyBytes);
  } catch {
    throw new Error("Documentation impact policy is not valid UTF-8");
  }
  const document = parseDocument(policyText, {
    maxAliasCount: LIMITS.maxYamlAliases,
    uniqueKeys: true
  });
  if (document.errors.length > 0) {
    throw new Error(`Documentation impact policy is invalid YAML:\n- ${document.errors.join("\n- ")}`);
  }
  const policy = document.toJS({ maxAliasCount: LIMITS.maxYamlAliases });
  let schemaText;
  try {
    schemaText = POLICY_UTF8_DECODER.decode(schemaBytes);
  } catch {
    throw new Error("Documentation impact policy schema is not valid UTF-8");
  }
  const schema = JSON.parse(schemaText);
  assertPolicySchemaProjections(schema);
  assertSchema(schema, policy, "Documentation impact policy");
  assertPolicySemantics(policy);
  return {
    policy,
    policyDigest: canonicalDigest(policy)
  };
}

export async function loadPolicy(policyPath, schemaPath = POLICY_SCHEMA_PATH) {
  return parsePolicyBytes(await readFile(policyPath), schemaPath);
}
