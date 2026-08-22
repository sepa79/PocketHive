import { CONTRACT_VALUES } from "../constants.mjs";

function compareText(left, right) {
  return left < right ? -1 : left > right ? 1 : 0;
}

function assertProjection(label, projected, valuesKey) {
  const expected = [...CONTRACT_VALUES[valuesKey]].sort(compareText);
  const actual = Array.isArray(projected) ? [...projected].sort(compareText) : [];
  if (JSON.stringify(actual) !== JSON.stringify(expected)) {
    throw new Error(
      `${label} must project the canonical ${valuesKey} values from tools/docs-impact/contracts/values.json`
    );
  }
}

function assertLimit(label, projected, limitKey) {
  if (projected !== CONTRACT_VALUES.limits[limitKey]) {
    throw new Error(
      `${label} must project canonical limit ${limitKey} from tools/docs-impact/contracts/values.json`
    );
  }
}

function assertValue(label, projected, expected) {
  if (projected !== expected) {
    throw new Error(`${label} must project canonical value ${expected}`);
  }
}

export function assertPolicySchemaProjections(schema) {
  if (schema.properties?.schemaVersion?.const !== 2) {
    throw new Error("policy schemaVersion must be exactly 2");
  }
  assertProjection("policy platformProfile", schema.properties?.platformProfile?.enum, "platformProfile");
  assertProjection("policy mode", schema.properties?.mode?.enum, "mode");
  assertProjection(
    "policy inventory classification",
    schema.properties?.inventoryRules?.items?.properties?.classification?.enum,
    "inventoryClassification"
  );
  assertProjection(
    "policy component kind",
    schema.properties?.components?.items?.properties?.kind?.enum,
    "componentKind"
  );
  assertProjection(
    "policy impact relation",
    schema.properties?.impactEdges?.items?.properties?.relation?.enum,
    "impactRelation"
  );
  assertProjection(
    "policy propagation decision",
    schema.properties?.impactEdges?.items?.properties?.decisions?.items?.properties?.propagation?.enum,
    "propagationDecision"
  );
  assertProjection("policy change kinds", schema.$defs?.changeKinds?.items?.enum, "changeKind");
  assertProjection(
    "policy optional no-documentation change kinds",
    schema.$defs?.changeKindsOrEmpty?.items?.enum,
    "changeKind"
  );
  assertProjection(
    "policy document role",
    schema.properties?.documents?.items?.properties?.role?.enum,
    "documentRole"
  );
  assertProjection(
    "policy publication kind",
    schema.properties?.publications?.items?.properties?.kind?.enum,
    "publication"
  );
  assertProjection(
    "policy publication locator kind",
    schema.properties?.publications?.items?.properties?.locatorKind?.enum,
    "publicationLocator"
  );
  assertValue(
    "policy document base presence",
    schema.properties?.documents?.items?.properties?.basePresence?.const,
    CONTRACT_VALUES.documentBasePresence[0]
  );
  assertProjection(
    "policy impact depth",
    schema.properties?.documentationRules?.items?.properties?.impactDepths?.items?.enum,
    "impactDepth"
  );
  assertProjection(
    "policy protection kind",
    schema.properties?.protectionRules?.items?.properties?.kind?.enum,
    "protectionKind"
  );
  assertProjection(
    "policy check availability",
    schema.properties?.checks?.items?.properties?.availability?.enum,
    "checkAvailability"
  );
  assertLimit("policy trustedToolPaths", schema.properties?.trustedToolPaths?.maxItems, "maxTrustedToolPaths");
  assertLimit(
    "policy repositoryId",
    schema.properties?.repositoryId?.maxLength,
    "maxRepositoryIdCharacters"
  );
  assertLimit("policy identifiers", schema.$defs?.id?.maxLength, "maxIdentifierCharacters");
  assertLimit(
    "policy GitHub principals",
    schema.properties?.owners?.items?.properties?.githubPrincipals?.items?.maxLength,
    "maxGithubPrincipalCharacters"
  );
  assertLimit("policy owners", schema.properties?.owners?.maxItems, "maxOwners");
  assertLimit("policy checks", schema.properties?.checks?.maxItems, "maxChecks");
  assertLimit("policy inventoryRules", schema.properties?.inventoryRules?.maxItems, "maxInventoryRules");
  assertLimit("policy components", schema.properties?.components?.maxItems, "maxComponents");
  assertLimit("policy impactNodes", schema.properties?.impactNodes?.maxItems, "maxImpactNodes");
  assertLimit("policy impactEdges", schema.properties?.impactEdges?.maxItems, "maxImpactEdges");
  assertLimit("policy documents", schema.properties?.documents?.maxItems, "maxDocuments");
  assertLimit(
    "policy documentationRules",
    schema.properties?.documentationRules?.maxItems,
    "maxDocumentationRules"
  );
  assertLimit(
    "policy protectionRules",
    schema.properties?.protectionRules?.maxItems,
    "maxProtectionRules"
  );
}

