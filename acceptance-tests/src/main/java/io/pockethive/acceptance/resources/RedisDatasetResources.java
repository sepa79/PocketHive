package io.pockethive.acceptance.resources;

import io.pockethive.acceptance.evidence.RunEvidence;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Responsibility: release dataset dependencies only when their swarm handle permits cleanup.
 * Must not: infer swarm outcomes, retry removal or implement individual resource acquisition/deletion.
 * Contract: RESP-ACCEPTANCE-RESOURCES — docs/architecture/acceptance-tests.md#redis-dataset-acceptance-da-1da-2.
 */
public final class RedisDatasetResources implements AutoCloseable {
  private final SwarmResource swarm;
  private final ScenarioResource scenario;
  private final List<RedisListResource> lists;
  private final RunEvidence evidence;

  public RedisDatasetResources(SwarmResource swarm, ScenarioResource scenario,
      List<RedisListResource> lists, RunEvidence evidence) {
    this.swarm = swarm;
    this.scenario = scenario;
    this.lists = List.copyOf(lists);
    this.evidence = evidence;
  }

  @Override public void close() throws IOException, InterruptedException {
    if (!swarm.permitsDependentCleanup()) {
      var retained = Map.of("swarmId", swarm.id(), "scenarioId", scenario.id(),
          "redisKeys", lists.stream().map(RedisListResource::key).toList());
      evidence.record("retained-dataset-resources", retained);
      throw new AssertionError("Dataset cleanup deferred: swarm removal is unconfirmed; retained identifiers " + retained);
    }
    closeDependencies(0);
  }
  private void closeDependencies(int index) throws IOException, InterruptedException {
    if (index == lists.size()) { scenario.close(); return; }
    // Nested scopes preserve suppression and attempt every close, even after a failure.
    try (var list = lists.get(index)) { closeDependencies(index + 1); }
  }
}
