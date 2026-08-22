import { readFileSync } from "node:fs";
import { lstat, readFile, realpath } from "node:fs/promises";
import path from "node:path";
import { TextDecoder } from "node:util";

import { canonicalDigest, canonicalJson, sha256 } from "../docs-impact/canonical.mjs";
import { assertSchema } from "../docs-impact/schema-validator.mjs";

const UTF8_DECODER = new TextDecoder("utf-8", { fatal: true });
const MAX_MANIFEST_BYTES = 1024 * 1024;
const CONFIGURED = "CONFIGURED";
const NOT_APPLICABLE = "NOT_APPLICABLE";

function readJson(relativeUrl) {
  return JSON.parse(UTF8_DECODER.decode(readFileSync(new URL(relativeUrl, import.meta.url))));
}

export const ADAPTER_MANIFEST_SCHEMA = Object.freeze(
  readJson("./contracts/adapter-manifest.schema.json"),
);
export const ADAPTER_MANIFEST_SCHEMA_ID = ADAPTER_MANIFEST_SCHEMA.$id;
export const ADAPTER_STATE = Object.freeze({ CONFIGURED, NOT_APPLICABLE });
export const EXECUTABLE_ADAPTER_IDS = Object.freeze(
  Object.keys(ADAPTER_MANIFEST_SCHEMA.properties.adapters.properties)
    .filter((adapterId) => adapterId !== "localRepository"),
);
export const ALL_ADAPTER_IDS = Object.freeze([
  ...EXECUTABLE_ADAPTER_IDS,
  "localRepository",
]);

function statIdentity(metadata) {
  return {
    ctimeMs: metadata.ctimeMs,
    dev: metadata.dev,
    ino: metadata.ino,
    mode: metadata.mode,
    mtimeMs: metadata.mtimeMs,
    size: metadata.size
  };
}

function assertSameStat(first, second, label) {
  if (canonicalJson(statIdentity(first)) !== canonicalJson(statIdentity(second))) {
    throw new Error(`${label} changed while it was captured`);
  }
}

async function captureRegularFile(filePath, label) {
  const before = await lstat(filePath);
  if (before.isSymbolicLink()) {
    throw new Error(`${label} must not be a symbolic link or junction-like entry`);
  }
  if (!before.isFile()) {
    throw new Error(`${label} must be a regular file`);
  }
  const firstBytes = await readFile(filePath);
  const middle = await lstat(filePath);
  const secondBytes = await readFile(filePath);
  const after = await lstat(filePath);
  assertSameStat(before, middle, label);
  assertSameStat(middle, after, label);
  if (!firstBytes.equals(secondBytes) || firstBytes.byteLength !== after.size) {
    throw new Error(`${label} changed while it was captured`);
  }
  return {
    bytes: firstBytes,
    sha256: sha256(firstBytes),
    sizeBytes: firstBytes.byteLength
  };
}

function sameFilesystemPath(left, right) {
  const normalize = (value) => {
    const resolved = path.resolve(value);
    return process.platform === "win32" ? resolved.toLowerCase() : resolved;
  };
  return normalize(left) === normalize(right);
}

function assertNullBinding(binding, adapterId) {
  const expectedKeys = adapterId === "localRepository"
    ? ["path", "state"]
    : ["path", "sha256", "sizeBytes", "state"];
  for (const key of expectedKeys) {
    if (key !== "state" && binding[key] !== null) {
      throw new Error(`${adapterId} NOT_APPLICABLE must declare ${key}: null`);
    }
  }
}

async function validateExecutableBinding(adapterId, binding) {
  if (binding.state === NOT_APPLICABLE) {
    assertNullBinding(binding, adapterId);
    return Object.freeze({ ...binding });
  }
  if (binding.state !== CONFIGURED) {
    throw new Error(`${adapterId} has an unsupported adapter state`);
  }
  if (typeof binding.path !== "string" || !path.isAbsolute(binding.path)) {
    throw new Error(`${adapterId} CONFIGURED path must be absolute`);
  }
  const canonicalPath = await realpath(binding.path).catch(() => undefined);
  if (!canonicalPath) {
    throw new Error(`${adapterId} CONFIGURED executable does not exist: ${binding.path}`);
  }
  if (binding.path !== canonicalPath) {
    throw new Error(`${adapterId} CONFIGURED path must identify its canonical executable path`);
  }
  const capture = await captureRegularFile(canonicalPath, `${adapterId} executable`);
  if (binding.sha256 !== capture.sha256 || binding.sizeBytes !== capture.sizeBytes) {
    throw new Error(`${adapterId} executable digest or size does not match the manifest`);
  }
  return Object.freeze({
    state: CONFIGURED,
    path: canonicalPath,
    sha256: capture.sha256,
    sizeBytes: capture.sizeBytes
  });
}

