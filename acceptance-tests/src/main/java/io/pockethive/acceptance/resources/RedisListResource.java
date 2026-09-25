package io.pockethive.acceptance.resources;

import com.fasterxml.jackson.databind.JsonNode;
import io.pockethive.acceptance.api.RedisCommanderApi;
import io.pockethive.acceptance.evidence.RunEvidence;
import java.io.IOException;
import java.util.UUID;

/**
 * Responsibility: own one UUID Redis list fixture, seeded or reserved for a producer, and verify exact-key cleanup.
 * Must not: delete pre-existing keys, flush databases, retry writes or decide dataset behavior.
 * Contract: RESP-ACCEPTANCE-REDIS-FIXTURE — docs/architecture/acceptance-tests.md#redis-fixture-preparation-da-prerequisite.
 */
public final class RedisListResource implements AutoCloseable {
  private final String key = "acceptance-redis-" + UUID.randomUUID();
  private final RedisCommanderApi api;
  private final RunEvidence evidence;
  private AcquisitionState state = AcquisitionState.NOT_REQUESTED;
  public RedisListResource(RedisCommanderApi api, RunEvidence evidence) {
    this.api = api;
    this.evidence = evidence;
  }
  public String key() { return key; }
  public JsonNode read() throws IOException, InterruptedException {
    var value = api.read(key);
    evidence.record(key + "-read", value);
    return value;
  }
  public void reserveForProducer() throws IOException, InterruptedException {
    requireUnusedAndAbsent();
    state = AcquisitionState.ACQUIRED;
    evidence.record(key + "-producer-reservation", java.util.Map.of("key", key));
  }
  private void requireUnusedAndAbsent() throws IOException, InterruptedException {
    if (state != AcquisitionState.NOT_REQUESTED) throw new IllegalStateException("Redis acquisition already attempted");
    if (!"none".equals(read().required("type").textValue())) throw new AssertionError("Redis fixture key already exists: " + key);
  }
  public void seed(String payload) throws IOException, InterruptedException {
    java.util.Objects.requireNonNull(payload, "payload");
    requireUnusedAndAbsent();
    state = AcquisitionState.UNCONFIRMED;
    var response = api.createList(key, payload);
    evidence.record(key + "-create", response);
    response.expect(200);
    if (!"ok".equals(response.body())) throw new AssertionError("Redis write not acknowledged: " + response.body());
    state = AcquisitionState.ACQUIRED;
    var value = read();
    if (!"list".equals(value.required("type").textValue())
        || !value.required("length").isIntegralNumber() || value.required("length").longValue() != 1) {
      throw new AssertionError("Redis seed must produce a one-item list: " + value);
    }
  }
  @Override public void close() throws IOException, InterruptedException {
    if (state == AcquisitionState.NOT_REQUESTED || state == AcquisitionState.RELEASED) return;
    var value = read();
    if ("none".equals(value.required("type").textValue())) {
      if (state == AcquisitionState.UNCONFIRMED) throw new AssertionError("Unconfirmed Redis write: " + key);
      state = AcquisitionState.RELEASED;
      evidence.record(key + "-absent", value);
      return;
    }
    var response = api.delete(key);
    evidence.record(key + "-delete", response);
    response.expect(200);
    var absent = read();
    if (!"none".equals(absent.required("type").textValue())) throw new AssertionError("Redis key remains after delete: " + key);
    evidence.record(key + "-absent", absent);
    state = AcquisitionState.RELEASED;
  }
}
