import assert from "node:assert/strict";
import { access, mkdtemp, readFile, readdir, rm, symlink, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import test from "node:test";

import { canonicalDigest, sha256 } from "../docs-impact/canonical.mjs";
import {
  CANDIDATE_STABILITY,
  CONTRACT_VALUES,
  RUN_STATUS,
  STAGE_STATUS,
  VALIDATION_SCHEMA_ID,
  artifactManifestDigest,
  assertRenderedRouteSemantics,
  assertReceiptSemantics,
  atomicWriteJson,
  candidateDigest,
  commandSpecDigest,
  fileIdentity,
  receiptId,
  summarizeResults,
  toolDigest,
} from "./evidence.mjs";
import {
  collectCandidateIdentity,
  executeStage,
  parseOptions,
  resolveAndValidateOptions,
} from "./run.mjs";
import { loadAdapterManifest } from "./adapters.mjs";
import {
  syntheticAdapterIdentity,
  writeAdapterManifest,
} from "./adapter-test-helpers.mjs";

const REPOSITORY_ROOT = resolve(import.meta.dirname, "../..");
const SHA_A = "a".repeat(40);
const SHA_B = "b".repeat(40);
const DIGEST_A = "a".repeat(64);

function validIdentity() {
  const candidateFields = {
    mode: "DIRTY_WORKTREE",
    verification: "VERIFIED",
    headSha: SHA_A,
    headTreeSha: SHA_B,
    isClean: false,
    trackedPatchSha256: DIGEST_A,
    untrackedManifestSha256: "b".repeat(64),
    untrackedFileCount: 1,
  };
  const files = [{ path: "tool.mjs", sha256: DIGEST_A, sizeBytes: 1 }];
  return {
    repository: { root: REPOSITORY_ROOT, gitTopLevel: REPOSITORY_ROOT },
    candidate: {
      ...candidateFields,
      candidateDigest: candidateDigest(candidateFields),
    },
    runtime: {
      nodeVersion: process.version,
      platform: process.platform,
      architecture: process.arch,
      osRelease: "test",
      gitVersion: "git version test",
    },
    adapters: syntheticAdapterIdentity(REPOSITORY_ROOT, DIGEST_A),
    tool: {
      version: CONTRACT_VALUES.toolVersion,
      digest: toolDigest(files),
      files,
    },
    lockfiles: [{ path: "package-lock.json", sha256: DIGEST_A, sizeBytes: 1 }],
  };
}

function validRunningReceipt() {
  const identity = validIdentity();
  const configuration = {
    profile: "static",
    repositoryRoot: REPOSITORY_ROOT,
    reportPath: join(REPOSITORY_ROOT, ".test-results", "test.json"),
    artifactDirectory: join(REPOSITORY_ROOT, ".test-results", "test.json.artifacts"),
    adapterManifestPath: identity.adapters.manifest.path,
    adapterManifestRawSha256: identity.adapters.manifest.rawSha256,
    adapterManifestCanonicalSha256: identity.adapters.manifest.canonicalSha256,
    candidateMode: "DIRTY_WORKTREE",
    baseUrl: null,
    docsUrl: null,
  };
  const startedAt = new Date().toISOString();
  const receipt = {
    schemaVersion: CONTRACT_VALUES.receiptSchemaVersion,
    schemaId: VALIDATION_SCHEMA_ID,
    toolVersion: CONTRACT_VALUES.toolVersion,
    receiptId: "0".repeat(64),
    checkpointSequence: 0,
    runStatus: RUN_STATUS.RUNNING,
    runDetail: null,
    startedAt,
    completedAt: null,
    currentStageId: null,
    configuration,
    identity,
    candidateStability: {
      status: CANDIDATE_STABILITY.PENDING,
      initialCandidateDigest: identity.candidate.candidateDigest,
      completedCandidateDigest: null,
    },
    results: [],
    summary: summarizeResults([]),
  };
  receipt.receiptId = receiptId(receipt);
  return receipt;
}

test("CLI requires an explicit profile, repository, report, candidate mode, and adapter manifest", () => {
  assert.throws(() => parseOptions([]), /--profile is required/);
  assert.throws(
    () =>
      parseOptions([
        "--profile",
        "static",
        "--repo",
        ".",
        "--report",
        "result.json",
      ]),
    /--candidate-mode is required/,
  );
  assert.throws(
    () => parseOptions([
      "--profile", "static",
      "--repo", ".",
      "--report", "result.json",
      "--candidate-mode", "DIRTY_WORKTREE",
    ]),
    /--adapter-manifest is required/,
  );
  const options = parseOptions(
    [
      "--profile",
      "static",
      "--repo",
      ".",
      "--report",
      ".test-results/docs-validation/static.json",
      "--candidate-mode",
      "DIRTY_WORKTREE",
      "--adapter-manifest",
      join(REPOSITORY_ROOT, ".test-results", "adapters.json"),
    ],
    REPOSITORY_ROOT,
  );
  assert.equal(options.repositoryRoot, REPOSITORY_ROOT);
  assert.equal(options.candidateMode, "DIRTY_WORKTREE");
});

test("in-repository evidence must be Git-ignored", async (t) => {
  const { manifestPath } = await writeAdapterManifest(t);
  const accepted = parseOptions(
    [
      "--profile",
      "static",
      "--repo",
      ".",
      "--report",
      ".test-results/docs-validation/schema-test.json",
      "--candidate-mode",
      "DIRTY_WORKTREE",
      "--adapter-manifest",
      manifestPath,
    ],
    REPOSITORY_ROOT,
  );
  await resolveAndValidateOptions(accepted);

  const rejected = { ...accepted, reportPath: join(REPOSITORY_ROOT, "unignored-evidence.json") };
  rejected.artifactDirectory = `${rejected.reportPath}.artifacts`;
  await assert.rejects(
    resolveAndValidateOptions(rejected),
    /must be Git-ignored/,
  );
});

test("candidate identity is stable and COMMITTED_GIT rejects this dirty worktree", async (t) => {
  const { manifestPath } = await writeAdapterManifest(t);
  const adapterManifest = await loadAdapterManifest({ manifestPath });
  const identity = await collectCandidateIdentity(
    REPOSITORY_ROOT,
    "DIRTY_WORKTREE",
    adapterManifest,
  );
  assert.equal(identity.candidateDigest.length, 64);
  assert.equal(identity.mode, "DIRTY_WORKTREE");
  assert.equal(identity.isClean, false);
  await assert.rejects(
    collectCandidateIdentity(REPOSITORY_ROOT, "COMMITTED_GIT", adapterManifest),
    /requires a clean tracked and untracked Git worktree/,
  );
});

test("atomic checkpoints replace the receipt without leaving temporary files", async () => {
  const directory = await mkdtemp(join(tmpdir(), "docs-validation-checkpoint-"));
  const path = join(directory, "receipt.json");
  try {
    const receipt = validRunningReceipt();
    await atomicWriteJson(path, receipt, assertReceiptSemantics);
    receipt.checkpointSequence = 1;
    receipt.currentStageId = "DOCS_CONTENT_AUDIT";
    receipt.receiptId = receiptId(receipt);
    await atomicWriteJson(path, receipt, assertReceiptSemantics);
    const persisted = JSON.parse(await readFile(path, "utf8"));
    assert.equal(persisted.checkpointSequence, 1);
    assert.equal(persisted.currentStageId, "DOCS_CONTENT_AUDIT");
    assert.deepEqual(await readdir(directory), ["receipt.json"]);
  } finally {
    await rm(directory, { force: true, recursive: true });
  }
});

test("file identity rejects a symbolic-link target", async (t) => {
  const directory = await mkdtemp(join(tmpdir(), "docs-validation-file-identity-"));
  const target = join(directory, "target.txt");
  const link = join(directory, "link.txt");
  try {
    await writeFile(target, "bound bytes", "utf8");
    try {
      await symlink(target, link, "file");
    } catch (error) {
      if (error?.code === "EPERM") {
        t.skip("Creating symbolic links requires an unavailable Windows privilege");
        return;
      }
      throw error;
    }
    await assert.rejects(
      fileIdentity(link, "link.txt"),
      /not a regular file/u,
    );
  } finally {
    await rm(directory, { force: true, recursive: true });
  }
});

test("receipt identity binds stage outcomes and final candidate stability", () => {
  const original = validRunningReceipt();
  const changedOutcome = structuredClone(original);
  changedOutcome.runStatus = RUN_STATUS.ERROR;
  changedOutcome.runDetail = "stage failed";
  assert.notEqual(receiptId(changedOutcome), original.receiptId);

  const changedCandidate = structuredClone(original);
  changedCandidate.candidateStability.status = CANDIDATE_STABILITY.MISMATCHED;
  changedCandidate.candidateStability.completedCandidateDigest = DIGEST_A;
  assert.notEqual(receiptId(changedCandidate), original.receiptId);

  const changedAdapterManifest = structuredClone(original);
  changedAdapterManifest.identity.adapters.manifest.rawSha256 = "c".repeat(64);
  assert.notEqual(receiptId(changedAdapterManifest), original.receiptId);
});

test("declared timeout kills the spawned process tree before descendants can write", async (t) => {
  const { manifestPath } = await writeAdapterManifest(t);
  const adapterManifest = await loadAdapterManifest({ manifestPath });
  const directory = await mkdtemp(join(tmpdir(), "docs-validation-timeout-"));
  const marker = join(directory, "descendant-ran.txt");
  const descendant = [
    "const fs = require('node:fs');",
    `setTimeout(() => fs.writeFileSync(${JSON.stringify(marker)}, 'unsafe'), 700);`,
  ].join("");
  const parent = [
    "const { spawn } = require('node:child_process');",
    `spawn(process.execPath, ['-e', ${JSON.stringify(descendant)}], { stdio: 'ignore' });`,
    "setInterval(() => {}, 1000);",
  ].join("");
  const stage = {
    stageId: "TIMEOUT_PROBE",
    commandSpecId: "TIMEOUT_PROBE_V1",
    name: "timeout process-tree probe",
    required: true,
    declaredTimeoutMs: 150,
    run: (context) => context.runCommand("node", ["-e", parent]),
  };
  try {
    const result = await executeStage(stage, {
      repositoryRoot: directory,
      artifactDirectory: join(directory, "artifacts"),
      adapterManifest,
    });
    assert.equal(result.status, STAGE_STATUS.TIMEOUT);
    assert.match(result.detail, /declared 150ms timeout/);
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 900));
    await assert.rejects(access(marker));
  } finally {
    await rm(directory, { force: true, recursive: true });
  }
});

