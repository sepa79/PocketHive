package io.pockethive.orchestrator.app;

import io.pockethive.orchestrator.app.ComponentConfigContracts.PreviewResponse;
import io.pockethive.orchestrator.app.ComponentConfigContracts.SideEffect;
import io.pockethive.orchestrator.app.ComponentConfigContracts.Target;
import io.pockethive.orchestrator.domain.Swarm;
import io.pockethive.orchestrator.domain.SwarmStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public final class ComponentConfigPreviewService {
    private final SwarmStore swarms;

    public ComponentConfigPreviewService(SwarmStore swarms) {
        this.swarms = Objects.requireNonNull(swarms, "swarms");
    }

    public PreviewResponse preview(String swarmId, String role, String instance, Map<String, Object> requestedPatch) {
        String exactSwarmId = requireText("swarmId", swarmId);
        String exactRole = requireText("role", role);
        String exactInstance = requireText("instance", instance);
        Map<String, Object> patch = ComponentConfigPatch.requireForPreview(requestedPatch);
        Swarm swarm = swarms.find(exactSwarmId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "swarm was not found"));
        Map<String, Object> current = currentConfig(swarm, exactRole, exactInstance);
        return new PreviewResponse(
            SideEffect.NONE,
            new Target(exactSwarmId, exactRole, exactInstance),
            current,
            patch,
            ComponentConfigPatch.shallowMerge(current, patch));
    }

    private static Map<String, Object> currentConfig(Swarm swarm, String role, String instance) {
        Object rawWorkers = swarm.getObservation().get("workers");
        if (!(rawWorkers instanceof List<?> workers)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "current control-plane worker observation is unavailable");
        }
        List<Map<String, Object>> matches = new ArrayList<>();
        for (Object item : workers) {
            if (!(item instanceof Map<?, ?> raw)) {
                continue;
            }
            Map<String, Object> worker = stringKeyed(raw);
            if (role.equals(worker.get("role")) && instance.equals(worker.get("instance"))) {
                matches.add(worker);
            }
        }
        if (matches.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "component config target was not found in the current control-plane observation");
        }
        if (matches.size() > 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "component config target is ambiguous in the current control-plane observation");
        }
        Object rawConfig = matches.getFirst().get("config");
        if (!(rawConfig instanceof Map<?, ?> config)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "component config is unavailable in the current control-plane observation");
        }
        return Collections.unmodifiableMap(stringKeyed(config));
    }

    private static Map<String, Object> stringKeyed(Map<?, ?> source) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (!(key instanceof String text)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "component observation contains a non-string field name");
            }
            result.put(text, value);
        });
        return result;
    }

    private static String requireText(String field, String value) {
        if (value == null || value.isBlank() || !value.equals(value.trim())) {
            throw new IllegalArgumentException(field + " must be non-blank and normalized");
        }
        return value;
    }
}
