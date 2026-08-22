import { randomBytes } from "node:crypto";
import { readFileSync } from "node:fs";
import { lstat, mkdir, open, readFile, rename, rm } from "node:fs/promises";
import { dirname } from "node:path";
import { TextDecoder } from "node:util";

import { canonicalDigest, canonicalJson, sha256 } from "../docs-impact/canonical.mjs";
import { assertSchema } from "../docs-impact/schema-validator.mjs";
import {
  ADAPTER_MANIFEST_SCHEMA,
  ADAPTER_STATE,
  EXECUTABLE_ADAPTER_IDS,
} from "./adapters.mjs";

const UTF8_DECODER = new TextDecoder("utf-8", { fatal: true });

function readJson(relativeUrl) {
  return JSON.parse(UTF8_DECODER.decode(readFileSync(new URL(relativeUrl, import.meta.url))));
}

export const CONTRACT_VALUES = Object.freeze(readJson("./contracts/values.json"));
export const VALIDATION_RESULT_SCHEMA = Object.freeze(
  readJson("./contracts/validation-result.schema.json"),
);
export const RENDERED_ROUTE_RESULT_SCHEMA = Object.freeze(
  readJson("./contracts/rendered-route-result.schema.json"),
);

function namedValues(values) {
  return Object.freeze(Object.fromEntries(values.map((value) => [value, value])));
}

export const ARTIFACT_KIND = namedValues(CONTRACT_VALUES.artifactKinds);
export const CANDIDATE_MODE = namedValues(CONTRACT_VALUES.candidateModes);
export const CANDIDATE_STABILITY = namedValues(CONTRACT_VALUES.candidateStability);
export const PROFILE = namedValues(CONTRACT_VALUES.profiles);
export const RENDER_OUTCOME_STATUS = namedValues(CONTRACT_VALUES.renderOutcomeStatuses);
export const RENDER_TARGET = namedValues(CONTRACT_VALUES.renderTargets);
export const RUN_STATUS = namedValues(CONTRACT_VALUES.runStatuses);
export const STAGE_STATUS = namedValues(CONTRACT_VALUES.stageStatuses);
export const STAGE_ID = Object.freeze(
  Object.fromEntries(Object.keys(CONTRACT_VALUES.stages).map((value) => [value, value])),
);

export const VALIDATION_SCHEMA_ID = VALIDATION_RESULT_SCHEMA.$id;
export const RENDERED_ROUTE_SCHEMA_ID = RENDERED_ROUTE_RESULT_SCHEMA.$id;

function assertSameValues(label, actual, expected) {
  if (JSON.stringify(actual) !== JSON.stringify(expected)) {
    throw new Error(`${label} must project the canonical contract values exactly`);
  }
}

function assertContractProjections() {
  assertSameValues(
    "adapter manifest states",
    ADAPTER_MANIFEST_SCHEMA.$defs.state.enum,
    CONTRACT_VALUES.adapterStates,
  );
  assertSameValues(
    "adapter manifest executable IDs",
    EXECUTABLE_ADAPTER_IDS,
    CONTRACT_VALUES.executableAdapterIds,
  );
  assertSameValues(
    "validation schema candidate modes",
    VALIDATION_RESULT_SCHEMA.properties.configuration.properties.candidateMode.enum,
    CONTRACT_VALUES.candidateModes,
  );
  assertSameValues(
    "validation schema profiles",
    VALIDATION_RESULT_SCHEMA.properties.configuration.properties.profile.enum,
    CONTRACT_VALUES.profiles,
  );
  assertSameValues(
    "validation schema run statuses",
    VALIDATION_RESULT_SCHEMA.properties.runStatus.enum,
    CONTRACT_VALUES.runStatuses,
  );
  assertSameValues(
    "validation schema stage statuses",
    VALIDATION_RESULT_SCHEMA.$defs.stageResult.properties.status.enum,
    CONTRACT_VALUES.stageStatuses,
  );
  assertSameValues(
    "validation schema adapter states",
    VALIDATION_RESULT_SCHEMA.$defs.executableAdapterBinding.properties.state.enum,
    CONTRACT_VALUES.adapterStates,
  );
  assertSameValues(
    "rendered schema target values",
    RENDERED_ROUTE_RESULT_SCHEMA.properties.renderTarget.enum,
    CONTRACT_VALUES.renderTargets,
  );
  assertSameValues(
    "rendered schema outcome statuses",
    RENDERED_ROUTE_RESULT_SCHEMA.properties.status.enum,
    CONTRACT_VALUES.renderOutcomeStatuses,
  );

  const configuredStageIds = new Set(Object.keys(CONTRACT_VALUES.stages));
  const commandSpecIds = new Set();
  for (const [stageId, stage] of Object.entries(CONTRACT_VALUES.stages)) {
    if (!/^[A-Z][A-Z0-9_]*$/.test(stageId)) {
      throw new Error(`Invalid canonical stage ID: ${stageId}`);
    }
    if (commandSpecIds.has(stage.commandSpecId)) {
      throw new Error(`Duplicate command spec ID: ${stage.commandSpecId}`);
    }
    commandSpecIds.add(stage.commandSpecId);
    if (!Number.isInteger(stage.declaredTimeoutMs) || stage.declaredTimeoutMs < 1) {
      throw new Error(`${stageId} must declare a positive integer timeout`);
    }
    if (typeof stage.required !== "boolean" || !stage.name) {
      throw new Error(`${stageId} must declare required and name`);
    }
  }
  for (const profile of CONTRACT_VALUES.profiles) {
    const stageIds = CONTRACT_VALUES.profileStages[profile];
    if (!Array.isArray(stageIds) || stageIds.length === 0) {
      throw new Error(`${profile} must declare at least one stage`);
    }
    if (new Set(stageIds).size !== stageIds.length) {
      throw new Error(`${profile} contains duplicate stage IDs`);
    }
    for (const stageId of stageIds) {
      if (!configuredStageIds.has(stageId)) {
        throw new Error(`${profile} references unknown stage ${stageId}`);
      }
    }
  }
}

