package io.pockethive.tools.sqltocsv;

import io.pockethive.tools.sqltocsv.security.*;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Callable;

/**
 * SQL to CSV Command-Line Tool
 * 
 * Exports SQL query results to CSV format for PocketHive scenario datasets.
 * Supports PostgreSQL, MySQL, SQL Server, and Oracle databases.
 */
@Command(
    name = "sql-to-csv",
    version = "1.0.0",
    description = "Export SQL query results to CSV for PocketHive scenario datasets",
    mixinStandardHelpOptions = true,
    headerHeading = "%n@|bold,underline PocketHive SQL-to-CSV Tool|@%n%n",
    descriptionHeading = "%n@|bold Description:|@%n",
    parameterListHeading = "%n@|bold Parameters:|@%n",
    optionListHeading = "%n@|bold Options:|@%n",
    footerHeading = "%n@|bold Examples:|@%n",
    footer = {
        "",
        "  PostgreSQL:",
        "    sql-to-csv -u jdbc:postgresql://localhost:5432/db -U admin -P secret \\",
        "      -q \"SELECT * FROM accounts\" -o datasets/accounts.csv",
        "",
        "  MySQL:",
        "    sql-to-csv -u jdbc:mysql://localhost:3306/db -U root -P secret \\",
        "      -q \"SELECT * FROM users LIMIT 1000\" -o datasets/users.csv",
        "",
        "  With query file:",
        "    sql-to-csv -u jdbc:postgresql://localhost/db -U admin -P secret \\",
        "      -f query.sql -o output.csv",
        "",
        "  Skip header row:",
        "    sql-to-csv -u jdbc:postgresql://localhost/db -U admin -P secret \\",
        "      -q \"SELECT * FROM data\" -o data.csv --no-header",
        ""
    }
)
public class SqlToCsvCommand implements Callable<Integer> {

    @Option(
        names = {"-u", "--jdbc-url"},
        description = "JDBC connection URL (e.g., jdbc:postgresql://host:5432/database)",
        required = true
    )
    private String jdbcUrl;

    @Option(
        names = {"-U", "--username"},
        description = "Database username"
    )
    private String username;

    @Option(
        names = {"-P", "--password"},
        description = "Database password (INSECURE - use --password-stdin, --password-file, or env vars instead)",
        interactive = true,
        arity = "0..1"
    )
    private String password;

    @Option(
        names = {"--password-stdin"},
        description = "Read password from stdin (secure for scripts)"
    )
    private boolean passwordStdin;

    @Option(
        names = {"--password-file"},
        description = "Read password from file (must have 0600 permissions on Unix)"
    )
    private Path passwordFile;

    @Option(
        names = {"--password-env"},
        description = "Environment variable name containing password (default: ${DEFAULT-VALUE})",
        defaultValue = "SQL_TO_CSV_PASSWORD"
    )
    private String passwordEnv;

    @Option(
        names = {"-q", "--query"},
        description = "SQL query to execute"
    )
    private String query;

    @Option(
        names = {"-f", "--query-file"},
        description = "File containing SQL query"
    )
    private File queryFile;

    @Option(
        names = {"-o", "--output"},
        description = "Output CSV file path",
        required = true
    )
    private File outputFile;

    @Option(
        names = {"-d", "--delimiter"},
        description = "CSV delimiter (default: ${DEFAULT-VALUE})",
        defaultValue = ","
    )
    private String delimiter;

    @Option(
        names = {"--no-header"},
        description = "Skip CSV header row"
    )
    private boolean noHeader;

    @Option(
        names = {"--null-value"},
        description = "String to use for NULL values (default: empty string)",
        defaultValue = ""
    )
    private String nullValue;

    @Option(
        names = {"--fetch-size"},
        description = "JDBC fetch size for streaming large result sets (default: ${DEFAULT-VALUE})",
        defaultValue = "1000"
    )
    private int fetchSize;

    @Option(
        names = {"--buffer-size"},
        description = "File write buffer size in KB (default: ${DEFAULT-VALUE})",
        defaultValue = "64"
    )
    private int bufferSizeKb;

    @Option(
        names = {"--allow-absolute-paths"},
        description = "Allow writing to absolute paths outside current directory"
    )
    private boolean allowAbsolutePaths;

    @Option(
        names = {"--audit-log"},
        description = "Path to audit log file (default: ${DEFAULT-VALUE})",
        defaultValue = "${user.home}/.sql-to-csv-audit.log"
    )
    private String auditLogPath;

    @Option(
        names = {"--no-audit"},
        description = "Disable audit logging (not recommended)"
    )
    private boolean noAudit;

    @Option(
        names = {"-v", "--verbose"},
        description = "Enable verbose output"
    )
    private boolean verbose;

