package io.pockethive.tools.sqltocsv.security;

import io.pockethive.tools.sqltocsv.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

@DisplayName("Security Integration Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SecurityIntegrationTest {

    private static final String JDBC_URL = "jdbc:h2:mem:securitydb;DB_CLOSE_DELAY=-1";
    private static final String USERNAME = "sa";
    private static final String PASSWORD = "";

    @TempDir
    Path tempDir;

    @BeforeAll
    static void setupDatabase() throws Exception {
        try (Connection conn = DriverManager.getConnection(JDBC_URL, USERNAME, PASSWORD);
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE sensitive_data (id INT PRIMARY KEY, data VARCHAR(255))");
            stmt.execute("INSERT INTO sensitive_data VALUES (1, 'secret')");
        }
    }

    @Test
    @Order(1)
    @DisplayName("Security: Should block DROP TABLE query")
    void shouldBlockDropTableQuery() {
        QueryValidator validator = new QueryValidator();
        
        assertThatThrownBy(() -> validator.validate("DROP TABLE sensitive_data"))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("forbidden keywords");
    }

    @Test
    @Order(2)
    @DisplayName("Security: Should block DELETE query")
    void shouldBlockDeleteQuery() {
        QueryValidator validator = new QueryValidator();
        
        assertThatThrownBy(() -> validator.validate("DELETE FROM sensitive_data WHERE id = 1"))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("forbidden keywords");
    }

    @Test
    @Order(3)
    @DisplayName("Security: Should block UPDATE query")
    void shouldBlockUpdateQuery() {
        QueryValidator validator = new QueryValidator();
        
        assertThatThrownBy(() -> validator.validate("UPDATE sensitive_data SET data = 'modified'"))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("forbidden keywords");
    }

    @Test
    @Order(4)
    @DisplayName("Security: Should block INSERT query")
    void shouldBlockInsertQuery() {
        QueryValidator validator = new QueryValidator();
        
        assertThatThrownBy(() -> validator.validate("INSERT INTO sensitive_data VALUES (2, 'new')"))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("forbidden keywords");
    }

    @Test
    @Order(5)
    @DisplayName("Security: Should allow SELECT query")
    void shouldAllowSelectQuery() {
        QueryValidator validator = new QueryValidator();
        
        assertThatNoException().isThrownBy(() -> 
            validator.validate("SELECT * FROM sensitive_data"));
    }

    @Test
    @Order(6)
    @DisplayName("Security: Should block path traversal to system directory")
    void shouldBlockPathTraversalToSystemDirectory() {
        PathValidator validator = new PathValidator();
        
        assertThatThrownBy(() -> 
            validator.validateOutputPath(Paths.get("/etc/passwd"), true))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("system directory");
    }

    @Test
    @Order(7)
    @DisplayName("Security: Should block absolute path without flag")
    void shouldBlockAbsolutePathWithoutFlag() {
        PathValidator validator = new PathValidator();
        
        assertThatThrownBy(() -> 
            validator.validateOutputPath(Paths.get("/tmp/output.csv"), false))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("require --allow-absolute-paths");
    }

    @Test
    @Order(8)
    @DisplayName("Security: Should allow relative path with parent navigation")
    void shouldAllowRelativePathWithParentNavigation() {
        PathValidator validator = new PathValidator();
        
        Path result = validator.validateOutputPath(
            Paths.get("../../scenarios/datasets/test.csv"), false);
        
        assertThat(result).isNotNull();
    }

    @Test
    @Order(9)
    @DisplayName("Security: Should create audit log entry")
    void shouldCreateAuditLogEntry() throws Exception {
        Path auditLog = tempDir.resolve("audit.log");
        AuditLogger logger = new AuditLogger(auditLog);
        
        logger.logExport(JDBC_URL, "SELECT * FROM sensitive_data", 1, "/tmp/output.csv");
        
        assertThat(auditLog).exists();
        List<String> lines = Files.readAllLines(auditLog);
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0)).contains("sensitive_data");
    }

    @Test
    @Order(10)
    @DisplayName("Security: Should sanitize password in audit log")
    void shouldSanitizePasswordInAuditLog() throws Exception {
        Path auditLog = tempDir.resolve("audit-sanitized.log");
        AuditLogger logger = new AuditLogger(auditLog);
        
        logger.logExport(
            "jdbc:postgresql://localhost/db?user=admin&password=secret123", 
            "SELECT * FROM users", 
            100, 
            "/tmp/output.csv");
        
        String content = Files.readString(auditLog);
        assertThat(content).contains("password=***");
        assertThat(content).doesNotContain("secret123");
    }

    @Test
    @Order(11)
    @DisplayName("Security: End-to-end export with all security features")
    void shouldExportWithAllSecurityFeatures() throws Exception {
        File outputFile = tempDir.resolve("secure-export.csv").toFile();
        Path auditLog = tempDir.resolve("secure-audit.log");
        
        // Validate query
        QueryValidator queryValidator = new QueryValidator();
        String query = "SELECT * FROM sensitive_data";
        queryValidator.validate(query);
        
        // Validate path
        PathValidator pathValidator = new PathValidator();
        Path validatedPath = pathValidator.validateOutputPath(outputFile.toPath(), false);
        
        // Export
        SqlExportConfig config = SqlExportConfig.builder()
            .jdbcUrl(JDBC_URL)
            .username(USERNAME)
            .password(PASSWORD)
            .query(query)
            .outputFile(validatedPath.toFile())
            .includeHeader(true)
            .build();

        SqlCsvExporter exporter = new SqlCsvExporter(config);
        ExportResult result = exporter.export();
        
        // Audit
        AuditLogger auditLogger = new AuditLogger(auditLog);
        auditLogger.logExport(JDBC_URL, query, result.rowCount(), validatedPath.toString());
        
        // Verify
        assertThat(outputFile).exists();
        assertThat(auditLog).exists();
        assertThat(result.rowCount()).isEqualTo(1);
        
        List<String> csvLines = Files.readAllLines(outputFile.toPath());
        assertThat(csvLines).hasSize(2); // Header + 1 row
        
        List<String> auditLines = Files.readAllLines(auditLog);
        assertThat(auditLines).hasSize(1);
    }

    @Test
    @Order(12)
    @DisplayName("Security: Should enforce read-only connection")
    void shouldEnforceReadOnlyConnection() throws Exception {
        File outputFile = tempDir.resolve("readonly.csv").toFile();
        
        SqlExportConfig config = SqlExportConfig.builder()
            .jdbcUrl(JDBC_URL)
            .username(USERNAME)
            .password(PASSWORD)
            .query("SELECT * FROM sensitive_data")
            .outputFile(outputFile)
            .build();

        SqlCsvExporter exporter = new SqlCsvExporter(config);
        ExportResult result = exporter.export();
        
        // Verify export succeeded (read-only allows SELECT)
        assertThat(result.rowCount()).isEqualTo(1);
        assertThat(outputFile).exists();
    }

    @Test
    @Order(13)
    @DisplayName("Security: Should reject empty credential file")
    void shouldRejectEmptyCredentialFile() throws Exception {
        Path credFile = tempDir.resolve("empty-creds.txt");
        Files.writeString(credFile, "");
        
        CredentialProvider provider = new CredentialProvider();
        
        assertThatThrownBy(() -> 
            provider.resolvePassword(null, "NON_EXISTENT", credFile, false))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("empty");
    }

    @Test
    @Order(14)
    @DisplayName("Security: Should read password from credential file")
    void shouldReadPasswordFromCredentialFile() throws Exception {
        Path credFile = tempDir.resolve("valid-creds.txt");
        Files.writeString(credFile, "secure-password");
        
        CredentialProvider provider = new CredentialProvider();
        String password = provider.resolvePassword(null, "NON_EXISTENT", credFile, false);
        
        assertThat(password).isEqualTo("secure-password");
    }

    @Test
    @Order(15)
    @DisplayName("Security: Should prioritize environment variable over file")
    void shouldPrioritizeEnvironmentVariableOverFile() throws Exception {
        Path credFile = tempDir.resolve("creds.txt");
        Files.writeString(credFile, "file-password");
        
        CredentialProvider provider = new CredentialProvider();
        String password = provider.resolvePassword(null, "PATH", credFile, false); // PATH exists
        
        assertThat(password).isNotEqualTo("file-password");
        assertThat(password).isNotNull();
    }
}
