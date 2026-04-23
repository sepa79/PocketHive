package io.pockethive.tools.sqltocsv.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.*;

@DisplayName("PathValidator Unit Tests")
class PathValidatorTest {

    private PathValidator validator;

    @BeforeEach
    void setUp() {
        validator = new PathValidator();
    }

    @Test
    @DisplayName("Should accept relative path in current directory")
    void shouldAcceptRelativePathInCurrentDir() {
        Path result = validator.validateOutputPath(Paths.get("output.csv"), false);
        assertThat(result).isNotNull();
        assertThat(result.isAbsolute()).isTrue();
    }

    @Test
    @DisplayName("Should accept relative path in subdirectory")
    void shouldAcceptRelativePathInSubdir() {
        Path result = validator.validateOutputPath(Paths.get("output/data.csv"), false);
        assertThat(result).isNotNull();
        assertThat(result.toString()).contains("output");
    }

    @Test
    @DisplayName("Should accept relative path with parent navigation within project")
    void shouldAcceptRelativePathWithParentNavigation() {
        Path result = validator.validateOutputPath(Paths.get("../../scenarios/datasets/users.csv"), false);
        assertThat(result).isNotNull();
        assertThat(result.isAbsolute()).isTrue();
    }

    @Test
    @DisplayName("Should reject absolute path without flag")
    void shouldRejectAbsolutePathWithoutFlag() {
        assertThatThrownBy(() -> 
            validator.validateOutputPath(Paths.get("/tmp/output.csv"), false))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("require --allow-absolute-paths");
    }

    @Test
    @DisplayName("Should accept absolute path with flag")
    void shouldAcceptAbsolutePathWithFlag() {
        Path result = validator.validateOutputPath(Paths.get("/tmp/output.csv"), true);
        assertThat(result).isNotNull();
        assertThat(result.isAbsolute()).isTrue();
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    @DisplayName("Should reject /etc directory on Linux")
    void shouldRejectEtcDirectory() {
        assertThatThrownBy(() -> 
            validator.validateOutputPath(Paths.get("/etc/passwd"), true))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("system directory");
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    @DisplayName("Should reject /sys directory on Linux")
    void shouldRejectSysDirectory() {
        assertThatThrownBy(() -> 
            validator.validateOutputPath(Paths.get("/sys/test.csv"), true))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("system directory");
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    @DisplayName("Should reject /proc directory on Linux")
    void shouldRejectProcDirectory() {
        assertThatThrownBy(() -> 
            validator.validateOutputPath(Paths.get("/proc/test.csv"), true))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("system directory");
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    @DisplayName("Should reject /boot directory on Linux")
    void shouldRejectBootDirectory() {
        assertThatThrownBy(() -> 
            validator.validateOutputPath(Paths.get("/boot/test.csv"), true))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("system directory");
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    @DisplayName("Should reject /root directory on Linux")
    void shouldRejectRootDirectory() {
        assertThatThrownBy(() -> 
            validator.validateOutputPath(Paths.get("/root/test.csv"), true))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("system directory");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("Should reject Windows system directory")
    void shouldRejectWindowsSystemDirectory() {
        assertThatThrownBy(() -> 
            validator.validateOutputPath(Paths.get("C:\\Windows\\test.csv"), true))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("system directory");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("Should reject Windows Program Files")
    void shouldRejectWindowsProgramFiles() {
        assertThatThrownBy(() -> 
            validator.validateOutputPath(Paths.get("C:\\Program Files\\test.csv"), true))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("system directory");
    }

    @Test
    @DisplayName("Should normalize path correctly")
    void shouldNormalizePath() {
        Path result = validator.validateOutputPath(Paths.get("./output/../data/file.csv"), false);
        assertThat(result).isNotNull();
        assertThat(result.toString()).doesNotContain("..");
    }
}
