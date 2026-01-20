package io.pockethive.tools.sqltocsv;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

@DisplayName("SqlExportConfig Validation Tests")
class SqlExportConfigTest {

    @Test
    @DisplayName("Should build valid config")
    void shouldBuildValidConfig(@TempDir Path tempDir) {
        File outputFile = tempDir.resolve("output.csv").toFile();
        
        SqlExportConfig config = SqlExportConfig.builder()
            .jdbcUrl("jdbc:h2:mem:test")
            .username("sa")
            .password("")
            .query("SELECT 1")
            .outputFile(outputFile)
            .build();
        
        assertThat(config.jdbcUrl()).isEqualTo("jdbc:h2:mem:test");
        assertThat(config.query()).isEqualTo("SELECT 1");
    }

    @Test
    @DisplayName("Should reject null JDBC URL")
    void shouldRejectNullJdbcUrl(@TempDir Path tempDir) {
        File outputFile = tempDir.resolve("output.csv").toFile();
        
        assertThatThrownBy(() -> 
            SqlExportConfig.builder()
                .jdbcUrl(null)
                .username("sa")
                .password("")
                .query("SELECT 1")
                .outputFile(outputFile)
                .build())
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Should reject null query")
    void shouldRejectNullQuery(@TempDir Path tempDir) {
        File outputFile = tempDir.resolve("output.csv").toFile();
        
        assertThatThrownBy(() -> 
            SqlExportConfig.builder()
                .jdbcUrl("jdbc:h2:mem:test")
                .username("sa")
                .password("")
                .query(null)
                .outputFile(outputFile)
                .build())
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Should reject null output file")
    void shouldRejectNullOutputFile() {
        assertThatThrownBy(() -> 
            SqlExportConfig.builder()
                .jdbcUrl("jdbc:h2:mem:test")
                .username("sa")
                .password("")
                .query("SELECT 1")
                .outputFile(null)
                .build())
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Should use default values")
    void shouldUseDefaultValues(@TempDir Path tempDir) {
        File outputFile = tempDir.resolve("output.csv").toFile();
        
        SqlExportConfig config = SqlExportConfig.builder()
            .jdbcUrl("jdbc:h2:mem:test")
            .username("sa")
            .password("")
            .query("SELECT 1")
            .outputFile(outputFile)
            .build();
        
        assertThat(config.delimiter()).isEqualTo(",");
        assertThat(config.includeHeader()).isTrue();
        assertThat(config.fetchSize()).isEqualTo(1000);
        assertThat(config.bufferSizeKb()).isEqualTo(64);
    }

    @Test
    @DisplayName("Should accept custom values")
    void shouldAcceptCustomValues(@TempDir Path tempDir) {
        File outputFile = tempDir.resolve("output.csv").toFile();
        
        SqlExportConfig config = SqlExportConfig.builder()
            .jdbcUrl("jdbc:h2:mem:test")
            .username("sa")
            .password("")
            .query("SELECT 1")
            .outputFile(outputFile)
            .delimiter("|")
            .includeHeader(false)
            .fetchSize(500)
            .bufferSizeKb(128)
            .nullValue("N/A")
            .build();
        
        assertThat(config.delimiter()).isEqualTo("|");
        assertThat(config.includeHeader()).isFalse();
        assertThat(config.fetchSize()).isEqualTo(500);
        assertThat(config.bufferSizeKb()).isEqualTo(128);
        assertThat(config.nullValue()).isEqualTo("N/A");
    }

    @Test
    @DisplayName("Should be immutable")
    void shouldBeImmutable(@TempDir Path tempDir) {
        File outputFile = tempDir.resolve("output.csv").toFile();
        
        SqlExportConfig config = SqlExportConfig.builder()
            .jdbcUrl("jdbc:h2:mem:test")
            .username("sa")
            .password("")
            .query("SELECT 1")
            .outputFile(outputFile)
            .build();
        
        // Config is a record, so it's immutable by design
        assertThat(config.jdbcUrl()).isEqualTo("jdbc:h2:mem:test");
        
        // Creating a new config doesn't affect the original
        SqlExportConfig config2 = SqlExportConfig.builder()
            .jdbcUrl("jdbc:h2:mem:test2")
            .username("sa")
            .password("")
            .query("SELECT 2")
            .outputFile(outputFile)
            .build();
        
        assertThat(config.jdbcUrl()).isEqualTo("jdbc:h2:mem:test");
        assertThat(config2.jdbcUrl()).isEqualTo("jdbc:h2:mem:test2");
    }
}
