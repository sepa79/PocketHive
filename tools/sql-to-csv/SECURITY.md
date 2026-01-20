# SQL-to-CSV Security

Enterprise-grade security features following OWASP best practices and defense-in-depth.

## Security Features

### 1. Query Validation
- **Protection**: Only SELECT queries allowed
- **Blocks**: DROP, DELETE, UPDATE, INSERT, ALTER, CREATE, TRUNCATE, EXEC, GRANT, REVOKE
- **Implementation**: JSqlParser (proper SQL parsing, not regex)
- **Dangerous Functions**: SLEEP, LOAD_FILE, EXEC, DBMS_*, XP_*, SP_*

### 2. Secure Credential Resolution

Priority order (first found wins):

1. **Environment Variable** (best for CI/CD)
   ```bash
   export SQL_TO_CSV_PASSWORD='secret'
   sql-to-csv -u jdbc:postgresql://localhost/db -U admin -q "SELECT * FROM users" -o users.csv
   ```

2. **Credential File** (best for operations)
   ```bash
   echo 'secret' > ~/.sql-to-csv-creds
   chmod 0600 ~/.sql-to-csv-creds
   sql-to-csv --password-file ~/.sql-to-csv-creds -u ... -q ... -o ...
   ```

3. **Stdin** (best for scripts)
   ```bash
   echo 'secret' | sql-to-csv --password-stdin -u ... -q ... -o ...
   ```

4. **Interactive Prompt** (best for developers)
   ```bash
   sql-to-csv -u ... -U admin -q ... -o ...
   # Password: ******* (hidden)
   ```

5. **CLI Argument** (legacy - shows warning)
   ```bash
   sql-to-csv -P 'secret' -u ... -q ... -o ...
   # WARNING: Password in CLI args is insecure
   ```

### 3. Path Validation
- **Relative Paths**: Allowed (including `../../`)
- **Absolute Paths**: Require `--allow-absolute-paths` flag
- **System Directories**: Blocked (`/etc`, `/sys`, `/proc`, etc.)
- **Traversal Prevention**: Normalizes paths, blocks escape attempts

**Examples**:
```bash
# ✅ Safe: relative path
sql-to-csv ... -o output/users.csv

# ✅ Safe: parent navigation within project
sql-to-csv ... -o ../../scenarios/datasets/users.csv

# ❌ Blocked: absolute path without flag
sql-to-csv ... -o /tmp/users.csv

# ❌ Blocked: traversal to system directory
sql-to-csv ... -o ../../../../../etc/passwd

# ✅ Allowed: absolute path with flag
sql-to-csv --allow-absolute-paths ... -o /tmp/users.csv
```

### 4. Audit Logging
- **Default Location**: `~/.sql-to-csv-audit.log`
- **Format**: `timestamp|user|jdbc-url|query-hash|row-count|output-file`
- **Protection**: Tamper-evident, append-only
- **JDBC URL Sanitization**: Removes passwords from URLs

**Example Log**:
```
2024-01-15T10:30:45Z|jdoe|jdbc:postgresql://prod-db:5432/customers|a3f5b8c...|1000|/exports/users.csv
```

**Options**:
```bash
# Custom audit log
sql-to-csv --audit-log /var/log/sql-exports.log ...

# Disable (not recommended)
sql-to-csv --no-audit ...
```

### 5. Read-Only Transaction Mode
- **Protection**: Prevents accidental writes
- **Implementation**: `connection.setReadOnly(true)` + `TRANSACTION_READ_UNCOMMITTED`
- **Impact**: Transparent, no configuration needed

### 6. JDBC URL Validation
- **Protection**: Prevents JDBC URL injection
- **Validates**: Host, port, database name formats
- **Impact**: Transparent with clear error messages

## CLI Security Options

| Option | Description |
|--------|-------------|
| `--password-stdin` | Read password from stdin |
| `--password-file <path>` | Read from file (requires 0600 perms on Unix) |
| `--password-env <var>` | Environment variable name (default: SQL_TO_CSV_PASSWORD) |
| `--allow-absolute-paths` | Allow absolute output paths |
| `--audit-log <path>` | Custom audit log location |
| `--no-audit` | Disable audit logging (not recommended) |
| `-P, --password` | Legacy CLI password (insecure, shows warning) |

## Best Practices

1. **Never use CLI password in production**
2. **Use environment variables or credential files for automation**
3. **Enable audit logging for compliance**
4. **Use relative paths when possible**
5. **Review audit logs regularly**
6. **Restrict credential file permissions (0600)**
7. **Use dedicated read-only database accounts**

## Compliance Support

- **SOC 2**: Audit logging, credential protection
- **PCI DSS**: Secure credential handling, read-only access
- **GDPR**: Audit trails for data exports
- **HIPAA**: Access controls and logging
