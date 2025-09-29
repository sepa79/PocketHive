package io.pockethive.scenarios.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ScenarioRunConfig {
    private String runPrefix;
    private Boolean allowParallel;

    public ScenarioRunConfig() {
    }

    public String getRunPrefix() {
        return runPrefix;
    }

    public void setRunPrefix(String runPrefix) {
        this.runPrefix = runPrefix;
    }

    public Boolean getAllowParallel() {
        return allowParallel;
    }

    public void setAllowParallel(Boolean allowParallel) {
        this.allowParallel = allowParallel;
    }
}
