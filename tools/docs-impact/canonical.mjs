import { createHash } from "node:crypto";

export function canonicalize(value) {
  if (Array.isArray(value)) {
    return value.map(canonicalize);
  }
  if (value && typeof value === "object") {
    return Object.fromEntries(
      Object.keys(value)
        .sort((left, right) => left < right ? -1 : left > right ? 1 : 0)
        .map((key) => [key, canonicalize(value[key])])
    );
  }
  return value;
}

export function canonicalJson(value) {
  return `${JSON.stringify(canonicalize(value), null, 2)}\n`;
}

export function canonicalJsonByteLength(value) {
  return Buffer.byteLength(canonicalJson(value), "utf8");
}

export function sha256(value) {
  return createHash("sha256").update(value).digest("hex");
}

export function canonicalDigest(value) {
  return sha256(canonicalJson(value));
}
