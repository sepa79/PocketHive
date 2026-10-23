package io.pockethive.templating;

import io.pebbletemplates.pebble.PebbleEngine;
import io.pebbletemplates.pebble.loader.StringLoader;
import io.pockethive.requesttemplates.PortableCapability;
import io.pockethive.requesttemplates.PortableHttpCallParser;
import io.pockethive.templatedata.TemplateDataError;
import io.pockethive.templatedata.TemplateDataException;
import io.pockethive.templating.api.TemplateRenderer;
import io.pockethive.templating.api.TemplateSyntaxValidator;
import java.util.Map;
import java.util.Set;

/**
 * Responsibility: compile/render the explicit bounded portable Pebble profile.
 * Must not: expose eval/SpEL/sequence or choose product bindings and transport.
 * Contract: RESP-TEMPLATE-RENDER — docs/architecture/runtime-responsibilities.md#resp-template-render.
 */
public final class PortableTemplateRenderer implements TemplateRenderer, TemplateSyntaxValidator {
    private final PebbleEngine engine;
    private final PebbleTemplateRenderer delegate;
    public PortableTemplateRenderer(Set<PortableCapability> capabilities) {
        PebbleDataExtension data = new PebbleDataExtension(Set.copyOf(capabilities), false);
        engine = new PebbleEngine.Builder().loader(new StringLoader()).strictVariables(true).autoEscaping(false).cacheActive(false)
            .methodAccessValidator((target, method) -> false)
            .maxRenderedSize(PortableHttpCallParser.MAX_TEMPLATE_LENGTH)
            .extension(data, new PortableSyntaxExtension(data.getFunctions())).build();
        delegate = new PebbleTemplateRenderer(engine);
    }
    @Override public String render(String source, Map<String, Object> context) {
        validateSyntax(source);
        try {
            return delegate.render(source, context);
        } catch (RuntimeException failure) {
            Throwable current = failure;
            while (current != null) {
                if (current instanceof TemplateDataException data) throw data;
                current = current.getCause();
            }
            throw new TemplateDataException(TemplateDataError.INVALID_VALUE, "Portable template rendering failed.");
        }
    }
    @Override public void validateSyntax(String source) {
        if (source == null || source.length() > PortableHttpCallParser.MAX_TEMPLATE_LENGTH) throw new TemplateDataException(TemplateDataError.LIMIT_EXCEEDED, "Template exceeds 64 KiB.");
        try { engine.getLiteralTemplate(source); }
        catch (RuntimeException failure) { throw new TemplateDataException(TemplateDataError.INVALID_VALUE, "Unsupported function or invalid portable template syntax."); }
    }
}
