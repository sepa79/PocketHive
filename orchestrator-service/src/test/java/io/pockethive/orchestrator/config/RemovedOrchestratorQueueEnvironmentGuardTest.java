package io.pockethive.orchestrator.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.SystemEnvironmentPropertySource;

class RemovedOrchestratorQueueEnvironmentGuardTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(RemovedOrchestratorQueueEnvironmentGuard.class);

    @ParameterizedTest
    @ValueSource(strings = {
        "POCKETHIVE_CONTROL_PLANE_ORCHESTRATOR_CONTROL_QUEUE_PREFIX",
        "POCKETHIVE_CONTROL_PLANE_ORCHESTRATOR_STATUS_QUEUE_PREFIX"})
    void removedEnvironmentOverridesFailStartupEvenWhenEmpty(String key) {
        withEnvironment(Map.of(key, "")).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .hasStackTraceContaining(key + " is removed");
        });
    }

    @Test
    void canonicalSharedPrefixIsAccepted() {
        withEnvironment(Map.of("POCKETHIVE_CONTROL_PLANE_CONTROL_QUEUE_PREFIX", "tenant.control"))
            .run(context -> assertThat(context).hasNotFailed());
    }

    private ApplicationContextRunner withEnvironment(Map<String, Object> values) {
        return runner.withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
            new SystemEnvironmentPropertySource("systemEnvironment", values)));
    }
}
