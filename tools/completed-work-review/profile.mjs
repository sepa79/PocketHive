import { TextDecoder } from "node:util";
import { canonicalDigest, canonicalJson } from "../docs-impact/canonical.mjs";
import {
  CONTRACT_VALUES,
  assertProfileSchemaProjections
} from "./contracts/projections.mjs";
import { captureStableRegularFile, HARD_LINK_POLICY } from "./file-safety.mjs";

const UTF8_DECODER = new TextDecoder("utf-8", { fatal: true });

const SUPPORTED_SCHEMA_KEYWORDS = new Set([
  "$defs",
  "$id",
  "$ref",
  "$schema",
  "additionalProperties",
  "const",
  "enum",
  "items",
  "maxItems",
  "maxLength",
  "maximum",
  "minItems",
  "minLength",
  "minimum",
  "multipleOf",
  "oneOf",
  "pattern",
  "properties",
  "required",
  "title",
  "type",
  "uniqueItems"
]);

function assertSupportedKeywords(schema, path = "$") {
  for (const key of Object.keys(schema)) {
    if (!SUPPORTED_SCHEMA_KEYWORDS.has(key)) {
      throw new Error(`${path}: unsupported schema keyword ${key}`);
    }
  }
  for (const [key, child] of Object.entries(schema.properties ?? {})) {
    assertSupportedKeywords(child, `${path}.properties.${key}`);
  }
  for (const [key, child] of Object.entries(schema.$defs ?? {})) {
    assertSupportedKeywords(child, `${path}.$defs.${key}`);
  }
  if (schema.items && typeof schema.items === "object") {
    assertSupportedKeywords(schema.items, `${path}.items`);
  }
  for (const [index, child] of (schema.oneOf ?? []).entries()) {
    assertSupportedKeywords(child, `${path}.oneOf[${index}]`);
  }
}

function resolveReference(rootSchema, reference) {
  if (!reference.startsWith("#/")) {
    throw new Error(`Only local schema references are supported: ${reference}`);
  }
  return reference
    .slice(2)
    .split("/")
    .map((segment) => segment.replaceAll("~1", "/").replaceAll("~0", "~"))
    .reduce((value, segment) => value?.[segment], rootSchema);
}

function matchesType(value, expected) {
  switch (expected) {
    case "array":
      return Array.isArray(value);
    case "integer":
      return Number.isInteger(value);
    case "null":
      return value === null;
    case "object":
      return value !== null && typeof value === "object" && !Array.isArray(value);
    default:
      return typeof value === expected;
  }
}

function sameValue(left, right) {
  return canonicalJson(left) === canonicalJson(right);
}

