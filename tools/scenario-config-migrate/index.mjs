import { promises as fs } from "node:fs";
import { basename, dirname, extname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { isMap, isSeq, isScalar, parseDocument } from "yaml";

const SCENARIO_FILE_NAMES = new Set(["scenario.yaml", "scenario.yml"]);
const SKIP_DIRS = new Set([".git", "node_modules", "target", "build", "dist"]);
const CAPABILITY_FILE_EXTENSIONS = new Set([".json", ".yaml", ".yml"]);
const DEFAULT_CAPABILITIES_DIR = resolve(
  dirname(fileURLToPath(import.meta.url)),
  "../../scenario-manager-service/capabilities"
);
const INPUT_IO_SCOPE = "INPUT";
const OUTPUT_IO_SCOPE = "OUTPUT";
const INPUT_CONFIG_ROOT = "inputs";
const OUTPUT_CONFIG_ROOT = "outputs";
const TYPE_CONFIG_LEAF = "type";

export async function runScenarioConfigMigration({
  command,
  paths,
  dryRun = false,
  capabilitiesDir = DEFAULT_CAPABILITIES_DIR,
} = {}) {
  if (!["check", "migrate"].includes(command)) {
    throw new Error("command must be 'check' or 'migrate'");
  }
  if (!Array.isArray(paths) || paths.length === 0) {
    throw new Error(`${command} requires at least one path`);
  }

  const ioRequirements = await loadIoSelectorRequirements(capabilitiesDir);
  const scenarioFiles = await discoverScenarioFiles(paths);
  const files = [];
  for (const file of scenarioFiles) {
    files.push(await processScenarioFile(file, { command, dryRun, ioRequirements }));
  }

  const summary = summarize(files);
  return {
    ok: summary.errors === 0 && (command !== "check" || summary.legacyFindings === 0),
    command,
    dryRun,
    files,
    summary,
  };
}

export async function discoverScenarioFiles(paths) {
  const discovered = new Map();
  for (const input of paths) {
    const root = resolve(input);
    await collectScenarioFiles(root, discovered);
  }
  return Array.from(discovered.values()).sort();
}

async function collectScenarioFiles(path, discovered) {
  const stat = await fs.stat(path);
  if (stat.isFile()) {
    if (!SCENARIO_FILE_NAMES.has(basename(path))) {
      throw new Error(`Not a scenario file: ${path}. Expected scenario.yaml or scenario.yml.`);
    }
    discovered.set(path, path);
    return;
  }
  if (!stat.isDirectory()) {
    throw new Error(`Unsupported path type: ${path}`);
  }
  const entries = await fs.readdir(path, { withFileTypes: true });
  for (const entry of entries) {
    const child = join(path, entry.name);
    if (entry.isDirectory()) {
      if (!SKIP_DIRS.has(entry.name)) {
        await collectScenarioFiles(child, discovered);
      }
      continue;
    }
    if (entry.isFile() && SCENARIO_FILE_NAMES.has(entry.name)) {
      discovered.set(child, child);
    }
  }
}

async function processScenarioFile(file, { command, dryRun, ioRequirements }) {
  const original = await fs.readFile(file, "utf8");
  const doc = parseDocument(original, { keepSourceTokens: true });
  const context = {
    file,
    command,
    migrate: command === "migrate",
    ioRequirements,
    operations: [],
    findings: [],
  };

  migrateDocument(doc, context);

  const changed = context.operations.some((operation) => operation.action !== "inspect");
  let output = null;
  if (context.migrate && changed) {
    output = doc.toString();
    if (!dryRun && !hasErrors(context.findings)) {
      await fs.writeFile(file, output, "utf8");
    }
  }

  return {
    path: file,
    changed,
    operations: context.operations,
    findings: context.findings,
    ...(dryRun && output !== null ? { output } : {}),
  };
}

function migrateDocument(doc, context) {
  const bees = doc.getIn(["template", "bees"], true);
  if (!isSeq(bees)) {
    return;
  }
  bees.items.forEach((bee, beeIndex) => {
    if (!isMap(bee)) {
      return;
    }
    const beeId = scalarValue(getNode(bee, "id")) ?? scalarValue(getNode(bee, "role")) ?? String(beeIndex);
    const config = getNode(bee, "config");
    if (!isMap(config)) {
      return;
    }
    migrateConfigMap(config, { ...context, beeIndex, beeId });
  });
}

function migrateConfigMap(config, context) {
  const worker = getNode(config, "worker");
  if (worker !== undefined) {
    handleWorkerMap(config, worker, {
      ...context,
      legacyRoot: "worker",
      sourceBase: `template.bees[${context.beeIndex}].config.worker`,
    });
  }

  const pockethive = getNode(config, "pockethive");
  if (pockethive !== undefined) {
    handlePockethiveMap(config, pockethive, {
      ...context,
      sourceBase: `template.bees[${context.beeIndex}].config.pockethive`,
    });
  }

  migrateIoSelectors(config, context);
}

async function loadIoSelectorRequirements(capabilitiesDir) {
  const dir = resolve(capabilitiesDir);
  const entries = await fs.readdir(dir, { withFileTypes: true }).catch((error) => {
    if (error && error.code === "ENOENT") {
      throw new Error(`Capability manifest directory not found: ${dir}`);
    }
    throw error;
  });
  const bySubblockPath = new Map();
  for (const entry of entries) {
    if (!entry.isFile() || !CAPABILITY_FILE_EXTENSIONS.has(extname(entry.name))) {
      continue;
    }
    const file = join(dir, entry.name);
    const manifest = parseDocument(await fs.readFile(file, "utf8")).toJSON();
    addIoSelectorRequirementsFromManifest(manifest, bySubblockPath, file);
  }
  return { bySubblockPath };
}

function addIoSelectorRequirementsFromManifest(manifest, bySubblockPath, file) {
  const ui = manifest?.ui;
  const ioType = trimToNull(ui?.ioType);
  const scope = trimToNull(ui?.ioScope)?.toUpperCase();
  const selectorPath = selectorPathForIoScope(scope);
  const configRoot = configRootForIoScope(scope);
  if (!ioType || !selectorPath || !configRoot || !Array.isArray(manifest?.config)) {
    return;
  }

  for (const entry of manifest.config) {
    const subblockPath = ioSubblockPath(entry?.name, configRoot);
    if (!subblockPath) {
      continue;
    }
    const existing = bySubblockPath.get(subblockPath);
    if (existing && existing.expectedSelector !== ioType) {
      throw new Error(
        `Conflicting IO selector requirements for ${subblockPath}: ${existing.expectedSelector} and ${ioType} (${file})`
      );
    }
    bySubblockPath.set(subblockPath, {
      subblockPath,
      configRoot,
      selectorKey: TYPE_CONFIG_LEAF,
      selectorPath,
      expectedSelector: ioType,
    });
  }
}

function selectorPathForIoScope(scope) {
  if (scope === INPUT_IO_SCOPE) {
    return `${INPUT_CONFIG_ROOT}.${TYPE_CONFIG_LEAF}`;
  }
  if (scope === OUTPUT_IO_SCOPE) {
    return `${OUTPUT_CONFIG_ROOT}.${TYPE_CONFIG_LEAF}`;
  }
  return null;
}

function configRootForIoScope(scope) {
  if (scope === INPUT_IO_SCOPE) {
    return INPUT_CONFIG_ROOT;
  }
  if (scope === OUTPUT_IO_SCOPE) {
    return OUTPUT_CONFIG_ROOT;
  }
  return null;
}

function ioSubblockPath(configName, configRoot) {
  const normalized = trimToNull(configName);
  if (!normalized) {
    return null;
  }
  const segments = normalized.split(".");
  if (segments.length < 3 || segments[0] !== configRoot || segments[1] === TYPE_CONFIG_LEAF) {
    return null;
  }
  return `${segments[0]}.${segments[1]}`;
}

function migrateIoSelectors(config, context) {
  migrateIoSelectorForRoot(config, INPUT_CONFIG_ROOT, context);
  migrateIoSelectorForRoot(config, OUTPUT_CONFIG_ROOT, context);
}

function migrateIoSelectorForRoot(config, configRoot, context) {
  const root = getNode(config, configRoot);
  if (!isMap(root)) {
    return;
  }

  const blocks = [];
  const seenSubblocks = new Set();
  for (const pair of root.items) {
    const key = keyOf(pair);
    if (key === TYPE_CONFIG_LEAF) {
      continue;
    }
    const subblockPath = `${configRoot}.${key}`;
    if (seenSubblocks.has(subblockPath)) {
      continue;
    }
    seenSubblocks.add(subblockPath);
    const requirement = context.ioRequirements.bySubblockPath.get(subblockPath);
    if (requirement) {
      blocks.push(requirement);
    }
  }

  if (blocks.length === 0) {
    return;
  }

  const selectorPath = `${configRoot}.${TYPE_CONFIG_LEAF}`;
  const selectorNode = getNode(root, TYPE_CONFIG_LEAF);
  const selector = trimToNull(scalarValue(selectorNode));
  if (!selector) {
    handleMissingIoSelector(root, selectorPath, blocks, context);
    return;
  }

  for (const block of blocks) {
    if (selector !== block.expectedSelector) {
      addError(context, {
        code: "IO_SELECTOR_MISMATCH",
        path: `template.bees[${context.beeIndex}].config.${selectorPath}`,
        message: `IO subblock '${block.subblockPath}' requires ${selectorPath}: ${block.expectedSelector}, but found '${selector}'.`,
        fix: `Set config.${selectorPath} to ${block.expectedSelector} or remove config.${block.subblockPath}.`,
      });
    }
  }
}

function handleMissingIoSelector(root, selectorPath, blocks, context) {
  const expectedSelectors = Array.from(new Set(blocks.map((block) => block.expectedSelector)));
  if (blocks.length !== 1 || expectedSelectors.length !== 1) {
    addError(context, {
      code: "IO_SELECTOR_AMBIGUOUS",
      path: `template.bees[${context.beeIndex}].config.${selectorPath}`,
      message: `Cannot infer ${selectorPath}; found IO subblocks ${blocks.map((block) => `'${block.subblockPath}'`).join(", ")}.`,
      fix: `Set config.${selectorPath} explicitly and remove IO subblocks that do not match it.`,
    });
    return;
  }

  const expectedSelector = expectedSelectors[0];
  if (context.command === "check") {
    addError(context, {
      code: "IO_SELECTOR_MISSING",
      path: `template.bees[${context.beeIndex}].config.${selectorPath}`,
      message: `IO subblock '${blocks[0].subblockPath}' requires explicit ${selectorPath}: ${expectedSelector}.`,
      fix: "Run tools/scenario-config-migrate migrate on this scenario file.",
    });
    return;
  }

  context.operations.push(setOperation(
    context,
    `template.bees[${context.beeIndex}].config.${selectorPath}`,
    expectedSelector,
    `required by ${blocks[0].subblockPath}`
  ));
  if (context.migrate) {
    root.set(TYPE_CONFIG_LEAF, expectedSelector);
  }
}

function handleWorkerMap(config, worker, context) {
  if (!isMap(worker)) {
    addError(context, {
      code: "LEGACY_CONFIG_NOT_MAP",
      path: context.sourceBase,
      message: "Legacy config.worker must be a mapping before it can be migrated.",
      fix: "Replace config.worker with a mapping or move the intended values directly under config.",
    });
    return;
  }

  reportLegacy(context, context.sourceBase, "config.worker is a legacy scenario bee config shape.");

  const nestedConfig = getNode(worker, "config");
  if (nestedConfig !== undefined) {
    if (!isMap(nestedConfig)) {
      addError(context, {
        code: "LEGACY_CONFIG_NOT_MAP",
        path: `${context.sourceBase}.config`,
        message: "Legacy config.worker.config must be a mapping before it can be migrated.",
        fix: "Replace config.worker.config with a mapping or move the intended values directly under config.",
      });
    } else {
      moveChildren({
        target: config,
        source: nestedConfig,
        sourcePath: `${context.sourceBase}.config`,
        targetPath: "template.bees[].config",
        context,
      });
    }
  }

  const workerWithoutConfig = worker.items.filter((pair) => keyOf(pair) !== "config");
  for (const pair of workerWithoutConfig) {
    movePair({
      target: config,
      pair,
      sourcePath: `${context.sourceBase}.${keyOf(pair)}`,
      targetPath: `template.bees[].config.${keyOf(pair)}`,
      context,
    });
  }

  if (context.migrate && !hasErrors(context.findings)) {
    deleteKey(config, context.legacyRoot);
    context.operations.push(removeOperation(context, context.sourceBase));
  }
}

function handlePockethiveMap(config, pockethive, context) {
  if (!isMap(pockethive)) {
    addError(context, {
      code: "LEGACY_CONFIG_NOT_MAP",
      path: context.sourceBase,
      message: "Legacy config.pockethive must be a mapping before it can be migrated.",
      fix: "Remove config.pockethive or move supported worker values directly under config.",
    });
    return;
  }
  reportLegacy(context, context.sourceBase, "config.pockethive is a legacy scenario bee config shape.");

  const worker = getNode(pockethive, "worker");
  if (worker !== undefined) {
    handleWorkerMap(config, worker, {
      ...context,
      legacyRoot: "worker",
      sourceBase: `${context.sourceBase}.worker`,
    });
    if (context.migrate && !hasErrors(context.findings)) {
      deleteKey(pockethive, "worker");
    }
  }

  const unsupported = pockethive.items.filter((pair) => keyOf(pair) !== "worker");
  if (unsupported.length > 0) {
    addError(context, {
      code: "UNSUPPORTED_POCKETHIVE_CONFIG",
      path: context.sourceBase,
      message: "config.pockethive contains keys outside pockethive.worker and cannot be migrated mechanically.",
      fix: "Move the intended public worker config directly under config, then remove config.pockethive.",
    });
    return;
  }

  if (context.migrate && !hasErrors(context.findings)) {
    deleteKey(config, "pockethive");
    context.operations.push(removeOperation(context, context.sourceBase));
  }
}

function moveChildren({ target, source, sourcePath, targetPath, context }) {
  for (const pair of source.items) {
    const key = keyOf(pair);
    movePair({
      target,
      pair,
      sourcePath: `${sourcePath}.${key}`,
      targetPath: `${targetPath}.${key}`,
      context,
    });
  }
}

function movePair({ target, pair, sourcePath, targetPath, context }) {
  const key = keyOf(pair);
  const existing = getNode(target, key);
  if (existing !== undefined) {
    if (!nodeEquals(existing, pair.value)) {
      addError(context, {
        code: "CONFIG_MIGRATION_CONFLICT",
        path: sourcePath,
        targetPath,
        message: `Cannot migrate ${sourcePath}; target ${targetPath} already exists with a different value.`,
        fix: "Resolve the target key manually, then rerun the migrator.",
      });
      return;
    }
    context.operations.push(moveOperation(context, sourcePath, targetPath, "target already has identical value"));
    return;
  }

  context.operations.push(moveOperation(context, sourcePath, targetPath));
  if (context.migrate) {
    target.set(key, pair.value);
  }
}

function reportLegacy(context, path, message) {
  if (context.command === "check") {
    context.findings.push({
      severity: "error",
      code: "LEGACY_CONFIG_SHAPE",
      path,
      beeId: context.beeId,
      beeIndex: context.beeIndex,
      message,
      fix: "Run tools/scenario-config-migrate migrate on this scenario file.",
    });
  }
}

function addError(context, finding) {
  context.findings.push({
    severity: "error",
    beeId: context.beeId,
    beeIndex: context.beeIndex,
    ...finding,
  });
}

function summarize(files) {
  const summary = {
    files: files.length,
    changed: 0,
    operations: 0,
    findings: 0,
    legacyFindings: 0,
    ioSelectorFindings: 0,
    errors: 0,
  };
  for (const file of files) {
    if (file.changed) summary.changed += 1;
    summary.operations += file.operations.length;
    summary.findings += file.findings.length;
    for (const finding of file.findings) {
      if (finding.code === "LEGACY_CONFIG_SHAPE") summary.legacyFindings += 1;
      if (finding.code.startsWith("IO_SELECTOR_")) summary.ioSelectorFindings += 1;
      if (finding.severity === "error") summary.errors += 1;
    }
  }
  return summary;
}

function hasErrors(findings) {
  return findings.some((finding) => finding.severity === "error" && finding.code !== "LEGACY_CONFIG_SHAPE");
}

function moveOperation(context, sourcePath, targetPath, note = null) {
  return {
    action: "move",
    beeId: context.beeId,
    beeIndex: context.beeIndex,
    sourcePath,
    targetPath,
    ...(note ? { note } : {}),
  };
}

function removeOperation(context, sourcePath) {
  return {
    action: "remove",
    beeId: context.beeId,
    beeIndex: context.beeIndex,
    sourcePath,
  };
}

function setOperation(context, targetPath, value, note = null) {
  return {
    action: "set",
    beeId: context.beeId,
    beeIndex: context.beeIndex,
    targetPath,
    value,
    ...(note ? { note } : {}),
  };
}

function getNode(map, key) {
  const pair = getPair(map, key);
  return pair ? pair.value : undefined;
}

function getPair(map, key) {
  if (!isMap(map)) return null;
  return map.items.find((pair) => keyOf(pair) === key) ?? null;
}

function deleteKey(map, key) {
  const index = map.items.findIndex((pair) => keyOf(pair) === key);
  if (index >= 0) {
    map.items.splice(index, 1);
    return true;
  }
  return false;
}

function keyOf(pair) {
  const key = pair.key;
  if (isScalar(key)) return String(key.value);
  return String(key);
}

function scalarValue(node) {
  if (node === undefined || node === null) return null;
  if (isScalar(node)) return String(node.value);
  return null;
}

function trimToNull(value) {
  if (value === undefined || value === null) return null;
  const trimmed = String(value).trim();
  return trimmed.length === 0 ? null : trimmed;
}

function nodeEquals(left, right) {
  return JSON.stringify(normalizeNode(left)) === JSON.stringify(normalizeNode(right));
}

function normalizeNode(node) {
  if (node === undefined || node === null) return null;
  const value = typeof node.toJSON === "function" ? node.toJSON() : node;
  return normalizeValue(value);
}

function normalizeValue(value) {
  if (Array.isArray(value)) return value.map(normalizeValue);
  if (value && typeof value === "object") {
    return Object.fromEntries(
      Object.entries(value)
        .sort(([a], [b]) => a.localeCompare(b))
        .map(([key, entry]) => [key, normalizeValue(entry)])
    );
  }
  return value;
}
