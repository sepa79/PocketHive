package io.pockethive.scenarios.validation;

import java.util.List;

public record TemplateValidationResult(
    boolean ok,
    String scenarioId,
    List<ValidationFinding> findings,
    List<String> referencedCallIds,
    List<String> definedCallIds
) {
    public TemplateValidationResult {
        findings = findings == null ? List.of() : List.copyOf(findings);
        referencedCallIds = referencedCallIds == null ? List.of() : List.copyOf(referencedCallIds);
        definedCallIds = definedCallIds == null ? List.of() : List.copyOf(definedCallIds);
    }
}
