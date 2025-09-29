package io.pockethive.scenarios.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ScenarioDefinition {
    @Valid
    @NotNull
    private ScenarioSchedule schedule;

    @Valid
    private ScenarioRunConfig runConfig;

    @Valid
    @NotEmpty
    private List<ScenarioTrack> tracks;

    public ScenarioDefinition() {
    }

    public ScenarioSchedule getSchedule() {
        return schedule;
    }

    public void setSchedule(ScenarioSchedule schedule) {
        this.schedule = schedule;
    }

    public ScenarioRunConfig getRunConfig() {
        return runConfig;
    }

    public void setRunConfig(ScenarioRunConfig runConfig) {
        this.runConfig = runConfig;
    }

    public List<ScenarioTrack> getTracks() {
        return tracks;
    }

    public void setTracks(List<ScenarioTrack> tracks) {
        this.tracks = tracks;
    }
}
