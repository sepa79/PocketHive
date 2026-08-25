package io.pockethive.orchestrator.app;

import java.util.Map;

public final class ComponentConfigContracts {
    private ComponentConfigContracts() {
    }

    public enum SideEffect {
        NONE
    }

    public record PreviewRequest(String swarmId, Map<String, Object> patch) {
    }

    public record Target(String swarmId, String role, String instance) {
    }

    public record PreviewResponse(
        SideEffect sideEffect,
        Target target,
        Map<String, Object> currentConfig,
        Map<String, Object> patch,
        Map<String, Object> effectiveConfig) {
    }
}
