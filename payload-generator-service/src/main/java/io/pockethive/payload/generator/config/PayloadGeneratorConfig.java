package io.pockethive.payload.generator.config;

import io.pockethive.worker.sdk.input.SchedulerStates;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record PayloadGeneratorConfig(
    double ratePerSec,
    boolean singleRequest,
    Template template
) implements SchedulerStates.RateConfig {

    public PayloadGeneratorConfig {
        ratePerSec = Double.isNaN(ratePerSec) || ratePerSec < 0 ? 0.0 : ratePerSec;
        template = template == null ? Template.defaults() : template;
    }

    public PayloadGeneratorConfig() {
        this(0.0, false, Template.defaults());
    }

    public static PayloadGeneratorConfig of(double ratePerSec, boolean singleRequest, Template template) {
        return new PayloadGeneratorConfig(ratePerSec, singleRequest, template);
    }

    public static PayloadGeneratorConfig copyOf(SchedulerStates.RateConfig config, Template template) {
        Objects.requireNonNull(config, "config");
        return new PayloadGeneratorConfig(config.ratePerSec(), config.singleRequest(), template);
    }

    public record Template(
        String method,
        String url,
        String baseUrl,
        String path,
        Map<String, String> query,
        Map<String, String> headers,
        String body
    ) {

        public Template {
            method = normalise(method, "POST");
            url = normalise(url, "");
            baseUrl = normalise(baseUrl, "");
            path = normalise(path, "/");
            query = query == null ? Map.of() : copy(query);
            headers = headers == null ? Map.of() : copy(headers);
            body = body == null ? "" : body;
        }

        private static String normalise(String value, String defaultValue) {
            if (value == null) {
                return defaultValue;
            }
            String trimmed = value.trim();
            return trimmed.isEmpty() ? defaultValue : trimmed;
        }

        private static Map<String, String> copy(Map<String, String> source) {
            Map<String, String> copy = new LinkedHashMap<>();
            source.forEach((key, val) -> {
                if (key != null && !key.isBlank()) {
                    copy.put(key.trim(), val == null ? "" : val);
                }
            });
            return copy.isEmpty() ? Map.of() : Collections.unmodifiableMap(copy);
        }

        public static Template defaults() {
            return new Template("POST", "", "", "/", Map.of(), Map.of(), "");
        }
    }
}
