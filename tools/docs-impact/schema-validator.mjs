import { canonicalJson } from "./canonical.mjs";

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
  if (schema.items) {
    assertSupportedKeywords(schema.items, `${path}.items`);
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

export function validateSchema(schema, value) {
  const errors = [];
  validateNode(schema, schema, value, "$", errors);
  return errors;
}

export function assertSchema(schema, value, label) {
  assertSupportedKeywords(schema);
  const errors = validateSchema(schema, value);
  if (errors.length > 0) {
    throw new Error(`${label} failed schema validation:\n- ${errors.join("\n- ")}`);
  }
}
