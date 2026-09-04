package io.pockethive.auth.service.oauth;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Objects;

/**
 * Responsibility: Atomically read and replace versioned dynamic OAuth client state in one configured file.
 * Must not: Fall back to another path or format, validate OAuth policy, or persist secrets and session artifacts.
 * Contract: docs/architecture/AUTH_SERVICE_API_SPEC.md.
 */
final class JsonFileDynamicClientStateStore implements DynamicClientStateStore {
    private static final int SCHEMA_VERSION = 1;
    private final ObjectMapper mapper;
    private final Path statePath;

    JsonFileDynamicClientStateStore(ObjectMapper mapper, Path statePath) {
        if (mapper == null || statePath == null || !statePath.isAbsolute()) {
            throw new IllegalArgumentException("OAUTH_DYNAMIC_CLIENT_STATE_PATH_INVALID");
        }
        this.mapper = mapper.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.statePath = statePath.normalize();
    }

    @Override
    public List<DynamicClientStateEntry> load() {
        try (InputStream input = Files.newInputStream(statePath)) {
            DynamicClientStateDocument document = mapper.readValue(input, DynamicClientStateDocument.class);
            if (document == null || document.clients() == null) {
                throw new IllegalStateException("OAUTH_DYNAMIC_CLIENT_STATE_READ_FAILED");
            }
            if (document.schemaVersion() != SCHEMA_VERSION) {
                throw new IllegalStateException("OAUTH_DYNAMIC_CLIENT_STATE_VERSION_UNSUPPORTED");
            }
            return document.clients();
        } catch (NoSuchFileException exception) {
            return List.of();
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("OAUTH_DYNAMIC_CLIENT_STATE_READ_FAILED", exception);
        }
    }

    @Override
    public void replace(List<DynamicClientStateEntry> clients) {
        Objects.requireNonNull(clients, "clients");
        Path parent = statePath.getParent();
        Path temporary = null;
        try {
            Files.createDirectories(parent);
            temporary = Files.createTempFile(parent, ".dynamic-clients-", ".tmp");
            mapper.writeValue(temporary.toFile(), new DynamicClientStateDocument(
                SCHEMA_VERSION, List.copyOf(clients)));
            Files.move(temporary, statePath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("OAUTH_DYNAMIC_CLIENT_STATE_WRITE_FAILED", exception);
        } finally {
            deleteTemporary(temporary);
        }
    }

    private static void deleteTemporary(Path temporary) {
        if (temporary == null) return;
        try {
            Files.deleteIfExists(temporary);
        } catch (IOException exception) {
            // A failed state operation is already explicit; an orphaned temporary file is never authoritative.
        }
    }
}
