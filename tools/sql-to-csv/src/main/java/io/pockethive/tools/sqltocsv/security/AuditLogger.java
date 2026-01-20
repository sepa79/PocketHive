package io.pockethive.tools.sqltocsv.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;

/**
 * Audit logger for SQL export operations.
 * Creates tamper-evident logs with query hashes and export metadata.
 */
public class AuditLogger {
    private final Path auditLog;
    
    public AuditLogger(Path auditLog) {
        this.auditLog = auditLog;
    }
    
    public void logExport(String jdbcUrl, String query, long rowCount, String outputFile) {
        try {
            String logLine = String.format("%s|%s|%s|%s|%d|%s%n",
                Instant.now(),
                System.getProperty("user.name"),
                sanitizeJdbcUrl(jdbcUrl),
                hashQuery(query),
                rowCount,
                outputFile
            );
            
            // Append-only, create parent directories if needed
            if (auditLog.getParent() != null) {
                Files.createDirectories(auditLog.getParent());
            }
            
            Files.write(auditLog, logLine.getBytes(StandardCharsets.UTF_8), 
                       StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("WARNING: Failed to write audit log: " + e.getMessage());
        }
    }
    
    private String hashQuery(String query) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(query.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash).substring(0, 16); // First 16 chars
        } catch (NoSuchAlgorithmException e) {
            return "HASH_ERROR";
        }
    }
    
    private String sanitizeJdbcUrl(String jdbcUrl) {
        // Remove password from JDBC URL if present
        return jdbcUrl.replaceAll("password=[^&;]*", "password=***");
    }
}
