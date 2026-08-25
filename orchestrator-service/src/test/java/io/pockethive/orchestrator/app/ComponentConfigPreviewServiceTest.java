package io.pockethive.orchestrator.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pockethive.orchestrator.app.ComponentConfigContracts.SideEffect;
import io.pockethive.orchestrator.domain.Swarm;
import io.pockethive.orchestrator.domain.SwarmStore;
import io.pockethive.swarm.model.NetworkMode;
import io.pockethive.swarm.model.lifecycle.ControllerState;
import io.pockethive.swarm.model.lifecycle.Health;
import io.pockethive.swarm.model.lifecycle.RuntimeResourceState;
import io.pockethive.swarm.model.lifecycle.WorkloadState;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class ComponentConfigPreviewServiceTest {
    @Test
    void previewsTheExactObservedTargetWithTheCanonicalShallowPatch() {
        SwarmStore store = observedStore();
        ComponentConfigPreviewService service = new ComponentConfigPreviewService(store);

        var result = service.preview("sw1", "generator", "generator-1",
            Map.of("enabled", true, "rate", 20));

        assertThat(result.sideEffect()).isEqualTo(SideEffect.NONE);
        assertThat(result.target()).isEqualTo(new ComponentConfigContracts.Target(
            "sw1", "generator", "generator-1"));
        assertThat(result.currentConfig()).containsExactlyInAnyOrderEntriesOf(Map.of(
            "enabled", false, "rate", 10, "inputs", Map.of("type", "SCHEDULER")));
        assertThat(result.effectiveConfig()).containsExactlyInAnyOrderEntriesOf(Map.of(
            "enabled", true, "rate", 20, "inputs", Map.of("type", "SCHEDULER")));
    }

    @Test
    void failsExplicitlyForUnknownOrUnavailableTargetsAndInvalidPatches() {
        ComponentConfigPreviewService service = new ComponentConfigPreviewService(observedStore());

        assertThatThrownBy(() -> service.preview("missing", "generator", "generator-1", Map.of("rate", 2)))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("404 NOT_FOUND");
        assertThatThrownBy(() -> service.preview("sw1", "processor", "processor-1", Map.of("rate", 2)))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("404 NOT_FOUND");
        assertThatThrownBy(() -> service.preview("sw1", "generator", "generator-1", Map.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("patch must not be empty");
        assertThatThrownBy(() -> service.preview(
            "sw1", "generator", "generator-1", Map.of("enabled", "yes")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("patch.enabled must be a boolean");

        SwarmStore unobserved = new SwarmStore();
        unobserved.register(new Swarm("sw1", "controller-1", "manager-1", "run-1", NetworkMode.DIRECT));
        assertThatThrownBy(() -> new ComponentConfigPreviewService(unobserved)
            .preview("sw1", "generator", "generator-1", Map.of("rate", 2)))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("409 CONFLICT");
    }

    @Test
    void canonicalPatchRulesAreExactForUpdatesAndEnabledExpectations() {
        assertThat(ComponentConfigPatch.normalizeForUpdate(null)).isNull();
        assertThat(ComponentConfigPatch.normalizeForUpdate(Map.of())).isNull();
        assertThatThrownBy(() -> ComponentConfigPatch.normalizeForUpdate(Map.of("enabled", "yes")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("patch.enabled must be a boolean");

        assertThat(ComponentConfigPatch.enabledExpectation(null))
            .isEqualTo(io.pockethive.orchestrator.domain.SwarmOperationCoordinator.ConfigEnabledExpectation.UNCHANGED);
        assertThat(ComponentConfigPatch.enabledExpectation(Map.of("rate", 10)))
            .isEqualTo(io.pockethive.orchestrator.domain.SwarmOperationCoordinator.ConfigEnabledExpectation.UNCHANGED);
        assertThat(ComponentConfigPatch.enabledExpectation(Map.of("enabled", false)))
            .isEqualTo(io.pockethive.orchestrator.domain.SwarmOperationCoordinator.ConfigEnabledExpectation.DISABLED);
    }

    @Test
    void canonicalPatchUtilityHasNoInstantiablePublicSurface() throws Exception {
        var constructor = ComponentConfigPatch.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        assertThat(constructor.newInstance()).isInstanceOf(ComponentConfigPatch.class);
    }

    @Test
    void rejectsAmbiguousMalformedAndUnnormalizedOwnerObservations() {
        assertThatThrownBy(() -> previewServiceWithWorkers(List.of(
            worker(Map.of("rate", 1)),
            worker(Map.of("rate", 2))))
            .preview("sw1", "generator", "generator-1", Map.of("rate", 3)))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("ambiguous");

        assertThatThrownBy(() -> previewServiceWithWorkers(List.of(
            Map.of("role", "generator", "instance", "generator-1")))
            .preview("sw1", "generator", "generator-1", Map.of("rate", 3)))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("component config is unavailable");

        Map<Object, Object> malformedConfig = new LinkedHashMap<>();
        malformedConfig.put(1, "not-a-string-key");
        assertThatThrownBy(() -> previewServiceWithWorkers(List.of(
            worker(malformedConfig)))
            .preview("sw1", "generator", "generator-1", Map.of("rate", 3)))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("non-string field name");

        assertThatThrownBy(() -> new ComponentConfigPreviewService(observedStore())
            .preview("sw1", " generator", "generator-1", Map.of("rate", 3)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("role must be non-blank and normalized");
    }

    private static SwarmStore observedStore() {
        Swarm swarm = new Swarm("sw1", "controller-1", "manager-1", "run-1", NetworkMode.DIRECT);
        swarm.updateObservation(
            ControllerState.READY,
            WorkloadState.STOPPED,
            Health.HEALTHY,
            RuntimeResourceState.PRESENT,
            Map.of("workers", List.of(Map.of(
                "role", "generator",
                "instance", "generator-1",
                "config", Map.of(
                    "enabled", false,
                    "rate", 10,
                    "inputs", Map.of("type", "SCHEDULER"))))),
            Instant.parse("2026-08-25T10:00:00Z"));
        SwarmStore store = new SwarmStore();
        store.register(swarm);
        return store;
    }

    private static ComponentConfigPreviewService previewServiceWithWorkers(List<?> workers) {
        Swarm swarm = new Swarm("sw1", "controller-1", "manager-1", "run-1", NetworkMode.DIRECT);
        swarm.updateObservation(
            ControllerState.READY,
            WorkloadState.STOPPED,
            Health.HEALTHY,
            RuntimeResourceState.PRESENT,
            Map.of("workers", workers),
            Instant.parse("2026-08-25T10:00:00Z"));
        SwarmStore store = new SwarmStore();
        store.register(swarm);
        return new ComponentConfigPreviewService(store);
    }

    private static Map<String, Object> worker(Map<?, ?> config) {
        return Map.of(
            "role", "generator",
            "instance", "generator-1",
            "config", config);
    }
}
