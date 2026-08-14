package io.pockethive.scenarios;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.pockethive.swarm.model.SutEnvironment;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Loads SUT environments from a YAML file and exposes them for HTTP APIs.
 * <p>
 * The location is configurable via {@code pockethive.sut.environments-path}
 * and defaults to {@code sut/sut-environments.yaml} relative to the working
 * directory.
 */
@Service
public class SutEnvironmentService {

    private static final Logger log = LoggerFactory.getLogger(SutEnvironmentService.class);
    private static final TypeReference<List<SutEnvironment>> LIST_TYPE =
        new TypeReference<>() {};

    private final Path configPath;
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
    private final Map<String, SutEnvironment> environments = new ConcurrentHashMap<>();

    public SutEnvironmentService(
        @Value("${pockethive.sut.environments-path:sut/sut-environments.yaml}") String path) {
        Objects.requireNonNull(path, "path");
        this.configPath = Paths.get(path);
    }

    @PostConstruct
    void init() throws IOException {
        reload();
    }

    public synchronized void reload() throws IOException {
        if (!Files.exists(configPath)) {
            log.info("No SUT environments file found at {}; keeping registry empty", configPath);
            environments.clear();
            return;
        }

        Map<String, SutEnvironment> byId = new LinkedHashMap<>();

        if (Files.isDirectory(configPath)) {
            List<Path> sources;
            try (var stream = Files.list(configPath)) {
                sources = stream
                    .filter(Files::isRegularFile)
                    .filter(SutEnvironmentService::isYaml)
                    .sorted()
                    .toList();
            }
            for (Path source : sources) {
                List<SutEnvironment> loaded = yamlMapper.readValue(source.toFile(), LIST_TYPE);
                addEnvironments(byId, loaded, source);
            }
        } else {
            List<SutEnvironment> loaded = yamlMapper.readValue(configPath.toFile(), LIST_TYPE);
            addEnvironments(byId, loaded, configPath);
        }

        environments.clear();
        environments.putAll(byId);

        log.info("Loaded {} SUT environment(s) from {}", environments.size(), configPath);
    }

    private static boolean isYaml(Path source) {
        String name = source.getFileName().toString();
        return name.endsWith(".yaml") || name.endsWith(".yml");
    }

    private static void addEnvironments(Map<String, SutEnvironment> target,
                                        List<SutEnvironment> loaded,
                                        Path source) throws IOException {
        if (loaded == null) {
            throw new IOException("SUT registry source did not contain an environment list: " + source);
        }
        for (SutEnvironment environment : loaded) {
            if (environment == null) {
                throw new IOException("SUT registry source contains a null environment: " + source);
            }
            if (target.putIfAbsent(environment.id(), environment) != null) {
                throw new IOException(
                    "Duplicate SUT environment id '" + environment.id() + "' in " + source);
            }
        }
    }

    public List<SutEnvironment> list() {
        return List.copyOf(environments.values());
    }

    public SutEnvironment find(String id) {
        if (id == null) {
            return null;
        }
        return environments.get(id);
    }

    public Map<String, SutEnvironment> asMap() {
        return Collections.unmodifiableMap(environments);
    }

    /**
     * Returns the raw YAML backing the SUT registry, if present.
     */
    public synchronized String readRaw() throws IOException {
        if (!Files.exists(configPath)) {
            return "";
        }
        return Files.readString(configPath);
    }

    /**
     * Replaces the underlying YAML with the given contents after validating that
     * it can be parsed as a list of {@link SutEnvironment} objects. If parsing
     * succeeds, the registry is reloaded from disk.
     */
    public synchronized void updateFromRaw(String yaml) throws IOException {
        Objects.requireNonNull(yaml, "yaml");
        // Validate first using the in-memory mapper.
        try (StringReader reader = new StringReader(yaml)) {
            List<SutEnvironment> parsed = yamlMapper.readValue(reader, LIST_TYPE);
            addEnvironments(new LinkedHashMap<>(), parsed, configPath);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to parse SUT environments YAML: " + e.getMessage(), e);
        }

        if (configPath.getParent() != null) {
            Files.createDirectories(configPath.getParent());
        }
        try (StringWriter writer = new StringWriter()) {
            // Normalise formatting while writing.
            writer.write(yaml);
            Files.writeString(configPath, writer.toString());
        }

        reload();
    }
}