function validateNode(rootSchema, schema, value, path, errors) {
  if (schema.$ref) {
    const referenced = resolveReference(rootSchema, schema.$ref);
    if (!referenced) {
      errors.push(`${path}: unresolved schema reference ${schema.$ref}`);
      return;
    }
    validateNode(rootSchema, referenced, value, path, errors);
    return;
  }

  if (schema.oneOf) {
    const branchResults = schema.oneOf.map((branch) => {
      const branchErrors = [];
      validateNode(rootSchema, branch, value, path, branchErrors);
      return branchErrors;
    });
    const matchingBranches = branchResults.filter((branchErrors) => branchErrors.length === 0);
    if (matchingBranches.length !== 1) {
      errors.push(`${path}: must match exactly one oneOf branch; matched ${matchingBranches.length}`);
      return;
    }
  }

  if (Object.hasOwn(schema, "const") && !sameValue(value, schema.const)) {
    errors.push(`${path}: must equal ${JSON.stringify(schema.const)}`);
    return;
  }
  if (schema.enum && !schema.enum.some((candidate) => sameValue(candidate, value))) {
    errors.push(`${path}: must be one of ${schema.enum.map(JSON.stringify).join(", ")}`);
    return;
  }
  if (schema.type) {
    const acceptedTypes = Array.isArray(schema.type) ? schema.type : [schema.type];
    if (!acceptedTypes.some((expected) => matchesType(value, expected))) {
      errors.push(`${path}: must have type ${acceptedTypes.join(" or ")}`);
      return;
    }
  }

  if (typeof value === "string") {
    if (schema.minLength !== undefined && value.length < schema.minLength) {
      errors.push(`${path}: must contain at least ${schema.minLength} characters`);
    }
    if (schema.maxLength !== undefined && value.length > schema.maxLength) {
      errors.push(`${path}: must contain at most ${schema.maxLength} characters`);
    }
    if (schema.pattern && !new RegExp(schema.pattern, "u").test(value)) {
      errors.push(`${path}: does not match ${schema.pattern}`);
    }
  }

  if (typeof value === "number") {
    if (schema.minimum !== undefined && value < schema.minimum) {
      errors.push(`${path}: must be at least ${schema.minimum}`);
    }
    if (schema.maximum !== undefined && value > schema.maximum) {
      errors.push(`${path}: must be at most ${schema.maximum}`);
    }
    if (schema.multipleOf !== undefined) {
      const quotient = value / schema.multipleOf;
      if (Math.abs(quotient - Math.round(quotient)) > Number.EPSILON * 100) {
        errors.push(`${path}: must be a multiple of ${schema.multipleOf}`);
      }
    }
  }

  if (Array.isArray(value)) {
    if (schema.minItems !== undefined && value.length < schema.minItems) {
      errors.push(`${path}: must contain at least ${schema.minItems} items`);
    }
    if (schema.maxItems !== undefined && value.length > schema.maxItems) {
      errors.push(`${path}: must contain at most ${schema.maxItems} items`);
    }
    if (schema.uniqueItems) {
      const identities = value.map(canonicalJson);
      if (new Set(identities).size !== identities.length) {
        errors.push(`${path}: items must be unique`);
      }
    }
    if (schema.items) {
      value.forEach((item, index) => validateNode(rootSchema, schema.items, item, `${path}[${index}]`, errors));
    }
  }

  if (value !== null && typeof value === "object" && !Array.isArray(value)) {
    for (const required of schema.required ?? []) {
      if (!Object.hasOwn(value, required)) {
        errors.push(`${path}: missing required property ${required}`);
      }
    }
    if (schema.additionalProperties === false) {
      const allowed = new Set(Object.keys(schema.properties ?? {}));
      for (const key of Object.keys(value)) {
        if (!allowed.has(key)) {
          errors.push(`${path}: unexpected property ${key}`);
        }
      }
    }
    for (const [key, childSchema] of Object.entries(schema.properties ?? {})) {
      if (Object.hasOwn(value, key)) {
        validateNode(rootSchema, childSchema, value[key], `${path}.${key}`, errors);
      }
    }
  }
}

export function validateContract(schema, value) {
  assertSupportedKeywords(schema);
  const errors = [];
  validateNode(schema, schema, value, "$", errors);
  return errors;
}

export function assertContract(schema, value, label) {
  const errors = validateContract(schema, value);
  if (errors.length > 0) {
    throw new Error(`${label} failed schema validation:\n- ${errors.join("\n- ")}`);
  }
}

function decodeUtf8(bytes, label) {
  try {
    return UTF8_DECODER.decode(bytes);
  } catch {
    throw new Error(`${label} is not valid UTF-8`);
  }
}

function deepFreeze(value) {
  if (value && typeof value === "object" && !Object.isFrozen(value)) {
    Object.freeze(value);
    for (const child of Object.values(value)) {
      deepFreeze(child);
    }
  }
  return value;
}

