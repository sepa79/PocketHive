package io.pockethive.orchestrator.planner;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.fasterxml.jackson.annotation.JsonAnySetter;

/**
 * Responsibility: carry one explicit selection from a versioned Planner catalogue.
 * Must not: trust browser-supplied tags or mutate planning state.
 * Contract: RESP-POCKETPLANNER-INTEGRATION — docs/architecture/runtime-responsibilities.md#resp-pocketplanner-integration.
 */
public record PlannerSelection(@NotBlank String instanceId, @JsonProperty(required = true) @JsonSetter(nulls = Nulls.FAIL) @Min(0) long revision,
                               @NotBlank String planId, @NotBlank String streamId, @NotBlank String activityId) {
    @JsonAnySetter
    public void rejectUnknown(String field, Object value) {
        throw new IllegalArgumentException("Unknown Planner selection field: " + field);
    }
}
