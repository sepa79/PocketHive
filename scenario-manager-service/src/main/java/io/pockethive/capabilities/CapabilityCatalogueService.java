package io.pockethive.capabilities;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

@Service
public class CapabilityCatalogueService {
    private static final Logger logger = LoggerFactory.getLogger(CapabilityCatalogueService.class);

    private final Path capabilitiesDir;
    private final ObjectMapper jsonMapper;
    private final ObjectMapper yamlMapper;

    private volatile Map<String, CapabilityManifest> manifestsByDigest = Map.of();
    private volatile Map<ImageCoordinate, CapabilityManifest> manifestsByNameAndTag = Map.of();
    private volatile List<CapabilityManifest> manifests = List.of();

    public CapabilityCatalogueService(@Value("${capabilities.dir:capabilities}") String directory) throws IOException {
        this.capabilitiesDir = Paths.get(directory).toAbsolutePath().normalize();
        Files.createDirectories(this.capabilitiesDir);
        this.jsonMapper = configuredMapper(new ObjectMapper());
        this.yamlMapper = configuredMapper(new ObjectMapper(new YAMLFactory()));
    }

    private static ObjectMapper configuredMapper(ObjectMapper mapper) {
        mapper.findAndRegisterModules();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return mapper;
    }

    @PostConstruct
    void init() {
        reload();
    }

    public synchronized void reload() {
        Map<String, CapabilityManifest> digestIndex = new HashMap<>();
        Map<ImageCoordinate, CapabilityManifest> nameTagIndex = new HashMap<>();
        List<CapabilityManifest> loaded = new ArrayList<>();

        if (!Files.isDirectory(capabilitiesDir)) {
            throw new IllegalStateException("Capability directory does not exist: " + capabilitiesDir);
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(capabilitiesDir, "*.{json,yaml,yml}")) {
            for (Path path : stream) {
                CapabilityManifest manifest = readManifest(path);
                ManifestCoordinates coordinates = validate(manifest, path);
                loaded.add(manifest);

                if (coordinates.digest() != null) {
                    CapabilityManifest previous = digestIndex.put(coordinates.digest(), manifest);
                    if (previous != null) {
                        throw duplicateKey("digest", coordinates.digest(), path);
                    }
                }

                ImageCoordinate nameTagKey = coordinates.nameTag();
                CapabilityManifest previous = nameTagKey == null ? null : nameTagIndex.put(nameTagKey, manifest);
                if (previous != null) {
                    throw duplicateKey("name+tag", nameTagKey.toString(), path);
                }
            }
        } catch (IOException | DirectoryIteratorException e) {
            throw new IllegalStateException("Failed to read capability manifests from " + capabilitiesDir, e);
        }

        loaded.sort(Comparator
                .comparing((CapabilityManifest m) -> m.image().name().toLowerCase(Locale.ROOT))
                .thenComparing(m -> Objects.toString(m.image().tag(), "")));

        manifestsByDigest = Map.copyOf(digestIndex);
        manifestsByNameAndTag = Map.copyOf(nameTagIndex);
        manifests = List.copyOf(loaded);

        logger.info("Loaded {} capability manifest(s) from {}", loaded.size(), capabilitiesDir);
    }

