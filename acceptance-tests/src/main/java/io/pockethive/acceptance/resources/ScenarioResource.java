package io.pockethive.acceptance.resources;

import com.fasterxml.jackson.databind.JsonNode;
import io.pockethive.acceptance.api.*;
import io.pockethive.acceptance.evidence.RunEvidence;
import java.io.IOException;

/**
 * Responsibility: retain a newly requested scenario ID and verify its deletion through the API.
 * Must not: modify existing fixtures, validate scenarios or clean up their filesystem directly.
 * Contract: RESP-ACCEPTANCE-RESOURCES — docs/architecture/acceptance-tests.md#scenario-and-swarm-authorization-au-7au-12.
 */
public final class ScenarioResource implements AutoCloseable {
  private final String id;
  private final ScenarioApi observer;
  private final RunEvidence evidence;
  private AcquisitionState state = AcquisitionState.NOT_REQUESTED;
  public ScenarioResource(String id, ScenarioApi observer, RunEvidence evidence) {
    this.id = id; this.observer = observer; this.evidence = evidence;
  }
  public String id() { return id; }
  public ApiResponse create(JsonNode body, ScenarioApi requester) throws IOException, InterruptedException {
    if (state != AcquisitionState.NOT_REQUESTED) throw new IllegalStateException("Scenario create already attempted");
    if (!id.equals(body.required("id").textValue())) throw new IllegalArgumentException("Scenario ID differs from owned ID");
    observer.read(id).expect(404);
    state = AcquisitionState.UNCONFIRMED;
    var response = requester.create(body);
    evidence.record("scenario-" + id + "-create", response);
    if (response.status() == 201) state = AcquisitionState.ACQUIRED;
    else if (response.status() >= 400 && response.status() < 500 && response.status() != 408) state = AcquisitionState.REJECTED;
    return response;
  }
  public void delete(ScenarioApi requester) throws IOException, InterruptedException {
    if (state == AcquisitionState.NOT_REQUESTED) throw new IllegalStateException("Scenario not requested");
    var response = requester.delete(id);
    evidence.record("scenario-" + id + "-delete", response);
    response.expect(204);
    verifyAbsent();
  }
  private void verifyAbsent() throws IOException, InterruptedException {
    var response = observer.read(id);
    evidence.record("scenario-" + id + "-after-delete", response);
    response.expect(404);
    state = AcquisitionState.RELEASED;
  }
  @Override public void close() throws IOException, InterruptedException {
    if (state == AcquisitionState.NOT_REQUESTED || state == AcquisitionState.RELEASED) return;
    var readback = observer.read(id);
    evidence.record("scenario-" + id + "-cleanup-read", readback);
    if (readback.status() == 404) {
      if (state == AcquisitionState.UNCONFIRMED) throw new AssertionError("Unconfirmed scenario create: " + id);
      state = AcquisitionState.RELEASED; return;
    }
    readback.expect(200);
    delete(observer);
  }
}
