import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import path from "node:path";
import test from "node:test";

import { canonicalDigest } from "../docs-impact/canonical.mjs";
import { loadReviewProfiles, validateContract } from "./profile.mjs";
import {
  assertReceiptClaimSemantics,
  buildFindingReadiness,
  buildGates,
} from "./readiness.mjs";

const ROOT = path.resolve(".");
const PROFILES_PATH = path.join(ROOT, "docs/ci/completed-work-review-profiles.json");
const PROFILE_SCHEMA_PATH = path.join(ROOT, "docs/ci/completed-work-review-profiles.schema.json");
const EVIDENCE_SCHEMA_PATH = path.join(
  ROOT,
  "tools/completed-work-review/contracts/evidence-receipt.schema.json",
);

function digest(character) {
  return character.repeat(64);
}

function gateOutcome(gateId, status = "PASS") {
  return {
    gateId,
    status,
    checkId: `${gateId}-check`,
    checkContractDigest: canonicalDigest({ gateId, contract: "test-contract-v1" }),
    configurationDigest: canonicalDigest({ gateId, configuration: "test-configuration-v1" }),
  };
}

function receipt({
  evidenceId,
  subject = "CANDIDATE",
  kind = "MEASURED",
  executionKind = "AUTOMATED_CHECK",
  adapter = "NODE_TEST",
  officialIngress = false,
  status = "PASS",
  gateOutcomes = [],
  scoreAttestations = [],
  searchDiscovery = null,
  independentReview = null,
  findingApprovals = [],
}) {
  return {
    schemaVersion: 1,
    receiptId: digest("a"),
    evidenceId,
    kind,
    subject,
    subjectIdentityRef: digest("b"),
    profileDigest: digest("c"),
    producer: { id: "test-producer", version: "1.0.0", digest: digest("d") },
    execution: {
      kind: executionKind,
      adapter,
      entrypoint: gateOutcomes.length > 0 ? "test-adapter" : null,
      arguments: [],
      officialIngress,
    },
    status,
    summary: `Evidence ${evidenceId}`,
    claims: {
      gateOutcomes,
      scoreAttestations,
      searchDiscovery,
      independentReview,
      findingApprovals,
    },
    artifacts: [],
    observations: gateOutcomes.length > 0
      ? [{ id: "check-result", label: "Check result", value: true, unit: null }]
      : [],
    createdAt: "2026-08-17T12:00:00.000Z",
  };
}

function trusted(overrides = {}) {
  return {
    ageSeconds: 0,
    identityMatches: true,
    producerState: "MATCH",
    profileMatches: true,
    usable: true,
    ...overrides,
  };
}

function gateInput(id, evidenceRefs = []) {
  return {
    id,
    summary: `Evaluate ${id}.`,
    evidenceRefs,
    unlock: {
      kind: "EXTERNAL_EVIDENCE",
      description: `Supply trusted evidence for ${id}.`,
      requiredEvidenceKinds: ["MEASURED"],
    },
  };
}

