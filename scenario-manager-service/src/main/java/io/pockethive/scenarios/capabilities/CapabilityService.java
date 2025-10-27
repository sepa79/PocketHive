package io.pockethive.scenarios.capabilities;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

@Service
public class CapabilityService implements InitializingBean {

    static final String SUPPORTED_SCHEMA_VERSION = "1.0";
    private static final Pattern CAPABILITIES_VERSION_PATTERN = Pattern.compile("\\d+");
    private static final Pattern DIGEST_PATTERN = Pattern.compile("(?i)[a-z0-9_+.-]+:[a-f0-9]{32,}");

    private static final Logger LOGGER = LoggerFactory.getLogger(CapabilityService.class);

    private final ObjectMapper jsonMapper;
    private final ObjectMapper yamlMapper;
    private final Path capabilitiesDirectory;

    private volatile List<CapabilityManifest> manifests = List.of();
    private volatile Map<String, CapabilityManifest> manifestsByDigest = Map.of();
    private volatile Map<ImageKey, CapabilityManifest> manifestsByNameTag = Map.of();

    public CapabilityService(
            Jackson2ObjectMapperBuilder mapperBuilder,
            @Value("${scenario-manager.capabilities.path:capabilities}") String directory) {
        this.jsonMapper = mapperBuilder.build();
        ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
        mapperBuilder.configure(yamlMapper);
        this.yamlMapper = yamlMapper;
        this.capabilitiesDirectory = Path.of(directory);
    }

    @Override
    public void afterPropertiesSet() {
        reload();
    }

    public synchronized void reload() {
        Map<String, CapabilityManifest> digestIndex = new HashMap<>();
        Map<ImageKey, CapabilityManifest> nameTagIndex = new HashMap<>();
        List<CapabilityManifest> loaded = new ArrayList<>();

        if (!Files.exists(capabilitiesDirectory)) {
            LOGGER.info("Capabilities directory '{}' does not exist; skipping load", capabilitiesDirectory);
            updateState(loaded, digestIndex, nameTagIndex);
            return;
        }

        try (Stream<Path> stream = Files.walk(capabilitiesDirectory)) {
            stream.filter(Files::isRegularFile)
                    .filter(this::isSupportedManifestFile)
                    .sorted()
                    .forEach(path -> {
                        CapabilityManifest manifest = readManifest(path);
                        validateManifest(manifest, path);
                        indexManifest(manifest, path, digestIndex, nameTagIndex);
                        loaded.add(manifest);
                    });
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read capability manifests", e);
        }

        updateState(loaded, digestIndex, nameTagIndex);
    }

    public List<CapabilityManifest> getAllCapabilities() {
        return manifests;
    }

    public Optional<CapabilityManifest> findByDigest(String digest) {
        if (!StringUtils.hasText(digest)) {
            return Optional.empty();
        }
        return Optional.ofNullable(manifestsByDigest.get(normalizeDigest(digest)));
    }

    public Optional<CapabilityManifest> findByImageNameAndTag(String name, String tag) {
        if (!StringUtils.hasText(name) || !StringUtils.hasText(tag)) {
            return Optional.empty();
        }
        return Optional.ofNullable(manifestsByNameTag.get(new ImageKey(name, tag)));
    }

    private void updateState(List<CapabilityManifest> loaded,
            Map<String, CapabilityManifest> digestIndex,
            Map<ImageKey, CapabilityManifest> nameTagIndex) {
        this.manifests = List.copyOf(loaded);
        this.manifestsByDigest = Map.copyOf(digestIndex);
        this.manifestsByNameTag = Map.copyOf(nameTagIndex);
        LOGGER.info("Loaded {} capability manifest(s) from {}", loaded.size(), capabilitiesDirectory);
    }

    private boolean isSupportedManifestFile(Path path) {
        String filename = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return filename.endsWith(".json") || filename.endsWith(".yaml") || filename.endsWith(".yml");
    }

    private CapabilityManifest readManifest(Path path) {
        try {
            String filename = path.getFileName().toString().toLowerCase(Locale.ROOT);
            if (filename.endsWith(".yaml") || filename.endsWith(".yml")) {
                return yamlMapper.readValue(path.toFile(), CapabilityManifest.class);
            }
            return jsonMapper.readValue(path.toFile(), CapabilityManifest.class);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to deserialize capability manifest at " + path, e);
        }
    }

    private void validateManifest(CapabilityManifest manifest, Path source) {
        if (!SUPPORTED_SCHEMA_VERSION.equals(manifest.schemaVersion())) {
            throw new IllegalStateException("Unsupported schemaVersion '" + manifest.schemaVersion()
                    + "' in manifest " + source);
        }
        if (!StringUtils.hasText(manifest.capabilitiesVersion())
                || !CAPABILITIES_VERSION_PATTERN.matcher(manifest.capabilitiesVersion()).matches()) {
            throw new IllegalStateException("Invalid capabilitiesVersion '" + manifest.capabilitiesVersion()
                    + "' in manifest " + source);
        }

        CapabilityImage image = manifest.image();
        if (image == null) {
            throw new IllegalStateException("Missing image block in manifest " + source);
        }
        if (!StringUtils.hasText(image.name())) {
            throw new IllegalStateException("Image name must be provided in manifest " + source);
        }
        if (StringUtils.hasText(image.digest()) && !DIGEST_PATTERN.matcher(image.digest()).matches()) {
            throw new IllegalStateException("Invalid image digest '" + image.digest() + "' in manifest " + source);
        }
        if (!StringUtils.hasText(image.digest()) && !StringUtils.hasText(image.tag())) {
            throw new IllegalStateException(
                    "Manifest " + source + " must provide either a digest or a tag for image '" + image.name() + "'");
        }
    }

    private void indexManifest(CapabilityManifest manifest, Path source,
            Map<String, CapabilityManifest> digestIndex,
            Map<ImageKey, CapabilityManifest> nameTagIndex) {
        CapabilityImage image = manifest.image();
        if (StringUtils.hasText(image.digest())) {
            String digestKey = normalizeDigest(image.digest());
            CapabilityManifest existing = digestIndex.putIfAbsent(digestKey, manifest);
            if (existing != null) {
                throw new IllegalStateException("Duplicate manifest for digest '" + image.digest() + "' at " + source);
            }
        }
        if (StringUtils.hasText(image.tag())) {
            ImageKey key = new ImageKey(image.name(), image.tag());
            CapabilityManifest existing = nameTagIndex.putIfAbsent(key, manifest);
            if (existing != null) {
                throw new IllegalStateException(
                        "Duplicate manifest for image '" + image.name() + ":" + image.tag() + "' at " + source);
            }
        }
    }

    private String normalizeDigest(String digest) {
        return digest.toLowerCase(Locale.ROOT);
    }

    private record ImageKey(String name, String tag) {
        private ImageKey {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(tag, "tag");
        }
    }
}
