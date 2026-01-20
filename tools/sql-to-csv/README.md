# SQL-to-CSV Tool

Professional command-line tool to export SQL query results to CSV format for PocketHive scenario datasets.

## Features

- ✅ **Multi-Database Support** - PostgreSQL, MySQL, SQL Server, Oracle
- ✅ **Flexible Input** - Inline queries or query files
- ✅ **Enterprise Security** - Query validation, secure credentials, audit logging, read-only mode
- ✅ **Configurable** - Custom delimiters, NULL handling, header control
- ✅ **High Performance** - Connection pooling, streaming, buffered I/O
- ✅ **Professional** - Colored output, progress tracking, verbose mode
- ✅ **SOLID Design** - Clean architecture, maintainable code

> **Security Note**: This tool implements enterprise-grade security features. See [SECURITY.md](SECURITY.md) for details.

## Installation

### Build from Source

```bash
cd tools/sql-to-csv
mvn clean package
```

This creates `target/sql-to-csv.jar` - a standalone executable JAR with all dependencies.

### Verify Installation

```bash
# Unix/Linux/macOS
./sql-to-csv.sh --version

# Windows
sql-to-csv.bat --version
```

## Usage

### Basic Syntax

```bash
sql-to-csv -u <jdbc-url> -U <username> -P <password> -q <query> -o <output-file>
```

### Options

| Option | Description | Required |
|--------|-------------|----------|
| `-u, --jdbc-url` | JDBC connection URL | Yes |
| `-U, --username` | Database username | No |
| `-P, --password` | Database password (INSECURE - see Security) | No |
| `--password-stdin` | Read password from stdin (secure) | No |
| `--password-file` | Read password from file (secure) | No |
| `--password-env` | Environment variable for password (default: SQL_TO_CSV_PASSWORD) | No |
| `-q, --query` | SQL query to execute (SELECT only) | Yes* |
| `-f, --query-file` | File containing SQL query | Yes* |
| `-o, --output` | Output CSV file path | Yes |
| `-d, --delimiter` | CSV delimiter (default: `,`) | No |
| `--no-header` | Skip CSV header row | No |
| `--null-value` | String for NULL values (default: empty) | No |
| `--fetch-size` | JDBC fetch size for streaming (default: 1000) | No |
| `--buffer-size` | File write buffer size in KB (default: 64) | No |
| `--allow-absolute-paths` | Allow absolute output paths | No |
| `--audit-log` | Audit log file path (default: ~/.sql-to-csv-audit.log) | No |
| `--no-audit` | Disable audit logging (not recommended) | No |
| `-v, --verbose` | Enable verbose output | No |
| `-h, --help` | Show help message | No |
| `-V, --version` | Show version | No |

*Either `--query` or `--query-file` must be specified

## Examples

### PostgreSQL

**Basic export:**
```bash
./sql-to-csv.sh \
  -u jdbc:postgresql://localhost:5432/pacasso \
  -U admin \
  -P secret \
  -q "SELECT acc_no, balance_code, currency_code FROM account WHERE acc_no LIKE '9999993%'" \
  -o ../../scenarios/bundles/my-test/datasets/accounts.csv
```

**With interactive password:**
```bash
./sql-to-csv.sh \
  -u jdbc:postgresql://localhost:5432/pacasso \
  -U admin \
  -P \
  -q "SELECT * FROM account LIMIT 1000" \
  -o accounts.csv
```

**Using query file:**
```bash
./sql-to-csv.sh \
  -u jdbc:postgresql://localhost:5432/pacasso \
  -U admin \
  -P secret \
  -f queries/export-accounts.sql \
  -o accounts.csv \
  -v
```

### MySQL

```bash
./sql-to-csv.sh \
  -u jdbc:mysql://localhost:3306/mydb \
  -U root \
  -P secret \
  -q "SELECT * FROM users WHERE created_at > '2024-01-01'" \
  -o users.csv
```

### SQL Server

```bash
./sql-to-csv.sh \
  -u "jdbc:sqlserver://localhost:1433;databaseName=mydb" \
  -U sa \
  -P secret \
  -q "SELECT TOP 1000 * FROM customers" \
  -o customers.csv
```