async function validateDirectoryBinding(binding) {
  if (binding.state === NOT_APPLICABLE) {
    assertNullBinding(binding, "localRepository");
    return Object.freeze({ ...binding });
  }
  if (binding.state !== CONFIGURED) {
    throw new Error("localRepository has an unsupported adapter state");
  }
  if (typeof binding.path !== "string" || !path.isAbsolute(binding.path)) {
    throw new Error("localRepository CONFIGURED path must be absolute");
  }
  const metadata = await lstat(binding.path).catch(() => undefined);
  if (!metadata || metadata.isSymbolicLink() || !metadata.isDirectory()) {
    throw new Error("localRepository CONFIGURED path must be an existing non-link directory");
  }
  const canonicalPath = await realpath(binding.path);
  if (binding.path !== canonicalPath) {
    throw new Error("localRepository CONFIGURED path must identify its canonical directory path");
  }
  return Object.freeze({ state: CONFIGURED, path: canonicalPath });
}

function assertExactAdapterSelections(selections) {
  if (!selections || typeof selections !== "object" || Array.isArray(selections)) {
    throw new Error("Adapter selections must be an object");
  }
  const supplied = Object.keys(selections).sort();
  const expected = [...ALL_ADAPTER_IDS].sort();
  if (canonicalJson(supplied) !== canonicalJson(expected)) {
    throw new Error(
      `Adapter selections must declare exactly: ${expected.join(", ")}`,
    );
  }
}

async function captureExecutableSelection(adapterId, selection) {
  if (selection === null) {
    return { state: NOT_APPLICABLE, path: null, sha256: null, sizeBytes: null };
  }
  if (typeof selection !== "string" || !path.isAbsolute(selection)) {
    throw new Error(`${adapterId} selection must be an explicit absolute path or null`);
  }
  const canonicalPath = await realpath(selection).catch(() => undefined);
  if (!canonicalPath) {
    throw new Error(`${adapterId} selected executable does not exist: ${selection}`);
  }
  const capture = await captureRegularFile(canonicalPath, `${adapterId} executable`);
  return {
    state: CONFIGURED,
    path: canonicalPath,
    sha256: capture.sha256,
    sizeBytes: capture.sizeBytes,
  };
}

async function captureDirectorySelection(selection) {
  if (selection === null) {
    return { state: NOT_APPLICABLE, path: null };
  }
  if (typeof selection !== "string" || !path.isAbsolute(selection)) {
    throw new Error("localRepository selection must be an explicit absolute path or null");
  }
  const canonicalPath = await realpath(selection).catch(() => undefined);
  if (!canonicalPath) {
    throw new Error(`localRepository selected directory does not exist: ${selection}`);
  }
  const metadata = await lstat(canonicalPath);
  if (metadata.isSymbolicLink() || !metadata.isDirectory()) {
    throw new Error("localRepository selection must identify a non-link directory");
  }
  return { state: CONFIGURED, path: canonicalPath };
}

async function assertPlatformSemantics(manifest, adapters) {
  if (manifest.platform !== process.platform) {
    throw new Error(
      `Adapter manifest platform ${manifest.platform} does not match runtime ${process.platform}`,
    );
  }
  for (const adapterId of ["node", "git", "commandShell"]) {
    if (adapters[adapterId].state !== CONFIGURED) {
      throw new Error(`${adapterId} must be CONFIGURED for the validation controller`);
    }
  }
  const taskkillState = adapters.taskkill.state;
  if (process.platform === "win32" && taskkillState !== CONFIGURED) {
    throw new Error("taskkill must be CONFIGURED on win32");
  }
  if (process.platform !== "win32" && taskkillState !== NOT_APPLICABLE) {
    throw new Error("taskkill must be NOT_APPLICABLE outside win32");
  }
  if (!sameFilesystemPath(adapters.node.path, process.execPath)) {
    throw new Error("node adapter must exactly identify process.execPath");
  }
  if (process.platform === "win32" && adapters.npm.state === CONFIGURED) {
    if (path.basename(adapters.npm.path).toLowerCase() !== "npm.cmd") {
      throw new Error("win32 npm adapter must identify npm.cmd");
    }
    const adjacentNode = await realpath(path.join(path.dirname(adapters.npm.path), "node.exe"))
      .catch(() => undefined);
    if (!adjacentNode || !sameFilesystemPath(adjacentNode, adapters.node.path)) {
      throw new Error("win32 npm.cmd must use the declared process.execPath Node adapter");
    }
  }
  if (process.platform !== "win32") {
    for (const adapterId of EXECUTABLE_ADAPTER_IDS) {
      const adapter = adapters[adapterId];
      if (adapter.state === CONFIGURED && /\.(?:bat|cmd)$/iu.test(adapter.path)) {
        throw new Error(`${adapterId} cannot configure a Windows command file outside win32`);
      }
    }
  }
}

