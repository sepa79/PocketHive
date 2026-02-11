# SQL-to-CSV Quick Start

## 1. Build

```bash
cd tools/sql-to-csv
mvn clean package
```

## 2. Basic Usage

```bash
./sql-to-csv.sh \
  -u jdbc:postgresql://localhost:5432/mydb \
  -U admin \
  -P secret \
  -q "SELECT * FROM users" \
  -o users.csv
```

## 3. Secure Password (Recommended)

```bash
# Option A: Environment variable
export SQL_TO_CSV_PASSWORD="secret"
./sql-to-csv.sh -u jdbc:postgresql://localhost/db -U admin \
  -q "SELECT * FROM users" -o users.csv

# Option B: Credential file
echo 'secret' > ~/.sql-to-csv-creds
chmod 0600 ~/.sql-to-csv-creds
./sql-to-csv.sh --password-file ~/.sql-to-csv-creds \
  -u jdbc:postgresql://localhost/db -U admin \
  -q "SELECT * FROM users" -o users.csv

# Option C: Interactive prompt
./sql-to-csv.sh -u jdbc:postgresql://localhost/db -U admin \
  -q "SELECT * FROM users" -o users.csv
# Password: ******* (hidden)
```

## 4. Common Options

```bash
# Query from file
./sql-to-csv.sh -u jdbc:postgresql://localhost/db -U admin -P secret \
  -f queries/export.sql -o output.csv

# Custom delimiter (tab)
./sql-to-csv.sh -u jdbc:postgresql://localhost/db -U admin -P secret \
  -q "SELECT * FROM data" -o data.tsv -d $'\t'

# Skip header
./sql-to-csv.sh -u jdbc:postgresql://localhost/db -U admin -P secret \
  -q "SELECT * FROM data" -o data.csv --no-header

# Verbose output
./sql-to-csv.sh -u jdbc:postgresql://localhost/db -U admin -P secret \
  -q "SELECT * FROM data" -o data.csv -v
```

## 5. Database Examples

```bash
# PostgreSQL
./sql-to-csv.sh -u jdbc:postgresql://localhost:5432/db -U admin -P secret \
  -q "SELECT * FROM accounts" -o accounts.csv

# MySQL
./sql-to-csv.sh -u jdbc:mysql://localhost:3306/db -U root -P secret \
  -q "SELECT * FROM users" -o users.csv

# SQL Server
./sql-to-csv.sh -u "jdbc:sqlserver://localhost:1433;databaseName=db" \
  -U sa -P secret -q "SELECT TOP 1000 * FROM customers" -o customers.csv

# Oracle
./sql-to-csv.sh -u jdbc:oracle:thin:@localhost:1521:orcl -U system -P secret \
  -q "SELECT * FROM employees WHERE ROWNUM <= 1000" -o employees.csv
```

## 6. PocketHive Integration

```bash
# Export dataset for scenario
./sql-to-csv.sh -u jdbc:postgresql://db:5432/app -U readonly -P secret \
  -q "SELECT * FROM accounts WHERE status = 'ACTIVE'" \
  -o ../../scenarios/bundles/my-test/datasets/accounts.csv

# Reference in scenario.yaml:
# inputs:
#   type: CSV_DATASET
#   csv:
#     filePath: /app/scenario/datasets/accounts.csv
```

## 7. Security Notes

- Only SELECT queries allowed (DDL/DML blocked)
- Relative paths safe by default
- Absolute paths require `--allow-absolute-paths` flag
- All exports logged to `~/.sql-to-csv-audit.log`

## 8. Troubleshooting

```bash
# Check version
./sql-to-csv.sh --version

# Show help
./sql-to-csv.sh --help

# Increase memory for large exports
java -Xmx2g -jar target/sql-to-csv.jar -u ... -q ... -o ...
```

## Next Steps

- See [README.md](README.md) for complete documentation
- See [SECURITY.md](SECURITY.md) for security details
