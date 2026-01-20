package io.pockethive.tools.sqltocsv;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages database connections with pooling for performance.
 * Implements Singleton pattern per JDBC URL.
 */
public class ConnectionManager implements AutoCloseable {
    
    private static final ConcurrentHashMap<String, HikariDataSource> pools = new ConcurrentHashMap<>();
    private final HikariDataSource dataSource;
    
    private ConnectionManager(String jdbcUrl, String username, String password) {
        var config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        if (username != null) {
            config.setUsername(username);
        }
        if (password != null) {
            config.setPassword(password);
        }
        config.setMaximumPoolSize(1); // CLI tool - single connection sufficient
        config.setConnectionTimeout(30000);
        config.setValidationTimeout(5000);
        config.setLeakDetectionThreshold(60000);
        config.setReadOnly(true); // Security: enforce read-only
        
        this.dataSource = new HikariDataSource(config);
    }
    
    public static ConnectionManager getInstance(String jdbcUrl, String username, String password) {
        return new ConnectionManager(jdbcUrl, username, password);
    }
    
    public Connection getConnection() throws Exception {
        return dataSource.getConnection();
    }
    
    @Override
    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }
}
