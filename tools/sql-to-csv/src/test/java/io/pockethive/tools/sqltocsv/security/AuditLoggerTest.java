package io.pockethive.tools.sqltocsv.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

@DisplayName("AuditLogger Unit Tests")
class AuditLoggerTest {

    @Test
    @DisplayName("Should create audit log file")
    void shouldCreateAuditLogFile(@TempDir Path tempDir) {
        Path auditLog = tempDir.resolve("audit.log");
        AuditLogger logger = new AuditLogger(auditLog);
        
        logger.logExport("jdbc:postgresql://localhost/db", "SELECT * FROM users", 100, "/tmp/output.csv");
        
        assertThat(auditLog).exists();
    }

    @Test
    @DisplayName("Should write audit entry with correct format")
    void shouldWriteAuditEntryWithCorrectFormat(@TempDir Path tempDir) throws Exception {
        Path auditLog = tempDir.resolve("audit.log");
        AuditLogger logger = new AuditLogger(auditLog);
        
        logger.logExport("jdbc:postgresql://localhost/db", "SELECT * FROM users", 100, "/tmp/output.csv");
        
        List<String> lines = Files.readAllLines(auditLog);
        assertThat(lines).hasSize(1);
        
        String entry = lines.get(0);
        String[] parts = entry.split("\\|");
        assertThat(parts).hasSize(7);
        assertThat(parts[0]).matches("\\d{4}-\\d{2}-\\d{2}T.*"); // Timestamp
        assertThat(parts[1]).isNotEmpty(); // Username
        assertThat(parts[2]).contains("jdbc:postgresql://localhost/db"); // JDBC URL
        assertThat(parts[3]).contains("SELECT * FROM users"); // Query
        assertThat(parts[4]).hasSize(16); // Query hash (truncated to 16 chars)
        assertThat(parts[5]).isEqualTo("100"); // Row count
        assertThat(parts[6]).isEqualTo("/tmp/output.csv"); // Output file
    }

    @Test
    @DisplayName("Should append multiple entries")
    void shouldAppendMultipleEntries(@TempDir Path tempDir) throws Exception {
        Path auditLog = tempDir.resolve("audit.log");
        AuditLogger logger = new AuditLogger(auditLog);
        
        logger.logExport("jdbc:postgresql://localhost/db", "SELECT * FROM users", 100, "/tmp/users.csv");
        logger.logExport("jdbc:postgresql://localhost/db", "SELECT * FROM orders", 200, "/tmp/orders.csv");
        
        List<String> lines = Files.readAllLines(auditLog);
        assertThat(lines).hasSize(2);
    }

    @Test
    @DisplayName("Should sanitize password in JDBC URL")
    void shouldSanitizePasswordInJdbcUrl(@TempDir Path tempDir) throws Exception {
        Path auditLog = tempDir.resolve("audit.log");
        AuditLogger logger = new AuditLogger(auditLog);
        
        logger.logExport("jdbc:postgresql://localhost/db?user=admin&password=secret123", 
                        "SELECT * FROM users", 100, "/tmp/output.csv");
        
        String entry = Files.readString(auditLog);
        assertThat(entry).contains("password=***");
        assertThat(entry).doesNotContain("secret123");
    }

    @Test
    @DisplayName("Should create parent directories if needed")
    void shouldCreateParentDirectories(@TempDir Path tempDir) {
        Path auditLog = tempDir.resolve("logs/audit/test.log");
        AuditLogger logger = new AuditLogger(auditLog);
        
        logger.logExport("jdbc:postgresql://localhost/db", "SELECT * FROM users", 100, "/tmp/output.csv");
        
        assertThat(auditLog).exists();
        assertThat(auditLog.getParent()).exists();
    }

    @Test
    @DisplayName("Should hash different queries differently")
    void shouldHashDifferentQueriesDifferently(@TempDir Path tempDir) throws Exception {
        Path auditLog = tempDir.resolve("audit.log");
        AuditLogger logger = new AuditLogger(auditLog);
        
        logger.logExport("jdbc:postgresql://localhost/db", "SELECT * FROM users", 100, "/tmp/users.csv");
        logger.logExport("jdbc:postgresql://localhost/db", "SELECT * FROM orders", 200, "/tmp/orders.csv");
        
        List<String> lines = Files.readAllLines(auditLog);
        String hash1 = lines.get(0).split("\\|")[4];
        String hash2 = lines.get(1).split("\\|")[4];
        
        assertThat(hash1).isNotEqualTo(hash2);
    }

    @Test
    @DisplayName("Should hash same query consistently")
    void shouldHashSameQueryConsistently(@TempDir Path tempDir) throws Exception {
        Path auditLog = tempDir.resolve("audit.log");
        AuditLogger logger = new AuditLogger(auditLog);
        
        String query = "SELECT * FROM users WHERE id = 1";
        logger.logExport("jdbc:postgresql://localhost/db", query, 100, "/tmp/output1.csv");
        logger.logExport("jdbc:postgresql://localhost/db", query, 100, "/tmp/output2.csv");
        
        List<String> lines = Files.readAllLines(auditLog);
        String hash1 = lines.get(0).split("\\|")[4];
        String hash2 = lines.get(1).split("\\|")[4];
        
        assertThat(hash1).isEqualTo(hash2);
    }
}
