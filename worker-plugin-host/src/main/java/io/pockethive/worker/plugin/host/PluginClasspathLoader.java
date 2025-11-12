package io.pockethive.worker.plugin.host;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

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
            PluginDescriptor descriptor = readDescriptor(jarPath);
            ClassLoader classLoader = createClassLoader(jarPath);
            validateDescriptor(descriptor, jarPath);
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

    private PluginDescriptor readDescriptor(Path jarPath) throws IOException {
        try (ZipFile zip = new ZipFile(jarPath.toFile())) {
            ZipEntry entry = zip.getEntry(DESCRIPTOR_PATH);
            if (entry == null) {
                throw new IllegalStateException("Plugin jar %s is missing %s".formatted(jarPath, DESCRIPTOR_PATH));
            }
            try (InputStream in = zip.getInputStream(entry)) {
                return mapper.readValue(in, PluginDescriptor.class);
            }
        }
    }

    private ClassLoader createClassLoader(Path jarPath) throws IOException {
        if (isBootJar(jarPath)) {
            return createBootClassLoader(jarPath);
        }
        URL url = jarPath.toUri().toURL();
        return new URLClassLoader(new URL[]{url}, getClass().getClassLoader());
    }

    private boolean isBootJar(Path jarPath) throws IOException {
        try (ZipFile zip = new ZipFile(jarPath.toFile())) {
            return zip.stream().anyMatch(entry -> entry.getName().startsWith("BOOT-INF/classes/"));
        }
    }

    private ClassLoader createBootClassLoader(Path jarPath) throws IOException {
        Path extractionRoot = Files.createTempDirectory("pockethive-plugin-" + sanitize(jarPath.getFileName().toString()));
        Path classesDir = extractionRoot.resolve("classes");
        Path libsDir = extractionRoot.resolve("lib");
        Files.createDirectories(classesDir);
        Files.createDirectories(libsDir);
        try (ZipFile zip = new ZipFile(jarPath.toFile())) {
            if (!zip.stream().anyMatch(entry -> entry.getName().startsWith("BOOT-INF/classes/"))) {
                throw new IllegalStateException("Boot plugin jar %s is missing BOOT-INF/classes".formatted(jarPath));
            }
            copyDirectory(zip, "BOOT-INF/classes/", classesDir);
            copyDirectoryIfPresent(zip, "config/", classesDir.resolve("config"));
            copyEntryIfPresent(zip, DESCRIPTOR_PATH, classesDir.resolve(DESCRIPTOR_PATH));
            List<Path> extractedLibs = extractNestedLibs(zip, libsDir);
            List<URL> urls = new ArrayList<>();
            urls.add(classesDir.toUri().toURL());
            for (Path lib : extractedLibs) {
                urls.add(lib.toUri().toURL());
            }
            return new URLClassLoader(urls.toArray(new URL[0]), getClass().getClassLoader());
        } catch (Exception ex) {
            deleteDirectoryQuietly(extractionRoot);
            if (ex instanceof IOException io) {
                throw io;
            }
            if (ex instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(ex);
        }
    }

    private static void validateDescriptor(PluginDescriptor descriptor, Path jarPath) {
        if (descriptor.role() == null || descriptor.role().isBlank()) {
            throw new IllegalStateException("Plugin descriptor in %s is missing role".formatted(jarPath));
        }
        if (descriptor.version() == null || descriptor.version().isBlank()) {
            throw new IllegalStateException("Plugin descriptor in %s is missing version".formatted(jarPath));
        }
        if (descriptor.configPrefix() == null || descriptor.configPrefix().isBlank()) {
            throw new IllegalStateException("Plugin descriptor in %s is missing configPrefix".formatted(jarPath));
        }
    }

    public record PluginHandle(PluginDescriptor descriptor, Class<?> configurationClass, ClassLoader classLoader) {}

    private static void copyDirectory(ZipFile zip, String prefix, Path targetRoot) throws IOException {
        Files.createDirectories(targetRoot);
        Enumeration<? extends ZipEntry> entries = zip.entries();
        boolean copied = false;
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (!entry.getName().startsWith(prefix)) {
                continue;
            }
            String relativeName = entry.getName().substring(prefix.length());
            if (relativeName.isEmpty()) {
                continue;
            }
            Path target = targetRoot.resolve(relativeName);
            if (entry.isDirectory()) {
                Files.createDirectories(target);
            } else {
                Files.createDirectories(target.getParent());
                try (InputStream in = zip.getInputStream(entry)) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            copied = true;
        }
        if (!copied) {
            throw new IllegalStateException("Plugin jar is missing entries under %s".formatted(prefix));
        }
    }

    private static void copyDirectoryIfPresent(ZipFile zip, String prefix, Path targetRoot) throws IOException {
        Enumeration<? extends ZipEntry> entries = zip.entries();
        boolean hasEntry = false;
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (entry.getName().startsWith(prefix)) {
                hasEntry = true;
                break;
            }
        }
        if (!hasEntry) {
            return;
        }
        copyDirectory(zip, prefix, targetRoot);
    }

    private static void copyEntryIfPresent(ZipFile zip, String entryName, Path target) throws IOException {
        ZipEntry entry = zip.getEntry(entryName);
        if (entry == null) {
            return;
        }
        Files.createDirectories(target.getParent());
        try (InputStream in = zip.getInputStream(entry)) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static List<Path> extractNestedLibs(ZipFile zip, Path libsDir) throws IOException {
        Files.createDirectories(libsDir);
        List<Path> libs = new ArrayList<>();
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String name = entry.getName();
            if (entry.isDirectory() || !name.startsWith("BOOT-INF/lib/") || !name.endsWith(".jar")) {
                continue;
            }
            String relativeName = name.substring("BOOT-INF/lib/".length());
            Path target = libsDir.resolve(relativeName);
            Files.createDirectories(target.getParent());
            try (InputStream in = zip.getInputStream(entry)) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            libs.add(target);
        }
        return libs;
    }

    private static void deleteDirectoryQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try {
            if (!Files.exists(dir)) {
                return;
            }
            Files.walk(dir)
                .sorted(Comparator.reverseOrder())
                .forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                        // best effort cleanup
                    }
                });
        } catch (IOException ignored) {
            // ignore
        }
    }

    private static String sanitize(String name) {
        return name.replaceAll("[^a-zA-Z0-9.-]", "_");
    }
}
