package io.pockethive.work.config;

import java.util.Map;

/**
 * Responsibility: locate named settings blocks under the invocation interceptor root.
 * Must not: interpret interceptor settings or discover template sources.
 * Contract: RESP-WORK-MESSAGE-TEMPLATE — docs/architecture/runtime-responsibilities.md#resp-work-message-template.
 */
public final class InvocationInterceptorConfiguration {
    private static final String ROOT = "interceptors";

    private InvocationInterceptorConfiguration() {}

    public static Map<?, ?> block(Map<String, Object> configuration, String name) {
        if (configuration == null || !(configuration.get(ROOT) instanceof Map<?, ?> interceptors)) {
            return Map.of();
        }
        return interceptors.get(name) instanceof Map<?, ?> block ? block : Map.of();
    }

    public static String path(String name) {
        return WorkConfigurationFields.path(ROOT, name);
    }
}