    @Override
    public Integer call() {
        try {
            validateInputs();
            
            // Security: Validate query
            String resolvedQuery = resolveQuery();
            new QueryValidator().validate(resolvedQuery);
            
            // Security: Resolve password securely
            CredentialProvider credProvider = new CredentialProvider();
            String resolvedPassword = credProvider.resolvePassword(password, passwordEnv, passwordFile, passwordStdin);
            
            // Security: Validate output path
            PathValidator pathValidator = new PathValidator();
            Path validatedOutputPath = pathValidator.validateOutputPath(outputFile.toPath(), allowAbsolutePaths);
            
            var config = SqlExportConfig.builder()
                .jdbcUrl(jdbcUrl)
                .username(username)
                .password(resolvedPassword)
                .query(resolvedQuery)
                .outputFile(validatedOutputPath.toFile())
                .delimiter(delimiter)
                .includeHeader(!noHeader)
                .nullValue(nullValue)
                .verbose(verbose)
                .fetchSize(fetchSize)
                .bufferSizeKb(bufferSizeKb)
                .build();

            var exporter = new SqlCsvExporter(config);
            var result = exporter.export();
            
            // Security: Audit log
            if (!noAudit) {
                try {
                    AuditLogger auditLogger = new AuditLogger(Paths.get(auditLogPath));
                    auditLogger.logExport(jdbcUrl, resolvedQuery, result.rowCount(), validatedOutputPath.toString());
                } catch (Exception e) {
                    System.err.println("WARNING: Audit logging failed: " + e.getMessage());
                }
            }

            System.out.println("✓ Export completed successfully");
            System.out.println("  Rows exported: " + result.rowCount());
            System.out.println("  Columns: " + result.columnCount());
            System.out.println("  Output file: " + validatedOutputPath.toAbsolutePath());
            System.out.println("  Total time: " + formatTime(result.totalTimeMs()));
            System.out.println("  Throughput: " + String.format("%.1f", result.throughputRowsPerSec()) + " rows/sec");
            
            if (verbose) {
                System.out.println("\nTiming breakdown:");
                System.out.println("  Connection: " + formatTime(result.connectTimeMs()));
                System.out.println("  Query execution: " + formatTime(result.queryTimeMs()));
                System.out.println("  File writing: " + formatTime(result.writeTimeMs()));
            }
            
            return 0;
            
        } catch (ValidationException e) {
            System.err.println("✗ Validation error: " + e.getMessage());
            return 1;
        } catch (SecurityException e) {
            System.err.println("✗ Security error: " + e.getMessage());
            return 1;
        } catch (ExportException e) {
            System.err.println("✗ Export failed: " + e.getMessage());
            if (verbose) {
                System.err.println("\nDetails:");
                e.printStackTrace();
            } else {
                System.err.println("(Use -v for detailed error information)");
            }
            return 2;
        } catch (Exception e) {
            System.err.println("✗ Unexpected error: " + e.getMessage());
            if (verbose) {
                e.printStackTrace();
            }
            return 3;
        }
    }

    private void validateInputs() {
        // Query validation
        if (query == null && queryFile == null) {
            throw new ValidationException("Either --query or --query-file must be specified");
        }
        if (query != null && queryFile != null) {
            throw new ValidationException("Cannot specify both --query and --query-file");
        }
        if (queryFile != null && !queryFile.exists()) {
            throw new ValidationException("Query file not found: " + queryFile.getAbsolutePath());
        }
        if (queryFile != null && !queryFile.canRead()) {
            throw new ValidationException("Cannot read query file: " + queryFile.getAbsolutePath());
        }
        if (query != null && query.trim().isEmpty()) {
            throw new ValidationException("Query cannot be empty");
        }
        
        // JDBC URL validation
        if (!jdbcUrl.startsWith("jdbc:")) {
            throw new ValidationException("Invalid JDBC URL. Must start with 'jdbc:' (e.g., jdbc:postgresql://host:5432/db)");
        }
        
        // Output file validation
        if (outputFile.exists() && outputFile.isDirectory()) {
            throw new ValidationException("Output path is a directory: " + outputFile.getAbsolutePath());
        }
        var parentDir = outputFile.getParentFile();
        if (parentDir != null && parentDir.exists() && !parentDir.canWrite()) {
            throw new ValidationException("Cannot write to output directory: " + parentDir.getAbsolutePath());
        }
        
        // Delimiter validation
        if (delimiter == null || delimiter.isEmpty()) {
            throw new ValidationException("Delimiter cannot be empty");
        }
        
        // Performance parameter validation
        if (fetchSize < 1) {
            throw new ValidationException("Fetch size must be positive (got: " + fetchSize + ")");
        }
        if (bufferSizeKb < 1) {
            throw new ValidationException("Buffer size must be positive (got: " + bufferSizeKb + ")");
        }
    }

    private String buildJdbcUrl() {
        // Deprecated - credentials now handled separately
        return jdbcUrl;
    }

    private String resolveQuery() throws Exception {
        if (query != null) {
            return query;
        }
        return java.nio.file.Files.readString(queryFile.toPath());
    }
    
    private String formatTime(long ms) {
        if (ms < 1000) {
            return ms + "ms";
        } else if (ms < 60000) {
            return String.format("%.2fs", ms / 1000.0);
        } else {
            var minutes = ms / 60000;
            var seconds = (ms % 60000) / 1000.0;
            return String.format("%dm %.1fs", minutes, seconds);
        }
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new SqlToCsvCommand())
            .setColorScheme(CommandLine.Help.defaultColorScheme(CommandLine.Help.Ansi.AUTO))
            .execute(args);
        System.exit(exitCode);
    }
}
