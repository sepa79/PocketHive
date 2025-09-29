package io.pockethive.scenarios.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class SwarmTemplateGenerator {
    @Positive
    private Integer maxConcurrency;

    @Min(0)
    private Integer thinkTimeMs;

    @Min(0)
    @Max(1)
    private Double jitterPct;

    public SwarmTemplateGenerator() {
    }

    public Integer getMaxConcurrency() {
        return maxConcurrency;
    }

    public void setMaxConcurrency(Integer maxConcurrency) {
        this.maxConcurrency = maxConcurrency;
    }

    public Integer getThinkTimeMs() {
        return thinkTimeMs;
    }

    public void setThinkTimeMs(Integer thinkTimeMs) {
        this.thinkTimeMs = thinkTimeMs;
    }

    public Double getJitterPct() {
        return jitterPct;
    }

    public void setJitterPct(Double jitterPct) {
        this.jitterPct = jitterPct;
    }
}
