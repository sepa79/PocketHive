import assert from "node:assert/strict";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import {
  CONTRACT_VALUES,
  assertContract,
  loadReviewProfiles,
  validateContract
} from "./profile.mjs";
import {
  assertCanonicalRepositoryPathValues,
  assertCandidateIdentitySchemaProjections,
  assertEvidenceReceiptSchemaProjections,
  assertProfileSchemaProjections,
  assertReviewResultSchemaProjections
} from "./contracts/projections.mjs";

const ROOT = path.resolve(".");
const PROFILES_PATH = path.join(ROOT, "docs/ci/completed-work-review-profiles.json");
const PROFILE_SCHEMA_PATH = path.join(
  ROOT,
  "docs/ci/completed-work-review-profiles.schema.json"
);
const CONTRACT_ROOT = path.join(ROOT, "tools/completed-work-review/contracts");

async function readJson(filePath) {
  return JSON.parse(await readFile(filePath, "utf8"));
}

function digest(character) {
  return character.repeat(64);
}

function gitObjectId(character) {
  return character.repeat(40);
}

function committedIdentity() {
  return {
    schemaVersion: CONTRACT_VALUES.candidateIdentitySchemaVersion,
    identityId: digest("a"),
    mode: "COMMITTED_GIT",
    repositoryId: "edenred/pockethive",
    repositoryRemote: {
      name: "origin",
      url: "https://github.com/edenred/PocketHive.git"
    },
    gitExecutable: {
      path: "C:\\Program Files\\Git\\cmd\\git.exe",
      sha256: digest("9"),
      sizeBytes: 123456
    },
    gitObjectFormat: "sha1",
    baseCommit: gitObjectId("b"),
    mergeBaseCommit: gitObjectId("c"),
    candidateCommit: gitObjectId("d"),
    candidateGitTree: gitObjectId("e"),
    candidateSnapshotDigest: digest("f"),
    trackedPatchDigest: null,
    untrackedFilesManifestDigest: null,
    untrackedFiles: [],
    dirty: false,
    capturedAt: "2026-08-17T12:00:00.000Z"
  };
}

test("loads the two explicit v1 profiles with deterministic identity", async () => {
  const loaded = await loadReviewProfiles({
    anchorPath: ROOT,
    profilesPath: PROFILES_PATH,
    schemaPath: PROFILE_SCHEMA_PATH
  });
  assert.match(loaded.configDigest, /^[a-f0-9]{64}$/u);
  assert.deepEqual(
    [...loaded.profilesById.keys()].sort(),
    [...CONTRACT_VALUES.profileId].sort()
  );
  assert.equal(loaded.profilesById.has("MIXED"), false);
  for (const profile of loaded.config.profiles) {
    assert.equal(profile.scoringMethod, "ANCHORED_RUBRIC_V1");
    assert.equal(
      profile.dimensions.reduce((sum, dimension) => sum + dimension.weight, 0),
      100
    );
    assert.equal(new Set(profile.dimensions.map(({ id }) => id)).size, profile.dimensions.length);
    assert.ok(profile.dimensions.every(({ required }) => required));
    assert.ok(profile.dimensions.every(({ allowedAdapters }) => allowedAdapters.length > 0));
    assert.ok(profile.dimensions.every(({ criterion }) => criterion.length > 0));
    assert.ok(profile.dimensions.every(({ scoreAnchors }) => (
      JSON.stringify(scoreAnchors.map(({ score }) => score)) === JSON.stringify([0, 5, 10])
    )));
    assert.deepEqual(
      profile.requiredGates.map(({ id }) => id).sort(),
      [...CONTRACT_VALUES.requiredGateIdsByProfile[profile.id]].sort()
    );
  }
});

test("all v1 schemas are closed projections of the canonical values", async () => {
  assert.equal(assertCanonicalRepositoryPathValues(), true);
  assert.equal(Object.isFrozen(CONTRACT_VALUES.canonicalRepositoryPaths), true);
  const [profileSchema, identitySchema, evidenceSchema, resultSchema] = await Promise.all([
    readJson(PROFILE_SCHEMA_PATH),
    readJson(path.join(CONTRACT_ROOT, "candidate-identity.schema.json")),
    readJson(path.join(CONTRACT_ROOT, "evidence-receipt.schema.json")),
    readJson(path.join(CONTRACT_ROOT, "review-result.schema.json"))
  ]);
  assertProfileSchemaProjections(profileSchema);
  assertCandidateIdentitySchemaProjections(identitySchema);
  assertEvidenceReceiptSchemaProjections(evidenceSchema);
  assertReviewResultSchemaProjections(resultSchema);
  assert.ok(validateContract(resultSchema, {}).some((error) => error.includes("missing required property")));
});

