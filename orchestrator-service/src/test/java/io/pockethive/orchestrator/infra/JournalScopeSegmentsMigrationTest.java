package io.pockethive.orchestrator.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class JournalScopeSegmentsMigrationTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
      .withDatabaseName("pockethive")
      .withUsername("pockethive")
      .withPassword("pockethive");

  @Test
  void backfillsLegacyBlankScopeSegmentsAndRejectsNewOnes() {
    DataSource dataSource = new DriverManagerDataSource(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .target("1")
        .load()
        .migrate();
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    Instant now = Instant.parse("2026-01-01T00:00:00Z");

    jdbc.update("""
        INSERT INTO journal_event (
          ts, scope, swarm_id, run_id, scope_role, scope_instance,
          severity, direction, kind, type, origin
        ) VALUES (?, 'HIVE', 'swarm-1', 'run-1', NULL, ' ', 'INFO', 'LOCAL', 'test', 'legacy', 'test')
        """, java.sql.Timestamp.from(now));
    jdbc.update("""
        INSERT INTO journal_capture (id, scope, swarm_id, run_id, mode)
        VALUES ('00000000-0000-0000-0000-000000000001', 'SWARM', 'swarm-1', 'run-1', 'FULL')
        """);
    jdbc.update("""
        INSERT INTO journal_event_archive (
          capture_id, source_id, ts, scope, swarm_id, run_id, scope_role, scope_instance,
          severity, direction, kind, type, origin
        ) VALUES (
          '00000000-0000-0000-0000-000000000001', 1, ?, 'SWARM', 'swarm-1', 'run-1', NULL, ' ',
          'INFO', 'LOCAL', 'test', 'legacy', 'test'
        )
        """, java.sql.Timestamp.from(now));

    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .load()
        .migrate();

    assertThat(jdbc.queryForObject(
        "SELECT scope_role FROM journal_event WHERE type = 'legacy'", String.class)).isEqualTo("ALL");
    assertThat(jdbc.queryForObject(
        "SELECT scope_instance FROM journal_event WHERE type = 'legacy'", String.class)).isEqualTo("ALL");
    assertThat(jdbc.queryForObject(
        "SELECT scope_role FROM journal_event_archive WHERE source_id = 1", String.class)).isEqualTo("ALL");
    assertThat(jdbc.queryForObject(
        "SELECT scope_instance FROM journal_event_archive WHERE source_id = 1", String.class)).isEqualTo("ALL");
    assertThatThrownBy(() -> jdbc.update("""
        INSERT INTO journal_event (
          ts, scope, swarm_id, run_id, scope_role, scope_instance,
          severity, direction, kind, type, origin
        ) VALUES (?, 'HIVE', 'swarm-1', 'run-1', '', 'ALL', 'INFO', 'LOCAL', 'test', 'invalid', 'test')
        """, java.sql.Timestamp.from(now)))
        .isInstanceOf(DataIntegrityViolationException.class);
  }
}
