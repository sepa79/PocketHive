package io.pockethive.tools.sqltocsv.security;

import io.pockethive.tools.sqltocsv.ValidationException;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Validates JDBC URL components to prevent injection attacks.
 * Follows Single Responsibility Principle - only validates, doesn't build.
 */
public class JdbcUrlValidator {
    private static final Pattern VALID_HOST = Pattern.compile("^[a-zA-Z0-9.-]+$");
    private static final Pattern VALID_DATABASE = Pattern.compile("^[a-zA-Z0-9_-]+$");
    private static final Pattern VALID_DB_TYPE = Pattern.compile("^[a-z]+$");
    private static final int MIN_PORT = 1;
    private static final int MAX_PORT = 65535;
    
    public void validateHost(String host) {
        Objects.requireNonNull(host, "Host cannot be null");
        if (host.isBlank()) {
            throw new ValidationException("Host cannot be empty");
        }
        if (!VALID_HOST.matcher(host).matches()) {
            throw new SecurityException("Invalid host format (alphanumeric, dots, hyphens only): " + host);
        }
    }
    
    public void validateDatabase(String database) {
        Objects.requireNonNull(database, "Database cannot be null");
        if (database.isBlank()) {
            throw new ValidationException("Database name cannot be empty");
        }
        if (!VALID_DATABASE.matcher(database).matches()) {
            throw new SecurityException("Invalid database name (alphanumeric, underscore, hyphen only): " + database);
        }
    }
    
    public void validatePort(int port) {
        if (port < MIN_PORT || port > MAX_PORT) {
            throw new ValidationException("Port must be between " + MIN_PORT + " and " + MAX_PORT + ", got: " + port);
        }
    }
    
    public void validateDbType(String dbType) {
        Objects.requireNonNull(dbType, "Database type cannot be null");
        if (dbType.isBlank()) {
            throw new ValidationException("Database type cannot be empty");
        }
        if (!VALID_DB_TYPE.matcher(dbType).matches()) {
            throw new SecurityException("Invalid database type (lowercase letters only): " + dbType);
        }
    }
    
    public void validateUsername(String username) {
        Objects.requireNonNull(username, "Username cannot be null");
        if (username.isBlank()) {
            throw new ValidationException("Username cannot be empty");
        }
    }
    
    /**
     * Validates a complete JDBC URL string.
     */
    public void validateJdbcUrl(String jdbcUrl) {
        Objects.requireNonNull(jdbcUrl, "JDBC URL cannot be null");
        if (!jdbcUrl.startsWith("jdbc:")) {
            throw new ValidationException("Invalid JDBC URL. Must start with 'jdbc:'");
        }
    }
}
