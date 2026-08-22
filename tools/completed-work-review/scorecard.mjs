import { canonicalJson } from "../docs-impact/canonical.mjs";
import {
  COMPARISON_STATUS,
  SCORE_VERIFICATION_STATUS,
} from "./contracts/constants.mjs";

const SCORE_STATUS = Object.freeze({
  NOT_VERIFIED: SCORE_VERIFICATION_STATUS.NOT_VERIFIED,
  VERIFIED: SCORE_VERIFICATION_STATUS.VERIFIED,
});

function roundOne(value) {
  return Math.round((value + Number.EPSILON) * 10) / 10;
}

function assertDisplayedScore(value, label) {
  if (value === null) return;
  if (!Number.isFinite(value) || value < 0 || value > 10 || Math.abs(value * 10 - Math.round(value * 10)) > 1e-9) {
    throw new Error(`${label} must be a displayed 0.0-10.0 score with one decimal place`);
  }
}

function statusFor(baseline, current) {
  if (baseline === null || current === null) return COMPARISON_STATUS.UNVERIFIED;
  const delta = roundOne(current - baseline);
  if (delta > 0) return COMPARISON_STATUS.IMPROVED;
  if (delta < 0) return COMPARISON_STATUS.DECREASED;
  return COMPARISON_STATUS.UNCHANGED;
}

function completeWeightedMean(dimensions, key) {
  if (dimensions.some((dimension) => dimension.required && dimension[key] === null)) return null;
  const scored = dimensions.filter((dimension) => dimension[key] !== null);
  if (scored.length !== dimensions.length) return null;
  const totalWeight = scored.reduce((sum, dimension) => sum + dimension.weight, 0);
  return roundOne(scored.reduce((sum, dimension) => sum + dimension[key] * dimension.weight, 0) / totalWeight);
}

function assertUnique(items, label) {
  if (new Set(items).size !== items.length) throw new Error(`${label} must be unique`);
}

export function buildScorecard(profile, inputs, { scoreStatus }) {
  if (!Object.values(SCORE_STATUS).includes(scoreStatus)) {
    throw new Error("Scorecard scoreStatus must be explicitly VERIFIED or NOT_VERIFIED");
  }
  const expectedIds = profile.dimensions.map(({ id }) => id);
  const suppliedIds = inputs.map(({ id }) => id);
  assertUnique(suppliedIds, "Dimension IDs");
  if (canonicalJson([...suppliedIds].sort()) !== canonicalJson([...expectedIds].sort())) {
    throw new Error(`Score request must declare every ${profile.id} dimension exactly once`);
  }
  const weights = profile.dimensions.map(({ weight }) => weight);
  if (weights.reduce((sum, weight) => sum + weight, 0) !== 100) {
    throw new Error(`${profile.id} dimension weights must total 100`);
  }

  const inputById = new Map(inputs.map((input) => [input.id, input]));
  const dimensions = profile.dimensions.map((definition) => {
    const input = inputById.get(definition.id);
    assertDisplayedScore(input.baseline, `${definition.id} baseline`);
    assertDisplayedScore(input.current, `${definition.id} current`);
    const submittedDelta = input.baseline === null || input.current === null
      ? null
      : roundOne(input.current - input.baseline);
    const baseline = scoreStatus === SCORE_STATUS.VERIFIED ? input.baseline : null;
    const current = scoreStatus === SCORE_STATUS.VERIFIED ? input.current : null;
    const delta = baseline === null || current === null ? null : roundOne(current - baseline);
    return {
      id: definition.id,
      label: definition.label,
      kind: definition.kind,
      direction: definition.direction,
      required: definition.required,
      weight: definition.weight,
      criterion: definition.criterion,
      scoreAnchors: definition.scoreAnchors.map((anchor) => ({ ...anchor })),
      scoreStatus,
      submittedBaseline: input.baseline,
      submittedCurrent: input.current,
      submittedDelta,
      submittedComparisonStatus: statusFor(input.baseline, input.current),
      baseline,
      current,
      delta,
      comparisonStatus: statusFor(baseline, current),
      evidenceRefs: [...input.evidenceRefs].sort(),
      notes: input.notes,
    };
  });
  const submittedBaseline = completeWeightedMean(dimensions, "submittedBaseline");
  const submittedCurrent = completeWeightedMean(dimensions, "submittedCurrent");
  const allSubmittedComparable = dimensions.every(
    (dimension) => !dimension.required
      || (dimension.submittedBaseline !== null && dimension.submittedCurrent !== null),
  );
  const submittedDelta = allSubmittedComparable
    && submittedBaseline !== null && submittedCurrent !== null
    ? roundOne(submittedCurrent - submittedBaseline)
    : null;
  const submittedComparisonStatus = allSubmittedComparable
    ? statusFor(submittedBaseline, submittedCurrent)
    : COMPARISON_STATUS.UNVERIFIED;
  const baseline = completeWeightedMean(dimensions, "baseline");
  const current = completeWeightedMean(dimensions, "current");
  const allRequiredComparable = dimensions.every(
    (dimension) => !dimension.required || (dimension.baseline !== null && dimension.current !== null),
  );
  const delta = allRequiredComparable && baseline !== null && current !== null
    ? roundOne(current - baseline)
    : null;
  const comparisonStatus = allRequiredComparable
    ? statusFor(baseline, current)
    : COMPARISON_STATUS.UNVERIFIED;
  return {
    comparisonStatus,
    dimensions,
    overall: { baseline, current, delta, comparisonStatus },
    submittedOverall: {
      baseline: submittedBaseline,
      current: submittedCurrent,
      delta: submittedDelta,
      comparisonStatus: submittedComparisonStatus,
    },
  };
}

export function assertScorecardSemantics(
  profile,
  dimensions,
  overall,
  submittedOverall,
  comparisonStatus,
) {
  const statuses = [...new Set(dimensions.map(({ scoreStatus }) => scoreStatus))];
  if (statuses.length !== 1) throw new Error("Review dimensions must share one score verification status");
  const rebuilt = buildScorecard(
    profile,
    dimensions.map(({ id, submittedBaseline, submittedCurrent, evidenceRefs, notes }) => ({
      id,
      baseline: submittedBaseline,
      current: submittedCurrent,
      evidenceRefs,
      notes,
    })),
    { scoreStatus: statuses[0] },
  );
  if (canonicalJson(rebuilt.dimensions) !== canonicalJson(dimensions)) {
    throw new Error("Review dimensions do not match the canonical profile scorecard");
  }
  if (canonicalJson(rebuilt.overall) !== canonicalJson(overall)) {
    throw new Error("Review Overall does not match the displayed dimension arithmetic");
  }
  if (canonicalJson(rebuilt.submittedOverall) !== canonicalJson(submittedOverall)) {
    throw new Error("Review submitted Overall does not match submitted dimension arithmetic");
  }
  if (rebuilt.comparisonStatus !== comparisonStatus) {
    throw new Error("Review comparisonStatus does not match the canonical scorecard");
  }
  return true;
}

export { COMPARISON_STATUS, SCORE_STATUS, roundOne };
