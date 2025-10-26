package io.pockethive.worker.sdk.capabilities;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads worker capabilities manifests from the classpath and caches them by role.
 */
public final class WorkerCapabilitiesManifestRepository {

    private static final Logger log = LoggerFactory.getLogger(WorkerCapabilitiesManifestRepository.class);
    private static final String BASE_PATH = "pockethive/capabilities/";
    private static final TypeReference<Map<String, Object>> MANIFEST_TYPE = new TypeReference<>() { };

    private final ObjectMapper objectMapper;
    private final ClassLoader classLoader;
    private final ConcurrentMap<String, Optional<WorkerCapabilitiesManifest>> cache = new ConcurrentHashMap<>();

    public WorkerCapabilitiesManifestRepository(ObjectMapper objectMapper) {
        this(objectMapper, Thread.currentThread().getContextClassLoader());
    }

    WorkerCapabilitiesManifestRepository(ObjectMapper objectMapper, ClassLoader classLoader) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.classLoader = Objects.requireNonNull(classLoader, "classLoader");
    }

    /**
     * Returns the manifest registered for the supplied worker role, if present on the classpath.
     */
    public Optional<WorkerCapabilitiesManifest> findByRole(String role) {
        String key = normaliseRole(role);
        return cache.computeIfAbsent(key, this::loadManifest);
    }

    private Optional<WorkerCapabilitiesManifest> loadManifest(String roleKey) {
        String resource = BASE_PATH + roleKey + ".json";
        try (InputStream stream = classLoader.getResourceAsStream(resource)) {
            if (stream == null) {
                log.debug("No worker capabilities manifest found for role '{}'", roleKey);
                return Optional.empty();
            }
            Map<String, Object> payload = objectMapper.readValue(stream, MANIFEST_TYPE);
            WorkerCapabilitiesManifest manifest = toManifest(resource, payload);
            return Optional.of(manifest);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to load worker capabilities manifest '" + resource + "'", ex);
        }
    }

    private WorkerCapabilitiesManifest toManifest(String resource, Map<String, Object> payload) {
        Map<String, Object> copy = payload == null || payload.isEmpty() ? Map.of() : Map.copyOf(payload);
        String schemaVersion = requireText(copy.get("schemaVersion"), resource, "schemaVersion");
        String capabilitiesVersion = requireText(copy.get("capabilitiesVersion"), resource, "capabilitiesVersion");
        String role = requireText(copy.get("role"), resource, "role");
        return new WorkerCapabilitiesManifest(schemaVersion, capabilitiesVersion, role, copy);
    }

    private static String requireText(Object value, String resource, String field) {
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        throw new IllegalStateException("Manifest '" + resource + "' is missing required field '" + field + "'");
    }

    private static String normaliseRole(String role) {
        if (role == null || role.isBlank()) {
            throw new IllegalArgumentException("role must not be blank");
        }
        return role.trim().toLowerCase(Locale.ROOT);
    }
}
