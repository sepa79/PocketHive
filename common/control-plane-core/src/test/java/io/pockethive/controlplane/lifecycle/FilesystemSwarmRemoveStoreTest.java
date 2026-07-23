package io.pockethive.controlplane.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.swarm.model.lifecycle.RemoveRequest;
import io.pockethive.swarm.model.lifecycle.RemoveResource;
import io.pockethive.swarm.model.lifecycle.RemoveResourceType;
import io.pockethive.swarm.model.lifecycle.RemoveResult;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FilesystemSwarmRemoveStoreTest {

  @TempDir Path root;

  @Test
  void sharesOneImmutableRequestAndResultPathContract() {
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    FilesystemSwarmRemoveStore store = new FilesystemSwarmRemoveStore(mapper, root);
    RemoveRequest request = RemoveRequest.create(
        "alpha", "run-1", "controller-1", "corr-1", "idem-1", Instant.parse("2026-07-22T12:00:00Z"));

    Path first = store.saveRequest(request);
    assertThat(store.saveRequest(request)).isEqualTo(first);
    assertThat(store.loadRequest("alpha", "corr-1")).isEqualTo(request);
    assertThat(first.toString()).endsWith("alpha/operations/remove/corr-1/request.json");

    RemoveResult result = RemoveResult.succeeded(
        "alpha", "run-1", "controller-1", "corr-1", "idem-1",
        List.of(new RemoveResource(RemoveResourceType.WORKER_RUNTIME, "worker-1")),
        Instant.parse("2026-07-22T12:00:01Z"));
    store.saveResult(result);
    store.saveResult(result);

    assertThat(store.findResult("alpha", "corr-1")).contains(result);
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

  @Test
  void deletesOnlyTheRequestedSwarmRuntimeTree() {
    FilesystemSwarmRemoveStore store = new FilesystemSwarmRemoveStore(new ObjectMapper(), root);
    store.saveRequest(RemoveRequest.create(
        "alpha", "run-1", "controller-1", "corr-1", "idem-1", Instant.parse("2026-07-22T12:00:00Z")));
    store.saveRequest(RemoveRequest.create(
        "beta", "run-2", "controller-2", "corr-2", "idem-2", Instant.parse("2026-07-22T12:00:00Z")));

    store.deleteSwarmRuntime("alpha");

    assertThat(root.resolve("alpha")).doesNotExist();
    assertThat(root.resolve("beta/operations/remove/corr-2/request.json")).isRegularFile();
  }
}
