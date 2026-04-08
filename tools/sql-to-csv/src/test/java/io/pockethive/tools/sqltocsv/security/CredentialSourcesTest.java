package io.pockethive.tools.sqltocsv.security;

import io.pockethive.tools.sqltocsv.ValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

@DisplayName("CredentialSources Tests")
class CredentialSourcesTest {

    @Test
    @DisplayName("CLI source should return password when provided")
    void cliSourceShouldReturnPasswordWhenProvided() {
        CredentialSource source = CredentialSources.cli("test-password");
        
        assertThat(source.resolve()).contains("test-password");
        assertThat(source.name()).isEqualTo("CLI");
    }

    @Test
    @DisplayName("CLI source should return empty when password is null")
    void cliSourceShouldReturnEmptyWhenNull() {
        CredentialSource source = CredentialSources.cli(null);
        
        assertThat(source.resolve()).isEmpty();
    }

    @Test
    @DisplayName("CLI source should return empty when password is empty")
    void cliSourceShouldReturnEmptyWhenEmpty() {
        CredentialSource source = CredentialSources.cli("");
        
        assertThat(source.resolve()).isEmpty();
    }

    @Test
    @DisplayName("EnvVar source should return empty when envVar is null")
    void envVarSourceShouldReturnEmptyWhenNull() {
        CredentialSource source = CredentialSources.envVar(null);
        
        assertThat(source.resolve()).isEmpty();
    }

    @Test
    @DisplayName("EnvVar source should return empty when envVar is blank")
    void envVarSourceShouldReturnEmptyWhenBlank() {
        CredentialSource source = CredentialSources.envVar("  ");
        
        assertThat(source.resolve()).isEmpty();
    }

    @Test
    @DisplayName("EnvVar source should have correct name")
    void envVarSourceShouldHaveCorrectName() {
        CredentialSource source = CredentialSources.envVar("TEST_VAR");
        
        assertThat(source.name()).isEqualTo("Environment Variable");
    }

    @Test
    @DisplayName("File source should return empty when file is null")
    void fileSourceShouldReturnEmptyWhenNull() {
        CredentialSource source = CredentialSources.file(null);
        
        assertThat(source.resolve()).isEmpty();
    }

    @Test
    @DisplayName("File source should return empty when file does not exist")
    void fileSourceShouldReturnEmptyWhenFileDoesNotExist(@TempDir Path tempDir) {
        Path nonExistent = tempDir.resolve("nonexistent.txt");
        CredentialSource source = CredentialSources.file(nonExistent);
        
        assertThat(source.resolve()).isEmpty();
    }

    @Test
    @DisplayName("File source should read password from file")
    void fileSourceShouldReadPasswordFromFile(@TempDir Path tempDir) throws IOException {
        Path credFile = tempDir.resolve("password.txt");
        Files.writeString(credFile, "file-password");
        
        // Set permissions if on Unix
        if (!System.getProperty("os.name").toLowerCase().contains("win")) {
            Files.setPosixFilePermissions(credFile, Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE
            ));
        }
        
        CredentialSource source = CredentialSources.file(credFile);
        
        assertThat(source.resolve()).contains("file-password");
        assertThat(source.name()).isEqualTo("File");
    }

    @Test
    @DisplayName("File source should throw when file is empty")
    void fileSourceShouldThrowWhenFileIsEmpty(@TempDir Path tempDir) throws IOException {
        Path credFile = tempDir.resolve("empty.txt");
        Files.writeString(credFile, "");
        
        if (!System.getProperty("os.name").toLowerCase().contains("win")) {
            Files.setPosixFilePermissions(credFile, Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE
            ));
        }
        
        CredentialSource source = CredentialSources.file(credFile);
        
        assertThatThrownBy(() -> source.resolve())
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("empty");
    }

    @Test
    @DisplayName("Stdin source should return empty when disabled")
    void stdinSourceShouldReturnEmptyWhenDisabled() {
        CredentialSource source = CredentialSources.stdin(false);
        
        assertThat(source.resolve()).isEmpty();
        assertThat(source.name()).isEqualTo("Stdin");
    }

    @Test
    @DisplayName("Console source should return empty when console is null")
    void consoleSourceShouldReturnEmptyWhenConsoleIsNull() {
        // System.console() returns null in test environments
        CredentialSource source = CredentialSources.console();
        
        assertThat(source.resolve()).isEmpty();
        assertThat(source.name()).isEqualTo("Console");
    }
}
