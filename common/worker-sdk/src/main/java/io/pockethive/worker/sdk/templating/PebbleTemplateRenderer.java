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
    private final PebbleWeightedSelectionExtension.SeededSelector seededSelector;

    public PebbleTemplateRenderer() {
        this(new PebbleWeightedSelectionExtension.SeededSelector());
    }

    private PebbleTemplateRenderer(PebbleWeightedSelectionExtension.SeededSelector seededSelector) {
        this(defaultEngine(seededSelector), seededSelector);
    }

    public PebbleTemplateRenderer(PebbleEngine engine) {
        this(engine, new PebbleWeightedSelectionExtension.SeededSelector());
    }

    private PebbleTemplateRenderer(PebbleEngine engine, PebbleWeightedSelectionExtension.SeededSelector seededSelector) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.seededSelector = Objects.requireNonNull(seededSelector, "seededSelector");
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

    @Override
    public void resetSeededSelections() {
        seededSelector.reset();
    }

    private static PebbleEngine defaultEngine(PebbleWeightedSelectionExtension.SeededSelector seededSelector) {
        SpelTemplateEvaluator evaluator = new SpelTemplateEvaluator();
        return new PebbleEngine.Builder()
            .extension(new PebbleEvalExtension(evaluator))
            .extension(new PebbleWeightedSelectionExtension(seededSelector))
            .autoEscaping(false)
            .cacheActive(true)
            .build();
    }
}
