package io.pockethive.scenarios.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ScenarioTrackBlock {
    @NotNull
    private BlockType type;

    @PositiveOrZero
    private Double rateFrom;

    @PositiveOrZero
    private Double rateTo;

    @PositiveOrZero
    private Double rate;

    @Positive
    private Integer durationSec;

    private String name;

    private Object payload;

    @Min(0)
    private Integer atOffsetSec;

    private String signal;

    @Positive
    private Integer timeoutSec;

    public ScenarioTrackBlock() {
    }

    public BlockType getType() {
        return type;
    }

    public void setType(BlockType type) {
        this.type = type;
    }

    public Double getRateFrom() {
        return rateFrom;
    }

    public void setRateFrom(Double rateFrom) {
        this.rateFrom = rateFrom;
    }

    public Double getRateTo() {
        return rateTo;
    }

    public void setRateTo(Double rateTo) {
        this.rateTo = rateTo;
    }

    public Double getRate() {
        return rate;
    }

    public void setRate(Double rate) {
        this.rate = rate;
    }

    public Integer getDurationSec() {
        return durationSec;
    }

    public void setDurationSec(Integer durationSec) {
        this.durationSec = durationSec;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Object getPayload() {
        return payload;
    }

    public void setPayload(Object payload) {
        this.payload = payload;
    }

    public Integer getAtOffsetSec() {
        return atOffsetSec;
    }

    public void setAtOffsetSec(Integer atOffsetSec) {
        this.atOffsetSec = atOffsetSec;
    }

    public String getSignal() {
        return signal;
    }

    public void setSignal(String signal) {
        this.signal = signal;
    }

    public Integer getTimeoutSec() {
        return timeoutSec;
    }

    public void setTimeoutSec(Integer timeoutSec) {
        this.timeoutSec = timeoutSec;
    }

    @AssertTrue(message = "Invalid block configuration")
    public boolean hasRequiredFields() {
        if (type == null) {
            return false;
        }
        return switch (type) {
            case RAMP -> rateFrom != null && rateTo != null && durationSec != null;
            case HOLD, SPIKE -> rate != null && durationSec != null;
            case PAUSE -> durationSec != null;
            case SIGNAL -> name != null && !name.isBlank();
            case WAIT_FOR -> signal != null && !signal.isBlank();
        };
    }

    public enum BlockType {
        RAMP("Ramp"),
        HOLD("Hold"),
        SPIKE("Spike"),
        PAUSE("Pause"),
        SIGNAL("Signal"),
        WAIT_FOR("WaitFor");

        private final String value;

        BlockType(String value) {
            this.value = value;
        }

        @JsonCreator
        public static BlockType fromValue(String value) {
            if (value == null) {
                return null;
            }
            for (BlockType type : values()) {
                if (type.value.equalsIgnoreCase(value) || type.name().equalsIgnoreCase(value)) {
                    return type;
                }
            }
            throw new IllegalArgumentException("Unknown block type: " + value);
        }

        @JsonValue
        public String toValue() {
            return value;
        }
    }
}
