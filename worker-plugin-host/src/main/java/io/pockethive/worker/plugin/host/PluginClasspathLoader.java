package io.pockethive.worker.plugin.host;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class PluginClasspathLoader {

    private static final String DESCRIPTOR_PATH = "META-INF/pockethive-plugin.yml";

    private final PluginHostProperties properties;
    private final ObjectMapper mapper = new ObjectMapper(new YAMLFactory());

    public PluginClasspathLoader(PluginHostProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    public PluginHandle loadPlugin() {
        Path pluginDir = properties.getPluginDir();
        if (pluginDir == null) {
            throw new IllegalStateException("pockethive.plugin-host.plugin-dir must be configured");
        }
        if (!Files.isDirectory(pluginDir)) {
            throw new IllegalStateException("Plugin directory %s does not exist".formatted(pluginDir));
        }
        List<Path> jars;
        try {
            jars = Files.list(pluginDir)
                .filter(p -> p.getFileName().toString().endsWith(".jar"))
                .collect(Collectors.toList());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to scan plugin directory %s".formatted(pluginDir), e);
        }
        if (jars.isEmpty()) {
            throw new IllegalStateException("No plugin jar found in %s".formatted(pluginDir));
        }
        if (jars.size() > 1) {
            throw new IllegalStateException("Exactly one plugin jar must be present in %s".formatted(pluginDir));
        }
        return loadSingleJar(jars.get(0));
    }

    private PluginHandle loadSingleJar(Path jarPath) {
        try {
            URL url = jarPath.toUri().toURL();
            URLClassLoader classLoader = new URLClassLoader(new URL[]{url}, getClass().getClassLoader());
            PluginDescriptor descriptor = readDescriptor(classLoader, jarPath);
            String pluginClass = descriptor.pluginClass();
            if (pluginClass == null || pluginClass.isBlank()) {
                throw new IllegalStateException("Plugin descriptor in %s is missing pluginClass".formatted(jarPath));
            }
            Class<?> configurationClass = Class.forName(pluginClass, true, classLoader);
            return new PluginHandle(descriptor, configurationClass, classLoader);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load plugin jar %s".formatted(jarPath), e);
        }
    }

    private PluginDescriptor readDescriptor(ClassLoader classLoader, Path jarPath) throws IOException {
        try (InputStream in = classLoader.getResourceAsStream(DESCRIPTOR_PATH)) {
            if (in == null) {
                throw new IllegalStateException("Plugin jar %s is missing %s".formatted(jarPath, DESCRIPTOR_PATH));
            }
            return mapper.readValue(in, PluginDescriptor.class);
        }
    }

    public record PluginHandle(PluginDescriptor descriptor, Class<?> configurationClass, ClassLoader classLoader) {}
}
