package io.pockethive.scenarios.validation;

import java.util.List;

public record TemplateValidationResult(
    boolean ok,
    String scenarioId,
    List<ValidationFinding> findings,
    List<String> referencedTemplateKeys,
    List<String> definedTemplateKeys
) {
    public TemplateValidationResult {
        findings = findings == null ? List.of() : List.copyOf(findings);
        referencedTemplateKeys = referencedTemplateKeys == null ? List.of() : List.copyOf(referencedTemplateKeys);
        definedTemplateKeys = definedTemplateKeys == null ? List.of() : List.copyOf(definedTemplateKeys);
    }
}
