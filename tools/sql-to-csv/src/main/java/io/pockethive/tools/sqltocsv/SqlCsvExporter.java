package io.pockethive.tools.sqltocsv;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;

/**
 * Exports SQL query results to CSV format.
 * Follows Single Responsibility Principle - only handles export logic.
 */
public class SqlCsvExporter {
    
    private final SqlExportConfig config;
    private final CsvWriter csvWriter;
    
    public SqlCsvExporter(SqlExportConfig config) {
        this.config = config;
        this.csvWriter = new CsvWriter(config.delimiter(), config.nullValue());
    }
    
    public ExportResult export() throws Exception {
        var startTime = System.currentTimeMillis();
        
        try (var connectionManager = ConnectionManager.getInstance(config.jdbcUrl(), config.username(), config.password())) {
            ensureOutputDirectoryExists();
            
            info("Connecting to database...");
            var connectStart = System.currentTimeMillis();
            
            try (var connection = connectionManager.getConnection()) {
                // Security: Force read-only mode
                connection.setReadOnly(true);
                connection.setTransactionIsolation(Connection.TRANSACTION_READ_UNCOMMITTED);
                
                var connectTime = System.currentTimeMillis() - connectStart;
                verbose("  Connected in " + connectTime + "ms (read-only mode)");
                
                info("Executing query...");
                var queryStart = System.currentTimeMillis();
                
                try (var statement = connection.prepareStatement(
                        config.query(),
                        ResultSet.TYPE_FORWARD_ONLY,
                        ResultSet.CONCUR_READ_ONLY)) {
                    
                    // Enable streaming for large result sets
                    statement.setFetchSize(config.fetchSize());
                    verbose("  Fetch size: " + config.fetchSize());
                    
                    try (var resultSet = statement.executeQuery()) {
                    
                    var queryTime = System.currentTimeMillis() - queryStart;
                    verbose("  Query executed in " + queryTime + "ms");
                    
                    info("Writing rows...");
                    var writeStart = System.currentTimeMillis();
                    
                    try (var writer = new BufferedWriter(
                            new FileWriter(config.outputFile()),
                            config.bufferSizeKb() * 1024)) {
                        var rowCount = 0;
                        var metadata = resultSet.getMetaData();
                        var columnCount = metadata.getColumnCount();
                        
                        if (config.includeHeader()) {
                            writer.write(csvWriter.writeHeader(metadata));
                            writer.newLine();
                        }
                        
                        while (resultSet.next()) {
                            writer.write(csvWriter.writeRow(resultSet, columnCount));
                            writer.newLine();
                            rowCount++;
                            
                            if (rowCount % 1000 == 0) {
                                verbose("  " + rowCount + " rows exported...");
                            }
                        }
                        
                        var writeTime = System.currentTimeMillis() - writeStart;
                        var totalTime = System.currentTimeMillis() - startTime;
                        
                        return new ExportResult(rowCount, columnCount, connectTime, queryTime, writeTime, totalTime);
                    }
                }
            } catch (java.sql.SQLException e) {
                throw new ExportException("Database error: " + e.getMessage(), e);
            } catch (java.io.IOException e) {
                throw new ExportException("File write error: " + e.getMessage(), e);
            }
        } catch (ExportException e) {
            throw e;
        } catch (Exception e) {
            throw new ExportException("Unexpected error: " + e.getMessage(), e);
        }
    }
    
    private void ensureOutputDirectoryExists() {
        var parentDir = config.outputFile().getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            if (!parentDir.mkdirs()) {
                throw new ValidationException("Failed to create output directory: " + parentDir);
            }
            verbose("Created output directory: " + parentDir);
        }
    }
    
    private void info(String message) {
        System.out.println(message);
    }
    
    private void verbose(String message) {
        if (config.verbose()) {
            System.out.println(message);
        }
    }
}
