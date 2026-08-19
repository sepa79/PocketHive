package io.pockethive.mcp.domain;

import java.util.Map;
import java.util.Objects;

public record ScenarioWorkflowSnapshot(
    String id,
    String agentSessionId,
    PrincipalKey principal,
    Map<QaRequirementTopic, RequirementAnswer> requirements,
    long revision,
    ScenarioWorkflowState state,
    CapabilityFingerprint capabilityFingerprint,
    String generatedFileSetDigest,
    ValidationReceipt validation,
    String publicationReceiptDigest
) {
    public ScenarioWorkflowSnapshot {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(agentSessionId, "agentSessionId");
        Objects.requireNonNull(principal, "principal");
        requirements = Map.copyOf(requirements);
        Objects.requireNonNull(state, "state");
        if (revision < 0) {
            throw new IllegalArgumentException("revision must not be negative");
        }
    }
}
