package io.pockethive.journal.api;

/**
 * Responsibility: validate the Planner-owned metadata reference wire shape.
 * Must not: generate Planner identities or resolve plan membership.
 * Contract: RESP-POCKETPLANNER-INTEGRATION — docs/architecture/runtime-responsibilities.md#resp-pocketplanner-integration.
 */
public record PlannerRunLink(String testPlan, String activityTag) {
    public static final String PLAN_PREFIX = "pp:";
    public static final String ACTIVITY_PREFIX = "pp.activity:";

    public PlannerRunLink {
        if (testPlan == null || !testPlan.startsWith(PLAN_PREFIX) || testPlan.length() > 512
            || !testPlan.equals(testPlan.trim()) || testPlan.length() <= PLAN_PREFIX.length()) {
            throw new IllegalArgumentException("Invalid Planner plan reference");
        }
        if (activityTag == null || !activityTag.matches("pp\\.activity:[A-Za-z0-9_-]{43}")) {
            throw new IllegalArgumentException("Invalid Planner Activity tag");
        }
    }
}