### Oracle

```bash
./sql-to-csv.sh \
  -u jdbc:oracle:thin:@localhost:1521:orcl \
  -U system \
  -P secret \
  -q "SELECT * FROM employees WHERE ROWNUM <= 1000" \
  -o employees.csv
```

### Advanced Options

**Custom delimiter (tab-separated):**
```bash
./sql-to-csv.sh \
  -u jdbc:postgresql://localhost/db \
  -U admin -P secret \
  -q "SELECT * FROM data" \
  -o data.tsv \
  -d $'\t'
```

**Skip header row:**
```bash
./sql-to-csv.sh \
  -u jdbc:postgresql://localhost/db \
  -U admin -P secret \
  -q "SELECT * FROM data" \
  -o data.csv \
  --no-header
```

**Custom NULL value:**
```bash
./sql-to-csv.sh \
  -u jdbc:postgresql://localhost/db \
  -U admin -P secret \
  -q "SELECT * FROM data" \
  -o data.csv \
  --null-value "N/A"
```

**Verbose output:**
```bash
./sql-to-csv.sh \
  -u jdbc:postgresql://localhost/db \
  -U admin -P secret \
  -q "SELECT * FROM large_table" \
  -o output.csv \
  -v
```

## Security Best Practices

> **Important**: See [SECURITY.md](SECURITY.md) for comprehensive security documentation.

### Secure Password Handling

**Best: Environment Variable**
```bash
export SQL_TO_CSV_PASSWORD="secret"
./sql-to-csv.sh -u jdbc:postgresql://localhost/db -U admin -q "SELECT * FROM users" -o users.csv
```

**Good: Credential File**
```bash
echo 'secret' > ~/.sql-to-csv-creds
chmod 0600 ~/.sql-to-csv-creds
./sql-to-csv.sh --password-file ~/.sql-to-csv-creds -u jdbc:postgresql://localhost/db -U admin -q "SELECT * FROM users" -o users.csv
```

**Good: Stdin**
```bash
echo 'secret' | ./sql-to-csv.sh --password-stdin -u jdbc:postgresql://localhost/db -U admin -q "SELECT * FROM users" -o users.csv
```

**Acceptable: Interactive Prompt**
```bash
./sql-to-csv.sh -u jdbc:postgresql://localhost/db -U admin -q "SELECT * FROM users" -o users.csv
# Password: ******* (hidden input)
```

### Query Restrictions

**Only SELECT queries are allowed**. DDL/DML operations are blocked for security:

```bash
# ✅ Allowed
./sql-to-csv.sh ... -q "SELECT * FROM users"

# ❌ Blocked
./sql-to-csv.sh ... -q "DROP TABLE users"  # SecurityException
./sql-to-csv.sh ... -q "DELETE FROM users"  # SecurityException
```

### Path Security

**Relative paths are safe by default** (including parent directory navigation):
```bash
# ✅ Safe - within project
./sql-to-csv.sh ... -o output/users.csv
./sql-to-csv.sh ... -o ../../scenarios/bundles/my-test/datasets/users.csv

# ❌ Blocked - outside project
./sql-to-csv.sh ... -o ../../../../../etc/passwd  # SecurityException

# ❌ Requires flag - absolute path
./sql-to-csv.sh ... -o /tmp/users.csv  # SecurityException

# ✅ With flag
./sql-to-csv.sh --allow-absolute-paths ... -o /tmp/users.csv
```

### Audit Logging

All exports are logged by default to `~/.sql-to-csv-audit.log`:
```
2024-01-15T10:30:45Z|jdoe|jdbc:postgresql://localhost/db|a3f5b8c...|1000|output/users.csv
```

## Workflow Integration

### Generate Dataset for PocketHive Scenario

```bash
# 1. Export data from database
./sql-to-csv.sh \
  -u jdbc:postgresql://prod-db:5432/pacasso \
  -U readonly -P secret \
  -q "SELECT acc_no, balance_code, currency_code, max_balance, product_class_code, customer_code FROM account WHERE acc_no LIKE '9999993%'" \
  -o ../../scenarios/bundles/pacasso-load-test/datasets/accounts.csv

# 2. Reference in scenario.yaml
# inputs:
#   type: "CSV_DATASET"
#   csv:
#     filePath: "/app/scenario/datasets/accounts.csv"

# 3. Run scenario in PocketHive
```

