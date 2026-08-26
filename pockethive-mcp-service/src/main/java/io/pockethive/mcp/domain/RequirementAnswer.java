package io.pockethive.mcp.domain;

/**
 * Responsibility: Model the RequirementAnswer MCP domain concept and enforce its local invariants.
 * Must not: Access transport, configuration, or infrastructure adapters.
 * Contract: docs/mcp/README.md.
 */

public record RequirementAnswer(
    RequirementDisposition disposition,
    String value,
    AnswerProvenance provenance,
    ConfirmedSource confirmedSource
) {
    public RequirementAnswer(RequirementDisposition disposition, String value, AnswerProvenance provenance) {
        this(disposition, value, provenance, null);
    }

    public RequirementAnswer {
        if (disposition == RequirementDisposition.USER_CONFIRMED_SOURCE && confirmedSource == null) {
            throw new IllegalArgumentException("confirmed source is required");
        }
        if (disposition != RequirementDisposition.USER_CONFIRMED_SOURCE && confirmedSource != null) {
            throw new IllegalArgumentException("confirmed source is forbidden for this disposition");
        }
    }

    public static RequirementAnswer unknown() {
        return new RequirementAnswer(RequirementDisposition.UNKNOWN, null, null, null);
    }

    public static RequirementAnswer userProvided(String value, AnswerProvenance provenance) {
        return new RequirementAnswer(RequirementDisposition.USER_PROVIDED, requireText(value), provenance, null);
    }

    public static RequirementAnswer userConfirmedSource(String value, ConfirmedSource source,
                                                         AnswerProvenance provenance) {
        return new RequirementAnswer(RequirementDisposition.USER_CONFIRMED_SOURCE,
            requireText(value), provenance, source);
    }

    public static RequirementAnswer notApplicable(String reason, AnswerProvenance provenance) {
        return new RequirementAnswer(RequirementDisposition.NOT_APPLICABLE, requireText(reason), provenance, null);
    }

    private static String requireText(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("answer value must not be blank");
        }
        return value.trim();
    }
}