async function documentationFixture() {
  const loaded = await loadReviewProfiles({
    anchorPath: ROOT,
    profilesPath: PROFILES_PATH,
    schemaPath: PROFILE_SCHEMA_PATH,
  });
  const profile = structuredClone(loaded.profilesById.get("POCKETHIVE_DOCUMENTATION_V1"));
  const dimensionIds = profile.dimensions.map(({ id }) => id);
  const baselineScores = dimensionIds.map((dimensionId) => ({
    dimensionId,
    side: "BASELINE",
    score: 6.0,
  }));
  const currentScores = dimensionIds.map((dimensionId) => ({
    dimensionId,
    side: "CURRENT",
    score: 8.0,
  }));
  const receipts = [
    receipt({
      evidenceId: "baseline-scores",
      subject: "BASELINE",
      kind: "REVIEWER_JUDGMENT",
      adapter: "MANUAL_INSPECTION",
      gateOutcomes: [gateOutcome("required-score-evidence")],
      scoreAttestations: baselineScores,
    }),
    receipt({
      evidenceId: "current-scores",
      subject: "REVIEW",
      kind: "REVIEWER_JUDGMENT",
      adapter: "MANUAL_INSPECTION",
      gateOutcomes: [gateOutcome("required-score-evidence")],
      scoreAttestations: currentScores,
    }),
    receipt({
      evidenceId: "documentation-validation",
      adapter: "DOCS_VALIDATION",
      officialIngress: true,
      gateOutcomes: [
        gateOutcome("documentation-validation"),
        gateOutcome("search-ai-discovery"),
      ],
      searchDiscovery: { status: "NO_MATERIAL_CHANGE" },
    }),
    receipt({
      evidenceId: "docs-impact",
      adapter: "DOCS_IMPACT",
      gateOutcomes: [gateOutcome("docs-impact-disposition")],
    }),
    ...["NOVICE", "EXPERT", "UX"].map((passKind) => receipt({
      evidenceId: `review-${passKind.toLowerCase()}`,
      subject: "REVIEW",
      kind: "REVIEWER_JUDGMENT",
      executionKind: "INDEPENDENT_REVIEW",
      adapter: "INDEPENDENT_REVIEW",
      gateOutcomes: [gateOutcome("independent-review")],
      independentReview: { reviewerId: `reviewer-${passKind.toLowerCase()}`, passKind },
    })),
  ];
  const receiptsById = new Map(receipts.map((item) => [item.evidenceId, item]));
  const trustById = new Map(receipts.map((item) => [item.evidenceId, trusted()]));
  const scoreRefs = ["baseline-scores", "current-scores"];
  const reviewRefs = ["review-novice", "review-expert", "review-ux"];
  const dimensions = profile.dimensions.map(({ id }) => ({
    id,
    baseline: 6.0,
    current: 8.0,
    evidenceRefs: scoreRefs,
    notes: `Attested ${id}.`,
  }));
  const gateInputs = profile.requiredGates.map(({ id }) => {
    if (id === "required-score-evidence") return gateInput(id, scoreRefs);
    if (id === "documentation-validation" || id === "search-ai-discovery") {
      return gateInput(id, ["documentation-validation"]);
    }
    if (id === "docs-impact-disposition") return gateInput(id, ["docs-impact"]);
    if (id === "independent-review") return gateInput(id, reviewRefs);
    return gateInput(id);
  });
  return {
    profile,
    identities: { candidate: { identityId: "candidate" }, baseline: { identityId: "baseline" } },
    dimensions,
    gateInputs,
    docsImpact: { authority: "INFORMATIONAL_UNVERIFIED" },
    searchDiscovery: { status: "NO_MATERIAL_CHANGE" },
    receiptsById,
    trustById,
  };
}

function assembleGates(fixture) {
  return buildGates({
    profile: fixture.profile,
    gateInputs: fixture.gateInputs,
    identities: fixture.identities,
    dimensions: fixture.dimensions,
    docsImpact: fixture.docsImpact,
    searchDiscovery: fixture.searchDiscovery,
    receiptsById: fixture.receiptsById,
    trustById: fixture.trustById,
  });
}

function statusOf(state, gateId) {
  return state.gates.find(({ id }) => id === gateId).status;
}

test("typed gates verify only exact adapter, execution kind, and ingress policies", async (t) => {
  const valid = await documentationFixture();
  const state = assembleGates(valid);
  assert.equal(statusOf(state, "required-score-evidence"), "VERIFIED");
  assert.equal(statusOf(state, "documentation-validation"), "VERIFIED");
  assert.equal(statusOf(state, "independent-review"), "VERIFIED");
  assert.equal(statusOf(state, "docs-impact-disposition"), "NOT_VERIFIED");
  assert.deepEqual(state.independentReview.passKinds, ["EXPERT", "NOVICE", "UX"]);

  for (const [label, mutate] of [
    ["adapter", (item) => { item.execution.adapter = "MANUAL_INSPECTION"; }],
    ["execution kind", (item) => { item.execution.kind = "INDEPENDENT_REVIEW"; }],
    ["official ingress", (item) => { item.execution.officialIngress = false; }],
  ]) {
    await t.test(`rejects wrong ${label}`, async () => {
      const fixture = await documentationFixture();
      mutate(fixture.receiptsById.get("documentation-validation"));
      assert.equal(statusOf(assembleGates(fixture), "documentation-validation"), "NOT_VERIFIED");
    });
  }
});

