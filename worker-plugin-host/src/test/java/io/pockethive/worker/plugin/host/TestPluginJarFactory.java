package io.pockethive.worker.plugin.host;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

final class TestPluginJarFactory {

    private static final String DEFAULT_CONFIG_PATH = "config/defaults.yaml";
    private static final String DEFAULT_CONFIG_YAML = """
        config:
          ratePerSec: 50
          message:
            path: /api/default
            body: ''
            method: POST
            headers: {}
        """;
    private static final String APPLICATION_YAML = """
        plugin:
          test:
            value: plugin-yaml
        pockethive:
          outputs:
            rabbit:
              exchange: ${POCKETHIVE_OUTPUT_RABBIT_EXCHANGE:}
              routingKey: ${POCKETHIVE_OUTPUT_RABBIT_ROUTING_KEY:}
        """;

    private TestPluginJarFactory() { }

    static Path buildJar(Path pluginDir, Class<?> configurationClass) throws IOException {
        return buildJar(pluginDir, configurationClass, DEFAULT_CONFIG_YAML);
    }

    static Path buildJar(Path pluginDir, Class<?> configurationClass, String defaultConfigYaml) throws IOException {
        Files.createDirectories(pluginDir);
        Path jar = pluginDir.resolve("test-plugin.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            writeClass(configurationClass, out, "");
            writeDefaultConfig(defaultConfigYaml, out, "");
            writeDescriptor(configurationClass, out);
            writeApplicationYaml(out, "");
        }
        return jar;
    }

    static Path buildBootJar(Path pluginDir, Class<?> configurationClass) throws IOException {
        Files.createDirectories(pluginDir);
        Path jar = pluginDir.resolve("test-boot-plugin.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            writeClass(configurationClass, out, "BOOT-INF/classes/");
            writeDefaultConfig(DEFAULT_CONFIG_YAML, out, "BOOT-INF/classes/");
            writeDescriptor(configurationClass, out);
            writeApplicationYaml(out, "BOOT-INF/classes/");
            writeDummyLibrary(out);
        }
        return jar;
    }

    private static void writeClass(Class<?> type, JarOutputStream out, String rootPrefix) throws IOException {
        String resource = type.getName().replace('.', '/') + ".class";
        JarEntry entry = new JarEntry(rootPrefix + resource);
        out.putNextEntry(entry);
        try (InputStream in = type.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing class bytes for " + type.getName());
            }
            in.transferTo(out);
        }
        out.closeEntry();
    }

    private static void writeDescriptor(Class<?> configurationClass, JarOutputStream out) throws IOException {
        JarEntry entry = new JarEntry("META-INF/pockethive-plugin.yml");
        out.putNextEntry(entry);
        String yaml = "pluginClass: " + configurationClass.getName() + "\n" +
            "role: test-role\n" +
            "version: 1.0.0\n" +
            "capabilities: []\n" +
            "configPrefix: pockethive.workers.test-role\n" +
            "defaultConfig: " + DEFAULT_CONFIG_PATH + "\n";
        out.write(yaml.getBytes());
        out.closeEntry();
    }

    private static void writeDefaultConfig(String yaml, JarOutputStream out, String rootPrefix) throws IOException {
        JarEntry entry = new JarEntry(rootPrefix + DEFAULT_CONFIG_PATH);
        out.putNextEntry(entry);
        out.write(yaml.getBytes());
        out.closeEntry();
    }

    private static void writeApplicationYaml(JarOutputStream out, String rootPrefix) throws IOException {
        JarEntry entry = new JarEntry(rootPrefix + "application.yml");
        out.putNextEntry(entry);
        out.write(APPLICATION_YAML.getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
    }

    private static void writeDummyLibrary(JarOutputStream out) throws IOException {
        JarEntry entry = new JarEntry("BOOT-INF/lib/dummy-lib.jar");
        out.putNextEntry(entry);
        out.write(createDummyJar());
        out.closeEntry();
    }

    private static byte[] createDummyJar() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (JarOutputStream libOut = new JarOutputStream(baos)) {
            JarEntry entry = new JarEntry("dummy.txt");
            libOut.putNextEntry(entry);
            libOut.write("placeholder".getBytes(StandardCharsets.UTF_8));
            libOut.closeEntry();
        }
        return baos.toByteArray();
    }
}
