package io.pockethive.worker.plugin.host;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class WorkerPluginHostApplicationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(PluginHostAutoConfiguration.class);

    @Test
    void hostLoadsPluginConfiguration() throws Exception {
        Path dir = Files.createTempDirectory("plugin-host-test");
        TestPluginJarFactory.buildJar(dir, TestPluginConfiguration.class);

        contextRunner
            .withPropertyValues(
                "pockethive.plugin-host.plugin-dir=" + dir.toAbsolutePath(),
                "POCKETHIVE_OUTPUT_RABBIT_EXCHANGE=ph.test.hive",
                "POCKETHIVE_OUTPUT_RABBIT_ROUTING_KEY=ph.test.queue"
            )
            .run(context -> {
                assertThat(context).hasBean("pluginSampleBean");
                assertThat(context.getBean("pluginSampleBean")).isEqualTo("plugin-loaded");
                assertThat(context).hasSingleBean(PluginDescriptor.class);
                assertThat(context.getEnvironment()
                    .getProperty("pockethive.workers.test-role.config.ratePerSec")).isEqualTo("50");
                assertThat(context.getEnvironment()
                    .getProperty("pockethive.control-plane.worker.role")).isEqualTo("test-role");
                assertThat(context.getEnvironment()
                    .getProperty("pockethive.control-plane.manager.enabled")).isEqualTo("false");
                assertThat(context.getEnvironment()
                    .getProperty("plugin.test.value")).isEqualTo("plugin-yaml");
                assertThat(context.getEnvironment()
                    .getProperty("pockethive.outputs.rabbit.exchange")).isEqualTo("ph.test.hive");
            });
    }

    @Test
    void hostOverridesPluginDefaultsFromFile() throws Exception {
        Path pluginDir = Files.createTempDirectory("plugin-host-test");
        Path overridesDir = Files.createTempDirectory("plugin-host-overrides");
        TestPluginJarFactory.buildJar(pluginDir, TestPluginConfiguration.class);
        Files.writeString(overridesDir.resolve("test-role.yaml"), """
            config:
              ratePerSec: 200
            """);

        contextRunner
            .withPropertyValues(
                "pockethive.plugin-host.plugin-dir=" + pluginDir.toAbsolutePath(),
                "pockethive.plugin-host.overrides-dir=" + overridesDir.toAbsolutePath(),
                "POCKETHIVE_OUTPUT_RABBIT_EXCHANGE=ph.test.hive",
                "POCKETHIVE_OUTPUT_RABBIT_ROUTING_KEY=ph.test.queue"
            )
            .run(context -> assertThat(context.getEnvironment()
                .getProperty("pockethive.workers.test-role.config.ratePerSec")).isEqualTo("200"));
    }
}
