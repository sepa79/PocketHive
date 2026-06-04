package io.pockethive.dbquery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.pockethive.observability.ObservabilityContext;
import io.pockethive.swarm.model.OutcomeHeaders;
import io.pockethive.worker.sdk.api.StatusPublisher;
import io.pockethive.worker.sdk.api.WorkItem;
import io.pockethive.worker.sdk.api.WorkerContext;
import io.pockethive.worker.sdk.api.WorkerInfo;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DbQueryRunnerTest {

  private static final Logger LOG = LoggerFactory.getLogger(DbQueryRunnerTest.class);

  private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

  @TempDir
  Path tempDir;

  @Test
  void executesSelectValidatesAndExtracts() throws Exception {
    writeTemplate("""
        serviceId: db
        queryId: dblink-check
        adapter: POSTGRES
        statementType: SELECT
        sqlTemplate: |
          select status, remote_marker from dblink_probe where probe_id = :probeId
        params:
          - name: probeId
            source: payload.probeId
            type: STRING
        result:
          mode: FIRST_ROW
        validation:
          minRows: 1
          columns:
            - name: status
              equals: OK
            - name: remote_marker
              notNull: true
            - name: remote_marker
              regex: "^DBLINK-"
        extracts:
          - fromColumn: remote_marker
            to: dblinkMarker
            required: true
        """);
    RecordingExecutor executor = new RecordingExecutor();
    executor.enqueue(new DbExecutionResult(List.of(Map.of("status", "OK", "remote_marker", "DBLINK-42")), null, 12));

    WorkItem seed = seed("{\"probeId\":\"P-1\"}");
    WorkItem out = newRunner(executor).run(seed, context(), config());

    assertThat(executor.calls()).hasSize(1);
    assertThat(executor.calls().getFirst().sql().jdbcSql()).contains("where probe_id = ?");
    assertThat(executor.calls().getFirst().sql().values()).containsExactly("P-1");
    JsonNode payload = mapper.readTree(out.payload());
    assertThat(payload.get("dblinkMarker").asText()).isEqualTo("DBLINK-42");
    assertThat(payload.get("dbQuery").get("rowCount").asInt()).isEqualTo(1);
    assertThat(out.stepHeaders())
        .containsEntry(DbQueryConstants.HEADER_QUERY_ID, "dblink-check")
        .containsEntry(DbQueryConstants.HEADER_ROWS, 1)
        .containsEntry(OutcomeHeaders.CALL_ID, "db/dblink-check")
        .containsEntry(OutcomeHeaders.PROCESSOR_STATUS, DbQueryConstants.OUTCOME_STATUS_SUCCESS)
        .containsEntry(OutcomeHeaders.PROCESSOR_SUCCESS, true)
        .containsEntry(OutcomeHeaders.PROCESSOR_DURATION_MS, 12L)
        .containsEntry(OutcomeHeaders.BUSINESS_CODE, DbQueryConstants.OUTCOME_BUSINESS_CODE_OK)
        .containsEntry(OutcomeHeaders.BUSINESS_SUCCESS, true)
        .containsEntry(OutcomeHeaders.dimension(DbQueryConstants.OUTCOME_DIMENSION_ADAPTER), "POSTGRES")
        .containsEntry(OutcomeHeaders.dimension(DbQueryConstants.OUTCOME_DIMENSION_QUERY_ID), "dblink-check")
        .containsEntry(OutcomeHeaders.dimension(DbQueryConstants.OUTCOME_DIMENSION_ROW_COUNT), 1);
  }

  @Test
  void retriesSqlStateClassBeforeSuccess() throws Exception {
    writeTemplate("""
        serviceId: db
        queryId: dblink-check
        adapter: POSTGRES
        statementType: SELECT
        sqlTemplate: select status from probe where probe_id = :probeId
        params:
          - name: probeId
            source: payload.probeId
            type: STRING
        result:
          mode: FIRST_ROW
        """);
    RecordingExecutor executor = new RecordingExecutor();
    executor.enqueue(new SQLException("connection broke", "08006"));
    executor.enqueue(new DbExecutionResult(List.of(Map.of("status", "OK")), null, 5));

    DbQueryWorkerConfig config = new DbQueryWorkerConfig(
        DbQueryWorkerConfig.Adapter.POSTGRES,
        tempDir.toString(),
        "db",
        "dblink-check",
        1,
        30000,
        connection(),
        pool(),
        new DbQueryWorkerConfig.Retry(2, 0, 1.0, 0, List.of("SQLSTATE_CLASS_08")),
        Map.of());

    WorkItem out = newRunner(executor).run(seed("{\"probeId\":\"P-1\"}"), context(), config);

    assertThat(executor.calls()).hasSize(2);
    assertThat(out.stepHeaders()).containsEntry(DbQueryConstants.HEADER_ATTEMPTS, 2);
  }

  @Test
  void validationFailureAborts() throws Exception {
    writeTemplate("""
        serviceId: db
        queryId: dblink-check
        adapter: POSTGRES
        statementType: SELECT
        sqlTemplate: select status from probe where probe_id = :probeId
        params:
          - name: probeId
            source: payload.probeId
            type: STRING
        result:
          mode: FIRST_ROW
        validation:
          columns:
            - name: status
              equals: OK
        """);
    RecordingExecutor executor = new RecordingExecutor();
    executor.enqueue(new DbExecutionResult(List.of(Map.of("status", "PENDING")), null, 3));

    assertThatThrownBy(() -> newRunner(executor).run(seed("{\"probeId\":\"P-1\"}"), context(), config()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("expected OK");
  }

  @Test
  void missingParamDefinitionFailsLoudly() throws Exception {
    writeTemplate("""
        serviceId: db
        queryId: dblink-check
        adapter: POSTGRES
        statementType: SELECT
        sqlTemplate: select status from probe where probe_id = :probeId
        result:
          mode: FIRST_ROW
        """);

    assertThatThrownBy(() -> newRunner(new RecordingExecutor()).run(seed("{\"probeId\":\"P-1\"}"), context(), config()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("has no params[] definition");
  }

  private DbQueryRunner newRunner(RecordingExecutor executor) {
    return new DbQueryRunner(mapper, new DbQueryTemplateLoader(), new NamedSqlParser(), executor);
  }

  private DbQueryWorkerConfig config() {
    return new DbQueryWorkerConfig(
        DbQueryWorkerConfig.Adapter.POSTGRES,
        tempDir.toString(),
        "db",
        "dblink-check",
        1,
        30000,
        connection(),
        pool(),
        DbQueryWorkerConfig.Retry.noRetry(),
        Map.of());
  }

  private DbQueryWorkerConfig.Connection connection() {
    return new DbQueryWorkerConfig.Connection("jdbc:postgresql://example/db", "user", "pass");
  }

  private DbQueryWorkerConfig.Pool pool() {
    return new DbQueryWorkerConfig.Pool(2, 1, 1000, 500, 60000, 1800000);
  }

  private WorkItem seed(String payload) {
    WorkerInfo info = new WorkerInfo(DbQueryConstants.ROLE, "swarm-1", "inst-1", null, null);
    return WorkItem.text(info, payload).contentType("application/json").build();
  }

  private WorkerContext context() {
    return new TestWorkerContext(new WorkerInfo(DbQueryConstants.ROLE, "swarm-1", "inst-1", null, null));
  }

  private void writeTemplate(String yaml) throws Exception {
    Files.writeString(tempDir.resolve("dblink-check.yaml"), yaml);
  }

  private static final class RecordingExecutor implements DbStatementExecutor {
    private final ArrayDeque<Object> results = new ArrayDeque<>();
    private final java.util.List<Call> calls = new java.util.ArrayList<>();

    void enqueue(Object resultOrException) {
      results.add(resultOrException);
    }

    java.util.List<Call> calls() {
      return List.copyOf(calls);
    }

    @Override
    public DbExecutionResult execute(DbQueryWorkerConfig config, DbQueryTemplate template, BoundSql sql) throws SQLException {
      calls.add(new Call(config, template, sql));
      Object next = results.isEmpty()
          ? new DbExecutionResult(List.of(Map.of("status", "OK")), null, 1)
          : results.removeFirst();
      if (next instanceof SQLException sqlException) {
        throw sqlException;
      }
      return (DbExecutionResult) next;
    }
  }

  private record Call(DbQueryWorkerConfig config, DbQueryTemplate template, BoundSql sql) {
  }

  private static final class TestWorkerContext implements WorkerContext {
    private final WorkerInfo info;

    private TestWorkerContext(WorkerInfo info) {
      this.info = info;
    }

    @Override
    public WorkerInfo info() {
      return info;
    }

    @Override
    public boolean enabled() {
      return true;
    }

    @Override
    public <C> C config(Class<C> type) {
      return null;
    }

    @Override
    public StatusPublisher statusPublisher() {
      return StatusPublisher.NO_OP;
    }

    @Override
    public Logger logger() {
      return LOG;
    }

    @Override
    public SimpleMeterRegistry meterRegistry() {
      return new SimpleMeterRegistry();
    }

    @Override
    public ObservationRegistry observationRegistry() {
      return ObservationRegistry.create();
    }

    @Override
    public ObservabilityContext observabilityContext() {
      return new ObservabilityContext();
    }
  }
}
