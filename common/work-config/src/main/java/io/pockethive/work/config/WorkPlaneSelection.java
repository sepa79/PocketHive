package io.pockethive.work.config;

import java.util.Map;
import java.util.Objects;

/**
 * Responsibility: retain the explicit deployment WorkPlane selection and its bootstrap projection.
 * Must not: infer selection, enumerate adapters or construct infrastructure.
 * Contract: RESP-WORK-ADAPTER-SELECTION — docs/architecture/runtime-responsibilities.md#resp-work-adapter-selection.
 */
public record WorkPlaneSelection(WorkIoType type) {
    public static final String PROPERTY = "pockethive.work.type";
    public static final String ENVIRONMENT = "POCKETHIVE_WORK_TYPE";
    public WorkPlaneSelection { Objects.requireNonNull(type, "type"); }
    public Map<String, String> environment() { return Map.of(ENVIRONMENT, type.name()); }
}
