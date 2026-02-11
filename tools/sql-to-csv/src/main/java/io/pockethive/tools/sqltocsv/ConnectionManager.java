package io.pockethive.tools.sqltocsv;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

/**
 * Manages database connections with pooling for performance.
 * Thread-safe: HikariCP handles concurrency internally.
 * Creates a new pool per export operation (CLI tool pattern).
 */
public class ConnectionManager implements AutoCloseable {
    
    private static final int DEFAULT_POOL_SIZE = 1;
    private static final int CONNECTION_TIMEOUT_MS = 30_000;
    private static final int VALIDATION_TIMEOUT_MS = 5_000;
    private static final int LEAK_DETECTION_THRESHOLD_MS = 60_000;
    
    private final HikariDataSource dataSource;
    
    private ConnectionManager(String jdbcUrl, String username, String password) {
        Objects.requireNonNull(jdbcUrl, "JDBC URL cannot be null");
        
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        if (username != null && !username.isEmpty()) {
            config.setUsername(username);
        }
        if (password != null && !password.isEmpty()) {
            config.setPassword(password);
        }
        config.setMaximumPoolSize(DEFAULT_POOL_SIZE);
        config.setConnectionTimeout(CONNECTION_TIMEOUT_MS);
        config.setValidationTimeout(VALIDATION_TIMEOUT_MS);
        config.setLeakDetectionThreshold(LEAK_DETECTION_THRESHOLD_MS);
        config.setReadOnly(true);
        config.setAutoCommit(false);
        
        this.dataSource = new HikariDataSource(config);
    }
    
    public static ConnectionManager getInstance(String jdbcUrl, String username, String password) {
        return new ConnectionManager(jdbcUrl, username, password);
    }
    
    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }
    
    @Override
    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }
}
