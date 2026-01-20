# SQL-to-CSV Tool

Enterprise-grade CLI tool to export SQL query results to CSV format for PocketHive scenario datasets.

## Features

- **Multi-Database** - PostgreSQL, MySQL, SQL Server, Oracle
- **Secure** - Query validation, credential protection, audit logging, read-only mode
- **Configurable** - Custom delimiters, NULL handling, headers
- **High Performance** - Streaming, connection pooling, buffered I/O
- **Production Ready** - SOLID design, 85% test coverage

## Quick Start

```bash
# Build
mvn clean package

# Run
./sql-to-csv.sh -u jdbc:postgresql://localhost/db -U admin -P secret \
  -q "SELECT * FROM users" -o users.csv
```

## Options

| Option | Description |
|--------|-------------|
| `-u, --jdbc-url` | JDBC connection URL (required) |
| `-U, --username` | Database username |
| `-P, --password` | Password (insecure - use alternatives) |
| `--password-stdin` | Read password from stdin |
| `--password-file` | Read password from file (0600 perms) |
| `--password-env` | Env var name (default: SQL_TO_CSV_PASSWORD) |
| `-q, --query` | SQL query (SELECT only) |
| `-f, --query-file` | File containing query |
| `-o, --output` | Output CSV file (required) |
| `-d, --delimiter` | CSV delimiter (default: `,`) |
| `--no-header` | Skip header row |
| `--null-value` | NULL representation (default: empty) |
| `--fetch-size` | Streaming batch size (default: 1000) |
| `--buffer-size` | Write buffer KB (default: 64) |
| `--allow-absolute-paths` | Allow absolute paths |
| `--audit-log` | Audit log path |
| `--no-audit` | Disable audit logging |
| `-v, --verbose` | Verbose output |
| `-h, --help` | Show help |
| `-V, --version` | Show version |

## Examples

```bash
# PostgreSQL
./sql-to-csv.sh -u jdbc:postgresql://localhost:5432/db -U admin -P secret \
  -q "SELECT * FROM accounts" -o accounts.csv

# MySQL
./sql-to-csv.sh -u jdbc:mysql://localhost:3306/db -U root -P secret \
  -q "SELECT * FROM users" -o users.csv

# Query file
./sql-to-csv.sh -u jdbc:postgresql://localhost/db -U admin -P secret \
  -f queries/export.sql -o output.csv -v

# Custom delimiter
./sql-to-csv.sh -u jdbc:postgresql://localhost/db -U admin -P secret \
  -q "SELECT * FROM data" -o data.tsv -d $'\t'

# Secure password (environment variable)
export SQL_TO_CSV_PASSWORD="secret"
./sql-to-csv.sh -u jdbc:postgresql://localhost/db -U admin \
  -q "SELECT * FROM users" -o users.csv
```

## Security

### Password Handling (Priority Order)
1. **Environment Variable** (best for CI/CD)
2. **Credential File** (best for ops, requires 0600 perms)
3. **Stdin** (best for scripts)
4. **Interactive Prompt** (best for dev)
5. **CLI Argument** (insecure, shows warning)

### Query Validation
- Only SELECT queries allowed
- DDL/DML operations blocked (DROP, DELETE, UPDATE, etc.)
- Dangerous functions blocked (EXEC, LOAD_FILE, etc.)

### Path Security
- Relative paths allowed (including `../../`)
- Absolute paths require `--allow-absolute-paths` flag
- System directories blocked

### Audit Logging
- Default: `~/.sql-to-csv-audit.log`
- Format: `timestamp|user|jdbc-url|query-hash|rows|output-file`

## PocketHive Integration

```bash
# Export dataset
./sql-to-csv.sh -u jdbc:postgresql://db:5432/app -U readonly -P secret \
  -q "SELECT * FROM accounts" \
  -o ../../scenarios/bundles/my-test/datasets/accounts.csv

# Reference in scenario.yaml
# inputs:
#   type: CSV_DATASET
#   csv:
#     filePath: /app/scenario/datasets/accounts.csv
```

## Troubleshooting

| Error | Solution |
|-------|----------|
| `No suitable driver` | Included: PostgreSQL, MySQL, SQL Server, Oracle |
| `Connection refused` | Check database running, host/port, firewall |
| `Authentication failed` | Verify credentials, SELECT privileges |
| `OutOfMemoryError` | Increase heap: `java -Xmx2g -jar ...` or limit rows |

## Architecture

```
SqlToCsvCommand (CLI)
  ↓
Security Layer (QueryValidator, CredentialProvider, PathValidator, AuditLogger)
  ↓
SqlCsvExporter (Orchestrator)
  ↓
ConnectionManager (HikariCP) + CsvWriter (Apache Commons CSV)
```

### Key Components
- **SqlToCsvCommand** - CLI interface (Picocli)
- **SqlCsvExporter** - Export orchestration
- **ConnectionManager** - Connection pooling (HikariCP)
- **CsvWriter** - CSV formatting (Apache Commons CSV)
- **QueryValidator** - SQL validation (JSqlParser)
- **CredentialProvider** - Secure password resolution
- **PathValidator** - Path traversal prevention
- **AuditLogger** - Tamper-evident logging

## Technical Details

- **Language**: Java 21
- **Build**: Maven
- **Libraries**: HikariCP, Apache Commons CSV, JSqlParser, Picocli
- **Test Coverage**: 85% (116 tests)
- **SOLID Compliant**: Yes
- **Production Ready**: Yes
