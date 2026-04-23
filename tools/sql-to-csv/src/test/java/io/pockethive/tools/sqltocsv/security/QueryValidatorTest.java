package io.pockethive.tools.sqltocsv.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;

@DisplayName("QueryValidator Unit Tests")
class QueryValidatorTest {

    private QueryValidator validator;

    @BeforeEach
    void setUp() {
        validator = new QueryValidator();
    }

    @Test
    @DisplayName("Should accept valid SELECT query")
    void shouldAcceptValidSelectQuery() {
        assertThatNoException().isThrownBy(() -> 
            validator.validate("SELECT * FROM users"));
    }

    @Test
    @DisplayName("Should accept SELECT with WHERE clause")
    void shouldAcceptSelectWithWhere() {
        assertThatNoException().isThrownBy(() -> 
            validator.validate("SELECT id, name FROM users WHERE active = true"));
    }

    @Test
    @DisplayName("Should accept SELECT with JOIN")
    void shouldAcceptSelectWithJoin() {
        assertThatNoException().isThrownBy(() -> 
            validator.validate("SELECT u.*, o.* FROM users u JOIN orders o ON u.id = o.user_id"));
    }

    @Test
    @DisplayName("Should accept SELECT with subquery")
    void shouldAcceptSelectWithSubquery() {
        assertThatNoException().isThrownBy(() -> 
            validator.validate("SELECT * FROM users WHERE id IN (SELECT user_id FROM orders)"));
    }

    @Test
    @DisplayName("Should accept SELECT with leading whitespace")
    void shouldAcceptSelectWithWhitespace() {
        assertThatNoException().isThrownBy(() -> 
            validator.validate("   \n\t  SELECT * FROM users"));
    }

    @Test
    @DisplayName("Should accept case-insensitive SELECT")
    void shouldAcceptCaseInsensitiveSelect() {
        assertThatNoException().isThrownBy(() -> 
            validator.validate("select * from users"));
        assertThatNoException().isThrownBy(() -> 
            validator.validate("SeLeCt * FrOm users"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"DROP TABLE users", "drop table users", "  DROP TABLE users"})
    @DisplayName("Should reject DROP statements")
    void shouldRejectDropStatements(String query) {
        assertThatThrownBy(() -> validator.validate(query))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("Only SELECT queries are allowed");
    }

    @ParameterizedTest
    @ValueSource(strings = {"DELETE FROM users", "delete from users WHERE id = 1"})
    @DisplayName("Should reject DELETE statements")
    void shouldRejectDeleteStatements(String query) {
        assertThatThrownBy(() -> validator.validate(query))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("Only SELECT queries are allowed");
    }

    @ParameterizedTest
    @ValueSource(strings = {"UPDATE users SET name = 'test'", "update users set active = false"})
    @DisplayName("Should reject UPDATE statements")
    void shouldRejectUpdateStatements(String query) {
        assertThatThrownBy(() -> validator.validate(query))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("Only SELECT queries are allowed");
    }

    @ParameterizedTest
    @ValueSource(strings = {"INSERT INTO users VALUES (1, 'test')", "insert into users (name) values ('test')"})
    @DisplayName("Should reject INSERT statements")
    void shouldRejectInsertStatements(String query) {
        assertThatThrownBy(() -> validator.validate(query))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("Only SELECT queries are allowed");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ALTER TABLE users ADD COLUMN email VARCHAR(255)", "alter table users drop column name"})
    @DisplayName("Should reject ALTER statements")
    void shouldRejectAlterStatements(String query) {
        assertThatThrownBy(() -> validator.validate(query))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("Only SELECT queries are allowed");
    }

    @ParameterizedTest
    @ValueSource(strings = {"CREATE TABLE test (id INT)", "create index idx_name on users(name)"})
    @DisplayName("Should reject CREATE statements")
    void shouldRejectCreateStatements(String query) {
        assertThatThrownBy(() -> validator.validate(query))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("Only SELECT queries are allowed");
    }

    @ParameterizedTest
    @ValueSource(strings = {"TRUNCATE TABLE users", "truncate users"})
    @DisplayName("Should reject TRUNCATE statements")
    void shouldRejectTruncateStatements(String query) {
        assertThatThrownBy(() -> validator.validate(query))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("Only SELECT queries are allowed");
    }

    @ParameterizedTest
    @ValueSource(strings = {"CALL my_procedure()", "call sp_test"})
    @DisplayName("Should reject CALL statements")
    void shouldRejectCallStatements(String query) {
        assertThatThrownBy(() -> validator.validate(query))
            .isInstanceOf(SecurityException.class);
    }
    
    @ParameterizedTest
    @ValueSource(strings = {
        "SELECT * FROM users WHERE id = SLEEP(5)",
        "SELECT BENCHMARK(1000000, MD5('test'))",
        "SELECT LOAD_FILE('/etc/passwd')",
        "SELECT * FROM users INTO OUTFILE '/tmp/users.txt'"
    })
    @DisplayName("Should reject dangerous functions")
    void shouldRejectDangerousFunctions(String query) {
        assertThatThrownBy(() -> validator.validate(query))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("dangerous operations");
    }

    @Test
    @DisplayName("Should reject non-SELECT query")
    void shouldRejectNonSelectQuery() {
        assertThatThrownBy(() -> validator.validate("SHOW TABLES"))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("Only SELECT queries are allowed");
    }

    @Test
    @DisplayName("Should reject null query")
    void shouldRejectNullQuery() {
        assertThatThrownBy(() -> validator.validate(null))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("cannot be empty");
    }

    @Test
    @DisplayName("Should reject empty query")
    void shouldRejectEmptyQuery() {
        assertThatThrownBy(() -> validator.validate(""))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("cannot be empty");
    }

    @Test
    @DisplayName("Should reject whitespace-only query")
    void shouldRejectWhitespaceQuery() {
        assertThatThrownBy(() -> validator.validate("   \n\t  "))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("cannot be empty");
    }
}
