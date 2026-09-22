package io.pockethive.acceptance.suites;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.swarm.model.BeeRoles;
import io.pockethive.swarm.model.lifecycle.SwarmStateView;
import io.pockethive.work.api.HttpResultEnvelope;
import io.pockethive.work.api.WorkItem;
import java.io.IOException;

/**
 * Responsibility: assert captured HTTP results and the selected swarm/processor identity.
 * Must not: construct outcomes, resolve transport addresses or own resource cleanup.
 * Contract: docs/architecture/acceptance-tests.md#worker-runtime-acceptance-slice — WK-1/WK-2/WK-3/SC-4/SW-1.
 */
final class HttpWorkAssertions {
  private HttpWorkAssertions() {}

  static String processorInstance(SwarmStateView state) {
    var processors = state.bees().stream().filter(bee -> BeeRoles.PROCESSOR.equals(bee.role())).toList();
    assertEquals(1, processors.size(), "HTTP fixtures require exactly one processor instance");
    return processors.getFirst().instance();
  }

  static HttpResultEnvelope requireSuccessfulResponse(WorkItem item, String swarmId, String processor,
                                                     String expectedResponse) throws IOException {
    HttpResultEnvelope result = item.asJson(HttpResultEnvelope.class);
    var json = new ObjectMapper();
    assertAll("Captured HTTP result",
        () -> assertEquals(swarmId, item.observabilityContext().orElseThrow().getSwarmId()),
        () -> assertEquals(BeeRoles.PROCESSOR, item.stepHeaders().get(WorkItem.STEP_SERVICE_HEADER)),
        () -> assertEquals(processor, item.stepHeaders().get(WorkItem.STEP_INSTANCE_HEADER), "Producing processor instance"),
        () -> assertEquals(HttpResultEnvelope.OUTCOME_HTTP_RESPONSE, result.outcome().type()),
        () -> assertEquals(200, result.outcome().status()),
        () -> assertNull(result.outcome().error()),
        () -> assertEquals(json.readTree(expectedResponse), json.readTree(result.outcome().body())));
    return result;
  }
}
