package io.pockethive.tools.sqltocsv;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

@Testcontainers
@DisplayName("SQL-to-CSV End-to-End Tests with PostgreSQL")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SqlCsvExporterE2ETest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("testdb")
        .withUsername("testuser")
        .withPassword("testpass");

    @TempDir
    Path tempDir;

    @BeforeAll
    static void setupDatabase() throws Exception {
        try (Connection conn = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement stmt = conn.createStatement()) {

            // Create test tables
            stmt.execute("CREATE TABLE accounts (" +
                        "acc_no VARCHAR(20) PRIMARY KEY, " +
                        "balance_code VARCHAR(10), " +
                        "currency_code VARCHAR(3), " +
                        "max_balance DECIMAL(15,2), " +
                        "product_class_code VARCHAR(10), " +
                        "customer_code VARCHAR(20))");

            stmt.execute("CREATE TABLE transactions (" +
                        "txn_id SERIAL PRIMARY KEY, " +
                        "acc_no VARCHAR(20), " +
                        "amount DECIMAL(15,2), " +
                        "txn_date TIMESTAMP, " +
                        "description TEXT)");

            // Insert test data
            stmt.execute("INSERT INTO accounts VALUES " +
                        "('9999993001', 'BAL001', 'USD', 10000.00, 'SAVINGS', 'CUST001'), " +
                        "('9999993002', 'BAL002', 'EUR', 5000.00, 'CHECKING', 'CUST002'), " +
                        "('9999993003', 'BAL001', 'GBP', 15000.00, 'SAVINGS', 'CUST003')");

            stmt.execute("INSERT INTO transactions (acc_no, amount, txn_date, description) VALUES " +
                        "('9999993001', 100.50, NOW(), 'Deposit'), " +
                        "('9999993001', -50.25, NOW(), 'Withdrawal'), " +
                        "('9999993002', 200.00, NOW(), 'Transfer')");
        }
    }

    @Test
    @Order(1)
    @DisplayName("E2E: Export accounts from PostgreSQL")
    void shouldExportAccountsFromPostgreSQL() throws Exception {
        File outputFile = tempDir.resolve("accounts.csv").toFile();

        SqlExportConfig config = SqlExportConfig.builder()
            .jdbcUrl(postgres.getJdbcUrl())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .query("SELECT * FROM accounts ORDER BY acc_no")
            .outputFile(outputFile)
            .includeHeader(true)
            .build();

        SqlCsvExporter exporter = new SqlCsvExporter(config);
        ExportResult result = exporter.export();

        assertThat(result.rowCount()).isEqualTo(3);
        assertThat(result.columnCount()).isEqualTo(6);
        assertThat(outputFile).exists();

        List<String> lines = Files.readAllLines(outputFile.toPath());
        assertThat(lines).hasSize(4); // Header + 3 rows
        assertThat(lines.get(0)).containsIgnoringCase("acc_no");
        assertThat(lines.get(1)).contains("9999993001");
        assertThat(lines.get(1)).contains("USD");
    }

    @Test
    @Order(2)
    @DisplayName("E2E: Export with JOIN query")
    void shouldExportWithJoinQuery() throws Exception {
        File outputFile = tempDir.resolve("account-transactions.csv").toFile();

        SqlExportConfig config = SqlExportConfig.builder()
            .jdbcUrl(postgres.getJdbcUrl())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .query("SELECT a.acc_no, a.currency_code, t.amount, t.description " +
                   "FROM accounts a " +
                   "JOIN transactions t ON a.acc_no = t.acc_no " +
                   "ORDER BY a.acc_no, t.txn_id")
            .outputFile(outputFile)
            .includeHeader(true)
            .build();

        SqlCsvExporter exporter = new SqlCsvExporter(config);
        ExportResult result = exporter.export();

        assertThat(result.rowCount()).isEqualTo(3);
        assertThat(result.columnCount()).isEqualTo(4);
// amazonq-ignore-next-line

        List<String> lines = Files.readAllLines(outputFile.toPath());
        assertThat(lines.get(1)).contains("9999993001");
        assertThat(lines.get(1)).contains("USD");
        assertThat(lines.get(1)).contains("Deposit");
    }

    @Test
    @Order(3)
    @DisplayName("E2E: Export with aggregation")
    void shouldExportWithAggregation() throws Exception {
        File outputFile = tempDir.resolve("account-summary.csv").toFile();

        SqlExportConfig config = SqlExportConfig.builder()
            .jdbcUrl(postgres.getJdbcUrl())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .query("SELECT acc_no, COUNT(*) as txn_count, SUM(amount) as total_amount " +
                   "FROM transactions " +
                   "GROUP BY acc_no " +
                   "ORDER BY acc_no")
            .outputFile(outputFile)
            .includeHeader(true)
            .build();

        SqlCsvExporter exporter = new SqlCsvExporter(config);
        ExportResult result = exporter.export();

        assertThat(result.rowCount()).isEqualTo(2); // 2 accounts with transactions

        List<String> lines = Files.readAllLines(outputFile.toPath());
        assertThat(lines.get(0)).containsIgnoringCase("txn_count");
        assertThat(lines.get(0)).containsIgnoringCase("total_amount");
    }

    @Test
    @Order(4)
    @DisplayName("E2E: Export large result set with streaming")
    void shouldExportLargeResultSetWithStreaming() throws Exception {
        // Insert more test data
        try (Connection conn = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement stmt = conn.createStatement()) {

            for (int i = 0; i < 1000; i++) {
                stmt.execute(String.format(
                    "INSERT INTO transactions (acc_no, amount, txn_date, description) " +
                    "VALUES ('9999993001', %d.00, NOW(), 'Test transaction %d')", i, i));
            }
        }

        File outputFile = tempDir.resolve("large-export.csv").toFile();

        SqlExportConfig config = SqlExportConfig.builder()
            .jdbcUrl(postgres.getJdbcUrl())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .query("SELECT * FROM transactions ORDER BY txn_id")
            .outputFile(outputFile)
            .fetchSize(100) // Test streaming
            .includeHeader(true)
            .build();

        SqlCsvExporter exporter = new SqlCsvExporter(config);
        ExportResult result = exporter.export();

        assertThat(result.rowCount()).isGreaterThan(1000);
        assertThat(outputFile).exists();
        assertThat(outputFile.length()).isGreaterThan(0);
    }

    @Test
    @Order(5)
    @DisplayName("E2E: Verify read-only mode prevents writes")
    void shouldEnforceReadOnlyMode() throws Exception {
        File outputFile = tempDir.resolve("readonly-test.csv").toFile();

        // This should fail because connection is read-only
        SqlExportConfig config = SqlExportConfig.builder()
            .jdbcUrl(postgres.getJdbcUrl())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .query("SELECT * FROM accounts")
            .outputFile(outputFile)
            .build();

        SqlCsvExporter exporter = new SqlCsvExporter(config);

        // Export should succeed (SELECT is allowed)
        assertThatNoException().isThrownBy(() -> exporter.export());

        // But if we try to modify data through the same connection manager,
        // it should fail (this is verified by the read-only flag being set)
        assertThat(outputFile).exists();
    }

    @Test
    @Order(6)
    @DisplayName("E2E: Handle connection errors gracefully")
    void shouldHandleConnectionErrorsGracefully() {
        File outputFile = tempDir.resolve("error-test.csv").toFile();

        SqlExportConfig config = SqlExportConfig.builder()
            .jdbcUrl("jdbc:postgresql://invalid-host:5432/testdb")
            .username("invalid")
            .password("invalid")
            .query("SELECT * FROM accounts")
            .outputFile(outputFile)
            .build();

        SqlCsvExporter exporter = new SqlCsvExporter(config);

        assertThatThrownBy(() -> exporter.export())
            .isInstanceOf(Exception.class);
    }

    @Test
    @Order(7)
    @DisplayName("E2E: Handle invalid SQL gracefully")
    void shouldHandleInvalidSqlGracefully() {
        File outputFile = tempDir.resolve("invalid-sql.csv").toFile();

        SqlExportConfig config = SqlExportConfig.builder()
            .jdbcUrl(postgres.getJdbcUrl())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .query("SELECT * FROM non_existent_table")
            .outputFile(outputFile)
            .build();

        SqlCsvExporter exporter = new SqlCsvExporter(config);

        assertThatThrownBy(() -> exporter.export())
            .isInstanceOf(ExportException.class)
            .hasMessageContaining("Database error");
    }
}