assertContractProjections();

export function commandSpecDigest(stageId, stage) {
  return canonicalDigest({
    commandSpecId: stage.commandSpecId,
    declaredTimeoutMs: stage.declaredTimeoutMs,
    name: stage.name,
    required: stage.required,
    stageId,
  });
}

export function summarizeResults(results) {
  return {
    total: results.length,
    passed: results.filter((result) => result.status === STAGE_STATUS.PASS).length,
    failed: results.filter((result) => result.status === STAGE_STATUS.FAIL).length,
    skipped: results.filter((result) => result.status === STAGE_STATUS.SKIP).length,
    errors: results.filter((result) => result.status === STAGE_STATUS.ERROR).length,
    timedOut: results.filter((result) => result.status === STAGE_STATUS.TIMEOUT).length,
    requiredNotPassed: results.filter(
      (result) => result.required && result.status !== STAGE_STATUS.PASS,
    ).length,
  };
}

export function candidateDigest(candidate) {
  const { candidateDigest: ignored, ...identity } = candidate;
  return canonicalDigest(identity);
}

export function toolDigest(files) {
  return canonicalDigest(files);
}

export function artifactManifestDigest(artifacts) {
  return canonicalDigest(artifacts);
}

export function receiptId(receipt) {
  const { receiptId: ignored, ...content } = receipt;
  return canonicalDigest(content);
}

function assertSummary(receipt) {
  const expected = summarizeResults(receipt.results);
  if (canonicalJson(expected) !== canonicalJson(receipt.summary)) {
    throw new Error("Documentation validation summary does not match stage results");
  }
}

function assertCandidateSemantics(candidate) {
  if (candidate.candidateDigest !== candidateDigest(candidate)) {
    throw new Error("Candidate digest does not match the candidate identity fields");
  }
  if (candidate.mode === CANDIDATE_MODE.COMMITTED_GIT) {
    if (!candidate.isClean || candidate.untrackedFileCount !== 0) {
      throw new Error("COMMITTED_GIT candidate identity must be clean with no untracked files");
    }
  }
}

function assertAdapterBindingSemantics(adapterId, binding) {
  if (binding.state === ADAPTER_STATE.CONFIGURED) {
    if (
      typeof binding.path !== "string"
      || (adapterId !== "localRepository"
        && (typeof binding.sha256 !== "string" || !Number.isInteger(binding.sizeBytes)))
    ) {
      throw new Error(`${adapterId} CONFIGURED receipt binding is incomplete`);
    }
    return;
  }
  const nullableFields = adapterId === "localRepository"
    ? ["path"]
    : ["path", "sha256", "sizeBytes"];
  if (nullableFields.some((field) => binding[field] !== null)) {
    throw new Error(`${adapterId} NOT_APPLICABLE receipt binding must contain only null values`);
  }
}

