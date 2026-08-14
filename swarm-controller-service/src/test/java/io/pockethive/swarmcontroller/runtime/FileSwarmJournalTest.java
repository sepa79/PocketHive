package io.pockethive.swarmcontroller.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.control.ControlScope;
import io.pockethive.controlplane.filesystem.RuntimeFilesystemLayout;
import io.pockethive.observability.metrics.PocketHiveMetricsAdapter;
import io.pockethive.sink.clickhouse.metrics.ClickHouseMetricsSinkProperties;
import io.pockethive.swarmcontroller.config.SwarmControllerProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileSwarmJournalTest {

  @TempDir
  Path tempDir;

  @Test
  void appendsCanonicalEntry() throws IOException {
    FileSwarmJournal journal = journal(new ObjectMapper());

    journal.append(entry("swarm-a"));

    Path file = tempDir.resolve("swarm-a/run-a/journal.ndjson");
    assertThat(Files.readAllLines(file)).hasSize(1);
    assertThat(Files.readString(file)).contains("\"swarmId\":\"swarm-a\"");
  }

  @Test
  void rejectsEntryForAnotherSwarm() {
    FileSwarmJournal journal = journal(new ObjectMapper());

    assertThatThrownBy(() -> journal.append(entry("swarm-b")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("swarm-b")
        .hasMessageContaining("swarm-a");
  }

  @Test
  void propagatesSerializationFailure() throws JsonProcessingException {
    ObjectMapper mapper = spy(new ObjectMapper());
    FileSwarmJournal journal = journal(mapper);
    doThrow(new JsonProcessingException("broken") { })
        .when(mapper).writeValueAsString(org.mockito.ArgumentMatchers.any());

    assertThatThrownBy(() -> journal.append(entry("swarm-a")))
        .isInstanceOf(SwarmJournalWriteException.class)
        .hasMessageContaining("serialise");
  }

  @Test
  void propagatesAppendFailure() throws IOException {
    FileSwarmJournal journal = journal(new ObjectMapper());
    Files.createDirectory(tempDir.resolve("swarm-a/run-a/journal.ndjson"));

    assertThatThrownBy(() -> journal.append(entry("swarm-a")))
        .isInstanceOf(SwarmJournalWriteException.class)
        .hasMessageContaining("append");
  }

  private FileSwarmJournal journal(ObjectMapper mapper) {
    RuntimeFilesystemLayout layout = RuntimeFilesystemLayout.of(tempDir.toString(), tempDir.toString());
    return new FileSwarmJournal(mapper, properties(), "run-a", layout);
  }

  private static SwarmControllerProperties properties() {
    return new SwarmControllerProperties(
        "swarm-a",
        "ph.control",
        "ph.control",
        new SwarmControllerProperties.Manager("swarm-controller"),
        new SwarmControllerProperties.SwarmController(
            new SwarmControllerProperties.Traffic("ph.swarm-a.hive", "ph.swarm-a"),
            new SwarmControllerProperties.Metrics(
                PocketHiveMetricsAdapter.DISABLED,
                Duration.ofSeconds(10),
                ClickHouseMetricsSinkProperties.disabled()),
            new SwarmControllerProperties.Docker(null, "/var/run/docker.sock", null),
            new SwarmControllerProperties.Features(false)));
  }

  private static SwarmJournal.SwarmJournalEntry entry(String swarmId) {
    return new SwarmJournal.SwarmJournalEntry(
        Instant.parse("2026-07-23T10:00:00Z"),
        swarmId,
        "INFO",
        SwarmJournal.Direction.LOCAL,
        "test",
        "test-entry",
        "test",
        ControlScope.forSwarm(swarmId),
        "correlation-a",
        "idempotency-a",
        null,
        Map.of("ok", true),
        null,
        null);
  }
}
