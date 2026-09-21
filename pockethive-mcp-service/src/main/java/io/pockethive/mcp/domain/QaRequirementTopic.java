package io.pockethive.mcp.domain;

/**
 * Responsibility: Model the QaRequirementTopic MCP domain concept and enforce its local invariants.
 * Must not: Access transport, configuration, or infrastructure adapters.
 * Contract: docs/mcp/README.md.
 */

public enum QaRequirementTopic {
    GOAL_AND_RISK,
    SUT_AND_ENDPOINTS,
    JOURNEYS_SCHEMAS_AND_EXPECTATIONS,
    SLA_AND_STOPPING,
    LOAD_PROFILE_AND_TRAFFIC_SHAPE,
    TEST_DATA_STRATEGY,
    AUTHENTICATION_AND_SECRETS,
    SETUP_TEARDOWN_AND_DEPENDENCIES,
    BACKGROUND_TRAFFIC_AND_ISOLATION,
    ORACLES_OBSERVABILITY_AND_TRIAGE,
    REPORTING_TRACEABILITY_AND_RETENTION,
    SAFETY_GOVERNANCE_AND_ABORT
}
