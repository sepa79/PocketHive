package io.pockethive.worker.plugin.host;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.boot.env.PropertySourceLoader;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

final class PluginConfigPropertySourceRegistrar {

    private static final Logger log = LoggerFactory.getLogger(PluginConfigPropertySourceRegistrar.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final List<String> OVERRIDE_EXTENSIONS = List.of(".yaml", ".yml");

    private final ConfigurableEnvironment environment;
    private final PluginHostProperties properties;
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    PluginConfigPropertySourceRegistrar(ConfigurableEnvironment environment, PluginHostProperties properties) {
        this.environment = Objects.requireNonNull(environment, "environment");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    void apply(PluginClasspathLoader.PluginHandle handle) {
        PluginDescriptor descriptor = handle.descriptor();
        registerPluginApplicationProperties(handle.classLoader(), descriptor);
        registerControlPlaneProperties(descriptor);
        Map<String, Object> defaults = loadPluginDefaults(descriptor, handle.classLoader());
        registerDefaults(descriptor.role(), defaults);
        Map<String, Object> overrides = loadHostOverrides(descriptor);
        registerOverrides(descriptor.role(), overrides);
    }

    private Map<String, Object> loadPluginDefaults(PluginDescriptor descriptor, ClassLoader classLoader) {
        String defaultConfig = descriptor.defaultConfig();
        if (defaultConfig == null || defaultConfig.isBlank()) {
            log.debug("Plugin {} does not declare defaultConfig; skipping defaults", descriptor.role());
            return Map.of();
        }
        try (InputStream in = classLoader.getResourceAsStream(defaultConfig)) {
            if (in == null) {
                throw new IllegalStateException(
                    "Plugin default config '%s' not found on classpath for role '%s'"
                        .formatted(defaultConfig, descriptor.role())
                );
            }
            Map<String, Object> payload = yamlMapper.readValue(in, MAP_TYPE);
            return flatten(descriptor.configPrefix(), payload);
        } catch (IOException ex) {
            throw new IllegalStateException(
                "Failed to read plugin defaults '%s' for role '%s'".formatted(defaultConfig, descriptor.role()), ex);
        }
    }

    private Map<String, Object> loadHostOverrides(PluginDescriptor descriptor) {
        Path overridesDir = properties.getOverridesDir();
        if (overridesDir == null || !Files.isDirectory(overridesDir)) {
            return Map.of();
        }
        Path candidate = resolveOverrideFile(overridesDir, descriptor.role());
        if (candidate == null) {
            log.debug("No host override file found for role '{}' under {}", descriptor.role(), overridesDir);
            return Map.of();
        }
        try (InputStream in = Files.newInputStream(candidate)) {
            Map<String, Object> payload = yamlMapper.readValue(in, MAP_TYPE);
            log.info("Applying host overrides from {} for role '{}'", candidate, descriptor.role());
            return flatten(descriptor.configPrefix(), payload);
        } catch (IOException ex) {
            throw new IllegalStateException(
                "Failed to read host overrides from %s for role '%s'".formatted(candidate, descriptor.role()), ex);
        }
    }

    private Path resolveOverrideFile(Path overridesDir, String role) {
        for (String extension : OVERRIDE_EXTENSIONS) {
            Path candidate = overridesDir.resolve(role + extension);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private Map<String, Object> flatten(String prefix, Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> flattened = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key == null) {
                return;
            }
            String propertyName = prefix == null || prefix.isBlank()
                ? key.toString()
                : prefix + "." + key;
            writeValue(flattened, propertyName, value);
        });
        return Map.copyOf(flattened);
    }

    private void writeValue(Map<String, Object> target, String propertyName, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof Map<?, ?> map) {
            if (map.isEmpty()) {
                target.put(propertyName, Map.of());
                return;
            }
            map.forEach((childKey, childValue) -> {
                if (childKey == null) {
                    return;
                }
                writeValue(target, propertyName + "." + childKey, childValue);
            });
            return;
        }
        if (value instanceof List<?> list) {
            if (list.isEmpty()) {
                target.put(propertyName, List.of());
                return;
            }
            for (int i = 0; i < list.size(); i++) {
                writeValue(target, "%s[%d]".formatted(propertyName, i), list.get(i));
            }
            return;
        }
        target.put(propertyName, value);
    }

