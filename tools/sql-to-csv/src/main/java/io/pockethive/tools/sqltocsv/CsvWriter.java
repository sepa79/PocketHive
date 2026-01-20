package io.pockethive.tools.sqltocsv;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Writes ResultSet data to CSV files.
 * Follows Single Responsibility Principle - only handles CSV writing.
 */
class CsvWriter {
    
    private final SqlExportConfig config;
    
    CsvWriter(SqlExportConfig config) {
        this.config = config;
    }
    
    int write(ResultSet resultSet, ProgressCallback callback) throws SQLException, IOException {
        CSVFormat csvFormat = CSVFormat.DEFAULT
            .builder()
            .setDelimiter(config.delimiter())
            .setNullString(config.nullValue())
            .build();
        
        Path outputPath = config.outputFile().toPath();
        try (BufferedWriter writer = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8);
             CSVPrinter csvPrinter = new CSVPrinter(writer, csvFormat)) {
            
            int columnCount = resultSet.getMetaData().getColumnCount();
            
            if (config.includeHeader()) {
                writeHeader(csvPrinter, resultSet, columnCount);
            }
            
            return writeRows(csvPrinter, resultSet, columnCount, callback);
        }
    }
    
    private void writeHeader(CSVPrinter csvPrinter, ResultSet resultSet, int columnCount) 
            throws SQLException, IOException {
        for (int i = 1; i <= columnCount; i++) {
            csvPrinter.print(resultSet.getMetaData().getColumnName(i));
        }
        csvPrinter.println();
    }
    
    private int writeRows(CSVPrinter csvPrinter, ResultSet resultSet, int columnCount, ProgressCallback callback) 
            throws SQLException, IOException {
        int rowCount = 0;
        while (resultSet.next()) {
            for (int i = 1; i <= columnCount; i++) {
                csvPrinter.print(resultSet.getObject(i));
            }
            csvPrinter.println();
            rowCount++;
            
            if (callback != null) {
                callback.onProgress(rowCount);
            }
        }
        return rowCount;
    }
    
    @FunctionalInterface
    interface ProgressCallback {
        void onProgress(int rowCount);
    }
}