function assertAdapterSemantics(receipt) {
  const identity = receipt.identity.adapters;
  const configuration = receipt.configuration;
  if (
    configuration.adapterManifestPath !== identity.manifest.path
    || configuration.adapterManifestRawSha256 !== identity.manifest.rawSha256
    || configuration.adapterManifestCanonicalSha256 !== identity.manifest.canonicalSha256
  ) {
    throw new Error("Receipt configuration does not match its adapter manifest identity");
  }
  let manifest;
  try {
    manifest = JSON.parse(identity.manifest.canonicalJson);
  } catch (error) {
    throw new Error(`Adapter manifest canonical JSON is invalid: ${error.message}`);
  }
  assertSchema(ADAPTER_MANIFEST_SCHEMA, manifest, "receipt adapter manifest");
  if (canonicalJson(manifest) !== identity.manifest.canonicalJson) {
    throw new Error("Adapter manifest canonical JSON is not canonical");
  }
  if (canonicalDigest(manifest) !== identity.manifest.canonicalSha256) {
    throw new Error("Adapter manifest canonical digest is invalid");
  }
  if (manifest.platform !== receipt.identity.runtime.platform) {
    throw new Error("Adapter manifest platform does not match receipt runtime platform");
  }
  const receiptBindings = Object.fromEntries(
    Object.entries(identity).filter(([adapterId]) => adapterId !== "manifest"),
  );
  if (canonicalJson(manifest.adapters) !== canonicalJson(receiptBindings)) {
    throw new Error("Receipt adapter bindings do not match the canonical adapter manifest");
  }
  for (const adapterId of [...EXECUTABLE_ADAPTER_IDS, "localRepository"]) {
    assertAdapterBindingSemantics(adapterId, receiptBindings[adapterId]);
  }
  for (const adapterId of ["node", "git", "commandShell"]) {
    if (receiptBindings[adapterId].state !== ADAPTER_STATE.CONFIGURED) {
      throw new Error(`${adapterId} must be CONFIGURED in a validation receipt`);
    }
  }
  const taskkillState = receiptBindings.taskkill.state;
  if (
    (manifest.platform === "win32" && taskkillState !== ADAPTER_STATE.CONFIGURED)
    || (manifest.platform !== "win32" && taskkillState !== ADAPTER_STATE.NOT_APPLICABLE)
  ) {
    throw new Error("taskkill receipt binding is invalid for the declared platform");
  }
}

export function assertReceiptSemantics(receipt) {
  assertSchema(VALIDATION_RESULT_SCHEMA, receipt, "documentation validation receipt");
  assertSummary(receipt);
  assertCandidateSemantics(receipt.identity.candidate);
  assertAdapterSemantics(receipt);

  if (receipt.identity.tool.digest !== toolDigest(receipt.identity.tool.files)) {
    throw new Error("Tool digest does not match the tool file manifest");
  }
  if (receipt.receiptId !== receiptId(receipt)) {
    throw new Error("Receipt ID does not match the complete receipt content");
  }

  const expectedOrder = CONTRACT_VALUES.profileStages[receipt.configuration.profile];
  if (receipt.results.length > expectedOrder.length) {
    throw new Error("Receipt contains more stage results than its profile declares");
  }
  const seen = new Set();
  receipt.results.forEach((result, index) => {
    if (seen.has(result.stageId)) {
      throw new Error(`Duplicate stage result: ${result.stageId}`);
    }
    seen.add(result.stageId);
    if (result.stageId !== expectedOrder[index]) {
      throw new Error(
        `Stage result ${index} must be ${expectedOrder[index]}, received ${result.stageId}`,
      );
    }
    const contract = CONTRACT_VALUES.stages[result.stageId];
    if (
      result.commandSpecId !== contract.commandSpecId ||
      result.commandSpecDigest !== commandSpecDigest(result.stageId, contract) ||
      result.name !== contract.name ||
      result.required !== contract.required ||
      result.declaredTimeoutMs !== contract.declaredTimeoutMs
    ) {
      throw new Error(`${result.stageId} does not match its canonical command specification`);
    }
    if (result.artifactManifestDigest !== artifactManifestDigest(result.artifacts)) {
      throw new Error(`${result.stageId} artifact manifest digest is invalid`);
    }
  });

  const stability = receipt.candidateStability;
  if (stability.initialCandidateDigest !== receipt.identity.candidate.candidateDigest) {
    throw new Error("Candidate stability must reference the initial candidate identity digest");
  }
  if (stability.status === CANDIDATE_STABILITY.PENDING) {
    if (stability.completedCandidateDigest !== null) {
      throw new Error("PENDING candidate stability cannot contain a completion digest");
    }
  } else if (stability.completedCandidateDigest === null) {
    throw new Error(`${stability.status} candidate stability requires a completion digest`);
  }
  if (
    stability.status === CANDIDATE_STABILITY.MATCHED &&
    stability.completedCandidateDigest !== stability.initialCandidateDigest
  ) {
    throw new Error("MATCHED candidate stability digests must be identical");
  }
  if (
    stability.status === CANDIDATE_STABILITY.MISMATCHED &&
    stability.completedCandidateDigest === stability.initialCandidateDigest
  ) {
    throw new Error("MISMATCHED candidate stability digests must differ");
  }

  if (receipt.runStatus === RUN_STATUS.RUNNING) {
    if (receipt.completedAt !== null || receipt.runDetail !== null) {
      throw new Error("RUNNING receipt cannot contain completion fields");
    }
  } else {
    if (receipt.completedAt === null || receipt.currentStageId !== null) {
      throw new Error(`${receipt.runStatus} receipt must be complete and have no current stage`);
    }
    if (stability.status === CANDIDATE_STABILITY.PENDING) {
      throw new Error(`${receipt.runStatus} receipt cannot have pending candidate stability`);
    }
  }
  if (receipt.runStatus === RUN_STATUS.COMPLETED) {
    if (receipt.results.length !== expectedOrder.length || receipt.runDetail !== null) {
      throw new Error("COMPLETED receipt must contain every declared result and no error detail");
    }
  }
  if (receipt.runStatus === RUN_STATUS.ERROR && !receipt.runDetail) {
    throw new Error("ERROR receipt must include runDetail");
  }
  return receipt;
}

