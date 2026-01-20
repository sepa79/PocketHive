package io.pockethive.tools.sqltocsv.security;

import java.util.regex.Pattern;

/**
 * Validates and builds secure JDBC URLs to prevent injection attacks.
 */
public class JdbcUrlValidator {
    private static final Pattern VALID_HOST = Pattern.compile("^[a-zA-Z0-9.-]+$");
    private static final Pattern VALID_DATABASE = Pattern.compile("^[a-zA-Z0-9_-]+$");
    
    public String buildSecureUrl(String dbType, String host, int port, String database, String username) {
        if (!VALID_HOST.matcher(host).matches()) {
            throw new SecurityException("Invalid host format (alphanumeric, dots, hyphens only): " + host);
        }
        
        if (!VALID_DATABASE.matcher(database).matches()) {
            throw new SecurityException("Invalid database name (alphanumeric, underscore, hyphen only): " + database);
        }
        
        if (port < 1 || port > 65535) {
            throw new SecurityException("Invalid port (must be 1-65535): " + port);
        }
        
        // Build URL with safe parameters only
        return String.format("jdbc:%s://%s:%d/%s?user=%s&ApplicationName=sql-to-csv", 
                           dbType, host, port, database, username);
    }
}
