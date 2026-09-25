package io.pockethive.acceptance.suites;

import static org.junit.jupiter.api.Assertions.*;
import io.pockethive.acceptance.config.TargetLoader;
import io.pockethive.acceptance.operations.Deadline;
import io.pockethive.acceptance.resources.RedisDatasetResources;
import io.pockethive.acceptance.resources.RedisListResource;
import io.pockethive.acceptance.resources.ScenarioResource;
import io.pockethive.swarm.model.BeeRoles;
import io.pockethive.swarm.model.NetworkMode;
import io.pockethive.swarm.model.lifecycle.SwarmCreateRequest;
import io.pockethive.swarm.model.lifecycle.WorkloadState;
import io.pockethive.work.api.TcpRequestEnvelope;
import io.pockethive.work.api.WorkItem;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Responsibility: exercise five isolated customers through the Redis WebAuth TCP loop.
 * Must not: mutate shared mock state, implement routing or remove data before confirmed swarm removal.
 * Contract: docs/architecture/acceptance-tests.md#five-customer-redis-webauth-loop-acceptance-da-4.
 */
@Tag("webauth-loop")
class WebAuthLoopAcceptanceIT {
  @Test void allFiveCustomersCompleteRedBalanceTopupAndReturnToRed() throws Exception {
    var target = TargetLoader.loadWebAuth(TargetLoader.selectedFile());
    assertEquals(5, target.lifecycle().fixture().samples());
    String nonce = UUID.randomUUID().toString();
    try (var run = LiveRun.open("webauth-loop", target.lifecycle())) {
      var lists = IntStream.range(0, 7).mapToObj(i -> new RedisListResource(run.redis(target.connectionId()), run.evidence)).toList();
      var scenario = new ScenarioResource("acceptance-webauth-" + UUID.randomUUID(), run.scenarios, run.evidence);
      var swarm = run.newSwarm();
      try (var dependencies = new RedisDatasetResources(swarm, scenario, lists, run.evidence); swarm) {
        var fixture = new WebAuthLoopFixture(lists.stream().map(RedisListResource::key).toList(), nonce);
        var owned = fixture.scenario(run.scenario, scenario.id());
        run.evidence.record("owned-fixture", owned);
        run.evidence.record("expected-requests", fixture.requests);
        scenario.create(owned, run.scenarios).expect(201);
        String template = fixture.substitute(run.scenarios.readTemplate(run.target.fixture().templateId(), WebAuthLoopFixture.TEMPLATE));
        run.scenarios.writeTemplate(scenario.id(), WebAuthLoopFixture.TEMPLATE, template);
        assertEquals(template, run.scenarios.readTemplate(scenario.id(), WebAuthLoopFixture.TEMPLATE));
        String sut = run.scenarios.readSutRaw(run.target.fixture().templateId(), run.target.fixture().sutId());
        run.scenarios.writeSutRaw(scenario.id(), run.target.fixture().sutId(), sut);
        assertEquals(sut, run.scenarios.readSutRaw(scenario.id(), run.target.fixture().sutId()));
        lists.get(5).reserveForProducer();
        lists.get(6).reserveForProducer();
        var mock = run.tcpMock(target.mockUsername(), target.mockPassword());
        var before = mock.requests(run.target.limits().request());
        run.evidence.record("tcp-before", before);
        assertFalse(StreamSupport.stream(before.spliterator(), false)
            .anyMatch(row -> row.required("message").textValue().contains(nonce)));
        swarm.create(SwarmCreateRequest.of(scenario.id(), UUID.randomUUID().toString(), false,
            run.target.fixture().sutId(), null, NetworkMode.DIRECT, null));
        var workers = WorkerObservations.awaitConfiguredWorkers(run, swarm,
            Set.of(BeeRoles.GENERATOR, BeeRoles.REQUEST_BUILDER, BeeRoles.PROCESSOR, BeeRoles.POSTPROCESSOR),
            "prepared-workers", (state, observed) -> state.workloadState() == WorkloadState.STOPPED
                && observed.stream().allMatch(worker -> worker.required("enabled").isBoolean()
                    && !worker.required("enabled").booleanValue()));
        String processor = workers.stream().filter(worker -> BeeRoles.PROCESSOR.equals(worker.required("role").textValue()))
            .findFirst().orElseThrow().required("instance").textValue();
        String builder = workers.stream().filter(worker -> BeeRoles.REQUEST_BUILDER.equals(worker.required("role").textValue()))
            .findFirst().orElseThrow().required("instance").textValue();
        for (int i = 0; i < 5; i++) lists.get(i).seed(fixture.records.get(i));
        try (var tap = run.newTap()) {
          tap.open(swarm.id(), run.target.fixture().tap());
          swarm.start();
          for (var item : tap.awaitSamples(run.target.fixture().samples())) {
            TcpWorkAssertions.requireSuccessfulResponse(item, swarm.id(), processor, run.target.fixture().expectedResponse());
            var built = StreamSupport.stream(item.steps().spliterator(), false)
                .filter(step -> BeeRoles.REQUEST_BUILDER.equals(step.headers().get(WorkItem.STEP_SERVICE_HEADER))).toList();
            assertEquals(1, built.size());
            assertEquals(builder, built.getFirst().headers().get(WorkItem.STEP_INSTANCE_HEADER));
            var request = new com.fasterxml.jackson.databind.ObjectMapper().readValue(built.getFirst().payload(), TcpRequestEnvelope.class);
            assertTrue(fixture.requests.values().stream().flatMap(java.util.List::stream)
                .anyMatch(message -> message.equals(request.request().body().strip())));
          }
        }
        var deadline = new Deadline(run.target.limits().capture(), "All five WebAuth customer loops for " + swarm.id());
        while (true) {
          var journal = mock.requests(deadline.remaining());
          run.evidence.record("tcp-journal", journal);
          if (WebAuthLoopAssertions.complete(journal, nonce, fixture.requests, run.target.fixture().expectedResponse())) break;
          deadline.pause(run.target.limits().poll());
        }
        swarm.stop();
        swarm.remove();
      }
    }
  }
}
