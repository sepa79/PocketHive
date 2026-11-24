package io.pockethive.templating;

import io.pebbletemplates.pebble.extension.AbstractExtension;
import io.pebbletemplates.pebble.extension.NodeVisitorFactory;
import io.pebbletemplates.pebble.template.PebbleTemplateImpl;
import io.pockethive.templating.api.TemplateRenderingException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Responsibility: project literal text and static variable reads from the canonical compiler AST.
 * Must not: tokenize source, execute runtime effects or infer optional/required value presence.
 * Contract: RESP-TEMPLATE-RENDER — docs/architecture/runtime-responsibilities.md#resp-template-render.
 */
public final class TemplateSourceInspector extends AbstractExtension {
    private final Set<String> variables = new LinkedHashSet<>();
    private final StringBuilder text = new StringBuilder();
    private boolean dynamic;
    private boolean deferredVariableReads;

    private TemplateSourceInspector() { }

    public static TemplateSourceAnalysis inspect(String source) {
        TemplateSourceInspector inspection = new TemplateSourceInspector();
        try {
            PebbleTemplateRenderer.inspectSyntax(source, inspection);
            return new TemplateSourceAnalysis(inspection.dynamic ? null : inspection.text.toString(), inspection.variables, inspection.deferredVariableReads);
        } catch (RuntimeException failure) {
            throw new TemplateRenderingException("Failed to compile template", failure);
        }
    }

    @Override public List<NodeVisitorFactory> getNodeVisitors() {
        return List.of(template -> new TemplateVariableVisitor((PebbleTemplateImpl) template,
            variables, text, () -> dynamic = true, () -> deferredVariableReads = true));
    }
}
