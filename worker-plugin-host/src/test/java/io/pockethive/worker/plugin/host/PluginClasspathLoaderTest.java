package io.pockethive.worker.plugin.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PluginClasspathLoaderTest {

    @Test
    void failsWhenNoPluginPresent() throws Exception {
        Path dir = Files.createTempDirectory("plugin-empty");
        PluginHostProperties props = new PluginHostProperties();
        props.setPluginDir(dir);
        PluginClasspathLoader loader = new PluginClasspathLoader(props);

        assertThatThrownBy(loader::loadPlugin)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("No plugin jar");
    }

    @Test
    void failsWhenMultiplePluginsPresent() throws Exception {
        Path dir = Files.createTempDirectory("plugin-multi");
        Files.createFile(dir.resolve("a.jar"));
        Files.createFile(dir.resolve("b.jar"));
        PluginHostProperties props = new PluginHostProperties();
        props.setPluginDir(dir);
        PluginClasspathLoader loader = new PluginClasspathLoader(props);

        assertThatThrownBy(loader::loadPlugin)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Exactly one plugin jar");
    }

    @Test
    void loadsPluginDescriptor() throws Exception {
        Path dir = Files.createTempDirectory("plugin-single");
        TestPluginJarFactory.buildJar(dir, TestPluginConfiguration.class);
        PluginHostProperties props = new PluginHostProperties();
        props.setPluginDir(dir);
        PluginClasspathLoader loader = new PluginClasspathLoader(props);

        PluginClasspathLoader.PluginHandle handle = loader.loadPlugin();

        PluginDescriptor descriptor = handle.descriptor();
        assertThat(descriptor.pluginClass()).isEqualTo(TestPluginConfiguration.class.getName());
        assertThat(descriptor.role()).isEqualTo("test-role");
        assertThat(descriptor.configPrefix()).isEqualTo("pockethive.workers.test-role");
        assertThat(descriptor.defaultConfig()).isEqualTo("config/defaults.yaml");
        assertThat(handle.configurationClass().getName()).isEqualTo(TestPluginConfiguration.class.getName());
        assertThat(handle.classLoader()).isNotNull();
    }

    @Test
    void loadsBootRepackagedPluginJar() throws Exception {
        Path dir = Files.createTempDirectory("plugin-boot");
        TestPluginJarFactory.buildBootJar(dir, TestPluginConfiguration.class);
        PluginHostProperties props = new PluginHostProperties();
        props.setPluginDir(dir);
        PluginClasspathLoader loader = new PluginClasspathLoader(props);

        PluginClasspathLoader.PluginHandle handle = loader.loadPlugin();

        assertThat(handle.descriptor().pluginClass()).isEqualTo(TestPluginConfiguration.class.getName());
        assertThat(handle.configurationClass().getName()).isEqualTo(TestPluginConfiguration.class.getName());
        try (InputStream in = handle.classLoader().getResourceAsStream("config/defaults.yaml")) {
            assertThat(in).isNotNull();
        }
    }
}
