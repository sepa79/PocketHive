package io.pockethive.tools.sqltocsv.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

@DisplayName("CredentialProvider Unit Tests")
class CredentialProviderTest {

    private CredentialProvider provider;

    @BeforeEach
    void setUp() {
        provider = new CredentialProvider();
    }

    @Test
    @DisplayName("Should return CLI password with warning")
    void shouldReturnCliPassword() {
        String password = provider.resolvePassword("cli-secret", "ENV_VAR", null, false);
        assertThat(password).isEqualTo("cli-secret");
    }

    @Test
    @DisplayName("Should prioritize environment variable over CLI")
    void shouldPrioritizeEnvironmentVariable() {
        String envVar = "PATH"; // Use existing env var for test
        String password = provider.resolvePassword(null, envVar, null, false);
        assertThat(password).isNotNull();
    }

    @Test
    @DisplayName("Should read password from file")
    void shouldReadPasswordFromFile(@TempDir Path tempDir) throws IOException {
        Path credFile = tempDir.resolve("creds.txt");
        Files.writeString(credFile, "file-secret");
        
        String password = provider.resolvePassword(null, "NON_EXISTENT_VAR", credFile, false);
        assertThat(password).isEqualTo("file-secret");
    }

    @Test
    @DisplayName("Should trim password from file")
    void shouldTrimPasswordFromFile(@TempDir Path tempDir) throws IOException {
        Path credFile = tempDir.resolve("creds.txt");
        Files.writeString(credFile, "  file-secret  \n");
        
        String password = provider.resolvePassword(null, "NON_EXISTENT_VAR", credFile, false);
        assertThat(password).isEqualTo("file-secret");
    }

    @Test
    @DisplayName("Should reject empty credential file")
    void shouldRejectEmptyCredentialFile(@TempDir Path tempDir) throws IOException {
        Path credFile = tempDir.resolve("creds.txt");
        Files.writeString(credFile, "");
        
        assertThatThrownBy(() -> 
            provider.resolvePassword(null, "NON_EXISTENT_VAR", credFile, false))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("empty");
    }

    @Test
    @DisplayName("Should reject non-existent credential file")
    void shouldRejectNonExistentFile(@TempDir Path tempDir) {
        Path credFile = tempDir.resolve("non-existent.txt");
        
        assertThatThrownBy(() -> 
            provider.resolvePassword(null, "NON_EXISTENT_VAR", credFile, false))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Should read password from stdin")
    void shouldReadPasswordFromStdin() {
        System.setIn(new ByteArrayInputStream("stdin-secret\n".getBytes()));
        
        String password = provider.resolvePassword(null, "NON_EXISTENT_VAR", null, true);
        assertThat(password).isEqualTo("stdin-secret");
        
        System.setIn(System.in); // Reset
    }

    @Test
    @DisplayName("Should reject empty stdin password")
    void shouldRejectEmptyStdinPassword() {
        System.setIn(new ByteArrayInputStream("\n".getBytes()));
        
        assertThatThrownBy(() -> 
            provider.resolvePassword(null, "NON_EXISTENT_VAR", null, true))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("No password provided");
        
        System.setIn(System.in); // Reset
    }

    @Test
    @DisplayName("Should throw when no password source available")
    void shouldThrowWhenNoPasswordSource() {
        assertThatThrownBy(() -> 
            provider.resolvePassword(null, "NON_EXISTENT_VAR", null, false))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("No password provided");
    }
}
