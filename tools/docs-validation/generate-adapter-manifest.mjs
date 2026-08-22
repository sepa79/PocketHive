import { lstat, realpath } from "node:fs/promises";
import { isAbsolute, resolve } from "node:path";
import { fileURLToPath } from "node:url";

import { canonicalJson } from "../docs-impact/canonical.mjs";
import {
  ALL_ADAPTER_IDS,
  createAdapterManifest,
  loadAdapterManifest,
} from "./adapters.mjs";
import { atomicWriteJson } from "./evidence.mjs";

export const NOT_APPLICABLE_SELECTION = "NOT_APPLICABLE";

const OPTION_SPECS = Object.freeze([
  ["--node", "node"],
  ["--git", "git"],
  ["--npm", "npm"],
  ["--command-shell", "commandShell"],
  ["--taskkill", "taskkill"],
  ["--bash", "bash"],
  ["--power-shell", "powerShell"],
  ["--java", "java"],
  ["--maven", "maven"],
  ["--local-repository", "localRepository"],
  ["--docker", "docker"],
  ["--chromium", "chromium"],
]);
const REQUIRED_OPTIONS = Object.freeze([
  "--output",
  "--platform",
  ...OPTION_SPECS.map(([option]) => option),
]);
const ALLOWED_OPTIONS = new Set(REQUIRED_OPTIONS);

function parseSelection(value, option) {
  if (value === NOT_APPLICABLE_SELECTION) return null;
  if (!isAbsolute(value)) {
    throw new Error(`${option} must be an explicit absolute path or ${NOT_APPLICABLE_SELECTION}`);
  }
  return value;
}

export function parseGenerationOptions(argv) {
  const supplied = new Map();
  for (let index = 0; index < argv.length; index += 1) {
    const option = argv[index];
    if (!ALLOWED_OPTIONS.has(option)) {
      throw new Error(`Unknown argument: ${option}`);
    }
    if (supplied.has(option)) {
      throw new Error(`Duplicate argument: ${option}`);
    }
    const value = argv[index + 1];
    if (!value || value.startsWith("--")) {
      throw new Error(`${option} requires a value`);
    }
    supplied.set(option, value);
    index += 1;
  }
  for (const option of REQUIRED_OPTIONS) {
    if (!supplied.has(option)) throw new Error(`${option} is required`);
  }

  const outputPath = supplied.get("--output");
  if (!isAbsolute(outputPath)) {
    throw new Error("--output must be an explicit absolute path");
  }
  if (!outputPath.toLowerCase().endsWith(".json")) {
    throw new Error("--output must identify a .json file");
  }
  const selections = Object.fromEntries(
    OPTION_SPECS.map(([option, adapterId]) => [
      adapterId,
      parseSelection(supplied.get(option), option),
    ]),
  );
  if (Object.keys(selections).length !== ALL_ADAPTER_IDS.length) {
    throw new Error("Generator option mapping does not cover the adapter contract");
  }
  return {
    outputPath,
    platform: supplied.get("--platform"),
    selections,
  };
}

async function assertOutputDoesNotExist(outputPath) {
  try {
    await lstat(outputPath);
  } catch (error) {
    if (error?.code === "ENOENT") return;
    throw error;
  }
  throw new Error(`Refusing to overwrite adapter manifest: ${outputPath}`);
}

export async function generateAdapterManifest({ outputPath, platform, selections }) {
  if (typeof outputPath !== "string" || !isAbsolute(outputPath)) {
    throw new Error("Adapter manifest output must be an explicit absolute path");
  }
  await assertOutputDoesNotExist(outputPath);
  const manifest = await createAdapterManifest({ platform, selections });
  await atomicWriteJson(outputPath, manifest);
  const canonicalOutputPath = await realpath(outputPath);
  const loaded = await loadAdapterManifest({ manifestPath: canonicalOutputPath });
  if (loaded.manifestIdentity.canonicalJson !== canonicalJson(manifest)) {
    throw new Error("Generated adapter manifest failed deterministic self-verification");
  }
  return {
    manifest,
    manifestPath: canonicalOutputPath,
    manifestSha256: loaded.manifestIdentity.rawSha256,
  };
}

async function main() {
  const generated = await generateAdapterManifest(parseGenerationOptions(process.argv.slice(2)));
  console.log(`Adapter manifest: ${generated.manifestPath}`);
  console.log(`SHA-256: ${generated.manifestSha256}`);
}

const invokedPath = process.argv[1] ? resolve(process.argv[1]) : "";
if (invokedPath === fileURLToPath(import.meta.url)) {
  main().catch((error) => {
    console.error(error.stack || error.message);
    process.exitCode = 1;
  });
}