function deepFreeze(value) {
  if (value && typeof value === "object" && !Object.isFrozen(value)) {
    Object.freeze(value);
    for (const child of Object.values(value)) deepFreeze(child);
  }
  return value;
}

export async function createAdapterManifest({ platform, selections }) {
  assertExactAdapterSelections(selections);
  const adapters = {};
  for (const adapterId of EXECUTABLE_ADAPTER_IDS) {
    adapters[adapterId] = await captureExecutableSelection(adapterId, selections[adapterId]);
  }
  adapters.localRepository = await captureDirectorySelection(selections.localRepository);
  const manifest = {
    schemaVersion: 1,
    schemaId: ADAPTER_MANIFEST_SCHEMA_ID,
    platform,
    adapters,
  };
  assertSchema(ADAPTER_MANIFEST_SCHEMA, manifest, "documentation validation adapter manifest");
  await assertPlatformSemantics(manifest, adapters);
  return deepFreeze(manifest);
}

export async function loadAdapterManifest({ manifestPath }) {
  if (typeof manifestPath !== "string" || !path.isAbsolute(manifestPath)) {
    throw new Error("--adapter-manifest must be an explicit absolute path");
  }
  const canonicalManifestPath = await realpath(manifestPath).catch(() => undefined);
  if (!canonicalManifestPath) {
    throw new Error(`--adapter-manifest does not exist: ${manifestPath}`);
  }
  if (!sameFilesystemPath(manifestPath, canonicalManifestPath)) {
    throw new Error("--adapter-manifest must identify its canonical file path");
  }
  const manifestCapture = await captureRegularFile(canonicalManifestPath, "Adapter manifest");
  if (manifestCapture.sizeBytes > MAX_MANIFEST_BYTES) {
    throw new Error(`Adapter manifest exceeds ${MAX_MANIFEST_BYTES} bytes`);
  }
  let manifest;
  try {
    manifest = JSON.parse(UTF8_DECODER.decode(manifestCapture.bytes));
  } catch (error) {
    if (error instanceof TypeError) throw new Error("Adapter manifest is not valid UTF-8");
    if (error instanceof SyntaxError) throw new Error(`Adapter manifest is not valid JSON: ${error.message}`);
    throw error;
  }
  assertSchema(ADAPTER_MANIFEST_SCHEMA, manifest, "documentation validation adapter manifest");

  const adapters = {};
  for (const adapterId of EXECUTABLE_ADAPTER_IDS) {
    adapters[adapterId] = await validateExecutableBinding(adapterId, manifest.adapters[adapterId]);
  }
  adapters.localRepository = await validateDirectoryBinding(manifest.adapters.localRepository);
  await assertPlatformSemantics(manifest, adapters);

  const canonicalManifestJson = canonicalJson(manifest);
  const loaded = {
    manifest: deepFreeze(manifest),
    manifestIdentity: Object.freeze({
      path: canonicalManifestPath,
      rawSha256: manifestCapture.sha256,
      rawSizeBytes: manifestCapture.sizeBytes,
      canonicalSha256: canonicalDigest(manifest),
      canonicalJson: canonicalManifestJson
    }),
    adapters: deepFreeze(adapters)
  };
  return deepFreeze(loaded);
}

export function requireConfiguredAdapter(loaded, adapterId, purpose) {
  const adapter = loaded?.adapters?.[adapterId];
  if (!adapter) {
    throw new Error(`Unknown documentation validation adapter: ${adapterId}`);
  }
  if (adapter.state !== CONFIGURED) {
    const error = new Error(`${adapterId} is NOT_APPLICABLE; cannot run ${purpose}`);
    error.code = "ADAPTER_NOT_APPLICABLE";
    throw error;
  }
  return adapter;
}

export function adapterReceiptIdentity(loaded) {
  return deepFreeze({
    manifest: { ...loaded.manifestIdentity },
    ...Object.fromEntries(
      Object.entries(loaded.adapters).map(([adapterId, adapter]) => [adapterId, { ...adapter }]),
    )
  });
}
