package io.pockethive.tools.sqltocsv.security;

import io.pockethive.tools.sqltocsv.ValidationException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Select;

import java.util.List;
import java.util.Locale;

/**
 * Validates SQL queries using proper SQL parsing.
 * Only SELECT queries are allowed; DDL/DML operations are blocked.
 */
public class QueryValidator {
    
    private static final List<String> DANGEROUS_PATTERNS = List.of(
        "EXEC", "EXECUTE", "XP_", "SP_", "DBMS_",     // Stored procedures
        "INTO OUTFILE", "INTO DUMPFILE",              // MySQL file operations
        "LOAD_FILE", "LOAD DATA",                     // File operations
        "WAITFOR DELAY", "BENCHMARK",                 // Time-based attacks
        "SLEEP(", "PG_SLEEP",                         // Sleep functions
        "UTL_FILE", "UTL_HTTP",                       // Oracle file/network
        "OPENROWSET", "OPENDATASOURCE"                // SQL Server external data
    );
    
    private static final int MAX_QUERY_LENGTH = 100_000;
    
    public void validate(String query) {
        if (query == null || query.isBlank()) {
            throw new SecurityException("Query cannot be empty");
        }
        
        if (query.length() > MAX_QUERY_LENGTH) {
            throw new ValidationException("Query exceeds maximum length of " + MAX_QUERY_LENGTH + " characters");
        }
        
        // Check for dangerous patterns first (before parsing, as some may cause parse errors)
        String normalized = query.toUpperCase(Locale.ROOT);
        for (String pattern : DANGEROUS_PATTERNS) {
            if (normalized.contains(pattern)) {
                throw new SecurityException(
                    "Query contains dangerous operations: " + pattern
                );
            }
        }
        
        try {
            Statement statement = CCJSqlParserUtil.parse(query);
            
            if (!(statement instanceof Select)) {
                throw new SecurityException(
                    "Only SELECT queries are allowed (forbidden keywords). Found: " + statement.getClass().getSimpleName()
                );
            }
            
        } catch (SecurityException | ValidationException e) {
            throw e;
        } catch (Exception e) {
            throw new ValidationException("Invalid SQL syntax: " + e.getMessage(), e);
        }
    }
}
