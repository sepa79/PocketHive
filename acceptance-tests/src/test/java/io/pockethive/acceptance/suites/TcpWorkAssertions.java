package io.pockethive.acceptance.suites;

import static org.junit.jupiter.api.Assertions.*;
import io.pockethive.swarm.model.BeeRoles;
import io.pockethive.work.api.TcpResultEnvelope;
import io.pockethive.work.api.WorkItem;
import java.io.IOException;

/**
 * Responsibility: compare captured TCP results with the owned processor and explicit fixture.
 * Must not: build transport settings, endpoints or domain outcomes.
 * Contract: docs/architecture/acceptance-tests.md#network-acceptance-extension-nw-2nw-3nw-5.
 */
final class TcpWorkAssertions {
  private TcpWorkAssertions() {}
  static TcpResultEnvelope requireSuccessfulResponse(WorkItem item, String swarmId, String processor,
                                                    String expectedResponse) throws IOException {
    var result = item.asJson(TcpResultEnvelope.class);
    assertAll("Captured TCP result",
        () -> assertEquals(swarmId, item.observabilityContext().orElseThrow().getSwarmId()),
        () -> assertEquals(BeeRoles.PROCESSOR, item.stepHeaders().get(WorkItem.STEP_SERVICE_HEADER)),
        () -> assertEquals(processor, item.stepHeaders().get(WorkItem.STEP_INSTANCE_HEADER)),
        () -> assertEquals(TcpResultEnvelope.OUTCOME_TCP_RESPONSE, result.outcome().type()),
        () -> assertEquals(200, result.outcome().status()),
        () -> assertNull(result.outcome().error()),
        () -> assertEquals(expectedResponse, result.outcome().body()));
    return result;
  }
}
