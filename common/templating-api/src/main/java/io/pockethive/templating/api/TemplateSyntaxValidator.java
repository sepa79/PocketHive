package io.pockethive.templating.api;

/**
 * Responsibility: validate syntax with the same compiler used for rendering.
 * Must not: execute sequence effects or introduce a second syntax parser.
 * Contract: RESP-TEMPLATE-RENDER — docs/architecture/runtime-responsibilities.md#resp-template-render.
 */
public interface TemplateSyntaxValidator {
    void validateSyntax(String templateSource);
}
