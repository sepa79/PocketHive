package io.pockethive.orchestrator.infra;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.orchestrator.app.JournalRunRegistration;
import io.pockethive.orchestrator.domain.SwarmTemplateMetadata;
import io.pockethive.journal.api.*;
import io.pockethive.journal.postgres.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class PostgresJournalRunMetadataTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void setup() {
        var ds = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(ds);
    }

    @BeforeEach
    void clear() {
        jdbc.update("DELETE FROM journal_event");
        jdbc.update("DELETE FROM journal_capture");
        jdbc.update("DELETE FROM journal_run");
    }

    private JournalRunMetadata metadata() {
        var json = new ObjectMapper();
        return new PostgresJournalRunMetadata(jdbc, json, new PostgresJournalRunQueries(jdbc, json));
    }

    @Test
    void registrationAndOperatorEditsPreserveEachOthersFieldsAndNullBodyClearsOnlyOperatorFields() throws Exception {
        var metadata = metadata();
        new JournalRunRegistration(metadata).upsertOnSwarmStart("alpha", "run",
            new SwarmTemplateMetadata("scenario", "image", List.of()));
        var updated = metadata.update("run", new SwarmRunMetadataUpdate(" plan ", " description ", List.of("tag")));
        assertThat(updated.scenarioId()).isEqualTo("scenario");
        assertThat(updated.testPlan()).isEqualTo("plan");
        assertThat(updated.description()).isEqualTo("description");
        assertThat(updated.tags()).containsExactly("tag");
        new JournalRunRegistration(metadata).upsertOnSwarmStart("alpha", "run", null);
        var read = new PostgresJournalRunQueries(jdbc, new ObjectMapper()).metadataSummary("alpha", "run");
        assertThat(read).isEqualTo(updated);
        metadata.register("alpha", "run", "new-scenario");
        var cleared = metadata.update("run", null);
        assertThat(cleared.scenarioId()).isEqualTo("new-scenario");
        assertThat(cleared.testPlan()).isNull();
        assertThat(cleared.description()).isNull();
        assertThat(cleared.tags()).isNull();
        assertThat(cleared.entries()).isZero();
    }

    @Test
    void usesEventOrCaptureIdentityWhenMetadataAbsentAndPreservesExistingPrecedence() throws Exception {
        var metadata = metadata();
        assertThat(metadata.update("missing", null)).isNull();
        jdbc.update("""
            INSERT INTO journal_event (ts, scope, swarm_id, run_id, scope_role, scope_instance,
                severity, direction, kind, type, origin)
            VALUES (now(), 'SWARM', 'alpha', 'event-run', 'processor', 'one', 'INFO', 'IN', 'event', 'test', 'test')
            """);
        assertThat(metadata.update("event-run", null).swarmId()).isEqualTo("alpha");
        new PostgresJournalCaptures(jdbc).pin("beta", "capture-run", null);
        assertThat(metadata.update("capture-run", null).swarmId()).isEqualTo("beta");
        metadata.register("alpha", "shared", "scenario");
        new PostgresJournalCaptures(jdbc).pin("beta", "shared", null);
        assertThat(metadata.update("shared", null).swarmId()).isEqualTo("alpha");
    }

    @Test
    void rejectsAmbiguousRunBeforeWritingAndIgnoresInvalidStartupIdentity() {
        var metadata = metadata();
        metadata.register("alpha", "shared", "scenario");
        metadata.register("beta", "shared", "scenario");
        metadata.register("", "invalid", "scenario");
        metadata.register("gamma", null, "scenario");
        assertThatThrownBy(() -> metadata.update("shared", new SwarmRunMetadataUpdate("plan", null, null)))
            .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM journal_run", Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM journal_run WHERE test_plan IS NOT NULL", Long.class)).isZero();
    }
}