test("closed receipt schema and semantic checks reject adversarial mutations", () => {
  const cases = [
    ["unknown property", (receipt) => { receipt.unexpected = true; }, /unexpected property/],
    ["invalid status", (receipt) => {
      const contract = CONTRACT_VALUES.stages.DOCS_CONTENT_AUDIT;
      receipt.results.push({
        stageId: "DOCS_CONTENT_AUDIT",
        commandSpecId: contract.commandSpecId,
        commandSpecDigest: commandSpecDigest("DOCS_CONTENT_AUDIT", contract),
        name: contract.name,
        required: contract.required,
        declaredTimeoutMs: contract.declaredTimeoutMs,
        startedAt: receipt.startedAt,
        completedAt: receipt.startedAt,
        durationMs: 0,
        status: "MAYBE",
        detail: null,
        artifacts: [],
        artifactManifestDigest: artifactManifestDigest([]),
      });
      receipt.summary = summarizeResults(receipt.results);
    }, /must be one of/],
    ["summary mismatch", (receipt) => { receipt.summary.total = 4; }, /summary does not match/],
    ["candidate tamper", (receipt) => {
      receipt.identity.candidate.untrackedFileCount = 99;
    }, /Candidate digest does not match/],
    ["adapter configuration tamper", (receipt) => {
      receipt.configuration.adapterManifestCanonicalSha256 = "c".repeat(64);
    }, /does not match its adapter manifest identity/],
    ["adapter canonical JSON tamper", (receipt) => {
      receipt.identity.adapters.manifest.canonicalJson = "{}\n";
    }, /receipt adapter manifest failed schema validation/],
  ];
  for (const [label, mutate, expected] of cases) {
    const receipt = validRunningReceipt();
    mutate(receipt);
    assert.throws(() => assertReceiptSemantics(receipt), expected, label);
  }
});