test("unknown evaluators and adapters fail explicitly", async () => {
  const evaluatorFixture = await documentationFixture();
  evaluatorFixture.profile.requiredGates[0].evaluator = "CANDIDATE_IDENTTY";
  assert.throws(() => assembleGates(evaluatorFixture), /Unknown gate evaluator/u);

  const adapterFixture = await documentationFixture();
  adapterFixture.receiptsById.get("documentation-validation").execution.adapter = "DOCS_VALDATION";
  assert.throws(() => assembleGates(adapterFixture), /Unknown evidence adapter/u);
});

test("score readiness requires one exact typed attestation for every side and dimension", async () => {
  const arbitraryScore = await documentationFixture();
  arbitraryScore.dimensions[0].current = 8.1;
  assert.equal(statusOf(assembleGates(arbitraryScore), "required-score-evidence"), "NOT_VERIFIED");

  const duplicate = await documentationFixture();
  duplicate.receiptsById.get("current-scores").claims.scoreAttestations.push({
    dimensionId: duplicate.dimensions[0].id,
    side: "CURRENT",
    score: 8.0,
  });
  assert.throws(() => assembleGates(duplicate), /repeats score claim/u);

  const wrongSubject = await documentationFixture();
  wrongSubject.receiptsById.get("baseline-scores").subject = "REVIEW";
  assert.throws(() => assembleGates(wrongSubject), /requires a BASELINE receipt subject/u);
});

test("closed typed claims require executable gate evidence and their matching gate outcomes", () => {
  const passing = receipt({
    evidenceId: "passing-gate",
    gateOutcomes: [gateOutcome("focused-tests")],
  });
  passing.execution.entrypoint = null;
  assert.throws(
    () => assertReceiptClaimSemantics(passing),
    /requires an explicit entrypoint/u,
  );

  const unobserved = receipt({
    evidenceId: "unobserved-gate",
    gateOutcomes: [gateOutcome("focused-tests")],
  });
  unobserved.observations = [];
  assert.throws(
    () => assertReceiptClaimSemantics(unobserved),
    /requires an artifact or observation/u,
  );

  const scoreWithoutGate = receipt({
    evidenceId: "score-without-gate",
    subject: "REVIEW",
    kind: "REVIEWER_JUDGMENT",
    adapter: "MANUAL_INSPECTION",
    scoreAttestations: [{ dimensionId: "safety", side: "CURRENT", score: 8.0 }],
  });
  assert.throws(
    () => assertReceiptClaimSemantics(scoreWithoutGate),
    /require a required-score-evidence gate outcome/u,
  );

  const discoveryWithoutGate = receipt({
    evidenceId: "discovery-without-gate",
    adapter: "DOCS_VALIDATION",
    officialIngress: true,
    searchDiscovery: { status: "NO_MATERIAL_CHANGE" },
  });
  assert.throws(
    () => assertReceiptClaimSemantics(discoveryWithoutGate),
    /requires a search-ai-discovery gate outcome/u,
  );

  const reviewWithoutGate = receipt({
    evidenceId: "review-without-gate",
    subject: "REVIEW",
    kind: "REVIEWER_JUDGMENT",
    executionKind: "INDEPENDENT_REVIEW",
    adapter: "INDEPENDENT_REVIEW",
    independentReview: { reviewerId: "reviewer-one", passKind: "NOVICE" },
  });
  assert.throws(
    () => assertReceiptClaimSemantics(reviewWithoutGate),
    /requires an independent-review gate outcome/u,
  );
});

