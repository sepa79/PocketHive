package io.pockethive.worker.sdk.templating;

import java.util.Map;

/**
 * Small, engine-agnostic templating API used by workers and interceptors.
 * <p>
 * Implementations are expected to be thread-safe.
 */
public interface TemplateRenderer {

    /**
     * Renders the given {@code template} using the supplied context map.
     *
     * @param template non-null template source
     * @param context  rendering context (may be {@code null}, treated as empty)
     * @return rendered template result
     * @throws TemplateRenderingException when rendering fails
     */
    String render(String template, Map<String, Object> context);

    /**
     * Clears any internal state related to deterministic templating helpers (for example
     * seeded weighted selection streams). Implementations that do not maintain such state
     * should treat this as a no-op.
     */
    default void resetSeededSelections() {
        // no-op
    }
}
