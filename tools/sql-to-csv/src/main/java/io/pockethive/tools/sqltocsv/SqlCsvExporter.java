package io.pockethive.tools.sqltocsv;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.logging.Logger;

/**
 * Exports SQL query results to CSV.
 * Follows Single Responsibility Principle - orchestrates export workflow.
 */
public class SqlCsvExporter {
    
    private static final Logger LOGGER = Logger.getLogger(SqlCsvExporter.class.getName());
    private static final int PROGRESS_INTERVAL = 1000;
    
    private final SqlExportConfig config;
    private final ExportLogger exportLogger;
    private final CsvWriter csvWriter;
    
    public SqlCsvExporter(SqlExportConfig config) {
        this(config, new ConsoleExportLogger(config.verbose()));
    }
    
    SqlCsvExporter(SqlExportConfig config, ExportLogger logger) {
        this.config = Objects.requireNonNull(config, "Config cannot be null");
        this.exportLogger = Objects.requireNonNull(logger, "Logger cannot be null");
        this.csvWriter = new CsvWriter(config);
    }
    
    public ExportResult export() throws ExportException {
        long startTime = System.currentTimeMillis();
        
        try (ConnectionManager connectionManager = ConnectionManager.getInstance(
                config.jdbcUrl(), config.username(), config.password())) {
            
            ensureOutputDirectoryExists();
            
            exportLogger.info("Connecting to database...");
            long connectStart = System.currentTimeMillis();
            
            try (Connection connection = connectionManager.getConnection()) {
                configureConnection(connection);
                long connectTime = System.currentTimeMillis() - connectStart;
                exportLogger.verbose("  Connected in " + connectTime + "ms (read-only mode)");
                
                exportLogger.info("Executing query...");
                long queryStart = System.currentTimeMillis();
                
                try (PreparedStatement statement = prepareStatement(connection)) {
                    try (ResultSet resultSet = statement.executeQuery()) {
                        long queryTime = System.currentTimeMillis() - queryStart;
                        exportLogger.verbose("  Query executed in " + queryTime + "ms");
                        
                        exportLogger.info("Writing rows...");
                        long writeStart = System.currentTimeMillis();
                        
                        int rowCount = csvWriter.write(resultSet, this::logProgress);
                        
                        long writeTime = System.currentTimeMillis() - writeStart;
                        long totalTime = System.currentTimeMillis() - startTime;
                        
                        return new ExportResult(
                            rowCount,
                            resultSet.getMetaData().getColumnCount(),
                            connectTime,
                            queryTime,
                            writeTime,
                            totalTime
                        );
                    }
                }
            } catch (SQLException e) {
                throw new ExportException("Database error: " + e.getMessage(), e);
            } catch (IOException e) {
                throw new ExportException("File write error: " + e.getMessage(), e);
            }
        } catch (ExportException e) {
            throw e;
        } catch (Exception e) {
            throw new ExportException("Unexpected error: " + e.getMessage(), e);
        }
    }
    
    private void configureConnection(Connection connection) throws SQLException {
        connection.setReadOnly(true);
        connection.setTransactionIsolation(Connection.TRANSACTION_READ_UNCOMMITTED);
    }
    
    private PreparedStatement prepareStatement(Connection connection) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(
            config.query(),
            ResultSet.TYPE_FORWARD_ONLY,
            ResultSet.CONCUR_READ_ONLY
        );
        statement.setFetchSize(config.fetchSize());
        exportLogger.verbose("  Fetch size: " + config.fetchSize());
        return statement;
    }
    
    private void logProgress(int rowCount) {
        if (rowCount % PROGRESS_INTERVAL == 0) {
            exportLogger.verbose("  " + rowCount + " rows exported...");
        }
    }
    
    private void ensureOutputDirectoryExists() {
        Path parentDir = config.outputFile().toPath().getParent();
        if (parentDir != null && !Files.exists(parentDir)) {
            try {
                Files.createDirectories(parentDir);
                exportLogger.verbose("Created output directory: " + parentDir);
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to create output directory: " + parentDir, e);
            }
        }
    }
    
    /**
     * Logger abstraction for export progress messages.
     * Allows testing without console output.
     */
    interface ExportLogger {
        void info(String message);
        void verbose(String message);
    }
    
    /**
     * Console-based logger implementation.
     */
    static class ConsoleExportLogger implements ExportLogger {
        private final boolean verbose;
        
        ConsoleExportLogger(boolean verbose) {
            this.verbose = verbose;
        }
        
        @Override
        public void info(String message) {
            System.out.println(message);
        }
        
        @Override
        public void verbose(String message) {
            if (verbose) {
                System.out.println(message);
            }
        }
    }
}
