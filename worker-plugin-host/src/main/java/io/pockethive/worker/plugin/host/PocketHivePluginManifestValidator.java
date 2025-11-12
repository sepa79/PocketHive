package io.pockethive.worker.plugin.host;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class PocketHivePluginManifestValidator {

    private static final Logger log = LoggerFactory.getLogger(PocketHivePluginManifestValidator.class);
    private static final String DESCRIPTOR_PATH = "META-INF/pockethive-plugin.yml";

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    PocketHivePluginDescriptor validate(String pluginId, ClassLoader pluginClassLoader) {
        try (InputStream in = pluginClassLoader.getResourceAsStream(DESCRIPTOR_PATH)) {
            if (in == null) {
                throw new IllegalStateException("Plugin " + pluginId + " is missing " + DESCRIPTOR_PATH);
            }
            PocketHivePluginDescriptor descriptor = yamlMapper.readValue(in, PocketHivePluginDescriptor.class);
            requireText(descriptor.role(), "role");
            requireText(descriptor.version(), "version");
            requireText(descriptor.configPrefix(), "configPrefix");
            if (descriptor.capabilities() == null) {
                descriptor = new PocketHivePluginDescriptor(
                    descriptor.role(),
                    descriptor.version(),
                    descriptor.configPrefix(),
                    descriptor.defaultConfig(),
                    List.of());
            }
            log.info("Loaded PocketHive plugin manifest id={} role={} version={} configPrefix={}",
                pluginId, descriptor.role(), descriptor.version(), descriptor.configPrefix());
            return descriptor;
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read " + DESCRIPTOR_PATH + " for plugin " + pluginId, ex);
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("PocketHive plugin manifest missing " + field);
        }
        return value;
    }
}
