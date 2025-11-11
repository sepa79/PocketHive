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
            .withPropertyValues("pockethive.plugin-host.plugin-dir=" + dir.toAbsolutePath())
            .run(context -> {
                assertThat(context).hasBean("pluginSampleBean");
                assertThat(context.getBean("pluginSampleBean")).isEqualTo("plugin-loaded");
                assertThat(context).hasSingleBean(PluginDescriptor.class);
            });
    }
}