test("independent pass and reviewer identities derive only from one scalar claim per receipt", async () => {
  const fixture = await documentationFixture();
  fixture.gateInputs.find(({ id }) => id === "independent-review").evidenceRefs = ["review-novice"];
  const state = assembleGates(fixture);
  assert.equal(statusOf(state, "independent-review"), "NOT_VERIFIED");
  assert.deepEqual(state.independentReview.reviewerIds, ["reviewer-novice"]);
  assert.deepEqual(state.independentReview.passKinds, ["NOVICE"]);

  const schema = JSON.parse(await readFile(EVIDENCE_SCHEMA_PATH, "utf8"));
  const forged = fixture.receiptsById.get("review-novice");
  forged.claims.independentReview = [
    { reviewerId: "reviewer-novice", passKind: "NOVICE" },
    { reviewerId: "reviewer-novice", passKind: "EXPERT" },
  ];
  assert.match(validateContract(schema, forged).join("\n"), /must have type object or null/u);
  assert.throws(() => assertReceiptClaimSemantics(forged), /Unknown independent review pass/u);
});

test("baselineRequired and every freshness require flag are consumed explicitly", async () => {
  const optionalBaseline = await documentationFixture();
  optionalBaseline.profile.baselineRequired = false;
  optionalBaseline.identities.baseline = null;
  optionalBaseline.dimensions.forEach((dimension) => { dimension.baseline = null; });
  optionalBaseline.gateInputs.find(({ id }) => id === "required-score-evidence").evidenceRefs = ["current-scores"];
  optionalBaseline.dimensions.forEach((dimension) => { dimension.evidenceRefs = ["current-scores"]; });
  optionalBaseline.receiptsById.delete("baseline-scores");
  optionalBaseline.trustById.delete("baseline-scores");
  const optionalState = assembleGates(optionalBaseline);
  assert.equal(statusOf(optionalState, "baseline-identity"), "VERIFIED");
  assert.equal(statusOf(optionalState, "required-score-evidence"), "VERIFIED");

  const requiredBaseline = await documentationFixture();
  requiredBaseline.identities.baseline = null;
  assert.equal(statusOf(assembleGates(requiredBaseline), "baseline-identity"), "NOT_VERIFIED");

  for (const [flag, trustChange] of [
    ["requireCandidateIdentityMatch", { identityMatches: false }],
    ["requireToolDigestMatch", { producerState: "NOT_VERIFIED" }],
    ["requireProfileDigestMatch", { profileMatches: false }],
  ]) {
    const strict = await documentationFixture();
    strict.trustById.set("documentation-validation", trusted(trustChange));
    assert.equal(statusOf(assembleGates(strict), "documentation-validation"), "NOT_VERIFIED");

    const relaxed = await documentationFixture();
    relaxed.profile.freshness[flag] = false;
    relaxed.trustById.set("documentation-validation", trusted(trustChange));
    assert.equal(statusOf(assembleGates(relaxed), "documentation-validation"), "VERIFIED");
  }
});

test("material gaps, BLOCKER regressions, and unapproved dispositions remain blocking", async () => {
  const fixture = await documentationFixture();
  const state = buildFindingReadiness({
    blockers: [{
      id: "resolved-risk",
      severity: "MATERIAL",
      status: "RESOLVED",
      summary: "Claimed resolved.",
      evidenceRefs: [],
      unlock: { kind: "HUMAN_APPROVAL", description: "Approve.", requiredEvidenceKinds: ["REVIEWER_JUDGMENT"] },
    }],
    regressions: [
      {
        id: "tradeoff-risk",
        dimensionId: "safety",
        severity: "MATERIAL",
        summary: "Claimed accepted.",
        evidenceRefs: [],
        disposition: "ACCEPTED_TRADE_OFF",
      },
      {
        id: "blocking-risk",
        dimensionId: "safety",
        severity: "NON_MATERIAL",
        summary: "Explicit blocker.",
        evidenceRefs: [],
        disposition: "BLOCKER",
      },
    ],
    remainingGaps: [{
      id: "unfinished-risk",
      dimensionId: "safety",
      severity: "MATERIAL",
      summary: "Still missing.",
      evidenceRefs: [],
      unlock: { kind: "IMPLEMENTATION", description: "Finish it.", requiredEvidenceKinds: ["MEASURED"] },
    }],
    receiptsById: fixture.receiptsById,
    trustById: fixture.trustById,
    freshness: fixture.profile.freshness,
  });
  assert.deepEqual(
    state.generatedBlockers.map(({ id }) => id),
    [
      "approval-resolved-resolved-risk",
      "approval-tradeoff-tradeoff-risk",
      "blocking-regression-blocking-risk",
      "material-gap-unfinished-risk",
    ],
  );
});