test("contract values stay canonical and digests are deterministic", () => {
  assert.deepEqual(Object.keys(CONTRACT_VALUES.profileStages), CONTRACT_VALUES.profiles);
  assert.equal(canonicalDigest({ b: 2, a: 1 }), canonicalDigest({ a: 1, b: 2 }));
  assert.equal(sha256(Buffer.alloc(0)).length, 64);
});

test("rendered-route schema is closed and its summary is derived from structured checks", () => {
  const report = {
    schemaVersion: 1,
    schemaId: "https://pockethive.dev/schemas/rendered-route-result-v1.schema.json",
    generatedAt: new Date().toISOString(),
    status: "ERROR",
    detail: "dependency unavailable",
    renderTarget: "LOCAL_STATIC",
    configuredBasePath: "/",
    testedBaseUrl: null,
    platform: {
      nodeVersion: process.version,
      operatingSystem: process.platform,
      architecture: process.arch,
    },
    browser: { engine: "CHROMIUM", executablePath: null, version: null },
    build: { status: "ERROR", detail: "dependency unavailable" },
    summary: {
      routes: 0,
      viewports: 0,
      routeViewportChecks: 0,
      routeViewportPassed: 0,
      routeViewportFailed: 0,
      linksChecked: 0,
      linksFailed: 0,
      imagesChecked: 0,
      imagesFailed: 0,
    },
    routeViewportResults: [],
    links: [],
    images: [],
  };
  assert.doesNotThrow(() => assertRenderedRouteSemantics(report));
  const unknown = structuredClone(report);
  unknown.browser.unexpected = true;
  assert.throws(() => assertRenderedRouteSemantics(unknown), /unexpected property/);
  const incorrectSummary = structuredClone(report);
  incorrectSummary.summary.routes = 1;
  assert.throws(
    () => assertRenderedRouteSemantics(incorrectSummary),
    /summary does not match/,
  );
});
