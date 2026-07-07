package io.pockethive.orchestrator.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationPropertiesBindException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class RuntimeLogSnapshotPropertiesBindingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(TestConfiguration.class);

    @Test
    void bindsRuntimeLogSnapshotSettings() {
        contextRunner
            .withPropertyValues(
                "pockethive.runtime-log-snapshots.mode=ERROR_ALERTS",
                "pockethive.runtime-log-snapshots.tail-lines=150",
                "pockethive.runtime-log-snapshots.since-before-alert=PT90S",
                "pockethive.runtime-log-snapshots.max-chars=4096")
            .run(context -> {
                assertThat(context).hasNotFailed();
                RuntimeLogSnapshotProperties properties = context.getBean(RuntimeLogSnapshotProperties.class);
                assertThat(properties.getMode()).isEqualTo(RuntimeLogSnapshotMode.ERROR_ALERTS);
                assertThat(properties.getTailLines()).isEqualTo(150);
                assertThat(properties.getSinceBeforeAlert()).isEqualTo(Duration.ofSeconds(90));
                assertThat(properties.getMaxChars()).isEqualTo(4096);
            });
    }

    @Test
    void rejectsOutOfRangeTailLines() {
        contextRunner
            .withPropertyValues(
                "pockethive.runtime-log-snapshots.mode=ERROR_ALERTS",
                "pockethive.runtime-log-snapshots.tail-lines=2001",
                "pockethive.runtime-log-snapshots.since-before-alert=PT2M",
                "pockethive.runtime-log-snapshots.max-chars=4096")
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .isInstanceOf(ConfigurationPropertiesBindException.class);
            });
    }

    @EnableConfigurationProperties(RuntimeLogSnapshotProperties.class)
    static class TestConfiguration {
        // registers RuntimeLogSnapshotProperties for ApplicationContextRunner
    }
}