    private void registerDefaults(String role, Map<String, Object> properties) {
        if (properties.isEmpty()) {
            return;
        }
        MapPropertySource propertySource = new MapPropertySource(defaultSourceName(role), properties);
        MutablePropertySources sources = environment.getPropertySources();
        if (sources.contains(defaultSourceName(role))) {
            sources.replace(defaultSourceName(role), propertySource);
        } else {
            sources.addLast(propertySource);
        }
        log.info("Registered {} plugin default properties for role '{}'", properties.size(), role);
    }

    private void registerOverrides(String role, Map<String, Object> properties) {
        if (properties.isEmpty()) {
            return;
        }
        MapPropertySource propertySource = new MapPropertySource(overrideSourceName(role), properties);
        MutablePropertySources sources = environment.getPropertySources();
        if (sources.contains(overrideSourceName(role))) {
            sources.replace(overrideSourceName(role), propertySource);
            return;
        }
        if (sources.contains(defaultSourceName(role))) {
            sources.addBefore(defaultSourceName(role), propertySource);
        } else {
            sources.addFirst(propertySource);
        }
        log.info("Registered {} host override properties for role '{}'", properties.size(), role);
    }

    private String defaultSourceName(String role) {
        return "pockethive-plugin-defaults-" + role;
    }

    private String overrideSourceName(String role) {
        return "pockethive-plugin-overrides-" + role;
    }

    private void registerPluginApplicationProperties(ClassLoader classLoader, PluginDescriptor descriptor) {
        MutablePropertySources sources = environment.getPropertySources();
        loadYamlPropertySource(classLoader, "application.yml", descriptor, sources);
        loadYamlPropertySource(classLoader, "application.yaml", descriptor, sources);
        loadPropertiesPropertySource(classLoader, "application.properties", descriptor, sources);
    }

    private void loadYamlPropertySource(ClassLoader classLoader, String location, PluginDescriptor descriptor,
                                        MutablePropertySources sources) {
        Resource resource = new ClassPathResource(location, classLoader);
        if (!resource.exists()) {
            return;
        }
        try {
            PropertySourceLoader loader = new YamlPropertySourceLoader();
            for (PropertySource<?> propertySource : loader.load(resource.getDescription(), resource)) {
                sources.addLast(propertySource);
                log.info("Registered plugin property source '{}' for role '{}'", resource.getDescription(), descriptor.role());
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to load plugin property file %s for role '%s'"
                .formatted(resource.getDescription(), descriptor.role()), ex);
        }
    }

    private void loadPropertiesPropertySource(ClassLoader classLoader, String location, PluginDescriptor descriptor,
                                              MutablePropertySources sources) {
        Resource resource = new ClassPathResource(location, classLoader);
        if (!resource.exists()) {
            return;
        }
        try {
            PropertySourceLoader loader = new PropertiesPropertySourceLoader();
            for (PropertySource<?> propertySource : loader.load(resource.getDescription(), resource)) {
                sources.addLast(propertySource);
                log.info("Registered plugin property source '{}' for role '{}'", resource.getDescription(), descriptor.role());
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to load plugin property file %s for role '%s'"
                .formatted(resource.getDescription(), descriptor.role()), ex);
        }
    }

    private void registerControlPlaneProperties(PluginDescriptor descriptor) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("pockethive.control-plane.worker.role", descriptor.role());
        properties.put("pockethive.control-plane.manager.enabled", false);
        MapPropertySource propertySource = new MapPropertySource(controlPlaneSourceName(descriptor.role()), properties);
        MutablePropertySources sources = environment.getPropertySources();
        if (sources.contains(propertySource.getName())) {
            sources.replace(propertySource.getName(), propertySource);
        } else {
            sources.addLast(propertySource);
        }
        log.info("Registered control plane properties for plugin role '{}'", descriptor.role());
    }

    private String controlPlaneSourceName(String role) {
        return "pockethive-plugin-control-plane-" + role;
    }
}
