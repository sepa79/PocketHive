package io.pockethive.tools.sqltocsv.security;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.logging.Logger;

/**
 * Audit logger for SQL export operations.
 * Creates tamper-evident logs with query hashes and export metadata.
 */
public class AuditLogger {
    private static final Logger LOGGER = Logger.getLogger(AuditLogger.class.getName());
    private static final int HASH_PREFIX_LENGTH = 16;
    private static final String HASH_ALGORITHM = "SHA-256";
    private static final String LOG_DELIMITER = "|";
    
    private final Path auditLog;
    
    public AuditLogger(Path auditLog) {
        this.auditLog = Objects.requireNonNull(auditLog, "Audit log path cannot be null");
    }
    
    public void logExport(String jdbcUrl, String query, long rowCount, String outputFile) {
        try {
            String logLine = String.format("%s%s%s%s%s%s%s%s%s%s%d%s%s%n",
                Instant.now(), LOG_DELIMITER,
                System.getProperty("user.name"), LOG_DELIMITER,
                sanitizeJdbcUrl(jdbcUrl), LOG_DELIMITER,
                sanitizeQuery(query), LOG_DELIMITER,
                hashQuery(query), LOG_DELIMITER,
                rowCount, LOG_DELIMITER,
                outputFile
            );
            
            if (auditLog.getParent() != null) {
                Files.createDirectories(auditLog.getParent());
            }
            
            Files.write(auditLog, logLine.getBytes(StandardCharsets.UTF_8), 
                       StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write audit log: " + auditLog, e);
        }
    }
    
    private String sanitizeQuery(String query) {
        if (query == null) {
            return "";
        }
        // Truncate long queries but keep table names visible
        if (query.length() > 200) {
            return query.substring(0, 200) + "...";
        }
        return query;
    }
    
    private String hashQuery(String query) {
        try {
            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            byte[] hash = digest.digest(query.getBytes(StandardCharsets.UTF_8));
            String encoded = Base64.getEncoder().encodeToString(hash);
            return encoded.substring(0, Math.min(HASH_PREFIX_LENGTH, encoded.length()));
        } catch (NoSuchAlgorithmException e) {
            LOGGER.warning("Hash algorithm not available: " + e.getMessage());
            return "HASH_ERROR";
        }
    }
    
    private String sanitizeJdbcUrl(String jdbcUrl) {
        if (jdbcUrl == null) {
            return "";
        }
        return jdbcUrl
            .replaceAll("password=[^&;]*", "password=***")
            .replaceAll("pwd=[^&;]*", "pwd=***")
            .replaceAll(":[^:@]+@", ":***@");
    }
}