export function assertAnalysisSchemaProjections(schema) {
  if (schema.properties?.schemaVersion?.const !== 2) {
    throw new Error("analysis schemaVersion must be exactly 2");
  }
  assertProjection("analysis classification", schema.properties?.classification?.enum, "classification");
  assertProjection(
    "analysis change status",
    schema.properties?.changes?.items?.properties?.status?.enum,
    "changeStatus"
  );
  assertProjection(
    "analysis change kind",
    schema.properties?.changes?.items?.properties?.changeKind?.enum?.filter(Boolean),
    "changeKind"
  );
  assertProjection(
    "analysis inventory classification",
    schema.properties?.changes?.items?.properties?.inventoryClassification?.enum?.filter(Boolean),
    "inventoryClassification"
  );
  assertProjection(
    "analysis candidate state",
    schema.$defs?.candidateState?.enum,
    "candidateState"
  );
  assertProjection("analysis impact depth", schema.$defs?.impactDepth?.enum, "impactDepth");
  assertProjection(
    "analysis target document role",
    schema.$defs?.obligationTarget?.properties?.role?.enum,
    "documentRole"
  );
  assertProjection(
    "analysis target publication kind",
    schema.$defs?.obligationPublication?.properties?.kind?.enum,
    "publication"
  );
  assertProjection(
    "analysis target publication locator kind",
    schema.$defs?.obligationPublication?.properties?.locatorKind?.enum,
    "publicationLocator"
  );
  assertProjection(
    "analysis validation publication kind",
    schema.properties?.publicationValidations?.items?.properties?.kind?.enum,
    "publication"
  );
  assertProjection(
    "analysis validation publication locator kind",
    schema.properties?.publicationValidations?.items?.properties?.locatorKind?.enum,
    "publicationLocator"
  );
  assertProjection(
    "analysis publication trigger kind",
    schema.properties?.publicationValidations?.items?.properties?.triggers?.items?.properties
      ?.triggerKinds?.items?.enum,
    "publicationTriggerKind"
  );
  assertValue(
    "analysis target base presence",
    schema.$defs?.obligationTarget?.properties?.basePresence?.const,
    CONTRACT_VALUES.documentBasePresence[0]
  );
  assertProjection(
    "analysis governance kind",
    schema.properties?.governanceReviews?.items?.properties?.kind?.enum,
    "protectionKind"
  );
  assertValue(
    "analysis documentation action type",
    schema.properties?.documentationObligations?.items?.properties?.actionType?.const,
    CONTRACT_VALUES.actionTypeByCollection.documentationObligations
  );
  assertValue(
    "analysis publication validation action type",
    schema.properties?.publicationValidations?.items?.properties?.actionType?.const,
    CONTRACT_VALUES.actionTypeByCollection.publicationValidations
  );
  assertLimit(
    "analysis publicationValidations",
    schema.properties?.publicationValidations?.maxItems,
    "maxPublicationValidations"
  );
  assertLimit(
    "analysis publication validation triggers",
    schema.properties?.publicationValidations?.items?.properties?.triggers?.maxItems,
    "maxTotalPublicationTriggers"
  );
  assertValue(
    "analysis governance action type",
    schema.properties?.governanceReviews?.items?.properties?.actionType?.const,
    CONTRACT_VALUES.actionTypeByCollection.governanceReviews
  );
}
