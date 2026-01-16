package io.pockethive.swarm.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.LinkedHashMap;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Work(Map<String, String> in, Map<String, String> out) {
    public Work {
        in = normalizePorts(in);
        out = normalizePorts(out);
    }

    public String defaultIn() {
        return in.get("in");
    }

    public String defaultOut() {
        return out.get("out");
    }

    public static Work ofDefaults(String in, String out) {
        Map<String, String> inMap = normalizePorts(in == null ? Map.of() : Map.of("in", in));
        Map<String, String> outMap = normalizePorts(out == null ? Map.of() : Map.of("out", out));
        return new Work(inMap, outMap);
    }

    private static Map<String, String> normalizePorts(Map<String, String> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : raw.entrySet()) {
            String key = entry.getKey() != null ? entry.getKey().trim() : "";
            String value = entry.getValue() != null ? entry.getValue().trim() : "";
            if (!key.isEmpty() && !value.isEmpty()) {
                normalized.put(key, value);
            }
        }
        return normalized.isEmpty() ? Map.of() : Map.copyOf(normalized);
    }
}
