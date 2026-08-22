import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { access, mkdtemp, readFile, realpath, rm, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { promisify } from "node:util";

import { ALL_ADAPTER_IDS, loadAdapterManifest } from "./adapters.mjs";
import {
  NOT_APPLICABLE_SELECTION,
  generateAdapterManifest,
  parseGenerationOptions,
} from "./generate-adapter-manifest.mjs";

const execFileAsync = promisify(execFile);
const GENERATOR_PATH = path.resolve(import.meta.dirname, "generate-adapter-manifest.mjs");
const OPTION_BY_ADAPTER = Object.freeze({
  node: "--node",
  git: "--git",
  npm: "--npm",
  commandShell: "--command-shell",
  taskkill: "--taskkill",
  bash: "--bash",
  powerShell: "--power-shell",
  java: "--java",
  maven: "--maven",
  localRepository: "--local-repository",
  docker: "--docker",
  chromium: "--chromium",
});

async function temporaryDirectory(t) {
  const directory = await mkdtemp(path.join(os.tmpdir(), "pockethive-adapter-generator-test-"));
  t.after(async () => {
    const resolved = path.resolve(directory);
    if (path.dirname(resolved) !== path.resolve(os.tmpdir())) {
      throw new Error(`Refusing unsafe generator-test cleanup: ${resolved}`);
    }
    await rm(resolved, { force: true, recursive: true });
  });
  return directory;
}

function explicitSelections() {
  const selections = Object.fromEntries(ALL_ADAPTER_IDS.map((adapterId) => [adapterId, null]));
  selections.node = process.execPath;
  selections.git = process.execPath;
  selections.commandShell = process.execPath;
  if (process.platform === "win32") selections.taskkill = process.execPath;
  return selections;
}

function generationArguments(outputPath, selections = explicitSelections(), platform = process.platform) {
  const args = ["--output", outputPath, "--platform", platform];
  for (const adapterId of ALL_ADAPTER_IDS) {
    args.push(
      OPTION_BY_ADAPTER[adapterId],
      selections[adapterId] ?? NOT_APPLICABLE_SELECTION,
    );
  }
  return args;
}

test("generation CLI requires one explicit selection for every adapter", async (t) => {
  const directory = await temporaryDirectory(t);
  const outputPath = path.join(directory, "manifest.json");
  const complete = generationArguments(outputPath);
  const parsed = parseGenerationOptions(complete);
  assert.deepEqual(parsed.selections, explicitSelections());

  assert.throws(
    () => parseGenerationOptions(complete.slice(0, -2)),
    /--local-repository is required|--chromium is required/u,
  );
  assert.throws(
    () => parseGenerationOptions([...complete, "--node", process.execPath]),
    /Duplicate argument: --node/u,
  );
  const relativeNode = [...complete];
  relativeNode[relativeNode.indexOf("--node") + 1] = "node";
  assert.throws(
    () => parseGenerationOptions(relativeNode),
    /--node must be an explicit absolute path/u,
  );
});

test("generation CLI emits deterministic, self-validating manifest bytes", async (t) => {
  const directory = await temporaryDirectory(t);
  const firstPath = path.join(directory, "first.json");
  const secondPath = path.join(directory, "second.json");
  const first = await execFileAsync(
    process.execPath,
    [GENERATOR_PATH, ...generationArguments(firstPath)],
    { windowsHide: true },
  );
  assert.match(first.stdout, /Adapter manifest:/u);
  await execFileAsync(
    process.execPath,
    [GENERATOR_PATH, ...generationArguments(secondPath)],
    { windowsHide: true },
  );

  assert.equal(await readFile(firstPath, "utf8"), await readFile(secondPath, "utf8"));
  const loaded = await loadAdapterManifest({ manifestPath: firstPath });
  assert.equal(loaded.manifest.platform, process.platform);
  assert.equal(loaded.adapters.node.path, await realpath(process.execPath));
});

test("generation fails closed for missing executables, platform mismatch, and overwrite", async (t) => {
  const directory = await temporaryDirectory(t);
  const missingOutput = path.join(directory, "missing.json");
  const missingSelections = explicitSelections();
  missingSelections.git = path.join(directory, "absent-git");
  await assert.rejects(
    generateAdapterManifest({
      outputPath: missingOutput,
      platform: process.platform,
      selections: missingSelections,
    }),
    /git selected executable does not exist/u,
  );
  await assert.rejects(access(missingOutput));

  await assert.rejects(
    generateAdapterManifest({
      outputPath: path.join(directory, "wrong-platform.json"),
      platform: process.platform === "win32" ? "linux" : "win32",
      selections: explicitSelections(),
    }),
    /does not match runtime/u,
  );

  const existingOutput = path.join(directory, "existing.json");
  await writeFile(existingOutput, "user-owned\n", "utf8");
  await assert.rejects(
    generateAdapterManifest({
      outputPath: existingOutput,
      platform: process.platform,
      selections: explicitSelections(),
    }),
    /Refusing to overwrite adapter manifest/u,
  );
  assert.equal(await readFile(existingOutput, "utf8"), "user-owned\n");
});
