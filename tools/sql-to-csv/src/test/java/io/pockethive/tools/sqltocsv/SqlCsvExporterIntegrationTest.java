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

@DisplayName("SQL-to-CSV Integration Tests with H2")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SqlCsvExporterIntegrationTest {

    private static final String JDBC_URL = "jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1";
    private static final String USERNAME = "sa";
    private static final String PASSWORD = "";

    @TempDir
    Path tempDir;

    @BeforeAll
    static void setupDatabase() throws Exception {
        try (Connection conn = DriverManager.getConnection(JDBC_URL, USERNAME, PASSWORD);
             Statement stmt = conn.createStatement()) {
            
            // Create test table
            stmt.execute("CREATE TABLE users (" +
                        "id INT PRIMARY KEY, " +
                        "name VARCHAR(255), " +
                        "email VARCHAR(255), " +
                        "age INT, " +
                        "active BOOLEAN)");
            
            // Insert test data
            stmt.execute("INSERT INTO users VALUES (1, 'John Doe', 'john@example.com', 30, true)");
            stmt.execute("INSERT INTO users VALUES (2, 'Jane Smith', 'jane@example.com', 25, true)");
            stmt.execute("INSERT INTO users VALUES (3, 'Bob Johnson', 'bob@example.com', 35, false)");
            stmt.execute("INSERT INTO users VALUES (4, 'Alice Brown', NULL, 28, true)");
            stmt.execute("INSERT INTO users VALUES (5, 'Charlie Wilson', 'charlie@example.com', NULL, true)");
        }
    }

    @Test
    @Order(1)
    @DisplayName("Should export all rows with header")
    void shouldExportAllRowsWithHeader() throws Exception {
        File outputFile = tempDir.resolve("users.csv").toFile();
        
        SqlExportConfig config = SqlExportConfig.builder()
            .jdbcUrl(JDBC_URL)
            .username(USERNAME)
            .password(PASSWORD)
            .query("SELECT * FROM users ORDER BY id")
            .outputFile(outputFile)
            .delimiter(",")
            .includeHeader(true)
            .nullValue("NULL")
            .verbose(false)
            .fetchSize(1000)
            .bufferSizeKb(64)
            .build();

        SqlCsvExporter exporter = new SqlCsvExporter(config);
        ExportResult result = exporter.export();

        assertThat(result.rowCount()).isEqualTo(5);
        assertThat(result.columnCount()).isEqualTo(5);
        assertThat(outputFile).exists();

        List<String> lines = Files.readAllLines(outputFile.toPath());
        assertThat(lines).hasSize(6); // Header + 5 rows
        assertThat(lines.get(0)).isEqualTo("ID,NAME,EMAIL,AGE,ACTIVE");
        assertThat(lines.get(1)).contains("John Doe");
        assertThat(lines.get(4)).contains("NULL"); // Alice's email is NULL
        assertThat(lines.get(5)).contains("NULL"); // Charlie's age is NULL
    }

    @Test
    @Order(2)
    @DisplayName("Should export without header")
    void shouldExportWithoutHeader() throws Exception {
        File outputFile = tempDir.resolve("users-no-header.csv").toFile();
        
        SqlExportConfig config = SqlExportConfig.builder()
            .jdbcUrl(JDBC_URL)
            .username(USERNAME)
            .password(PASSWORD)
            .query("SELECT * FROM users ORDER BY id")
            .outputFile(outputFile)
            .includeHeader(false)
            .build();

        SqlCsvExporter exporter = new SqlCsvExporter(config);
        ExportResult result = exporter.export();

        assertThat(result.rowCount()).isEqualTo(5);
        
        List<String> lines = Files.readAllLines(outputFile.toPath());
        assertThat(lines).hasSize(5); // No header
        assertThat(lines.get(0)).doesNotContain("ID,NAME");
    }

    @Test
    @Order(3)
    @DisplayName("Should export with WHERE clause")
    void shouldExportWithWhereClause() throws Exception {
        File outputFile = tempDir.resolve("active-users.csv").toFile();
        
        SqlExportConfig config = SqlExportConfig.builder()
            .jdbcUrl(JDBC_URL)
            .username(USERNAME)
            .password(PASSWORD)
            .query("SELECT * FROM users WHERE active = true ORDER BY id")
            .outputFile(outputFile)
            .includeHeader(true)
            .build();

        SqlCsvExporter exporter = new SqlCsvExporter(config);
        ExportResult result = exporter.export();

        assertThat(result.rowCount()).isEqualTo(4); // Only active users
        
        List<String> lines = Files.readAllLines(outputFile.toPath());
        assertThat(lines).hasSize(5); // Header + 4 rows
    }

    @Test
    @Order(4)
    @DisplayName("Should export specific columns")
    void shouldExportSpecificColumns() throws Exception {
        File outputFile = tempDir.resolve("users-names.csv").toFile();
        
        SqlExportConfig config = SqlExportConfig.builder()
            .jdbcUrl(JDBC_URL)
            .username(USERNAME)
            .password(PASSWORD)
            .query("SELECT id, name FROM users ORDER BY id")
            .outputFile(outputFile)
            .includeHeader(true)
            .build();

        SqlCsvExporter exporter = new SqlCsvExporter(config);
        ExportResult result = exporter.export();

        assertThat(result.rowCount()).isEqualTo(5);
        assertThat(result.columnCount()).isEqualTo(2);
        
        List<String> lines = Files.readAllLines(outputFile.toPath());
        assertThat(lines.get(0)).isEqualTo("ID,NAME");
        assertThat(lines.get(1)).doesNotContain("@example.com");
    }

    @Test
    @Order(5)
    @DisplayName("Should use custom delimiter")
    void shouldUseCustomDelimiter() throws Exception {
        File outputFile = tempDir.resolve("users-tab.csv").toFile();
        
        SqlExportConfig config = SqlExportConfig.builder()
            .jdbcUrl(JDBC_URL)
            .username(USERNAME)
            .password(PASSWORD)
            .query("SELECT id, name FROM users ORDER BY id")
            .outputFile(outputFile)
            .delimiter("\t")
            .includeHeader(true)
            .build();

        SqlCsvExporter exporter = new SqlCsvExporter(config);
        exporter.export();
        
        List<String> lines = Files.readAllLines(outputFile.toPath());
        assertThat(lines.get(0)).contains("\t");
        assertThat(lines.get(0)).doesNotContain(",");
    }

    @Test
    @Order(6)
    @DisplayName("Should use custom NULL value")
    void shouldUseCustomNullValue() throws Exception {
        File outputFile = tempDir.resolve("users-custom-null.csv").toFile();
        
        SqlExportConfig config = SqlExportConfig.builder()
            .jdbcUrl(JDBC_URL)
            .username(USERNAME)
            .password(PASSWORD)
            .query("SELECT * FROM users WHERE id = 4")
            .outputFile(outputFile)
            .nullValue("N/A")
            .includeHeader(true)
            .build();

        SqlCsvExporter exporter = new SqlCsvExporter(config);
        exporter.export();
        
        List<String> lines = Files.readAllLines(outputFile.toPath());
        assertThat(lines.get(1)).contains("N/A");
        assertThat(lines.get(1)).doesNotContain("NULL");
    }

    @Test
    @Order(7)
    @DisplayName("Should handle empty result set")
    void shouldHandleEmptyResultSet() throws Exception {
        File outputFile = tempDir.resolve("empty.csv").toFile();
        
        SqlExportConfig config = SqlExportConfig.builder()
            .jdbcUrl(JDBC_URL)
            .username(USERNAME)
            .password(PASSWORD)
            .query("SELECT * FROM users WHERE id > 1000")
            .outputFile(outputFile)
            .includeHeader(true)
            .build();

        SqlCsvExporter exporter = new SqlCsvExporter(config);
        ExportResult result = exporter.export();

        assertThat(result.rowCount()).isEqualTo(0);
        
        List<String> lines = Files.readAllLines(outputFile.toPath());
        assertThat(lines).hasSize(1); // Only header
    }

    @Test
    @Order(8)
    @DisplayName("Should create output directory if not exists")
    void shouldCreateOutputDirectory() throws Exception {
        File outputFile = tempDir.resolve("subdir/nested/users.csv").toFile();
        
        SqlExportConfig config = SqlExportConfig.builder()
            .jdbcUrl(JDBC_URL)
            .username(USERNAME)
            .password(PASSWORD)
            .query("SELECT * FROM users LIMIT 1")
            .outputFile(outputFile)
            .build();

        SqlCsvExporter exporter = new SqlCsvExporter(config);
        exporter.export();

        assertThat(outputFile).exists();
        assertThat(outputFile.getParentFile()).exists();
    }

    @Test
    @Order(9)
    @DisplayName("Should report timing metrics")
    void shouldReportTimingMetrics() throws Exception {
        File outputFile = tempDir.resolve("metrics.csv").toFile();
        
        SqlExportConfig config = SqlExportConfig.builder()
            .jdbcUrl(JDBC_URL)
            .username(USERNAME)
            .password(PASSWORD)
            .query("SELECT * FROM users")
            .outputFile(outputFile)
            .build();

        SqlCsvExporter exporter = new SqlCsvExporter(config);
        ExportResult result = exporter.export();

        assertThat(result.connectTimeMs()).isGreaterThanOrEqualTo(0);
        assertThat(result.queryTimeMs()).isGreaterThanOrEqualTo(0);
        assertThat(result.writeTimeMs()).isGreaterThanOrEqualTo(0);
        assertThat(result.totalTimeMs()).isGreaterThanOrEqualTo(
            result.connectTimeMs() + result.queryTimeMs() + result.writeTimeMs());
        assertThat(result.throughputRowsPerSec()).isGreaterThan(0);
    }
}
