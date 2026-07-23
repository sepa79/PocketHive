package io.pockethive.swarmcontroller.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.swarm.model.lifecycle.RemoveRequest;
import io.pockethive.swarm.model.lifecycle.RemoveResource;
import io.pockethive.swarm.model.lifecycle.RemoveResourceType;
import io.pockethive.swarm.model.lifecycle.RemoveResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FilesystemSwarmRemoveStoreTest {

  @TempDir Path root;

  @Test
  void readsTheExactRequestAndWritesTheMatchingResult() throws Exception {
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    Path directory = root.resolve("alpha/operations/remove/corr-1");
    Files.createDirectories(directory);
    RemoveRequest request = RemoveRequest.create(
        "alpha", "run-1", "controller-1", "corr-1", "idem-1", Instant.parse("2026-07-22T12:00:00Z"));
    mapper.writeValue(directory.resolve("request.json").toFile(), request);
    FilesystemSwarmRemoveStore store = new FilesystemSwarmRemoveStore(mapper, root);

    assertThat(store.loadRequest("alpha", "corr-1")).isEqualTo(request);
    RemoveResult result = RemoveResult.succeeded(
        "alpha", "run-1", "controller-1", "corr-1", "idem-1",
        List.of(new RemoveResource(RemoveResourceType.WORKER_RUNTIME, "worker-1")),
        Instant.parse("2026-07-22T12:00:01Z"));
    store.saveResult(result);
    store.saveResult(result);

    assertThat(mapper.readValue(directory.resolve("result.json").toFile(), RemoveResult.class)).isEqualTo(result);
  }
}
