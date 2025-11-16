package io.pockethive.worker.sdk.config;

/**
 * Generic configuration for the templating interceptor.
 * <p>
 * Intended to be embedded in worker-specific config types under a {@code templating} field so
 * the shared interceptor can be enabled purely via configuration.
 */
public record TemplatingConfig(
    boolean enabled,
    String template
) {

    public TemplatingConfig {
        template = template == null ? "" : template;
    }
}

