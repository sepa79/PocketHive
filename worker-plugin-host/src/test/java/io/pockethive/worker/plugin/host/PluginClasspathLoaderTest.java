package io.pockethive.worker.plugin.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

        assertThat(handle.descriptor().pluginClass()).isEqualTo(TestPluginConfiguration.class.getName());
        assertThat(handle.configurationClass().getName()).isEqualTo(TestPluginConfiguration.class.getName());
        assertThat(handle.classLoader()).isNotNull();
    }
}
