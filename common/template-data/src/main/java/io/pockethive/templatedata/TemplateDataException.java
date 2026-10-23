package io.pockethive.templatedata;

import java.util.Objects;

/**
 * Responsibility: expose a categorized template-data failure without parser details.
 * Must not: retain response content or parser causes.
 * Contract: RESP-TEMPLATE-DATA — docs/architecture/runtime-responsibilities.md#resp-template-data.
 */
public final class TemplateDataException extends IllegalArgumentException {
    private final TemplateDataError code;

    public TemplateDataException(TemplateDataError code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
    }

    public TemplateDataError code() {
        return code;
    }
}
