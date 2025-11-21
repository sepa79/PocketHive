package io.pockethive.worker.sdk.templating;

import com.mitchellbosecke.pebble.PebbleEngine;
import com.mitchellbosecke.pebble.error.PebbleException;
import com.mitchellbosecke.pebble.template.PebbleTemplate;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.util.Map;
import java.util.Objects;

/**
 * {@link TemplateRenderer} backed by the Pebble templating engine.
 * <p>
 * This implementation uses {@link PebbleEngine#getLiteralTemplate(String)} so templates are provided
 * inline rather than loaded from files.
 */
public final class PebbleTemplateRenderer implements TemplateRenderer {

    private final PebbleEngine engine;

    public PebbleTemplateRenderer() {
        this(defaultEngine());
    }

    public PebbleTemplateRenderer(PebbleEngine engine) {
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    @Override
    public String render(String templateSource, Map<String, Object> context) {
        Objects.requireNonNull(templateSource, "templateSource");
        Map<String, Object> safeContext = context == null ? Map.of() : context;
        try {
            PebbleTemplate template = engine.getLiteralTemplate(templateSource);
            try (Writer writer = new StringWriter()) {
                template.evaluate(writer, safeContext);
                return writer.toString();
            }
        } catch (PebbleException | IOException ex) {
            throw new TemplateRenderingException("Failed to render template", ex);
        }
    }

    private static PebbleEngine defaultEngine() {
        SpelTemplateEvaluator evaluator = new SpelTemplateEvaluator();
        return new PebbleEngine.Builder()
            .extension(new PebbleEvalExtension(evaluator))
            .autoEscaping(false)
            .cacheActive(true)
            .build();
    }
}