test("candidate identity modes fail closed instead of accepting a hybrid identity", async () => {
  const schema = await readJson(path.join(CONTRACT_ROOT, "candidate-identity.schema.json"));
  assertContract(schema, committedIdentity(), "committed identity");

  const dirty = {
    ...committedIdentity(),
    mode: "DIRTY_WORKTREE",
    candidateCommit: null,
    candidateGitTree: null,
    trackedPatchDigest: digest("1"),
    untrackedFilesManifestDigest: digest("2"),
    untrackedFiles: [
      {
        path: "docs/new-page.md",
        sha256: digest("3"),
        sizeBytes: 123
      }
    ],
    dirty: true
  };
  assertContract(schema, dirty, "dirty identity");

  const hybrid = { ...dirty, candidateCommit: gitObjectId("d") };
  assert.match(validateContract(schema, hybrid).join("\n"), /oneOf branch/u);
});

test("evidence receipts accept explicit TIMEOUT and reject undeclared data", async () => {
  const schema = await readJson(path.join(CONTRACT_ROOT, "evidence-receipt.schema.json"));
  const receipt = {
    schemaVersion: 1,
    receiptId: digest("a"),
    evidenceId: "stress-timeout",
    kind: "MEASURED",
    subject: "CANDIDATE",
    subjectIdentityRef: digest("b"),
    profileDigest: digest("c"),
    producer: {
      id: "node-test",
      version: "1.0.0",
      digest: digest("d")
    },
    execution: {
      kind: "AUTOMATED_CHECK",
      adapter: "NODE_TEST",
      entrypoint: "node",
      arguments: ["--test"],
      officialIngress: false
    },
    status: "TIMEOUT",
    summary: "The bounded stress check exceeded its declared timeout.",
    claims: {
      gateOutcomes: [],
      scoreAttestations: [],
      searchDiscovery: null,
      independentReview: null,
      findingApprovals: []
    },
    artifacts: [],
    observations: [],
    createdAt: "2026-08-17T12:00:00.000Z"
  };
  assertContract(schema, receipt, "timeout evidence receipt");
  assert.match(
    validateContract(schema, { ...receipt, inferredFallback: true }).join("\n"),
    /unexpected property inferredFallback/u
  );
});

test("profile loading rejects unknown fields, duplicate dimensions, and invalid weight totals", async () => {
  const [profileText, schemaText] = await Promise.all([
    readFile(PROFILES_PATH, "utf8"),
    readFile(PROFILE_SCHEMA_PATH, "utf8")
  ]);
  const baseConfig = JSON.parse(profileText);
  const temporaryRoot = await mkdtemp(path.join(os.tmpdir(), "pockethive-review-profile-"));
  const temporarySchemaPath = path.join(temporaryRoot, "schema.json");
  await writeFile(temporarySchemaPath, schemaText, "utf8");
  try {
    for (const [label, mutate, expected] of [
      [
        "unknown",
        (config) => { config.profiles[0].fallbackProfile = "MIXED"; },
        /unexpected property fallbackProfile/u
      ],
      [
        "duplicate",
        (config) => { config.profiles[0].dimensions[1].id = config.profiles[0].dimensions[0].id; },
        /dimension IDs must be unique/u
      ],
      [
        "weight",
        (config) => { config.profiles[0].dimensions[0].weight = 19; },
        /weights must total exactly 100/u
      ],
      [
        "unequal-weight",
        (config) => {
          config.profiles[0].dimensions[0].weight = 21;
          config.profiles[0].dimensions[1].weight = 19;
        },
        /must use equal dimension weights/u
      ],
      [
        "typo-evaluator",
        (config) => { config.profiles[0].requiredGates[0].evaluator = "CANDIDATE_IDENTTY"; },
        /must be one of/u
      ],
      [
        "wrong-evaluator",
        (config) => { config.profiles[0].requiredGates[0].evaluator = "BASELINE_IDENTITY"; },
        /must use evaluator CANDIDATE_IDENTITY/u
      ],
      [
        "empty-policy",
        (config) => { config.profiles[0].requiredGates[2].allowedAdapters = []; },
        /requires non-empty typed evidence allowlists/u
      ],
      [
        "anchor-order",
        (config) => {
          const anchors = config.profiles[0].dimensions[0].scoreAnchors;
          [anchors[0], anchors[1]] = [anchors[1], anchors[0]];
        },
        /must declare canonical score anchors in order/u
      ],
      [
        "duplicate-anchor-description",
        (config) => {
          const anchors = config.profiles[0].dimensions[0].scoreAnchors;
          anchors[1].description = anchors[0].description;
        },
        /score-anchor descriptions must be unique/u
      ]
    ]) {
      const config = structuredClone(baseConfig);
      mutate(config);
      const candidatePath = path.join(temporaryRoot, `${label}.json`);
      await writeFile(candidatePath, JSON.stringify(config), "utf8");
      await assert.rejects(
        loadReviewProfiles({
          anchorPath: temporaryRoot,
          profilesPath: candidatePath,
          schemaPath: temporarySchemaPath,
        }),
        expected
      );
    }
  } finally {
    await rm(temporaryRoot, { recursive: true, force: true });
  }
});

test("profile projection drift is rejected before configuration validation", async () => {
  const schema = await readJson(PROFILE_SCHEMA_PATH);
  schema.properties.profiles.items.properties.id.enum.push("MIXED");
  assert.throws(() => assertProfileSchemaProjections(schema), /canonical profileId/u);
});
