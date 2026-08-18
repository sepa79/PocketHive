package io.pockethive.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SwarmReadinessWaiterTest {
    private static final String PATH = "/orchestrator/api/swarms/swarm%2Fone";

    @Test
    void reportsReadyOnlyWhenStatusIsReadyAndAllDesiredWorkersAreHealthy() {
        FakeOwner owner = new FakeOwner(status("READY", 3, 3));
        SwarmReadinessObserver observer = new SwarmReadinessObserver(owner, new ObjectMapper());

        SwarmReadinessResult result = observer.observe(PATH, "swarm/one");

        assertThat(result).isEqualTo(new SwarmReadinessResult(
            true, "swarm/one", Map.of("desired", 3, "healthy", 3), "READY", 1));
        assertThat(owner.path).isEqualTo(PATH);
        assertThat(owner.calls).isEqualTo(1);
    }

    @Test
    void returnsNotReadyImmediatelyWithoutSleepingOrRetrying() {
        FakeOwner owner = new FakeOwner(status("CREATING", 2, 1));
        SwarmReadinessObserver observer = new SwarmReadinessObserver(owner, new ObjectMapper());

        assertThat(observer.observe(PATH, "swarm/one")).isEqualTo(new SwarmReadinessResult(
            false, "swarm/one", Map.of("desired", 2, "healthy", 1), "CREATING", 1));
        assertThat(owner.calls).isEqualTo(1);
    }

    @Test
    void desiredMustBePositiveAndStatusMustBeExactlyReady() {
        assertThat(new SwarmReadinessObserver(new FakeOwner(status("READY", 0, 0)), new ObjectMapper())
            .observe(PATH, "swarm").ready()).isFalse();
        assertThat(new SwarmReadinessObserver(new FakeOwner(status("CREATING", 1, 1)), new ObjectMapper())
            .observe(PATH, "swarm").ready()).isFalse();
        assertThat(new SwarmReadinessObserver(new FakeOwner(status("READY", 2, 1)), new ObjectMapper())
            .observe(PATH, "swarm").ready()).isFalse();
    }

    @Test
    void failsOnMalformedOwnerStateAndPropagatesOwnerFailureWithoutRetry() {
        SwarmReadinessObserver malformed = new SwarmReadinessObserver(
            new FakeOwner(Map.of("envelope", Map.of())), new ObjectMapper());

        assertThatThrownBy(() -> malformed.observe(PATH, "swarm"))
            .isInstanceOfSatisfying(ToolExecutionException.class,
                exception -> assertThat(exception.code()).isEqualTo("SWARM_STATUS_INVALID"));

        RuntimeException ownerFailure = new RuntimeException("owner unavailable");
        OwnerApiPort failingOwner = new OwnerApiPort() {
            @Override
            public Object get(String path) {
                throw ownerFailure;
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

    private static Map<String, Object> status(String swarmStatus, int desired, int healthy) {
        return Map.of("envelope", Map.of("data", Map.of("context", Map.of(
            "swarmStatus", swarmStatus,
            "totals", Map.of("desired", desired, "healthy", healthy)))));
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
        public Object post(String path, Object body) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object delete(String path) {
            throw new UnsupportedOperationException();
        }
    }
}
