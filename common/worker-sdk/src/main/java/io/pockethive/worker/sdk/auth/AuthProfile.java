package io.pockethive.worker.sdk.auth;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.LinkedHashMap;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = false)
public final class AuthProfile {
    private AuthType type;
    private Storage storage = new Storage();
    private Refresh refresh = new Refresh();
    private Map<String, Object> http = Map.of();
    private final Map<String, Object> properties = new LinkedHashMap<>();

    public AuthType getType() {
        return type;
    }

    public void setType(AuthType type) {
        this.type = type;
    }

    public Storage getStorage() {
        return storage;
    }

    public void setStorage(Storage storage) {
        this.storage = storage == null ? new Storage() : storage;
    }

    public Refresh getRefresh() {
        return refresh;
    }

    public void setRefresh(Refresh refresh) {
        this.refresh = refresh == null ? new Refresh() : refresh;
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

    public static final class Storage {
        private AuthStorageMode mode = AuthStorageMode.NONE;
        private String tokenKey;

        public AuthStorageMode getMode() {
            return mode;
        }

        public void setMode(AuthStorageMode mode) {
            this.mode = mode == null ? AuthStorageMode.NONE : mode;
        }

        public String getTokenKey() {
            return tokenKey;
        }

        public void setTokenKey(String tokenKey) {
            this.tokenKey = normalize(tokenKey);
        }
    }

    public static final class Refresh {
        private int refreshAheadSeconds = 60;
        private int emergencyRefreshAheadSeconds = 10;
        private int leaseSeconds = 15;

        public int getRefreshAheadSeconds() {
            return refreshAheadSeconds;
        }

        public void setRefreshAheadSeconds(int refreshAheadSeconds) {
            this.refreshAheadSeconds = Math.max(0, refreshAheadSeconds);
        }

        public int getEmergencyRefreshAheadSeconds() {
            return emergencyRefreshAheadSeconds;
        }

        public void setEmergencyRefreshAheadSeconds(int emergencyRefreshAheadSeconds) {
            this.emergencyRefreshAheadSeconds = Math.max(0, emergencyRefreshAheadSeconds);
        }

        public int getLeaseSeconds() {
            return leaseSeconds;
        }

        public void setLeaseSeconds(int leaseSeconds) {
            this.leaseSeconds = leaseSeconds <= 0 ? 15 : leaseSeconds;
        }
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
