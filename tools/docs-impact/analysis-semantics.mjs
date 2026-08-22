import { CLASSIFICATION } from "./constants.mjs";

export function assertAnalysisSemantics(analysis) {
  const actionCount = analysis.documentationObligations.length
    + analysis.publicationValidations.length
    + analysis.governanceReviews.length;
  if (analysis.classification === CLASSIFICATION.POLICY_ERROR) {
    if (analysis.policyErrors.length === 0) {
      throw new Error("POLICY_ERROR analysis must contain at least one policy error");
    }
    if (actionCount !== 0) {
      throw new Error("POLICY_ERROR analysis must not contain authoritative actions");
    }
    return analysis;
  }
  if (analysis.policyErrors.length !== 0) {
    throw new Error(`${analysis.classification} analysis must not contain policy errors`);
  }
  if (analysis.classification === CLASSIFICATION.ACTION_REQUIRED) {
    if (actionCount === 0) {
      throw new Error("ACTION_REQUIRED analysis must contain at least one action");
    }
    return analysis;
  }
  if (analysis.classification === CLASSIFICATION.NO_ACTION_REQUIRED) {
    if (actionCount !== 0) {
      throw new Error("NO_ACTION_REQUIRED analysis must not contain actions");
    }
    return analysis;
  }
  throw new Error(`Unsupported analysis classification ${analysis.classification}`);
}
