package io.pockethive.tools.sqltocsv;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

@DisplayName("CSV Escaping Integration Tests")
class CsvEscapingIntegrationTest {

    private static final String JDBC_URL = "jdbc:h2:mem:escapedb" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
    private static final String USERNAME = "sa";
    private static final String PASSWORD = "";

    @TempDir
    Path tempDir;

    @BeforeAll
    static void setupDatabase() throws Exception {
        try (Connection conn = DriverManager.getConnection(JDBC_URL, USERNAME, PASSWORD);
             Statement stmt = conn.createStatement()) {

            stmt.execute("CREATE TABLE test_data (" +
                        "id INT PRIMARY KEY, " +
                        "\"value\" VARCHAR(1000))");

            // Test various edge cases
            stmt.execute("INSERT INTO test_data VALUES (1, 'Simple text')");
            stmt.execute("INSERT INTO test_data VALUES (2, 'Text with, comma')");
            stmt.execute("INSERT INTO test_data VALUES (3, 'Text with \"quotes\"')");
            stmt.execute("INSERT INTO test_data VALUES (4, 'Text with\nnewline')");
            stmt.execute("INSERT INTO test_data VALUES (5, 'Text with\r\nCRLF')");
            stmt.execute("INSERT INTO test_data VALUES (6, 'Text with\ttab')");
            stmt.execute("INSERT INTO test_data VALUES (7, NULL)");
            stmt.execute("INSERT INTO test_data VALUES (8, '')");
            stmt.execute("INSERT INTO test_data VALUES (9, '=FORMULA()')"); // CSV injection
            stmt.execute("INSERT INTO test_data VALUES (10, 'Multiple, \"special\", \ncharacters')");
        }
    }

    @Test
    @DisplayName("Should properly escape commas")
    void shouldEscapeCommas() throws Exception {
        File outputFile = tempDir.resolve("commas.csv").toFile();

        SqlExportConfig config = SqlExportConfig.builder()
            .jdbcUrl(JDBC_URL)
            .username(USERNAME)
            .password(PASSWORD)
            .query("SELECT * FROM test_data WHERE id = 2")
            .outputFile(outputFile)
            .includeHeader(true)
            .build();

        new SqlCsvExporter(config).export();

        List<String> lines = Files.readAllLines(outputFile.toPath());
        assertThat(lines.get(1)).contains("\"Text with, comma\"");
    }

    @Test
    @DisplayName("Should properly escape quotes")
    void shouldEscapeQuotes() throws Exception {
        File outputFile = tempDir.resolve("quotes.csv").toFile();

        SqlExportConfig config = SqlExportConfig.builder()
            .jdbcUrl(JDBC_URL)
            .username(USERNAME)
            .password(PASSWORD)
            .query("SELECT * FROM test_data WHERE id = 3")
            .outputFile(outputFile)
            .includeHeader(true)
            .build();

        new SqlCsvExporter(config).export();

        List<String> lines = Files.readAllLines(outputFile.toPath());
        assertThat(lines.get(1)).contains("\"Text with \"\"quotes\"\"\"");
    }

    @Test
    @DisplayName("Should properly handle newlines")
    void shouldHandleNewlines() throws Exception {
        File outputFile = tempDir.resolve("newlines.csv").toFile();

        SqlExportConfig config = SqlExportConfig.builder()
            .jdbcUrl(JDBC_URL)
            .username(USERNAME)
            .password(PASSWORD)
            .query("SELECT * FROM test_data WHERE id = 4")
            .outputFile(outputFile)
            .includeHeader(true)
            .build();

        new SqlCsvExporter(config).export();

        String content = Files.readString(outputFile.toPath());
        assertThat(content).contains("\"Text with\nnewline\"");
    }

    @Test
    @DisplayName("Should handle NULL values")
    void shouldHandleNullValues() throws Exception {
        File outputFile = tempDir.resolve("nulls.csv").toFile();

        SqlExportConfig config = SqlExportConfig.builder()
            .jdbcUrl(JDBC_URL)
            .username(USERNAME)
            .password(PASSWORD)
            .query("SELECT * FROM test_data WHERE id = 7")
            .outputFile(outputFile)
            .includeHeader(true)
            .nullValue("NULL")
            .build();

        new SqlCsvExporter(config).export();

        List<String> lines = Files.readAllLines(outputFile.toPath());
        assertThat(lines.get(1)).endsWith(",NULL");
    }

    @Test
    @DisplayName("Should handle empty strings")
    void shouldHandleEmptyStrings() throws Exception {
        File outputFile = tempDir.resolve("empty.csv").toFile();

        SqlExportConfig config = SqlExportConfig.builder()
            .jdbcUrl(JDBC_URL)
            .username(USERNAME)
            .password(PASSWORD)
            .query("SELECT * FROM test_data WHERE id = 8")
            .outputFile(outputFile)
            .includeHeader(true)
            .build();

        new SqlCsvExporter(config).export();

        List<String> lines = Files.readAllLines(outputFile.toPath());
        assertThat(lines.get(1)).endsWith(",");
    }

    @Test
    @DisplayName("Should handle multiple special characters")
    void shouldHandleMultipleSpecialCharacters() throws Exception {
        File outputFile = tempDir.resolve("multiple.csv").toFile();

        SqlExportConfig config = SqlExportConfig.builder()
            .jdbcUrl(JDBC_URL)
            .username(USERNAME)
            .password(PASSWORD)
            .query("SELECT * FROM test_data WHERE id = 10")
            .outputFile(outputFile)
            .includeHeader(true)
            .build();

        new SqlCsvExporter(config).export();

        String content = Files.readString(outputFile.toPath());
        assertThat(content).contains("\"Multiple, \"\"special\"\", \ncharacters\"");
    }

    @Test
    @DisplayName("Should use custom delimiter")
    void shouldUseCustomDelimiter() throws Exception {
        File outputFile = tempDir.resolve("pipe.csv").toFile();

        SqlExportConfig config = SqlExportConfig.builder()
            .jdbcUrl(JDBC_URL)
            .username(USERNAME)
            .password(PASSWORD)
            .query("SELECT * FROM test_data WHERE id = 2")
            .outputFile(outputFile)
            .delimiter("|")
            .includeHeader(true)
            .build();

        new SqlCsvExporter(config).export();

        List<String> lines = Files.readAllLines(outputFile.toPath());
        assertThat(lines.get(0)).contains("|");
        assertThat(lines.get(1)).doesNotContain("\"Text with, comma\""); // Comma doesn't need escaping with pipe delimiter
    }
}