    public Optional<CapabilityManifest> findByDigest(String digest) {
        String normalized = normalizeDigest(digest);
        if (normalized == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(manifestsByDigest.get(normalized));
    }

    public Optional<CapabilityManifest> findByNameAndTag(String name, String tag) {
        ImageCoordinate key = createCoordinate(name, tag);
        if (key == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(manifestsByNameAndTag.get(key));
    }

    public List<CapabilityManifest> allManifests() {
        return manifests;
    }

    public Optional<CapabilityManifest> findByImageReference(String imageReference) {
        ImageReference reference = parseImageReference(imageReference);
        if (reference == null) {
            return Optional.empty();
        }

        if (reference.digest() != null) {
            Optional<CapabilityManifest> byDigest = findByDigest(reference.digest());
            if (byDigest.isPresent()) {
                return byDigest;
            }
        }

        if (reference.name() != null && reference.tag() != null) {
            return findByNameAndTag(reference.name(), reference.tag());
        }

        return Optional.empty();
    }

    private CapabilityManifest readManifest(Path path) throws IOException {
        String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
        ObjectMapper mapper = (fileName.endsWith(".yaml") || fileName.endsWith(".yml")) ? yamlMapper : jsonMapper;
        try {
            return mapper.readValue(path.toFile(), CapabilityManifest.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse capability manifest " + path + ": " + e.getOriginalMessage(), e);
        }
    }

    private ManifestCoordinates validate(CapabilityManifest manifest, Path source) {
        List<String> errors = new ArrayList<>();
        if (isBlank(manifest.schemaVersion())) {
            errors.add("schemaVersion is required");
        }
        if (isBlank(manifest.capabilitiesVersion())) {
            errors.add("capabilitiesVersion is required");
        }
        if (isBlank(manifest.role())) {
            errors.add("role is required");
        }
        CapabilityManifest.Image image = manifest.image();
        if (image == null) {
            errors.add("image is required");
        }

        String name = null;
        String tag = null;
        String digest = null;

        if (image != null) {
            name = normalizeName(image.name());
            tag = normalizeTag(image.tag());
            digest = normalizeDigest(image.digest());

            if (name == null) {
                errors.add("image.name is required");
            }
            if (tag == null && digest == null) {
                errors.add("image requires a tag when digest is absent");
            }
        }

        if (!errors.isEmpty()) {
            throw new IllegalStateException("Invalid capability manifest " + source + ": " + String.join(", ", errors));
        }

        ImageCoordinate nameTag = (tag == null) ? null : new ImageCoordinate(name, tag);
        return new ManifestCoordinates(digest, nameTag);
    }

    private static IllegalStateException duplicateKey(String keyType, String key, Path path) {
        return new IllegalStateException("Duplicate capability manifest " + keyType + " '" + key + "' from " + path);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String normalizeName(String name) {
        if (isBlank(name)) {
            return null;
        }
        return name.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeTag(String tag) {
        if (isBlank(tag)) {
            return null;
        }
        return tag.trim();
    }

    private static String normalizeDigest(String digest) {
        if (isBlank(digest)) {
            return null;
        }
        return digest.trim().toLowerCase(Locale.ROOT);
    }

    private ImageCoordinate createCoordinate(String name, String tag) {
        String normalizedName = normalizeName(name);
        String normalizedTag = normalizeTag(tag);
        if (normalizedName == null || normalizedTag == null) {
            return null;
        }
        return new ImageCoordinate(normalizedName, normalizedTag);
    }

    private ImageReference parseImageReference(String reference) {
        if (isBlank(reference)) {
            return null;
        }

        String trimmed = reference.trim();
        String digest = null;
        String remainder = trimmed;

        int digestSep = trimmed.indexOf('@');
        if (digestSep >= 0) {
            digest = normalizeDigest(trimmed.substring(digestSep + 1));
            remainder = trimmed.substring(0, digestSep);
        }

        remainder = remainder.trim();
        String namePart = remainder;
        String tag = null;

        if (!remainder.isEmpty()) {
            int lastColon = remainder.lastIndexOf(':');
            int lastSlash = remainder.lastIndexOf('/');
            if (lastColon > lastSlash) {
                tag = normalizeTag(remainder.substring(lastColon + 1));
                namePart = remainder.substring(0, lastColon);
            }
        }

        String name = normalizeName(namePart);
        return new ImageReference(name, tag, digest);
    }

    private record ManifestCoordinates(String digest, ImageCoordinate nameTag) { }

    private record ImageCoordinate(String name, String tag) { }

    private record ImageReference(String name, String tag, String digest) { }
}
