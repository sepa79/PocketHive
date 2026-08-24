package io.pockethive.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class SwarmReadinessWaiterTest {
    private static final String PATH = "/orchestrator/api/swarms/swarm%2Fone";

    @Test
    void reportsReadyOnlyWhenCanonicalProjectionConfirmsStartupReadiness() {
        FakeOwner owner = new FakeOwner(status("READY", "STOPPED", false, true, 3, 3));
        SwarmReadinessObserver observer = new SwarmReadinessObserver(owner, new ObjectMapper());

        SwarmReadinessResult result = observer.observe(PATH, "swarm/one");

        assertThat(result).isEqualTo(new SwarmReadinessResult(
            true, "swarm/one", Map.of("desired", 3, "healthy", 3), "READY", 1));
        assertThat(owner.path).isEqualTo(PATH);
        assertThat(owner.calls).isEqualTo(1);
    }

    @Test
    void returnsNotReadyImmediatelyWithoutSleepingOrRetrying() {
        FakeOwner owner = new FakeOwner(status("PROVISIONING", "STOPPED", false, false, 2, 1));
        SwarmReadinessObserver observer = new SwarmReadinessObserver(owner, new ObjectMapper());

        assertThat(observer.observe(PATH, "swarm/one")).isEqualTo(new SwarmReadinessResult(
            false, "swarm/one", Map.of("desired", 2, "healthy", 1), "PROVISIONING", 1));
        assertThat(owner.calls).isEqualTo(1);
    }

    @Test
    void readinessRequiresFreshStoppedStartupReadyObservationAndPositiveDesiredWorkers() {
        assertReady(status("READY", "STOPPED", false, true, 0, 0), false);
        assertReady(status("PROVISIONING", "STOPPED", false, true, 1, 1), false);
        assertReady(status("READY", "RUNNING", false, true, 1, 1), false);
        assertReady(status("READY", "STOPPED", true, true, 1, 1), false);
        assertReady(status("READY", "STOPPED", false, false, 1, 1), false);
        assertReady(status("READY", "STOPPED", false, true, 2, 1), false);
        assertReady(status("READY", "STOPPED", false, true, 1, 1), true);
    }

    @Test
    void rejectsMalformedCanonicalOwnerProjection() {
        for (Object response : List.of(
            Map.of(),
            status("", "STOPPED", false, true, 1, 1),
            status(42, "STOPPED", false, true, 1, 1),
            Map.of(
                "controllerState", "READY",
                "workloadState", "STOPPED",
                "observationStale", false,
                "observation", Map.of("startupReady", true, "expectedWorkers", List.of())),
            Map.of(
                "controllerState", "READY",
                "workloadState", "STOPPED",
                "observationStale", false,
                "observation", Map.of(
                    "startupReady", true,
                    "expectedWorkers", List.of(Map.of("role", "generator")),
                    "workers", List.of(Map.of("role", "generator")))))) {
            SwarmReadinessObserver malformed = new SwarmReadinessObserver(
                new FakeOwner(response), new ObjectMapper());

            assertThatThrownBy(() -> malformed.observe(PATH, "swarm"))
                .isInstanceOfSatisfying(ToolExecutionException.class,
                    exception -> assertThat(exception.code()).isEqualTo("SWARM_STATUS_INVALID"));
        }
    }

    @Test
    void rejectsUnknownCanonicalLifecycleStates() {
        for (Object response : List.of(
            status("CREATING", "STOPPED", false, true, 1, 1),
            status("READY", "PAUSED", false, true, 1, 1))) {
            SwarmReadinessObserver observer = new SwarmReadinessObserver(
                new FakeOwner(response), new ObjectMapper());

            assertThatThrownBy(() -> observer.observe(PATH, "swarm"))
                .isInstanceOfSatisfying(ToolExecutionException.class,
                    exception -> assertThat(exception.code()).isEqualTo("SWARM_STATUS_INVALID"));
        }
    }

    @Test
    void propagatesOwnerFailureWithoutRetry() {
        RuntimeException ownerFailure = new RuntimeException("owner unavailable");
        OwnerApiPort failingOwner = new OwnerApiPort() {
            @Override
            public Object get(String path) {
                throw ownerFailure;
            }

            @Override
            public String getText(String path) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Object post(String path, Object body) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Object delete(String path) {
                throw new UnsupportedOperationException();
            }
        };
        SwarmReadinessObserver failing = new SwarmReadinessObserver(failingOwner, new ObjectMapper());

        assertThatThrownBy(() -> failing.observe(PATH, "swarm")).isSameAs(ownerFailure);
    }

    private static void assertReady(Map<String, Object> status, boolean expected) {
        assertThat(new SwarmReadinessObserver(new FakeOwner(status), new ObjectMapper())
            .observe(PATH, "swarm").ready()).isEqualTo(expected);
    }

    private static Map<String, Object> status(Object controllerState,
                                              Object workloadState,
                                              boolean observationStale,
                                              boolean startupReady,
                                              int desired,
                                              int healthy) {
        List<Map<String, Object>> expectedWorkers = IntStream.range(0, desired)
            .mapToObj(index -> Map.<String, Object>of(
                "role", "worker-" + index,
                "instance", "swarm-worker-" + index))
            .toList();
        List<Map<String, Object>> workers = IntStream.range(0, desired)
            .mapToObj(index -> Map.<String, Object>of(
                "role", "worker-" + index,
                "instance", "swarm-worker-" + index,
                "stale", index >= healthy))
            .toList();
        return Map.of(
            "controllerState", controllerState,
            "workloadState", workloadState,
            "observationStale", observationStale,
            "observation", Map.of(
                "startupReady", startupReady,
                "expectedWorkers", expectedWorkers,
                "workers", workers));
    }

    private static final class FakeOwner implements OwnerApiPort {
        private final Object response;
        private String path;
        private int calls;

        private FakeOwner(Object response) {
            this.response = response;
        }

        @Override
        public Object get(String path) {
            this.path = path;
            calls++;
            return response;
        }

        @Override
        public String getText(String path) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object post(String path, Object body) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object delete(String path) {
            throw new UnsupportedOperationException();
        }
    }
}
