package io.pockethive.auth.service.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JsonFileDynamicClientStateStoreTest {
    private static final Instant ISSUED_AT = Instant.parse("2026-09-04T08:00:00Z");
    private static final Instant EXPIRES_AT = Instant.parse("2026-10-05T08:00:00Z");

    @Test
    void missingStateIsAnExplicitEmptyFirstStart(@TempDir Path directory) {
        JsonFileDynamicClientStateStore store = store(directory.resolve("missing/state.json"));

        assertThat(store.load()).isEmpty();
    }

    @Test
    void atomicallyReplacesAndReloadsVersionedState(@TempDir Path directory) throws Exception {
        Path statePath = directory.resolve("oauth/state.json");
        JsonFileDynamicClientStateStore store = store(statePath);
        DynamicClientStateEntry entry = entry();

        store.replace(List.of(entry));

        assertThat(store.load()).containsExactly(entry);
        assertThat(Files.readString(statePath)).contains("\"schemaVersion\":1")
            .contains("phmcp_client_test")
            .doesNotContain("clientSecret", "accessToken", "refreshToken", "authorizationCode");
        try (var files = Files.list(statePath.getParent())) {
            assertThat(files.map(path -> path.getFileName().toString()).toList())
                .containsExactly("state.json");
        }
    }

    @Test
    void rejectsMalformedUnsupportedAndRelativeState(@TempDir Path directory) throws Exception {
        Path malformed = directory.resolve("malformed.json");
        Files.writeString(malformed, "not-json");
        assertThatThrownBy(() -> store(malformed).load())
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("OAUTH_DYNAMIC_CLIENT_STATE_READ_FAILED");

        Path unsupported = directory.resolve("unsupported.json");
        Files.writeString(unsupported, "{\"schemaVersion\":2,\"clients\":[]}");
        assertThatThrownBy(() -> store(unsupported).load())
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("OAUTH_DYNAMIC_CLIENT_STATE_VERSION_UNSUPPORTED");

        Path nullClients = directory.resolve("null-clients.json");
        Files.writeString(nullClients, "{\"schemaVersion\":1,\"clients\":null}");
        assertThatThrownBy(() -> store(nullClients).load())
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("OAUTH_DYNAMIC_CLIENT_STATE_READ_FAILED");

        Path unknownField = directory.resolve("unknown-field.json");
        Files.writeString(unknownField, "{\"schemaVersion\":1,\"clients\":[],\"fallback\":true}");
        assertThatThrownBy(() -> store(unknownField).load())
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("OAUTH_DYNAMIC_CLIENT_STATE_READ_FAILED");

        assertThatThrownBy(() -> store(Path.of("relative-state.json")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("OAUTH_DYNAMIC_CLIENT_STATE_PATH_INVALID");
    }

    @Test
    void removesTemporaryFileWhenAtomicReplacementFails(@TempDir Path directory) throws Exception {
        Path statePath = directory.resolve("state.json");
        Files.createDirectory(statePath);

        assertThatThrownBy(() -> store(statePath).replace(List.of(entry())))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageStartingWith("OAUTH_DYNAMIC_CLIENT_STATE_");
        try (var files = Files.list(directory)) {
            assertThat(files.map(path -> path.getFileName().toString()).toList())
                .containsExactly("state.json");
        }
    }

    @Test
    void cleanupFailureDoesNotMaskTheAuthoritativeStateWriteFailure(@TempDir Path directory)
        throws Exception {
        ObjectMapper mapper = mock(ObjectMapper.class);
        when(mapper.copy()).thenReturn(mapper);
        when(mapper.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)).thenReturn(mapper);
        doAnswer(invocation -> {
            Path temporary = ((File) invocation.getArgument(0)).toPath();
            Files.delete(temporary);
            Files.createDirectory(temporary);
            Files.writeString(temporary.resolve("blocked"), "blocked");
            throw new IOException("write failed");
        }).when(mapper).writeValue(any(File.class), any());
        JsonFileDynamicClientStateStore store = new JsonFileDynamicClientStateStore(
            mapper, directory.resolve("state.json"));

        assertThatThrownBy(() -> store.replace(List.of(entry())))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("OAUTH_DYNAMIC_CLIENT_STATE_WRITE_FAILED")
            .hasCauseInstanceOf(IOException.class)
            .hasRootCauseMessage("write failed");
    }

    private static JsonFileDynamicClientStateStore store(Path path) {
        return new JsonFileDynamicClientStateStore(new ObjectMapper().findAndRegisterModules(), path);
    }

    private static DynamicClientStateEntry entry() {
        return new DynamicClientStateEntry(
            "dynamic:phmcp_client_test", "phmcp_client_test", ISSUED_AT, "Amazon Q",
            List.of("http://localhost:52000/oauth/callback"),
            List.of("authorization_code", "refresh_token"),
            List.of("mcp:discover", "mcp:read"), EXPIRES_AT);
    }
}
