package io.pockethive.swarmcontroller.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.control.ControlScope;
import io.pockethive.swarmcontroller.runtime.SwarmJournal.SwarmJournalEntry;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class PostgresSwarmJournalTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("pockethive")
          .withUsername("pockethive")
          .withPassword("pockethive");

  private static JdbcTemplate jdbc;
  private static PostgresSwarmJournal journal;

  @BeforeAll
  static void setup() {
    DriverManagerDataSource dataSource =
        new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    jdbc = new JdbcTemplate(dataSource);
    jdbc.execute(
        """
        CREATE TABLE IF NOT EXISTS journal_event (
          id BIGSERIAL PRIMARY KEY,
          ts TIMESTAMPTZ NOT NULL,
          scope TEXT NOT NULL,
          swarm_id TEXT NOT NULL,
          scope_role TEXT,
          scope_instance TEXT,
          severity TEXT NOT NULL,
          direction TEXT NOT NULL,
          kind TEXT NOT NULL,
          type TEXT NOT NULL,
          origin TEXT NOT NULL,
          correlation_id TEXT,
          idempotency_key TEXT,
          routing_key TEXT,
          data JSONB,
          raw JSONB,
          extra JSONB
        )
        """);
    journal = new PostgresSwarmJournal(new ObjectMapper(), jdbc);
  }

  @Test
  void writesEntriesToJournalEventTable() {
    SwarmJournalEntry entry = new SwarmJournalEntry(
        Instant.parse("2025-01-01T00:00:00Z"),
        "sw1",
        "INFO",
        SwarmJournal.Direction.IN,
        "signal",
        "swarm-start",
        "swarm-controller",
        ControlScope.forInstance("sw1", "swarm-controller", "swarm-controller-1"),
        "c-1",
        "i-1",
        "rk-1",
        Map.of("hello", "world"),
        null,
        null);
    journal.append(entry);
    journal.flush();

    Integer count = jdbc.queryForObject(
        "SELECT COUNT(*) FROM journal_event WHERE scope = 'SWARM' AND swarm_id = 'sw1'",
        Integer.class);
    assertThat(count).isEqualTo(1);

    String json = jdbc.queryForObject(
        "SELECT data::text FROM journal_event WHERE swarm_id = 'sw1' ORDER BY id DESC LIMIT 1",
        String.class);
    assertThat(json).contains("hello").contains("world");
  }
}

