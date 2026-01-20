package io.pockethive.tools.sqltocsv;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;

/**
 * Handles CSV formatting logic.
 * Follows Single Responsibility Principle - only handles CSV writing.
 */
public class CsvWriter {
    
    private final String delimiter;
    private final String nullValue;
    
    public CsvWriter(String delimiter, String nullValue) {
        this.delimiter = delimiter;
        this.nullValue = nullValue;
    }
    
    public String writeHeader(ResultSetMetaData metadata) throws Exception {
        var builder = new StringBuilder();
        var columnCount = metadata.getColumnCount();
        
        for (int i = 1; i <= columnCount; i++) {
            builder.append(escapeValue(metadata.getColumnName(i)));
            if (i < columnCount) {
                builder.append(delimiter);
            }
        }
        
        return builder.toString();
    }
    
    public String writeRow(ResultSet resultSet, int columnCount) throws Exception {
        var builder = new StringBuilder();
        
        for (int i = 1; i <= columnCount; i++) {
            var value = resultSet.getObject(i);
            builder.append(formatValue(value));
            if (i < columnCount) {
                builder.append(delimiter);
            }
        }
        
        return builder.toString();
    }
    
    private String formatValue(Object value) {
        if (value == null) {
            return nullValue;
        }
        return escapeValue(value.toString());
    }
    
    private String escapeValue(String value) {
        if (value == null) {
            return nullValue;
        }
        
        // Escape if contains delimiter, quotes, or newlines
        if (value.contains(delimiter) || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        
        return value;
    }
}
