package io.pockethive.templating;

import io.pockethive.templating.api.TemplateRenderer;
import io.pockethive.templating.api.TemplateRenderingException;
import io.pockethive.templating.api.TemplateSyntaxValidator;
import io.pockethive.templating.api.SequenceAccess;
import io.pockethive.templating.api.DisabledSequenceAccess;
import io.pebbletemplates.pebble.PebbleEngine;
import io.pebbletemplates.pebble.template.PebbleTemplate;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * {@link TemplateRenderer} backed by the Pebble templating engine.
 * <p>
 * This implementation uses {@link PebbleEngine#getLiteralTemplate(String)} so templates are provided
 * inline rather than loaded from files.
 * <p>
 * Template compilation results are cached for performance.
 * <p>
 * Responsibility: compile/render templates and validate syntax with the canonical engine.
 * Must not: select sequence connections or expose engine exceptions as its contract.
 * Contract: RESP-TEMPLATE-RENDER — docs/architecture/runtime-responsibilities.md#resp-template-render.
 */
public final class PebbleTemplateRenderer implements TemplateRenderer, TemplateSyntaxValidator {

    private final PebbleEngine engine;
    private final PebbleEngine validationEngine;
    private final PebbleWeightedSelectionExtension.SeededSelector seededSelector;
    private static final int TEMPLATE_CACHE_SIZE = 10;
    private final Map<String, PebbleTemplate> templateCache = Collections.synchronizedMap(
        new LinkedHashMap<String, PebbleTemplate>(TEMPLATE_CACHE_SIZE, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, PebbleTemplate> eldest) {
                return size() > TEMPLATE_CACHE_SIZE;
            }
        });

    public PebbleTemplateRenderer(SequenceAccess sequences) {
        this(sequences, new PebbleWeightedSelectionExtension.SeededSelector());
    }

    private PebbleTemplateRenderer(SequenceAccess sequences, PebbleWeightedSelectionExtension.SeededSelector seededSelector) {
        this(defaultEngine(sequences, seededSelector), seededSelector);
    }

    public PebbleTemplateRenderer(PebbleEngine engine) {
        this(engine, new PebbleWeightedSelectionExtension.SeededSelector());
    }

    private PebbleTemplateRenderer(PebbleEngine engine, PebbleWeightedSelectionExtension.SeededSelector seededSelector) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.seededSelector = Objects.requireNonNull(seededSelector, "seededSelector");
        this.validationEngine = validationEngine();
    }

    @Override
    public String render(String templateSource, Map<String, Object> context) {
        Objects.requireNonNull(templateSource, "templateSource");
        Map<String, Object> safeContext = context == null ? Map.of() : context;
        try {
            PebbleTemplate template = templateCache.get(templateSource);
            if (template == null) {
                template = engine.getLiteralTemplate(templateSource);
                templateCache.put(templateSource, template);
            }
            try (Writer writer = new StringWriter()) {
                template.evaluate(writer, safeContext);
                return writer.toString();
            }
        } catch (RuntimeException | IOException ex) {
            throw new TemplateRenderingException("Failed to render template", ex);
        }
    }

    public void validateSyntax(String templateSource) {
        Objects.requireNonNull(templateSource, "templateSource");
        try {
            PebbleTemplate template = validationEngine.getLiteralTemplate(templateSource);
            try (Writer writer = new StringWriter()) {
                template.evaluate(writer, Map.of());
            }
        } catch (IOException | RuntimeException ex) {
            throw new TemplateRenderingException("Failed to compile template", ex);
        }
    }

    @Override
    public void resetSeededSelections() {
        seededSelector.reset();
    }

    private static PebbleEngine defaultEngine(SequenceAccess sequences, PebbleWeightedSelectionExtension.SeededSelector seededSelector) {
        SpelTemplateEvaluator evaluator = new SpelTemplateEvaluator(sequences);
        return new PebbleEngine.Builder()
            .extension(new PebbleEvalExtension(evaluator))
            .extension(new PebbleWeightedSelectionExtension(seededSelector))
            .autoEscaping(false)
            .cacheActive(true)
            .build();
    }

    private static PebbleEngine validationEngine() {
        return new PebbleEngine.Builder()
            .extension(new PebbleEvalExtension(new SpelTemplateEvaluator(DisabledSequenceAccess.INSTANCE), true))
            .extension(new PebbleWeightedSelectionExtension(new PebbleWeightedSelectionExtension.SeededSelector()))
            .autoEscaping(false)
            .cacheActive(true)
            .build();
    }
}