function assertProfileSemantics(config) {
  const profileIds = config.profiles.map((profile) => profile.id);
  if (new Set(profileIds).size !== profileIds.length) {
    throw new Error("Completed-work review profile IDs must be unique");
  }
  const canonicalIds = [...CONTRACT_VALUES.profileId].sort();
  if (JSON.stringify([...profileIds].sort()) !== JSON.stringify(canonicalIds)) {
    throw new Error("Profile configuration must declare every canonical profile exactly once");
  }

  for (const profile of config.profiles) {
    if (profile.kind !== CONTRACT_VALUES.profileKindById[profile.id]) {
      throw new Error(`${profile.id} must use kind ${CONTRACT_VALUES.profileKindById[profile.id]}`);
    }
    if (profile.scope !== CONTRACT_VALUES.profileScopeById[profile.id]) {
      throw new Error(`${profile.id} must use scope ${CONTRACT_VALUES.profileScopeById[profile.id]}`);
    }
    if (!CONTRACT_VALUES.scoringMethod.includes(profile.scoringMethod)) {
      throw new Error(`${profile.id} must use a canonical scoring method`);
    }
    const dimensionIds = profile.dimensions.map((dimension) => dimension.id);
    if (new Set(dimensionIds).size !== dimensionIds.length) {
      throw new Error(`${profile.id} dimension IDs must be unique`);
    }
    const totalWeight = profile.dimensions.reduce((sum, dimension) => sum + dimension.weight, 0);
    if (totalWeight !== 100) {
      throw new Error(`${profile.id} dimension weights must total exactly 100; found ${totalWeight}`);
    }
    const expectedEqualWeight = 100 / profile.dimensions.length;
    if (!profile.dimensions.every(({ weight }) => weight === expectedEqualWeight)) {
      throw new Error(
        `${profile.id} is a PocketHive v1 profile and must use equal dimension weights of `
        + `${expectedEqualWeight}`
      );
    }
    for (const dimension of profile.dimensions) {
      const anchorScores = dimension.scoreAnchors.map(({ score }) => score);
      if (canonicalJson(anchorScores) !== canonicalJson(CONTRACT_VALUES.scoreAnchorValues)) {
        throw new Error(
          `${profile.id} dimension ${dimension.id} must declare canonical score anchors in order`,
        );
      }
      const anchorDescriptions = dimension.scoreAnchors.map(({ description }) => description);
      if (new Set(anchorDescriptions).size !== anchorDescriptions.length) {
        throw new Error(`${profile.id} dimension ${dimension.id} score-anchor descriptions must be unique`);
      }
    }
    const gateIds = profile.requiredGates.map(({ id }) => id);
    if (new Set(gateIds).size !== gateIds.length) {
      throw new Error(`${profile.id} required gate IDs must be unique`);
    }
    const expectedGateIds = CONTRACT_VALUES.requiredGateIdsByProfile[profile.id];
    if (JSON.stringify([...gateIds].sort()) !== JSON.stringify([...expectedGateIds].sort())) {
      throw new Error(`${profile.id} must declare every canonical required gate exactly once`);
    }
    for (const gate of profile.requiredGates) {
      const expectedEvaluator = CONTRACT_VALUES.gateEvaluatorById[gate.id];
      if (gate.evaluator !== expectedEvaluator) {
        throw new Error(`${profile.id} gate ${gate.id} must use evaluator ${expectedEvaluator}`);
      }
      const policyLists = [
        gate.allowedAdapters,
        gate.allowedExecutionKinds,
        gate.allowedEvidenceKinds
      ];
      if (gate.officialIngressPolicy === "NO_EVIDENCE") {
        if (policyLists.some((values) => values.length !== 0)) {
          throw new Error(`${profile.id} gate ${gate.id} NO_EVIDENCE policy requires empty allowlists`);
        }
      } else if (policyLists.some((values) => values.length === 0)) {
        throw new Error(`${profile.id} gate ${gate.id} requires non-empty typed evidence allowlists`);
      }
    }
  }
}

export async function loadReviewProfiles({ anchorPath, profilesPath, schemaPath }) {
  if (typeof anchorPath !== "string" || anchorPath.length === 0) {
    throw new Error("anchorPath is required");
  }
  if (typeof profilesPath !== "string" || profilesPath.length === 0) {
    throw new Error("profilesPath is required");
  }
  if (typeof schemaPath !== "string" || schemaPath.length === 0) {
    throw new Error("schemaPath is required");
  }
  const [profilesBytes, schemaBytes] = await Promise.all([
    captureStableRegularFile({
      anchorPath,
      hardLinkPolicy: HARD_LINK_POLICY.REJECT,
      path: profilesPath,
      label: "Review profile configuration",
      maxBytes: CONTRACT_VALUES.limits.maxProfileBytes,
    }),
    captureStableRegularFile({
      anchorPath,
      hardLinkPolicy: HARD_LINK_POLICY.REJECT,
      path: schemaPath,
      label: "Review profile schema",
      maxBytes: CONTRACT_VALUES.limits.maxProfileBytes,
    })
  ]);
  if (profilesBytes.length > CONTRACT_VALUES.limits.maxProfileBytes) {
    throw new Error(
      `Completed-work review profile configuration exceeds `
      + `${CONTRACT_VALUES.limits.maxProfileBytes} bytes`
    );
  }

  let config;
  try {
    config = JSON.parse(decodeUtf8(profilesBytes, "Review profile configuration"));
  } catch (error) {
    if (error instanceof SyntaxError) {
      throw new Error(`Review profile configuration is invalid JSON: ${error.message}`);
    }
    throw error;
  }

  let schema;
  try {
    schema = JSON.parse(decodeUtf8(schemaBytes, "Review profile schema"));
  } catch (error) {
    if (error instanceof SyntaxError) {
      throw new Error(`Review profile schema is invalid JSON: ${error.message}`);
    }
    throw error;
  }
  assertProfileSchemaProjections(schema);
  assertContract(schema, config, "Completed-work review profiles");
  assertProfileSemantics(config);
  deepFreeze(config);

  return {
    config,
    configDigest: canonicalDigest(config),
    profilesById: new Map(config.profiles.map((profile) => [profile.id, profile]))
  };
}

export { CONTRACT_VALUES };
