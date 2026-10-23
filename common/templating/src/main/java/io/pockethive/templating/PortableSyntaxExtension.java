package io.pockethive.templating;

import io.pebbletemplates.pebble.extension.AbstractExtension;
import io.pebbletemplates.pebble.extension.Function;
import io.pebbletemplates.pebble.extension.NodeVisitorFactory;
import io.pebbletemplates.pebble.template.PebbleTemplateImpl;
import java.util.List;
import java.util.Map;

/**
 * Responsibility: attach portable admission to the real Pebble compiler.
 * Must not: duplicate language parsing or decide function capability registration.
 * Contract: RESP-TEMPLATE-RENDER — docs/architecture/runtime-responsibilities.md#resp-template-render.
 */
final class PortableSyntaxExtension extends AbstractExtension {
    private final Map<String, Function> functions;
    PortableSyntaxExtension(Map<String, Function> functions) { this.functions = functions; }
    @Override public List<NodeVisitorFactory> getNodeVisitors() {
        return List.of(template -> new PortableSyntaxVisitor((PebbleTemplateImpl) template, functions));
    }
}
