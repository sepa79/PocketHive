package io.pockethive.acceptance.suites;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.swarm.model.BeeRoles;
import io.pockethive.swarm.model.lifecycle.WorkloadState;
import io.pockethive.work.api.HttpRequestEnvelope;
import io.pockethive.work.api.WorkItem;
import io.pockethive.work.api.WorkPayloadEncoding;
import java.util.Map;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Responsibility: verify concrete templating and selected scenario-variable values in processed traffic.
 * Must not: render templates, resolve variables, implement lifecycle or access a broker/SUT management port.
 * Contract: docs/architecture/acceptance-tests.md#templating-and-scenario-variables-acceptance-slice — WK-3/SC-4.
 */
@Tag("templating")
class TemplatingAcceptanceIT {
  @ParameterizedTest(name = "profile {0}")
  @MethodSource("profiles")
  void rendersSelectedProfileInRequestBodyAndHeaders(String profile, String expectedJson) throws Exception {
    var json = new ObjectMapper();
    var expectedBody = json.readTree(expectedJson);
    try (var run = LiveRun.open("templating-" + profile); var swarm = run.newSwarm()) {
      run.evidence.record("expected-rendering", Map.of("profile", profile, "body", expectedBody));
      swarm.create(run.createRequest(profile));
      try (var tap = run.newTap()) {
        tap.open(swarm.id(), run.target.fixture().tap());
        swarm.start();
        var running = run.swarms.state(swarm.id());
        assertEquals(swarm.id(), running.id());
        assertEquals(swarm.runId(), running.runId());
        assertEquals(WorkloadState.RUNNING, running.workloadState());
        String processor = HttpWorkAssertions.processorInstance(running);
        var generators = running.bees().stream().filter(bee -> BeeRoles.GENERATOR.equals(bee.role())).toList();
        assertEquals(1, generators.size(), "Fixture requires one generating worker");
        String generator = generators.getFirst().instance();
        var samples = tap.awaitSamples(run.target.fixture().samples());
        assertEquals(run.target.fixture().samples(), samples.stream().map(WorkItem::messageId).distinct().count());
        for (var item : samples) {
          var result = HttpWorkAssertions.requireSuccessfulResponse(
              item, swarm.id(), processor, run.target.fixture().expectedResponse());
          var generatorSteps = StreamSupport.stream(item.steps().spliterator(), false)
              .filter(step -> BeeRoles.GENERATOR.equals(step.headers().get(WorkItem.STEP_SERVICE_HEADER))).toList();
          assertFalse(generatorSteps.isEmpty(), "FULL history must retain the generated HTTP request");
          var generated = generatorSteps.getLast();
          assertEquals(generator, generated.headers().get(WorkItem.STEP_INSTANCE_HEADER));
          assertEquals(WorkPayloadEncoding.UTF_8, generated.payloadEncoding());
          var request = json.readValue(generated.payload(), HttpRequestEnvelope.class).request();
          assertAll("Rendered request for " + profile,
              () -> assertEquals("POST", request.method()),
              () -> assertEquals("/api/test", request.path()),
              () -> assertEquals(expectedBody, json.readTree(assertInstanceOf(String.class, request.body()))),
              () -> assertEquals(Map.of(
                  "content-type", "application/json",
                  "x-acceptance-customer", expectedBody.required("customer").textValue(),
                  "x-acceptance-next", expectedBody.required("next").asText()), request.headers()),
              () -> assertEquals(request.method(), result.request().method()),
              () -> assertEquals(request.path(), result.request().path()));
        }
      }
      swarm.stop();
      swarm.remove();
    }
  }

  private static Stream<Arguments> profiles() {
    return Stream.of(
        Arguments.of("amber", """
            {"source":"ACCEPTANCE","profile":"amber-selected","customer":"AMBER-CUSTOMER",
             "quantity":3,"next":4,"enabled":false,"choice":"OFF"}
            """),
        Arguments.of("violet", """
            {"source":"ACCEPTANCE","profile":"violet-selected","customer":"VIOLET-CUSTOMER",
             "quantity":8,"next":9,"enabled":true,"choice":"ON"}
            """));
  }
}
