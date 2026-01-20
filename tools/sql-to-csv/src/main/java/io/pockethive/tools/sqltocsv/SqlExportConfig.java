package io.pockethive.tools.sqltocsv;

import java.io.File;

/**
 * Immutable configuration for SQL export operations.
 * Uses builder pattern for flexible construction.
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
    
    public static Builder builder() {
        return new Builder();
    }
    
    public static class Builder {
        private String jdbcUrl;
        private String username;
        private String password;
        private String query;
        private File outputFile;
        private String delimiter = ",";
        private boolean includeHeader = true;
        private String nullValue = "";
        private boolean verbose = false;
        private int fetchSize = 1000;
        private int bufferSizeKb = 64;
        
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
