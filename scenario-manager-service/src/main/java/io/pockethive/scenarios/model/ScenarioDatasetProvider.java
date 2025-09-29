package io.pockethive.scenarios.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ScenarioDatasetProvider {
    @NotNull
    private ProviderType type;

    @Valid
    private ScenarioRedisProvider redis;

    @Valid
    private ScenarioCsvProvider csv;

    @Valid
    private ScenarioInlineProvider inline;

    public ScenarioDatasetProvider() {
    }

    public ProviderType getType() {
        return type;
    }

    public void setType(ProviderType type) {
        this.type = type;
    }

    public ScenarioRedisProvider getRedis() {
        return redis;
    }

    public void setRedis(ScenarioRedisProvider redis) {
        this.redis = redis;
    }

    public ScenarioCsvProvider getCsv() {
        return csv;
    }

    public void setCsv(ScenarioCsvProvider csv) {
        this.csv = csv;
    }

    public ScenarioInlineProvider getInline() {
        return inline;
    }

    public void setInline(ScenarioInlineProvider inline) {
        this.inline = inline;
    }

    @AssertTrue(message = "Provider configuration missing for selected type")
    public boolean isConfigurationValid() {
        if (type == null) {
            return false;
        }
        return switch (type) {
            case REDIS -> redis != null;
            case CSV -> csv != null;
            case INLINE -> inline != null;
        };
    }

    public enum ProviderType {
        REDIS("redis"),
        CSV("csv"),
        INLINE("inline");

        private final String value;

        ProviderType(String value) {
            this.value = value;
        }

        @JsonCreator
        public static ProviderType fromValue(String value) {
            if (value == null) {
                return null;
            }
            for (ProviderType type : values()) {
                if (type.value.equalsIgnoreCase(value)) {
                    return type;
                }
            }
            throw new IllegalArgumentException("Unknown provider type: " + value);
        }

        @JsonValue
        public String toValue() {
            return value;
        }
    }
}
