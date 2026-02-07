package io.pockethive.orchestrator.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.control.ControlScope;
import io.pockethive.orchestrator.app.JournalController;
import io.pockethive.orchestrator.app.JournalPageResponse;
import io.pockethive.orchestrator.app.SwarmJournalController;
import io.pockethive.orchestrator.domain.HiveJournal;
import io.pockethive.orchestrator.domain.Swarm;
import io.pockethive.orchestrator.domain.SwarmStore;
import java.time.Instant;
import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class PostgresJournalStorageTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
      .withDatabaseName("pockethive")
      .withUsername("pockethive")
      .withPassword("pockethive");

  static JdbcTemplate jdbc;
  static ObjectMapper mapper;
  static SwarmStore store;

  @BeforeAll
  static void setup() {
    DataSource ds = new DriverManagerDataSource(
        POSTGRES.getJdbcUrl(),
        POSTGRES.getUsername(),
        POSTGRES.getPassword());
    Flyway.configure()
        .dataSource(ds)
        .locations("classpath:db/migration")
        .load()
        .migrate();
    jdbc = new JdbcTemplate(ds);
    mapper = new ObjectMapper().findAndRegisterModules();
    store = new SwarmStore();
  }

  @Test
  void hiveJournalPagePaginatesByCursorAndFiltersByCorrelation() {
    jdbc.update("DELETE FROM journal_event");
    Instant base = Instant.parse("2025-01-01T00:00:00Z");
    for (int i = 0; i < 5; i++) {
      jdbc.update(
          """
              INSERT INTO journal_event (
                ts,
                scope,
                swarm_id,
                run_id,
                scope_role,
                scope_instance,
                severity,
                direction,
                kind,
                type,
                origin,
                correlation_id,
                idempotency_key,
                routing_key,
                data,
                raw,
                extra
              ) VALUES (
                ?,
                'HIVE',
                ?,
                ?,
                NULL,
                NULL,
                'INFO',
                'LOCAL',
                'test',
                ?,
                'test-origin',
                ?,
                NULL,
                NULL,
                NULL,
                NULL,
                NULL
              )
              """,
          java.sql.Timestamp.from(base.plusSeconds(i)),
          "s1",
          "run-1",
          "t-" + i,
          "c-" + i);
    }

    JournalController ctrl = new JournalController(jdbc, mapper);
    ReflectionTestUtils.setField(ctrl, "journalSink", "postgres");

    JournalPageResponse first = ctrl.hiveJournalPage("s1", "run-1", null, null, null, 2).getBody();
    assertThat(first).isNotNull();
    assertThat(first.items()).hasSize(2);
    assertThat(first.hasMore()).isTrue();
    assertThat(first.nextCursor()).isNotNull();
    assertThat(first.items().get(0).get("type")).isEqualTo("t-4");
    assertThat(first.items().get(1).get("type")).isEqualTo("t-3");

    JournalPageResponse.Cursor cursor = first.nextCursor();
    JournalPageResponse second = ctrl.hiveJournalPage("s1", "run-1", null, cursor.ts(), cursor.id(), 2).getBody();
    assertThat(second).isNotNull();
    assertThat(second.items()).hasSize(2);
    assertThat(second.items().get(0).get("type")).isEqualTo("t-2");
    assertThat(second.items().get(1).get("type")).isEqualTo("t-1");

    JournalPageResponse byCorrelation = ctrl.hiveJournalPage("s1", "run-1", "c-3", null, null, 200).getBody();
    assertThat(byCorrelation).isNotNull();
    assertThat(byCorrelation.items()).hasSize(1);
    assertThat(byCorrelation.items().get(0).get("type")).isEqualTo("t-3");
  }

  @Test
  void swarmJournalPageOrdersByTsThenId() {
    jdbc.update("DELETE FROM journal_event_archive");
    jdbc.update("DELETE FROM journal_capture");
    jdbc.update("DELETE FROM journal_event");
    Instant ts = Instant.parse("2025-01-01T00:00:00Z");

    // Insert two events with identical timestamps so ordering is by id DESC.
    jdbc.update(
        """
            INSERT INTO journal_event (
              ts,
              scope,
              swarm_id,
              run_id,
              scope_role,
              scope_instance,
              severity,
              direction,
              kind,
              type,
              origin
            ) VALUES (
              ?,
              'SWARM',
              ?,
              ?,
              'swarm-controller',
              'inst-1',
              'INFO',
              'IN',
              'signal',
              ?,
              'origin'
            )
            """,
        java.sql.Timestamp.from(ts),
        "sw1",
        "run-1",
        "t-1");
    jdbc.update(
        """
            INSERT INTO journal_event (
              ts,
              scope,
              swarm_id,
              run_id,
              scope_role,
              scope_instance,
              severity,
              direction,
              kind,
              type,
              origin
            ) VALUES (
              ?,
              'SWARM',
              ?,
              ?,
              'swarm-controller',
              'inst-1',
              'INFO',
              'IN',
              'signal',
              ?,
              'origin'
            )
            """,
        java.sql.Timestamp.from(ts),
        "sw1",
        "run-1",
        "t-2");

    SwarmJournalController ctrl = new SwarmJournalController(mapper, jdbc, store);
    ReflectionTestUtils.setField(ctrl, "journalSink", "postgres");

    JournalPageResponse page = ctrl.journalPage("sw1", null, null, 10, "run-1", null).getBody();
    assertThat(page).isNotNull();
    assertThat(page.items()).hasSize(2);
    assertThat(page.items().get(0).get("type")).isEqualTo("t-2");
    assertThat(page.items().get(1).get("type")).isEqualTo("t-1");
  }

  @Test
  void hiveJournalOverloadEvictsInfoToKeepError() throws Exception {
    jdbc.update("DELETE FROM journal_event");
    store.register(new Swarm("s1", "inst", "container", "run-1"));
    PostgresHiveJournal journal = new PostgresHiveJournal(mapper, jdbc, store, 3, 50, 30_000L, 1_000L);
    ControlScope scope = new ControlScope("s1", "orchestrator", "inst");

    for (int i = 0; i < 3; i++) {
      journal.append(new HiveJournal.HiveJournalEntry(
          Instant.now(),
          "s1",
          "INFO",
          HiveJournal.Direction.LOCAL,
          "test",
          "info-" + i,
          "origin",
          scope,
          null,
          null,
          null,
          null,
          null,
          null));
    }

    journal.append(new HiveJournal.HiveJournalEntry(
        Instant.now(),
        "s1",
        "ERROR",
        HiveJournal.Direction.LOCAL,
        "test",
        "error-1",
        "origin",
        scope,
        null,
        null,
        null,
        null,
        null,
        null));

    journal.flush();

    Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM journal_event WHERE scope='HIVE' AND swarm_id='s1'", Integer.class);
    Integer errors = jdbc.queryForObject("SELECT COUNT(*) FROM journal_event WHERE scope='HIVE' AND swarm_id='s1' AND severity='ERROR'", Integer.class);
    assertThat(total).isEqualTo(3);
    assertThat(errors).isEqualTo(1);
  }

  @Test
  void hiveJournalDropEmitsSingleStartStopEvents() throws Exception {
    jdbc.update("DELETE FROM journal_event");
    store.register(new Swarm("s1", "inst", "container", "run-1"));
    PostgresHiveJournal journal = new PostgresHiveJournal(mapper, jdbc, store, 2, 50, 30_000L, 1_000L);
    ControlScope scope = new ControlScope("s1", "orchestrator", "inst");

    journal.append(new HiveJournal.HiveJournalEntry(
        Instant.now(),
        "s1",
        "INFO",
        HiveJournal.Direction.LOCAL,
        "test",
        "info-1",
        "origin",
        scope,
        null,
        null,
        null,
        null,
        null,
        null));
    journal.append(new HiveJournal.HiveJournalEntry(
        Instant.now(),
        "s1",
        "INFO",
        HiveJournal.Direction.LOCAL,
        "test",
        "info-2",
        "origin",
        scope,
        null,
        null,
        null,
        null,
        null,
        null));
    // This should be dropped due to backpressure.
    journal.append(new HiveJournal.HiveJournalEntry(
        Instant.now(),
        "s1",
        "INFO",
        HiveJournal.Direction.LOCAL,
        "test",
        "info-3",
        "origin",
        scope,
        null,
        null,
        null,
        null,
        null,
        null));

    journal.flush();
    Thread.sleep(1100L);
    journal.flush();

    Integer info = jdbc.queryForObject("SELECT COUNT(*) FROM journal_event WHERE scope='HIVE' AND kind='test' AND severity='INFO'", Integer.class);
    Integer start = jdbc.queryForObject(
        "SELECT COUNT(*) FROM journal_event WHERE scope='HIVE' AND kind='infra' AND type='journal-backpressure-start'", Integer.class);
    Integer stop = jdbc.queryForObject(
        "SELECT COUNT(*) FROM journal_event WHERE scope='HIVE' AND kind='infra' AND type='journal-backpressure-stop'", Integer.class);

    assertThat(info).isEqualTo(2);
    assertThat(start).isEqualTo(1);
    assertThat(stop).isEqualTo(1);
  }

  @Test
  void pinRunCopiesToArchiveAndPageReadsFromArchive() {
    jdbc.update("DELETE FROM journal_event_archive");
    jdbc.update("DELETE FROM journal_capture");
    jdbc.update("DELETE FROM journal_event");

    Instant base = Instant.parse("2025-01-01T00:00:00Z");
    for (int i = 0; i < 3; i++) {
      jdbc.update(
          """
              INSERT INTO journal_event (
                ts,
                scope,
                swarm_id,
                run_id,
                scope_role,
                scope_instance,
                severity,
                direction,
                kind,
                type,
                origin,
                raw
              ) VALUES (
                ?,
                'SWARM',
                ?,
                ?,
                'swarm-controller',
                'inst-1',
                'INFO',
                'IN',
                'signal',
                ?,
                'origin',
                ?::jsonb
              )
              """,
          java.sql.Timestamp.from(base.plusSeconds(i)),
          "sw1",
          "run-1",
          "t-" + i,
          "{\"x\":" + i + "}");
    }

    SwarmJournalController ctrl = new SwarmJournalController(mapper, jdbc, store);
    ReflectionTestUtils.setField(ctrl, "journalSink", "postgres");

    ResponseEntity<SwarmJournalController.PinRunResponse> pinned =
        ctrl.pinSwarmJournalRun("sw1", new SwarmJournalController.PinRunRequest("run-1", "SLIM", null));
    assertThat(pinned.getStatusCode().value()).isEqualTo(200);
    assertThat(pinned.getBody()).isNotNull();
    assertThat(pinned.getBody().runId()).isEqualTo("run-1");
    assertThat(pinned.getBody().mode()).isEqualTo("SLIM");

    jdbc.update("DELETE FROM journal_event WHERE scope='SWARM' AND swarm_id='sw1' AND run_id='run-1'");

    JournalPageResponse page = ctrl.journalPage("sw1", null, null, 10, "run-1", null).getBody();
    assertThat(page).isNotNull();
    assertThat(page.items()).hasSize(3);
    Map<String, Object> first = (Map<String, Object>) page.items().get(0);
    assertThat(first.get("runId")).isEqualTo("run-1");
    assertThat(first.get("raw")).isNull();
    assertThat(first.get("extra")).isNull();
  }

  @Test
  void resolveRunIdFallsBackToPinnedRunsWhenMainIsGone() {
    jdbc.update("DELETE FROM journal_event_archive");
    jdbc.update("DELETE FROM journal_capture");
    jdbc.update("DELETE FROM journal_event");

    Instant base = Instant.parse("2025-01-01T00:00:00Z");
    jdbc.update(
        """
            INSERT INTO journal_event (
              ts,
              scope,
              swarm_id,
              run_id,
              scope_role,
              scope_instance,
              severity,
              direction,
              kind,
              type,
              origin
            ) VALUES (
              ?,
              'SWARM',
              ?,
              ?,
              'swarm-controller',
              'inst-1',
              'INFO',
              'IN',
              'signal',
              't-1',
              'origin'
            )
            """,
        java.sql.Timestamp.from(base),
        "sw1",
        "run-1");

    SwarmJournalController ctrl = new SwarmJournalController(mapper, jdbc, store);
    ReflectionTestUtils.setField(ctrl, "journalSink", "postgres");

    ctrl.pinSwarmJournalRun("sw1", new SwarmJournalController.PinRunRequest("run-1", "FULL", null));
    jdbc.update("DELETE FROM journal_event WHERE scope='SWARM' AND swarm_id='sw1' AND run_id='run-1'");

    JournalPageResponse page = ctrl.journalPage("sw1", null, null, 10, null, null).getBody();
    assertThat(page).isNotNull();
    assertThat(page.items()).hasSize(1);
  }
}
