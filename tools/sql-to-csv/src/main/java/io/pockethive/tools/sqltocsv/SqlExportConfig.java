package io.pockethive.tools.sqltocsv;

import java.io.File;
import java.util.Objects;

/**
 * Immutable configuration for SQL export operations with validation.
 * Thread-safe: All fields are final and immutable.
 */
public record SqlExportConfig(
    String jdbcUrl,
    String username,
    String password,
    String query,
    File outputFile,
    String delimiter,
    boolean includeHeader,
    String nullValue,
    boolean verbose,
    int fetchSize,
    int bufferSizeKb
) {
    
    public static final String DEFAULT_DELIMITER = ",";
    public static final String DEFAULT_NULL_VALUE = "";
    public static final int DEFAULT_FETCH_SIZE = 1000;
    public static final int DEFAULT_BUFFER_SIZE_KB = 64;
    public static final int MIN_FETCH_SIZE = 1;
    public static final int MAX_FETCH_SIZE = 100000;
    public static final int MIN_BUFFER_SIZE_KB = 1;
    public static final int MAX_BUFFER_SIZE_KB = 1024;
    
    public SqlExportConfig {
        Objects.requireNonNull(jdbcUrl, "JDBC URL cannot be null");
        Objects.requireNonNull(query, "Query cannot be null");
        Objects.requireNonNull(outputFile, "Output file cannot be null");
        Objects.requireNonNull(delimiter, "Delimiter cannot be null");
        Objects.requireNonNull(nullValue, "Null value cannot be null");
        
        if (jdbcUrl.isBlank()) {
            throw new IllegalArgumentException("JDBC URL cannot be blank");
        }
        if (query.isBlank()) {
            throw new IllegalArgumentException("Query cannot be blank");
        }
        if (delimiter.isEmpty()) {
            throw new IllegalArgumentException("Delimiter cannot be empty");
        }
        if (fetchSize < MIN_FETCH_SIZE || fetchSize > MAX_FETCH_SIZE) {
            throw new IllegalArgumentException(
                "Fetch size must be between " + MIN_FETCH_SIZE + " and " + MAX_FETCH_SIZE + ", got: " + fetchSize
            );
        }
        if (bufferSizeKb < MIN_BUFFER_SIZE_KB || bufferSizeKb > MAX_BUFFER_SIZE_KB) {
            throw new IllegalArgumentException(
                "Buffer size must be between " + MIN_BUFFER_SIZE_KB + " and " + MAX_BUFFER_SIZE_KB + " KB, got: " + bufferSizeKb
            );
        }
    }
    
    public static Builder builder() {
        return new Builder();
    }
    
    public static class Builder {
        private String jdbcUrl;
        private String username;
        private String password;
        private String query;
        private File outputFile;
        private String delimiter = DEFAULT_DELIMITER;
        private boolean includeHeader = true;
        private String nullValue = DEFAULT_NULL_VALUE;
        private boolean verbose = false;
        private int fetchSize = DEFAULT_FETCH_SIZE;
        private int bufferSizeKb = DEFAULT_BUFFER_SIZE_KB;
        
        public Builder jdbcUrl(String jdbcUrl) {
            this.jdbcUrl = jdbcUrl;
            return this;
        }
        
        public Builder username(String username) {
            this.username = username;
            return this;
        }
        
        public Builder password(String password) {
            this.password = password;
            return this;
        }
        
        public Builder query(String query) {
            this.query = query;
            return this;
        }
        
        public Builder outputFile(File outputFile) {
            this.outputFile = outputFile;
            return this;
        }
        
        public Builder delimiter(String delimiter) {
            this.delimiter = delimiter;
            return this;
        }
        
        public Builder includeHeader(boolean includeHeader) {
            this.includeHeader = includeHeader;
            return this;
        }
        
        public Builder nullValue(String nullValue) {
            this.nullValue = nullValue;
            return this;
        }
        
        public Builder verbose(boolean verbose) {
            this.verbose = verbose;
            return this;
        }
        
        public Builder fetchSize(int fetchSize) {
            this.fetchSize = fetchSize;
            return this;
        }
        
        public Builder bufferSizeKb(int bufferSizeKb) {
            this.bufferSizeKb = bufferSizeKb;
            return this;
        }
        
        public SqlExportConfig build() {
            return new SqlExportConfig(
                jdbcUrl,
                username,
                password,
                query,
                outputFile,
                delimiter,
                includeHeader,
                nullValue,
                verbose,
                fetchSize,
                bufferSizeKb
            );
        }
    }
}
