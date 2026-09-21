package io.pockethive.worker.sdk.auth;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Responsibility: define the AuthProfile contract.
 * Must not: select infrastructure clients or own adapter lifecycle.
 * Contract: RESP-AUTH-VALUES — docs/architecture/runtime-responsibilities.md#resp-auth-values.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public final class AuthProfile {
    private AuthType type;
    private AuthProfileStorage storage = new AuthProfileStorage();
    private AuthProfileRefresh refresh = new AuthProfileRefresh();
    private Map<String, Object> http = Map.of();
    private final Map<String, Object> properties = new LinkedHashMap<>();

    public AuthType getType() {
        return type;
    }

    public void setType(AuthType type) {
        this.type = type;
    }

    public AuthProfileStorage getStorage() {
        return storage;
    }

    public void setStorage(AuthProfileStorage storage) {
        this.storage = storage == null ? new AuthProfileStorage() : storage;
    }

    public AuthProfileRefresh getRefresh() {
        return refresh;
    }

    public void setRefresh(AuthProfileRefresh refresh) {
        this.refresh = refresh == null ? new AuthProfileRefresh() : refresh;
    }

    public Map<String, Object> getHttp() {
        return http;
    }

    public void setHttp(Map<String, Object> http) {
        this.http = http == null ? Map.of() : Map.copyOf(http);
    }

    @JsonAnySetter
    public void putProperty(String key, Object value) {
        properties.put(key, value);
    }

    @JsonAnyGetter
    public Map<String, Object> properties() {
        return Map.copyOf(properties);
    }

    @JsonIgnore
    public Map<String, Object> mergedProperties() {
        Map<String, Object> merged = new LinkedHashMap<>();
        merged.putAll(properties);
        merged.putAll(http);
        return Map.copyOf(merged);
    }

    static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
