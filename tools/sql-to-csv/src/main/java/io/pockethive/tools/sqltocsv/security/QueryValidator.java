package io.pockethive.tools.sqltocsv.security;

import java.util.regex.Pattern;

/**
 * Validates SQL queries to prevent dangerous operations.
 * Only SELECT queries are allowed; DDL/DML operations are blocked.
 */
public class QueryValidator {
    private static final Pattern SELECT_PATTERN = 
        Pattern.compile("^\\s*SELECT\\s+", Pattern.CASE_INSENSITIVE);
    private static final Pattern DANGEROUS_KEYWORDS = 
        Pattern.compile("\\b(DROP|DELETE|UPDATE|INSERT|ALTER|CREATE|TRUNCATE|EXEC|EXECUTE|GRANT|REVOKE)\\b", 
                       Pattern.CASE_INSENSITIVE);
    
    public void validate(String query) throws SecurityException {
        if (query == null || query.trim().isEmpty()) {
            throw new SecurityException("Query cannot be empty");
        }
        
        if (!SELECT_PATTERN.matcher(query).find()) {
            throw new SecurityException("Only SELECT queries are allowed");
        }
        
        if (DANGEROUS_KEYWORDS.matcher(query).find()) {
            throw new SecurityException("Query contains forbidden keywords (DDL/DML operations not allowed)");
        }
    }
}
