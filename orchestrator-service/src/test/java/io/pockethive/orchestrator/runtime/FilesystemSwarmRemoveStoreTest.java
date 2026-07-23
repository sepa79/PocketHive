package io.pockethive.orchestrator.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.swarm.model.lifecycle.RemoveRequest;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FilesystemSwarmRemoveStoreTest {

  @TempDir Path root;

  @Test
  void writesOneImmutableRequestPerCorrelationId() {
    FilesystemSwarmRemoveStore store = new FilesystemSwarmRemoveStore(new ObjectMapper(), root);
    RemoveRequest request = RemoveRequest.create(
        "alpha", "run-1", "controller-1", "corr-1", "idem-1", Instant.parse("2026-07-22T12:00:00Z"));

    Path first = store.saveRequest(request);
    Path duplicate = store.saveRequest(request);

    assertThat(first).isEqualTo(duplicate);
    assertThat(first).isRegularFile();
    assertThat(first.toString()).endsWith("alpha/operations/remove/corr-1/request.json");
  }

  @Test
  void rejectsDifferentContentAtTheSameImmutablePath() {
    FilesystemSwarmRemoveStore store = new FilesystemSwarmRemoveStore(new ObjectMapper(), root);
    store.saveRequest(RemoveRequest.create(
        "alpha", "run-1", "controller-1", "corr-1", "idem-1", Instant.parse("2026-07-22T12:00:00Z")));

    assertThatThrownBy(() -> store.saveRequest(RemoveRequest.create(
        "alpha", "run-1", "controller-1", "corr-1", "idem-1", Instant.parse("2026-07-22T12:00:01Z"))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Immutable");
  }
}