test("trusted independent approval claims unlock only the named resolved blocker and trade-off", async () => {
  const fixture = await documentationFixture();
  const resolvedBlocker = {
    id: "resolved-risk",
    severity: "MATERIAL",
    status: "RESOLVED",
    summary: "Resolved.",
    evidenceRefs: ["finding-approval"],
    unlock: {
      kind: "HUMAN_APPROVAL",
      description: "Approve.",
      requiredEvidenceKinds: ["REVIEWER_JUDGMENT"],
    },
  };
  const acceptedTradeoff = {
    id: "tradeoff-risk",
    dimensionId: "safety",
    severity: "MATERIAL",
    summary: "Accepted.",
    evidenceRefs: ["finding-approval"],
    disposition: "ACCEPTED_TRADE_OFF",
  };
  const approval = receipt({
    evidenceId: "finding-approval",
    subject: "REVIEW",
    kind: "REVIEWER_JUDGMENT",
    executionKind: "INDEPENDENT_REVIEW",
    adapter: "INDEPENDENT_REVIEW",
    gateOutcomes: [gateOutcome("independent-review")],
    independentReview: { reviewerId: "approver-one", passKind: "SECURITY" },
    findingApprovals: [
      {
        findingId: resolvedBlocker.id,
        findingDigest: canonicalDigest(resolvedBlocker),
        kind: "RESOLVED_BLOCKER",
      },
      {
        findingId: acceptedTradeoff.id,
        findingDigest: canonicalDigest(acceptedTradeoff),
        kind: "ACCEPTED_TRADE_OFF",
      },
    ],
  });
  fixture.receiptsById.set(approval.evidenceId, approval);
  fixture.trustById.set(approval.evidenceId, trusted());
  const state = buildFindingReadiness({
    blockers: [resolvedBlocker],
    regressions: [acceptedTradeoff],
    remainingGaps: [],
    receiptsById: fixture.receiptsById,
    trustById: fixture.trustById,
    freshness: fixture.profile.freshness,
  });
  assert.deepEqual(state.generatedBlockers, []);
  assert.deepEqual(state.approvals.map(({ findingId }) => findingId), ["resolved-risk", "tradeoff-risk"]);

  approval.claims.findingApprovals.find(
    ({ findingId }) => findingId === acceptedTradeoff.id,
  ).findingDigest = digest("f");
  const wrongFindingDigest = buildFindingReadiness({
    blockers: [],
    regressions: [acceptedTradeoff],
    remainingGaps: [],
    receiptsById: fixture.receiptsById,
    trustById: fixture.trustById,
    freshness: fixture.profile.freshness,
  });
  assert.deepEqual(
    wrongFindingDigest.generatedBlockers.map(({ id }) => id),
    ["approval-tradeoff-tradeoff-risk"],
  );

  fixture.trustById.set(approval.evidenceId, trusted({ producerState: "NOT_VERIFIED" }));
  const untrusted = buildFindingReadiness({
    blockers: [],
    regressions: [acceptedTradeoff],
    remainingGaps: [],
    receiptsById: fixture.receiptsById,
    trustById: fixture.trustById,
    freshness: fixture.profile.freshness,
  });
  assert.deepEqual(untrusted.generatedBlockers.map(({ id }) => id), ["approval-tradeoff-tradeoff-risk"]);
});
