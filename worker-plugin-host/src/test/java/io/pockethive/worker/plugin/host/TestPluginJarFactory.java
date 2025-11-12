package io.pockethive.worker.plugin.host;

import io.pockethive.worker.plugin.host.fixture.TestWorkerPlugin;
import io.pockethive.worker.plugin.host.fixture.TestWorkerPluginConfig;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

final class TestPluginJarFactory {

    private TestPluginJarFactory() {
    }

    static Path build(Path pluginsDir) throws IOException {
        Files.createDirectories(pluginsDir);
        Path jar = pluginsDir.resolve("test-plugin.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            writeClass(TestWorkerPlugin.class, out);
            writeClass(TestWorkerPluginConfig.class, out);
            writeResource(out, "plugin.properties", pluginProperties());
            writeResource(out, "META-INF/pockethive-plugin.yml", pocketHiveManifest());
            writeResource(out, "config/defaults.yaml", "config: {}\n");
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

    private static void writeResource(JarOutputStream out, String path, String contents) throws IOException {
        JarEntry entry = new JarEntry(path);
        out.putNextEntry(entry);
        out.write(contents.getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
    }

    private static String pluginProperties() {
        return "plugin.id=test-plugin\n" +
            "plugin.version=1.0.0\n" +
            "plugin.provider=tests\n" +
            "plugin.class=" + TestWorkerPlugin.class.getName() + "\n";
    }

    private static String pocketHiveManifest() {
        return "role: test-role\n" +
            "version: 1.0.0\n" +
            "configPrefix: pockethive.workers.test\n" +
            "defaultConfig: config/defaults.yaml\n" +
            "capabilities: []\n";
    }
}
