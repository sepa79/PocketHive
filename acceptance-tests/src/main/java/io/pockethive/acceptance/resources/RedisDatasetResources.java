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
  private final RedisListResource first;
  private final RedisListResource second;
  private final RunEvidence evidence;

  public RedisDatasetResources(SwarmResource swarm, ScenarioResource scenario,
      RedisListResource first, RedisListResource second, RunEvidence evidence) {
    this.swarm = swarm;
    this.scenario = scenario;
    this.first = first;
    this.second = second;
    this.evidence = evidence;
  }

  @Override public void close() throws IOException, InterruptedException {
    if (!swarm.permitsDependentCleanup()) {
      var retained = Map.of("swarmId", swarm.id(), "scenarioId", scenario.id(),
          "redisKeys", List.of(first.key(), second.key()));
      evidence.record("retained-dataset-resources", retained);
      throw new AssertionError("Dataset cleanup deferred: swarm removal is unconfirmed; retained identifiers " + retained);
    }
    try (first; second; scenario) {
      // Close all independent dependencies, preserving any cleanup failures.
    }
  }
}
