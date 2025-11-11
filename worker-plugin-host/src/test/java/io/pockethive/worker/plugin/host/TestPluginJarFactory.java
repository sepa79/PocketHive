package io.pockethive.worker.plugin.host;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

final class TestPluginJarFactory {

    private TestPluginJarFactory() { }

    static Path buildJar(Path pluginDir, Class<?> configurationClass) throws IOException {
        Files.createDirectories(pluginDir);
        Path jar = pluginDir.resolve("test-plugin.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            writeClass(configurationClass, out);
            writeDescriptor(configurationClass, out);
        }
        return jar;
    }

    private static void writeClass(Class<?> type, JarOutputStream out) throws IOException {
        String resource = type.getName().replace('.', '/') + ".class";
        JarEntry entry = new JarEntry(resource);
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
            "role: test\n" +
            "version: 1.0.0\n" +
            "capabilities: []\n";
        out.write(yaml.getBytes());
        out.closeEntry();
    }
}
