package io.pockethive.templating;

import java.util.Set;

/**
 * Responsibility: carry the compiler's static source facts without predicting runtime values.
 * Must not: decide variable requiredness or enumerate dynamic selectors.
 * Contract: RESP-TEMPLATE-RENDER — docs/architecture/runtime-responsibilities.md#resp-template-render.
 */
public record TemplateSourceAnalysis(String literal, Set<String> variableNames, boolean deferredVariableReads) {
    public TemplateSourceAnalysis { variableNames = Set.copyOf(variableNames); }
}
