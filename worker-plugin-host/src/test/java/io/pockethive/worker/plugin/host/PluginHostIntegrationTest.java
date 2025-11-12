package io.pockethive.worker.plugin.host;

import static org.assertj.core.api.Assertions.assertThat;

import io.pockethive.worker.plugin.api.PocketHiveWorkerExtension;
import io.pockethive.worker.plugin.host.fixture.TestWorkerPlugin;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class PluginHostIntegrationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(WorkerPluginHostApplication.class)
        .withPropertyValues("pockethive.plugin-host.fail-on-missing-plugin=true");

    @Test
    void loadsPluginFromDirectory() throws Exception {
        Path pluginDir = Files.createTempDirectory("plugin-host-int");
        TestPluginJarFactory.build(pluginDir);
        TestWorkerPlugin.reset();

        contextRunner.withPropertyValues("pockethive.plugin-host.plugins-dir=" + pluginDir)
            .run(context -> {
                assertThat(context).hasSingleBean(PocketHiveWorkerExtension.class);
                assertThat(TestWorkerPlugin.started()).isTrue();
            });
    }
}