export function assertRenderedRouteSemantics(report) {
  assertSchema(RENDERED_ROUTE_RESULT_SCHEMA, report, "rendered documentation route report");
  const passed = report.routeViewportResults.filter((result) => result.status === "PASS").length;
  const failed = report.routeViewportResults.filter((result) => result.status === "FAIL").length;
  const linksFailed = report.links.filter((result) => result.status === "FAIL").length;
  const imagesFailed = report.images.filter((result) => result.status === "FAIL").length;
  const expected = {
    routes: new Set(report.routeViewportResults.map((result) => result.routePath)).size,
    viewports: new Set(report.routeViewportResults.map((result) => result.viewportId)).size,
    routeViewportChecks: report.routeViewportResults.length,
    routeViewportPassed: passed,
    routeViewportFailed: failed,
    linksChecked: report.links.length,
    linksFailed,
    imagesChecked: report.images.length,
    imagesFailed,
  };
  if (canonicalJson(expected) !== canonicalJson(report.summary)) {
    throw new Error("Rendered route summary does not match structured results");
  }
  if (
    report.status === RENDER_OUTCOME_STATUS.PASS &&
    (report.build.status !== RENDER_OUTCOME_STATUS.PASS || failed + linksFailed + imagesFailed > 0)
  ) {
    throw new Error("PASS rendered route report contains a failed build or check");
  }
  return report;
}

export async function fileIdentity(path, recordedPath) {
  const signature = (metadata) => ({
    ctimeMs: metadata.ctimeMs,
    dev: metadata.dev,
    ino: metadata.ino,
    mode: metadata.mode,
    mtimeMs: metadata.mtimeMs,
    size: metadata.size,
  });
  const before = await lstat(path);
  if (before.isSymbolicLink() || !before.isFile()) {
    throw new Error(`Evidence identity target is not a regular file: ${path}`);
  }
  const firstBytes = await readFile(path);
  const middle = await lstat(path);
  const secondBytes = await readFile(path);
  const after = await lstat(path);
  if (
    middle.isSymbolicLink()
    || after.isSymbolicLink()
    || !middle.isFile()
    || !after.isFile()
    || canonicalJson(signature(before)) !== canonicalJson(signature(middle))
    || canonicalJson(signature(middle)) !== canonicalJson(signature(after))
    || !firstBytes.equals(secondBytes)
    || firstBytes.byteLength !== after.size
  ) {
    throw new Error(`Evidence identity target changed while it was captured: ${path}`);
  }
  return {
    path: recordedPath,
    sha256: sha256(firstBytes),
    sizeBytes: firstBytes.byteLength,
  };
}

export async function readAndValidateRenderedRouteReport(path) {
  const raw = await readFile(path, "utf8");
  const report = JSON.parse(raw);
  assertRenderedRouteSemantics(report);
  return report;
}

export async function atomicWriteJson(path, value, validate = undefined) {
  if (validate) validate(value);
  await mkdir(dirname(path), { recursive: true });
  const temporaryPath = `${path}.${process.pid}.${randomBytes(8).toString("hex")}.tmp`;
  let handle;
  try {
    handle = await open(temporaryPath, "wx");
    await handle.writeFile(canonicalJson(value), "utf8");
    await handle.sync();
    await handle.close();
    handle = undefined;
    await rename(temporaryPath, path);
  } finally {
    await handle?.close();
    await rm(temporaryPath, { force: true });
  }
}
