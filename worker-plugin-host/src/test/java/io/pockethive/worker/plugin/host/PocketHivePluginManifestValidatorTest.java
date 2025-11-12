package io.pockethive.worker.plugin.host;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PocketHivePluginManifestValidatorTest {

    private final PocketHivePluginManifestValidator validator = new PocketHivePluginManifestValidator();

    @Test
    void parsesManifest() throws Exception {
        Path dir = Files.createTempDirectory("plugin-test");
        Path jar = TestPluginJarFactory.build(dir);
        try (var cl = new java.net.URLClassLoader(new java.net.URL[]{jar.toUri().toURL()})) {
            PocketHivePluginDescriptor descriptor = validator.validate("test-plugin", cl);
            assertThat(descriptor.role()).isEqualTo("test-role");
            assertThat(descriptor.configPrefix()).isEqualTo("pockethive.workers.test");
        }
    }
}