### Batch Export Multiple Tables

```bash
#!/bin/bash
DB_URL="jdbc:postgresql://localhost:5432/pacasso"
DB_USER="admin"
DB_PASS="secret"
OUTPUT_DIR="../../scenarios/bundles/my-test/datasets"

./sql-to-csv.sh -u "$DB_URL" -U "$DB_USER" -P "$DB_PASS" \
  -q "SELECT * FROM accounts" -o "$OUTPUT_DIR/accounts.csv"

./sql-to-csv.sh -u "$DB_URL" -U "$DB_USER" -P "$DB_PASS" \
  -q "SELECT * FROM cards" -o "$OUTPUT_DIR/cards.csv"

./sql-to-csv.sh -u "$DB_URL" -U "$DB_USER" -P "$DB_PASS" \
  -q "SELECT * FROM transactions" -o "$OUTPUT_DIR/transactions.csv"
```

## Troubleshooting

### JDBC Driver Not Found

**Error:** `No suitable driver found for jdbc:...`

**Solution:** The tool includes drivers for PostgreSQL, MySQL, SQL Server, and Oracle. If you need another driver, add it to `pom.xml` and rebuild.

### Connection Refused

**Error:** `Connection refused`

**Solution:** 
- Verify database is running
- Check host and port in JDBC URL
- Verify firewall rules
- Test connection: `telnet <host> <port>`

### Authentication Failed

**Error:** `Authentication failed`

**Solution:**
- Verify username and password
- Check database user permissions
- Ensure user has SELECT privileges on target tables

### Out of Memory

**Error:** `java.lang.OutOfMemoryError`

**Solution:** Increase JVM heap size:
```bash
java -Xmx2g -jar target/sql-to-csv.jar -u ... -q ... -o ...
```

Or limit result set size in query:
```sql
SELECT * FROM large_table LIMIT 10000
```

## Architecture

### Security Architecture

```
CLI Layer (SqlToCsvCommand)
    ↓
Security Layer
  - QueryValidator (SELECT-only enforcement)
  - CredentialProvider (secure password resolution)
  - PathValidator (path traversal prevention)
  - AuditLogger (tamper-evident logging)
    ↓
Export Layer (SqlCsvExporter)
  - Read-only transaction enforcement
  - Connection pooling (HikariCP)
  - Streaming result sets
```

### SOLID Principles

- **Single Responsibility** - Each class has one reason to change
  - `SqlToCsvCommand` - CLI interface
  - `SqlCsvExporter` - Export orchestration
  - `CsvWriter` - CSV formatting
  - `SqlExportConfig` - Configuration

- **Open/Closed** - Open for extension, closed for modification
  - `CsvWriter` can be extended for custom formats
  - New database drivers added via Maven dependencies

- **Liskov Substitution** - Not applicable (no inheritance hierarchy)

- **Interface Segregation** - Minimal, focused interfaces
  - `Callable<Integer>` for command execution

- **Dependency Inversion** - Depend on abstractions
  - Uses JDBC interfaces, not concrete implementations

### Class Diagram

```
SqlToCsvCommand (CLI)
    ↓ validates with
QueryValidator, CredentialProvider, PathValidator
    ↓ creates
SqlExportConfig (Immutable)
    ↓ used by
SqlCsvExporter (Orchestrator)
    ↓ uses
ConnectionManager (HikariCP) + CsvWriter (Formatter)
    ↓ logs to
AuditLogger (Tamper-evident)
```

## Future Enhancements

- [ ] UI integration for point-and-click CSV generation
- [ ] Streaming for very large result sets
- [ ] Parallel export for multiple queries
- [ ] JSON/XML output formats
- [ ] Data masking/anonymization
- [ ] Compression support (gzip)

## Contributing

Follow existing code style and SOLID principles. All changes must include:
- Unit tests
- Updated documentation
- Clean commit messages

## License

Part of PocketHive project. See main repository LICENSE.
