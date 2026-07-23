import { promises as fs } from "node:fs";
import { basename, dirname, extname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { isMap, isSeq, isScalar, parseDocument } from "yaml";

const SCENARIO_FILE_NAMES = new Set(["scenario.yaml", "scenario.yml"]);
const SUT_FILE_NAME = "sut.yaml";
const BUNDLE_CONFIG_FILE_NAMES = new Set([...SCENARIO_FILE_NAMES, SUT_FILE_NAME]);
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
const NO_SAFE_VALUE = Symbol("NO_SAFE_VALUE");

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

  const bundleFiles = await discoverScenarioBundleFiles(paths);
  const hasScenarioFiles = bundleFiles.some((file) => SCENARIO_FILE_NAMES.has(basename(file)));
  const ioRequirements = hasScenarioFiles
    ? await loadIoSelectorRequirements(capabilitiesDir)
    : new Map();
  const files = [];
  for (const file of bundleFiles) {
    files.push(basename(file) === SUT_FILE_NAME
      ? await processSutFile(file, { command, dryRun })
      : await processScenarioFile(file, { command, dryRun, ioRequirements }));
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

export async function discoverScenarioBundleFiles(paths) {
  const discovered = new Map();
  for (const input of paths) {
    const root = resolve(input);
    await collectScenarioBundleFiles(root, discovered);
  }
  return Array.from(discovered.values()).sort();
}

async function collectScenarioBundleFiles(path, discovered) {
  const stat = await fs.stat(path);
  if (stat.isFile()) {
    if (!BUNDLE_CONFIG_FILE_NAMES.has(basename(path))) {
      throw new Error(`Not a scenario bundle config file: ${path}. Expected scenario.yaml, scenario.yml, or sut.yaml.`);
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
        await collectScenarioBundleFiles(child, discovered);
      }
      continue;
    }
    if (entry.isFile() && BUNDLE_CONFIG_FILE_NAMES.has(entry.name)) {
      discovered.set(child, child);
    }
  }
}

async function processSutFile(file, { command, dryRun }) {
  const original = await fs.readFile(file, "utf8");
  const doc = parseDocument(original, { keepSourceTokens: true });
  const context = {
    doc,
    file,
    command,
    migrate: command === "migrate",
    operations: [],
    findings: [],
  };

  if (!reportYamlParseErrors(doc, context)) {
    migrateSutEndpointIds(doc, context);
  }

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

function migrateSutEndpointIds(doc, context) {
  const endpoints = doc.get("endpoints", true);
  if (!isMap(endpoints)) {
    return;
  }
  endpoints.items.forEach((endpointPair) => {
    const endpointId = keyOf(endpointPair);
    const endpoint = endpointPair.value;
    if (!isMap(endpoint)) {
      return;
    }
    const nestedIdPair = getPair(endpoint, "id");
    if (!nestedIdPair) {
      return;
    }
    const nestedIdNode = nestedIdPair.value;
    const nestedIdIsNull = isScalar(nestedIdNode)
      && (nestedIdNode.value === null || nestedIdNode.value === undefined);
    const nestedIdMatchesKey = isScalar(nestedIdNode)
      && typeof nestedIdNode.value === "string"
      && nestedIdNode.value === endpointId;
    const path = `endpoints.${endpointId}.id`;
    if (!nestedIdIsNull && !nestedIdMatchesKey) {
      addError(context, {
        code: "SUT_ENDPOINT_ID_CONFLICT",
        path,
        message: `Nested SUT endpoint id must be null or exactly match canonical map key '${endpointId}'.`,
        fix: "Choose the intended endpoint map key, remove the nested id manually, then rerun the migrator.",
      });
      return;
    }
    if (context.command === "check") {
      context.findings.push({
        severity: "error",
        code: "LEGACY_SUT_ENDPOINT_ID",
        path,
        message: "Nested SUT endpoint id is legacy; the endpoints map key is the only endpoint identifier.",
        fix: "Run tools/scenario-config-migrate migrate on this scenario bundle.",
      });
      return;
    }
    context.operations.push(removeOperation(context, path));
    deleteKey(endpoint, "id");
  });
}

async function processScenarioFile(file, { command, dryRun, ioRequirements }) {
  const original = await fs.readFile(file, "utf8");
  const doc = parseDocument(original, { keepSourceTokens: true });
  const context = {
    doc,
    file,
    command,
    migrate: command === "migrate",
    ioRequirements,
    operations: [],
    findings: [],
  };

  if (!reportYamlParseErrors(doc, context)) {
    migrateDocument(doc, context);
  }

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
  const authoring = migrateScenarioAuthoring(bees, context);
  migrateTopologyEndpoints(doc, authoring, context);
  bees.items.forEach((bee, beeIndex) => {
    if (!isMap(bee)) {
      return;
    }
    const beeRole = authoring.rolesByBeeIndex.get(beeIndex)
      ?? scalarValue(getNode(bee, "role"))
      ?? String(beeIndex);
    const config = getNode(bee, "config");
    if (!isMap(config)) {
      return;
    }
    migrateConfigMap(config, { ...context, beeIndex, beeRole });
  });
}

function migrateScenarioAuthoring(bees, context) {
  const legacyIdToRole = new Map();
  const declaredRoles = new Set();
  const rolesByBeeIndex = new Map();

  bees.items.forEach((bee, beeIndex) => {
    if (!isMap(bee)) {
      return;
    }
    const legacyId = trimToNull(scalarValue(getNode(bee, "id")));
    const currentRole = trimToNull(scalarValue(getNode(bee, "role")));
    const migratedRole = legacyId ?? currentRole;
    const beeContext = { ...context, beeIndex, beeRole: migratedRole ?? String(beeIndex) };

    if (!migratedRole) {
      addError(beeContext, {
        code: "SCENARIO_AUTHORING_INVALID",
        path: `template.bees[${beeIndex}].role`,
        message: "Scenario bee role is required.",
        fix: "Set template.bees[].role to the unique scenario node role.",
      });
      return;
    }

    if (declaredRoles.has(migratedRole)) {
      addError(beeContext, {
        code: "SCENARIO_AUTHORING_CONFLICT",
        path: `template.bees[${beeIndex}].role`,
        message: `Scenario bee role '${migratedRole}' is not unique.`,
        fix: "Rename one bee role and update topology endpoints to the new role.",
      });
    }
    declaredRoles.add(migratedRole);
    rolesByBeeIndex.set(beeIndex, migratedRole);

    if (!legacyId) {
      return;
    }
    legacyIdToRole.set(legacyId, migratedRole);
    if (context.command === "check") {
      context.findings.push({
        severity: "error",
        code: "LEGACY_SCENARIO_AUTHORING",
        path: `template.bees[${beeIndex}].id`,
        beeRole: migratedRole,
        beeIndex,
        message: "template.bees[].id is legacy scenario authoring shape; role is the only scenario node key.",
        fix: "Run tools/scenario-config-migrate migrate on this scenario file.",
      });
      return;
    }

    context.operations.push(setOperation(
      beeContext,
      `template.bees[${beeIndex}].role`,
      migratedRole,
      "template.bees[].id becomes the unique role"
    ));
    context.operations.push(removeOperation(beeContext, `template.bees[${beeIndex}].id`));
    if (context.migrate) {
      bee.set("role", migratedRole);
      deleteKey(bee, "id");
    }
  });

  return { legacyIdToRole, declaredRoles, rolesByBeeIndex };
}

function migrateTopologyEndpoints(doc, authoring, context) {
  const edges = doc.getIn(["topology", "edges"], true);
  if (!isSeq(edges)) {
    return;
  }
  edges.items.forEach((edge, edgeIndex) => {
    if (!isMap(edge)) {
      return;
    }
    for (const endpointName of ["from", "to"]) {
      const endpoint = getNode(edge, endpointName);
      if (!isMap(endpoint)) {
        continue;
      }
      migrateTopologyEndpoint(endpoint, endpointName, edgeIndex, authoring, context);
    }
  });
}

function migrateTopologyEndpoint(endpoint, endpointName, edgeIndex, authoring, context) {
  const legacyBeeId = trimToNull(scalarValue(getNode(endpoint, "beeId")));
  const currentRole = trimToNull(scalarValue(getNode(endpoint, "role")));
  const migratedRole = legacyBeeId ? (authoring.legacyIdToRole.get(legacyBeeId) ?? legacyBeeId) : currentRole;
  const pathBase = `topology.edges[${edgeIndex}].${endpointName}`;
  if (currentRole && migratedRole && currentRole !== migratedRole) {
    addError(context, {
      code: "SCENARIO_AUTHORING_CONFLICT",
      path: `${pathBase}.role`,
      message: `Cannot migrate ${pathBase}; existing role '${currentRole}' conflicts with legacy beeId '${legacyBeeId}'.`,
      fix: "Resolve the topology endpoint manually, then rerun the migrator.",
    });
    return;
  }
  if (migratedRole && !authoring.declaredRoles.has(migratedRole)) {
    addError(context, {
      code: "SCENARIO_AUTHORING_INVALID",
      path: `${pathBase}.${legacyBeeId ? "beeId" : "role"}`,
      message: `Topology endpoint role '${migratedRole}' is not declared in template.bees.`,
      fix: "Use an existing template.bees role or add the missing bee.",
    });
    return;
  }
  if (!legacyBeeId) {
    return;
  }

  if (context.command === "check") {
    context.findings.push({
      severity: "error",
      code: "LEGACY_SCENARIO_AUTHORING",
      path: `${pathBase}.beeId`,
      message: "topology endpoint beeId is legacy scenario authoring shape; role is the only topology endpoint key.",
      fix: "Run tools/scenario-config-migrate migrate on this scenario file.",
    });
    return;
  }

  const endpointContext = { ...context, beeRole: migratedRole };
  context.operations.push(moveOperation(endpointContext, `${pathBase}.beeId`, `${pathBase}.role`));
  if (context.migrate) {
    endpoint.set("role", migratedRole);
    deleteKey(endpoint, "beeId");
  }
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
  const bySelector = new Map();
  for (const entry of entries) {
    if (!entry.isFile() || !CAPABILITY_FILE_EXTENSIONS.has(extname(entry.name))) {
      continue;
    }
    const file = join(dir, entry.name);
    const manifest = parseDocument(await fs.readFile(file, "utf8")).toJSON();
    addIoRequirementsFromManifest(manifest, { bySubblockPath, bySelector }, file);
  }
  return { bySubblockPath, bySelector };
}

function addIoRequirementsFromManifest(manifest, requirements, file) {
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
    const existing = requirements.bySubblockPath.get(subblockPath);
    if (existing && existing.expectedSelector !== ioType) {
      throw new Error(
        `Conflicting IO selector requirements for ${subblockPath}: ${existing.expectedSelector} and ${ioType} (${file})`
      );
    }
    requirements.bySubblockPath.set(subblockPath, {
      subblockPath,
      configRoot,
      selectorKey: TYPE_CONFIG_LEAF,
      selectorPath,
      expectedSelector: ioType,
    });
  }

  const requiredEntries = manifest.config
    .filter((entry) => entry?.required === true)
    .map((entry) => ({
      path: trimToNull(entry.name),
      allowBlank: entry.allowBlank === true,
      defaultValue: Object.hasOwn(entry, "default") ? entry.default : NO_SAFE_VALUE,
    }))
    .filter((entry) => entry.path);
  if (requiredEntries.length > 0) {
    requirements.bySelector.set(`${selectorPath}|${ioType}`, {
      selectorPath,
      expectedSelector: ioType,
      requiredEntries,
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
  migrateSelectedIoRequiredConfig(config, context);
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

function migrateSelectedIoRequiredConfig(config, context) {
  for (const configRoot of [INPUT_CONFIG_ROOT, OUTPUT_CONFIG_ROOT]) {
    const root = getNode(config, configRoot);
    if (!isMap(root)) {
      continue;
    }
    const selectorPath = `${configRoot}.${TYPE_CONFIG_LEAF}`;
    const selector = trimToNull(scalarValue(getNode(root, TYPE_CONFIG_LEAF)));
    if (!selector) {
      continue;
    }
    const requirement = context.ioRequirements.bySelector.get(`${selectorPath}|${selector}`);
    if (!requirement) {
      continue;
    }
    for (const entry of requirement.requiredEntries) {
      if (hasPathValue(config, entry.path, entry.allowBlank)) {
        continue;
      }
      handleMissingSelectedIoConfig(config, entry.path, context, entry.defaultValue);
    }
  }
}

function handleMissingSelectedIoConfig(config, dottedPath, context, defaultValue = NO_SAFE_VALUE) {
  const value = defaultValue;
  const path = `template.bees[${context.beeIndex}].config.${dottedPath}`;
  if (value === NO_SAFE_VALUE) {
    addError(context, {
      code: "IO_REQUIRED_CONFIG_MISSING",
      path,
      message: `Selected IO config is missing required field '${dottedPath}'.`,
      fix: `Set config.${dottedPath} explicitly.`,
    });
    return;
  }
  if (context.command === "check") {
    addError(context, {
      code: "IO_REQUIRED_CONFIG_MISSING",
      path,
      message: `Selected IO config is missing required field '${dottedPath}'.`,
      fix: "Run tools/scenario-config-migrate migrate on this scenario file.",
    });
    return;
  }
  context.operations.push(setOperation(
    context,
    path,
    value,
    "required by selected IO manifest"
  ));
  if (context.migrate) {
    setPathValue(config, dottedPath, value, context);
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
      beeRole: context.beeRole,
      beeIndex: context.beeIndex,
      message,
      fix: "Run tools/scenario-config-migrate migrate on this scenario file.",
    });
  }
}

function addError(context, finding) {
  context.findings.push({
    severity: "error",
    beeRole: context.beeRole,
    beeIndex: context.beeIndex,
    ...finding,
  });
}

function reportYamlParseErrors(doc, context) {
  for (const error of doc.errors) {
    const position = error.linePos?.[0];
    addError(context, {
      code: "INVALID_YAML",
      path: position ? `$:${position.line}:${position.col}` : "$",
      message: `Invalid YAML: ${error.message}`,
      fix: "Fix the YAML syntax, then rerun the migrator.",
    });
  }
  return doc.errors.length > 0;
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
      if (finding.code.startsWith("LEGACY_")) summary.legacyFindings += 1;
      if (finding.code.startsWith("IO_SELECTOR_")) summary.ioSelectorFindings += 1;
      if (finding.severity === "error") summary.errors += 1;
    }
  }
  return summary;
}

function hasErrors(findings) {
  return findings.some((finding) => finding.severity === "error" && !finding.code.startsWith("LEGACY_"));
}

function moveOperation(context, sourcePath, targetPath, note = null) {
  return {
    action: "move",
    beeRole: context.beeRole,
    beeIndex: context.beeIndex,
    sourcePath,
    targetPath,
    ...(note ? { note } : {}),
  };
}

function removeOperation(context, sourcePath) {
  return {
    action: "remove",
    beeRole: context.beeRole,
    beeIndex: context.beeIndex,
    sourcePath,
  };
}

function setOperation(context, targetPath, value, note = null) {
  return {
    action: "set",
    beeRole: context.beeRole,
    beeIndex: context.beeIndex,
    targetPath,
    value,
    ...(note ? { note } : {}),
  };
}

function hasPathValue(map, dottedPath, allowBlank = false) {
  const node = getPathNode(map, dottedPath);
  if (node === undefined || node === null) {
    return false;
  }
  if (isScalar(node)) {
    const value = node.value;
    if (value === undefined || value === null) {
      return false;
    }
    return typeof value !== "string" || allowBlank || value.trim().length > 0;
  }
  return true;
}

function getPathNode(map, dottedPath) {
  let current = map;
  for (const segment of dottedPath.split(".")) {
    if (!isMap(current)) {
      return undefined;
    }
    current = getNode(current, segment);
    if (current === undefined) {
      return undefined;
    }
  }
  return current;
}

function setPathValue(map, dottedPath, value, context) {
  const segments = dottedPath.split(".");
  let current = map;
  for (const segment of segments.slice(0, -1)) {
    let child = getNode(current, segment);
    if (!isMap(child)) {
      child = context.doc.createNode({});
      current.set(segment, child);
    }
    current = child;
  }
  current.set(segments.at(-1), value);
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
  if (isScalar(node)) return node.value === undefined || node.value === null ? null : String(node.value);
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
