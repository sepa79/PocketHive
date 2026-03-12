package io.pockethive.scenarios;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.pockethive.swarm.model.NetworkProfile;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class NetworkProfileService {

    private static final Logger log = LoggerFactory.getLogger(NetworkProfileService.class);
    private static final TypeReference<List<NetworkProfile>> LIST_TYPE = new TypeReference<>() {};

    private final Path configPath;
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
    private final Map<String, NetworkProfile> profiles = new ConcurrentHashMap<>();

    public NetworkProfileService(
        @Value("${pockethive.network.profiles-path:network/network-profiles.yaml}") String path) {
        Objects.requireNonNull(path, "path");
        this.configPath = Paths.get(path);
    }

    @PostConstruct
    void init() throws IOException {
        reload();
    }

    public synchronized void reload() throws IOException {
        if (!Files.exists(configPath)) {
            log.info("No network profiles file found at {}; keeping registry empty", configPath);
            profiles.clear();
            return;
        }
        requireRegularProfilesFile();

        Map<String, NetworkProfile> byId = new ConcurrentHashMap<>();

        List<NetworkProfile> loaded = yamlMapper.readValue(configPath.toFile(), LIST_TYPE);
        for (NetworkProfile profile : loaded) {
            if (profile == null || profile.id() == null || profile.id().isBlank()) {
                continue;
            }
            byId.put(profile.id(), profile);
        }

        profiles.clear();
        profiles.putAll(byId);

        log.info("Loaded {} network profile(s) from {}", profiles.size(), configPath);
    }

    public List<NetworkProfile> list() {
        return List.copyOf(profiles.values());
    }

    public NetworkProfile find(String id) {
        if (id == null) {
            return null;
        }
        return profiles.get(id);
    }

    public Map<String, NetworkProfile> asMap() {
        return Collections.unmodifiableMap(profiles);
    }

    public synchronized String readRaw() throws IOException {
        if (!Files.exists(configPath)) {
            return "";
        }
        requireRegularProfilesFile();
        return Files.readString(configPath);
    }

    public synchronized void updateFromRaw(String yaml) throws IOException {
        Objects.requireNonNull(yaml, "yaml");
        if (Files.exists(configPath)) {
            requireRegularProfilesFile();
        }
        try (StringReader reader = new StringReader(yaml)) {
            List<NetworkProfile> parsed = yamlMapper.readValue(reader, LIST_TYPE);
            if (parsed == null) {
                throw new IOException("YAML did not contain any network profiles");
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to parse network profiles YAML: " + e.getMessage(), e);
        }

        if (configPath.getParent() != null) {
            Files.createDirectories(configPath.getParent());
        }
        try (StringWriter writer = new StringWriter()) {
            writer.write(yaml);
            Files.writeString(configPath, writer.toString());
        }

        reload();
    }

    private void requireRegularProfilesFile() throws IOException {
        if (Files.isDirectory(configPath)) {
            throw new IOException("pockethive.network.profiles-path must point to a single YAML file, not a directory: " + configPath);
        }
    }
}
