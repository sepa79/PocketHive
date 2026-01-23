package io.pockethive.orchestrator.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.control.ConfirmationScope;
import io.pockethive.control.ControlSignal;
import io.pockethive.controlplane.ControlPlaneSignals;
import io.pockethive.controlplane.messaging.ControlPlanePublisher;
import io.pockethive.controlplane.messaging.SignalMessage;
import io.pockethive.controlplane.routing.ControlPlaneRouting;
import io.pockethive.controlplane.spring.ControlPlaneProperties;
import io.pockethive.orchestrator.domain.IdempotencyStore;
import io.pockethive.orchestrator.domain.ScenarioPlan;
import io.pockethive.orchestrator.domain.Swarm;
import io.pockethive.orchestrator.domain.SwarmCreateRequest;
import io.pockethive.orchestrator.domain.SwarmCreateTracker;
import io.pockethive.orchestrator.domain.SwarmCreateTracker.Phase;
import io.pockethive.orchestrator.domain.ScenarioTimelineRegistry;
import io.pockethive.orchestrator.domain.SwarmPlanRegistry;
import io.pockethive.orchestrator.domain.SwarmRegistry;
import io.pockethive.orchestrator.domain.SwarmStatus;
import io.pockethive.orchestrator.domain.SwarmTemplateMetadata;
import io.pockethive.orchestrator.domain.HiveJournal;
import io.pockethive.orchestrator.infra.InMemoryIdempotencyStore;
import io.pockethive.swarm.model.Bee;
import io.pockethive.swarm.model.SwarmPlan;
import io.pockethive.swarm.model.SwarmTemplate;
import io.pockethive.swarm.model.Work;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class SwarmControllerTest {

    @Mock
    ControlPlanePublisher publisher;

    @Mock
    ContainerLifecycleManager lifecycle;

    @Mock
    ScenarioClient scenarioClient;

    @Mock
    JdbcTemplate jdbc;

    private final ObjectMapper mapper = new JacksonConfiguration().objectMapper();

    @TempDir
    Path tempDir;

    @Test
    void startPublishesControlSignal() throws Exception {
        SwarmCreateTracker tracker = new SwarmCreateTracker();
        SwarmRegistry registry = new SwarmRegistry();
        registry.register(new Swarm("sw1", "inst", "c", "run-1"));
        registry.updateStatus("sw1", SwarmStatus.CREATING);
        registry.updateStatus("sw1", SwarmStatus.READY);
        SwarmController ctrl = controller(tracker, registry, new SwarmPlanRegistry());
        SwarmController.ControlRequest req = new SwarmController.ControlRequest("idem", null);

        ResponseEntity<ControlResponse> resp = ctrl.start("sw1", req);

        ArgumentCaptor<SignalMessage> captor = ArgumentCaptor.forClass(SignalMessage.class);
        verify(publisher).publishSignal(captor.capture());
        SignalMessage message = captor.getValue();
        assertThat(message.routingKey())
            .isEqualTo(ControlPlaneRouting.signal(ControlPlaneSignals.SWARM_START, "sw1", "swarm-controller", "inst"));
        assertThat(message.payload()).isInstanceOf(String.class);
        ControlSignal sig = mapper.readValue(message.payload().toString(), ControlSignal.class);
        assertThat(sig.type()).isEqualTo(ControlPlaneSignals.SWARM_START);
        assertThat(sig.scope().swarmId()).isEqualTo("sw1");
        assertThat(sig.idempotencyKey()).isEqualTo("idem");
        assertThat(resp.getBody().watch().successTopic()).isEqualTo(
            ControlPlaneRouting.event("outcome", ControlPlaneSignals.SWARM_START,
                new ConfirmationScope("sw1", "swarm-controller", "inst")));
        assertThat(tracker.complete("sw1", Phase.START)).isPresent();
        assertThat(registry.find("sw1").get().getStatus()).isEqualTo(SwarmStatus.STARTING);
    }

    @Test
    void createFetchesTemplateAndRegistersPlan() throws Exception {
        SwarmCreateTracker tracker = new SwarmCreateTracker();
        SwarmPlanRegistry plans = new SwarmPlanRegistry();
        SwarmTemplate template = new SwarmTemplate("ctrl-image", List.of(
            new Bee("generator", "img", Work.ofDefaults(null, "out"), java.util.Map.of())
        ));
        when(scenarioClient.fetchScenario("tpl-1")).thenReturn(new ScenarioPlan(template, null, null, null));
        when(scenarioClient.prepareScenarioRuntime("tpl-1", "sw1")).thenReturn("/tmp/runtime/sw1");
        AtomicReference<String> capturedInstance = new AtomicReference<>();
        when(lifecycle.startSwarm(
            eq("sw1"),
            eq("ctrl-image"),
            anyString(),
            any(SwarmTemplateMetadata.class),
            eq(false))).thenAnswer(inv -> {
            String instanceId = inv.getArgument(2);
            capturedInstance.set(instanceId);
            return new Swarm("sw1", instanceId, "c1", "run-1");
        });
        SwarmController ctrl = controller(tracker, new SwarmRegistry(), plans);
        SwarmCreateRequest req = new SwarmCreateRequest("tpl-1", "idem", null);

        ctrl.create("sw1", req);

        String instanceId = capturedInstance.get();
        assertThat(instanceId).isNotBlank();
        SwarmCreateTracker.Pending pending = tracker.remove(instanceId).orElseThrow();
        assertThat(pending.phase()).isEqualTo(Phase.CONTROLLER);
        assertThat(pending.correlationId()).isNotBlank();
        assertThat(plans.find(instanceId)).isPresent();
        verify(scenarioClient).fetchScenario("tpl-1");
    }

    @Test
    void startIsIdempotent() {
        SwarmRegistry registry = new SwarmRegistry();
        registry.register(new Swarm("sw1", "controller-inst", "ctrl", "run-1"));
        SwarmController ctrl = controller(new SwarmCreateTracker(), registry, new SwarmPlanRegistry());
        SwarmController.ControlRequest req = new SwarmController.ControlRequest("idem", null);

        ResponseEntity<ControlResponse> r1 = ctrl.start("sw1", req);
        ResponseEntity<ControlResponse> r2 = ctrl.start("sw1", req);

        verify(publisher, times(1)).publishSignal(any(SignalMessage.class));
        assertThat(r1.getBody().correlationId()).isEqualTo(r2.getBody().correlationId());
    }

    @Test
    void concurrentCreateRequestsReuseReservation() throws Exception {
        SwarmCreateTracker tracker = new SwarmCreateTracker();
        SwarmPlanRegistry plans = new SwarmPlanRegistry();
        SwarmRegistry registry = new SwarmRegistry();
        SwarmTemplate template = new SwarmTemplate("ctrl-image", List.of(
            new Bee("generator", "img", Work.ofDefaults(null, "out"), java.util.Map.of())
        ));
        when(scenarioClient.fetchScenario("tpl-1")).thenReturn(new ScenarioPlan(template, null, null, null));
        when(scenarioClient.prepareScenarioRuntime("tpl-1", "sw1")).thenReturn("/tmp/runtime/sw1");
        when(lifecycle.startSwarm(
            eq("sw1"),
            eq("ctrl-image"),
            anyString(),
            any(SwarmTemplateMetadata.class),
            eq(false)))
            .thenAnswer(invocation -> new Swarm("sw1", invocation.getArgument(2), "corr", "run-1"));
        IdempotencyStore store = new InMemoryIdempotencyStore();
        SwarmController ctrl = controller(tracker, registry, plans, store);
        SwarmCreateRequest request = new SwarmCreateRequest("tpl-1", "idem", null);

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<ResponseEntity<?>> first = executor.submit(() -> {
            start.await();
            return ctrl.create("sw1", request);
        });
        Future<ResponseEntity<?>> second = executor.submit(() -> {
            start.await();
            return ctrl.create("sw1", request);
        });

        start.countDown();
        ResponseEntity<?> response1 = first.get(5, TimeUnit.SECONDS);
        ResponseEntity<?> response2 = second.get(5, TimeUnit.SECONDS);
        executor.shutdownNow();

        verify(lifecycle, times(1)).startSwarm(
            eq("sw1"),
            eq("ctrl-image"),
            anyString(),
            any(SwarmTemplateMetadata.class),
            eq(false));
        verify(scenarioClient, times(1)).fetchScenario("tpl-1");
        assertThat(response1.getBody()).isInstanceOf(ControlResponse.class);
        assertThat(response2.getBody()).isInstanceOf(ControlResponse.class);
        ControlResponse body1 = (ControlResponse) response1.getBody();
        ControlResponse body2 = (ControlResponse) response2.getBody();
        assertThat(body1.correlationId()).isEqualTo(body2.correlationId());
    }

    @Test
    void concurrentCreateRequestsWithDifferentKeysShareLeaderCorrelation() throws Exception {
        SwarmCreateTracker tracker = new SwarmCreateTracker();
        SwarmPlanRegistry plans = new SwarmPlanRegistry();
        SwarmRegistry registry = new SwarmRegistry();
        SwarmTemplate template = new SwarmTemplate("ctrl-image", List.of(
            new Bee("generator", "img", Work.ofDefaults(null, "out"), java.util.Map.of())
        ));
        when(scenarioClient.fetchScenario("tpl-1")).thenReturn(new ScenarioPlan(template, null, null, null));
        when(scenarioClient.prepareScenarioRuntime("tpl-1", "sw1")).thenReturn("/tmp/runtime/sw1");
        when(lifecycle.startSwarm(
            eq("sw1"),
            eq("ctrl-image"),
            anyString(),
            any(SwarmTemplateMetadata.class),
            eq(false)))
            .thenAnswer(invocation -> new Swarm("sw1", invocation.getArgument(2), "corr", "run-1"));
        InMemoryIdempotencyStore store = new InMemoryIdempotencyStore();
        SwarmController ctrl = controller(tracker, registry, plans, store);
        SwarmCreateRequest leaderRequest = new SwarmCreateRequest("tpl-1", "idem-1", null);
        SwarmCreateRequest followerRequest = new SwarmCreateRequest("tpl-1", "idem-2", null);

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<ResponseEntity<?>> leader = executor.submit(() -> {
            start.await();
            return ctrl.create("sw1", leaderRequest);
        });
        Future<ResponseEntity<?>> follower = executor.submit(() -> {
            start.await();
            return ctrl.create("sw1", followerRequest);
        });

        start.countDown();
        ResponseEntity<?> leaderResponse = leader.get(5, TimeUnit.SECONDS);
        ResponseEntity<?> followerResponse = follower.get(5, TimeUnit.SECONDS);
        executor.shutdownNow();

        verify(lifecycle, times(1)).startSwarm(
            eq("sw1"),
            eq("ctrl-image"),
            anyString(),
            any(SwarmTemplateMetadata.class),
            eq(false));
        verify(scenarioClient, times(1)).fetchScenario("tpl-1");
        assertThat(leaderResponse.getBody()).isInstanceOf(ControlResponse.class);
        assertThat(followerResponse.getBody()).isInstanceOf(ControlResponse.class);
        ControlResponse leaderBody = (ControlResponse) leaderResponse.getBody();
        ControlResponse followerBody = (ControlResponse) followerResponse.getBody();
        assertThat(leaderBody.correlationId()).isEqualTo(followerBody.correlationId());
        assertThat(store.findCorrelation("sw1", "swarm-create", "idem-1").orElseThrow())
            .isEqualTo(leaderBody.correlationId());
        assertThat(store.findCorrelation("sw1", "swarm-create", "idem-2").orElseThrow())
            .isEqualTo(leaderBody.correlationId());
        assertThat(store.findCorrelation("sw1", "swarm-create", "__create-lock__")).isEmpty();
    }

    @Test
    void followerAfterControllerPendingReusesLeaderCorrelation() {
        SwarmCreateTracker tracker = new SwarmCreateTracker();
        SwarmPlanRegistry plans = new SwarmPlanRegistry();
        SwarmRegistry registry = new SwarmRegistry();
        InMemoryIdempotencyStore store = new InMemoryIdempotencyStore();
        tracker.register("leader-inst", new SwarmCreateTracker.Pending(
            "sw1",
            "leader-inst",
            "corr-leader",
            "idem-leader",
            Phase.CONTROLLER,
            Instant.now().plusSeconds(60)));
        SwarmController ctrl = controller(tracker, registry, plans, store);
        SwarmCreateRequest followerRequest = new SwarmCreateRequest("tpl-1", "idem-follower", null);

        ResponseEntity<?> response = ctrl.create("sw1", followerRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isInstanceOf(ControlResponse.class);
        ControlResponse body = (ControlResponse) response.getBody();
        assertThat(body.correlationId()).isEqualTo("corr-leader");
        assertThat(store.findCorrelation("sw1", "swarm-create", "idem-follower").orElseThrow())
            .isEqualTo("corr-leader");
        assertThat(store.findCorrelation("sw1", "swarm-create", "__create-lock__")).isEmpty();
        verifyNoInteractions(lifecycle);
        verifyNoInteractions(scenarioClient);
    }

    @Test
    void exposesSwarmSummaryWithTemplateMetadata() throws Exception {
        SwarmRegistry registry = new SwarmRegistry();
        Swarm swarm = new Swarm("sw1", "inst", "c", "run-1");
        String statusFull = """
            {
              "timestamp": "2026-01-22T12:00:00Z",
              "version": "1",
              "kind": "metric",
              "type": "status-full",
              "origin": "inst",
              "scope": { "swarmId": "sw1", "role": "swarm-controller", "instance": "inst" },
              "correlationId": null,
              "idempotencyKey": null,
              "data": {
                "enabled": true,
                "config": {},
                "startedAt": "2026-01-22T12:00:00Z",
                "runtime": {
                  "runId": "run-1",
                  "containerId": "container-1",
                  "image": "ctrl-image",
                  "stackName": "ph-sw1"
                },
                "io": {},
                "ioState": {},
                "context": {
                  "swarmStatus": "READY",
                  "swarmHealth": "RUNNING",
                  "template": {
                    "id": "tpl-1",
                    "image": "ctrl-image",
                    "bees": [ { "role": "generator", "image": "gen-image" } ]
                  }
                }
              }
            }
            """;
        swarm.updateControllerStatusFull(mapper.readTree(statusFull), Instant.now());
        registry.register(swarm);
        SwarmController ctrl = controller(new SwarmCreateTracker(), registry, new SwarmPlanRegistry());

        ResponseEntity<SwarmController.StatusFullSnapshot> resp = ctrl.view("sw1");
        SwarmController.StatusFullSnapshot body = resp.getBody();
        assertThat(body.envelope().path("scope").path("swarmId").asText()).isEqualTo("sw1");
        assertThat(body.envelope().path("data").path("enabled").asBoolean()).isTrue();
        assertThat(body.envelope().path("data").path("context").path("template").path("id").asText())
            .isEqualTo("tpl-1");
        assertThat(body.envelope().path("data").path("context").path("template").path("image").asText())
            .isEqualTo("ctrl-image");
        assertThat(body.envelope().path("data").path("context").path("template").path("bees").get(0).path("role").asText())
            .isEqualTo("generator");
    }

    @Test
    void listReturnsSortedSwarmsWithMetadata() throws Exception {
        SwarmRegistry registry = new SwarmRegistry();
        Swarm alpha = new Swarm("alpha", "inst-a", "c1", "run-a");
        String alphaStatusFull = """
            {
              "timestamp": "2026-01-22T12:00:01Z",
              "version": "1",
              "kind": "metric",
              "type": "status-full",
              "origin": "inst-a",
              "scope": { "swarmId": "alpha", "role": "swarm-controller", "instance": "inst-a" },
              "correlationId": null,
              "idempotencyKey": null,
              "data": {
                "enabled": true,
                "config": {},
                "startedAt": "2026-01-22T12:00:01Z",
                "runtime": {
                  "runId": "run-alpha",
                  "containerId": "container-alpha",
                  "image": "ctrl-alpha",
                  "stackName": "ph-alpha"
                },
                "io": {},
                "ioState": {},
                "context": {
                  "swarmStatus": "READY",
                  "swarmHealth": "RUNNING",
                  "workers": [ { "role": "generator", "instance": "gen-1" } ],
                  "template": {
                    "id": "tpl-alpha",
                    "image": "ctrl-alpha",
                    "bees": [ { "role": "generator", "image": "gen-alpha" } ]
                  }
                }
              }
            }
            """;
        alpha.updateControllerStatusFull(mapper.readTree(alphaStatusFull), Instant.now());
        Swarm bravo = new Swarm("bravo", "inst-b", "c2", "run-b");
        String bravoStatusFull = """
            {
              "timestamp": "2026-01-22T12:00:02Z",
              "version": "1",
              "kind": "metric",
              "type": "status-full",
              "origin": "inst-b",
              "scope": { "swarmId": "bravo", "role": "swarm-controller", "instance": "inst-b" },
              "correlationId": null,
              "idempotencyKey": null,
              "data": {
                "enabled": true,
                "config": {},
                "startedAt": "2026-01-22T12:00:02Z",
                "runtime": {
                  "runId": "run-bravo",
                  "containerId": "container-bravo",
                  "image": "ctrl-bravo",
                  "stackName": "ph-bravo"
                },
                "io": {},
                "ioState": {},
                "context": {
                  "swarmStatus": "READY",
                  "swarmHealth": "RUNNING",
                  "workers": [ { "role": "moderator", "instance": "mod-1" } ],
                  "template": {
                    "id": "tpl-bravo",
                    "image": "ctrl-bravo",
                    "bees": [ { "role": "moderator", "image": "mod-bravo" } ]
                  }
                }
              }
            }
            """;
        bravo.updateControllerStatusFull(mapper.readTree(bravoStatusFull), Instant.now());
        registry.register(bravo);
        registry.register(alpha);
        SwarmController ctrl = controller(new SwarmCreateTracker(), registry, new SwarmPlanRegistry());

        ResponseEntity<List<SwarmController.SwarmSummary>> resp = ctrl.list();
        List<SwarmController.SwarmSummary> body = resp.getBody();

        assertThat(body).extracting(SwarmController.SwarmSummary::id).containsExactly("alpha", "bravo");
        assertThat(body.get(0).templateId()).isNull();
        assertThat(body.get(0).bees()).containsExactly(new SwarmController.BeeSummary("generator", null));
        assertThat(body.get(1).templateId()).isNull();
        assertThat(body.get(1).bees()).containsExactly(new SwarmController.BeeSummary("moderator", null));
    }

    @Test
    void createFailsWhenTemplateLookupFails() throws Exception {
        SwarmCreateTracker tracker = new SwarmCreateTracker();
        SwarmPlanRegistry plans = new SwarmPlanRegistry();
        when(scenarioClient.fetchScenario("tpl-missing")).thenThrow(new RuntimeException("boom"));
        SwarmController ctrl = controller(tracker, new SwarmRegistry(), plans);
        SwarmCreateRequest req = new SwarmCreateRequest("tpl-missing", "idem", null);

        assertThatThrownBy(() -> ctrl.create("sw1", req))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Failed to fetch template tpl-missing");

        verifyNoInteractions(lifecycle);
        assertThat(tracker.remove("anything")).isEmpty();
    }

    @Test
    void createRejectsRequestWhenTemplateIdMissing() throws Exception {
        SwarmController ctrl = controller(new SwarmCreateTracker(), new SwarmRegistry(), new SwarmPlanRegistry());

        MockMvc mvc = MockMvcBuilders.standaloneSetup(ctrl)
            .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
            .build();

        mvc.perform(post("/api/swarms/sw1/create")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"idempotencyKey\":\"idem\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(result -> assertThat(result.getResolvedException())
                .hasRootCauseInstanceOf(IllegalArgumentException.class));

        verifyNoInteractions(lifecycle);
    }

    @Test
    void createRejectsRequestWhenTemplateIdBlank() throws Exception {
        SwarmController ctrl = controller(new SwarmCreateTracker(), new SwarmRegistry(), new SwarmPlanRegistry());

        MockMvc mvc = MockMvcBuilders.standaloneSetup(ctrl)
            .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
            .build();

        mvc.perform(post("/api/swarms/sw1/create")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"templateId\":\"\",\"idempotencyKey\":\"idem\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(result -> assertThat(result.getResolvedException())
                .hasRootCauseInstanceOf(IllegalArgumentException.class));

        verifyNoInteractions(lifecycle);
    }

    @Test
    void createRejectsDuplicateSwarm() throws Exception {
        SwarmRegistry registry = new SwarmRegistry();
        registry.register(new Swarm("sw1", "inst", "c", "run-1"));
        SwarmController ctrl = controller(new SwarmCreateTracker(), registry, new SwarmPlanRegistry());

        MockMvc mvc = MockMvcBuilders.standaloneSetup(ctrl)
            .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
            .build();

        mvc.perform(post("/api/swarms/sw1/create")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"templateId\":\"tpl-1\",\"idempotencyKey\":\"idem\"}"))
            .andExpect(status().isConflict())
            .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                .contains("already exists"));

        verifyNoInteractions(lifecycle);
        verifyNoInteractions(scenarioClient);
    }

    @Test
    void createReturnsExistingCorrelationWhenSwarmAlreadyExists() {
        SwarmRegistry registry = new SwarmRegistry();
        registry.register(new Swarm("sw1", "inst", "c", "run-1"));
        InMemoryIdempotencyStore store = new InMemoryIdempotencyStore();
        store.record("sw1", "swarm-create", "idem", "corr-123");
        SwarmController ctrl = controller(new SwarmCreateTracker(), registry, new SwarmPlanRegistry(), store);
        SwarmCreateRequest req = new SwarmCreateRequest("tpl-1", "idem", null);

        ResponseEntity<?> response = ctrl.create("sw1", req);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isInstanceOf(ControlResponse.class);
        ControlResponse body = (ControlResponse) response.getBody();
        assertThat(body.correlationId()).isEqualTo("corr-123");
        assertThat(body.idempotencyKey()).isEqualTo("idem");
        assertThat(body.watch().successTopic()).isEqualTo(
            ControlPlaneRouting.event("outcome", "swarm-create",
                new ConfirmationScope("sw1", "orchestrator", "orch-instance")));
        verifyNoInteractions(lifecycle);
        verifyNoInteractions(scenarioClient);
    }

    @Test
    void journalReadsSwarmJournalNdjsonFromRuntimeRoot() throws Exception {
        SwarmJournalController ctrl = journalController(new SwarmRegistry());
        Path root = Path.of("scenarios-runtime").toAbsolutePath().normalize();
        Path swarmDir = root.resolve("sw1").resolve("run-1");
        Files.createDirectories(swarmDir);
        Path journal = swarmDir.resolve("journal.ndjson");

        Map<String, Object> scope = Map.of(
            "swarmId", "sw1",
            "role", "swarm-controller",
            "instance", "inst-1"
        );
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("timestamp", "2025-01-01T00:00:00Z");
        entry.put("swarmId", "sw1");
        entry.put("severity", "INFO");
        entry.put("direction", "IN");
        entry.put("kind", "signal");
        entry.put("type", "swarm-start");
        entry.put("origin", "orchestrator-1");
        entry.put("scope", scope);
        entry.put("correlationId", "c-1");
        entry.put("idempotencyKey", "i-1");
        entry.put("routingKey", "signal.swarm-start.sw1.swarm-controller.inst-1");
        entry.put("data", Map.of());
        entry.put("raw", Map.of("kind", "signal", "type", "swarm-start"));

        Files.writeString(journal, mapper.writeValueAsString(entry) + "\n");

        ResponseEntity<List<Map<String, Object>>> response = ctrl.journal("sw1", "run-1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody()).hasSize(1);
        Map<String, Object> parsed = response.getBody().get(0);
        assertThat(parsed.get("swarmId")).isEqualTo("sw1");
        assertThat(parsed.get("kind")).isEqualTo("signal");
        assertThat(parsed.get("type")).isEqualTo("swarm-start");
        assertThat(parsed.get("idempotencyKey")).isEqualTo("i-1");
        assertThat(parsed.get("correlationId")).isEqualTo("c-1");
        assertThat(parsed.get("scope")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> parsedScope = (Map<String, Object>) parsed.get("scope");
        assertThat(parsedScope.get("swarmId")).isEqualTo("sw1");
        assertThat(parsedScope.get("role")).isEqualTo("swarm-controller");
    }

    private SwarmController controller(
        SwarmCreateTracker tracker,
        SwarmRegistry registry,
        SwarmPlanRegistry plans) {
        return controller(tracker, registry, plans, new InMemoryIdempotencyStore());
    }

    private SwarmController controller(
        SwarmCreateTracker tracker,
        SwarmRegistry registry,
        SwarmPlanRegistry plans,
        IdempotencyStore store) {
        SwarmController controller = new SwarmController(
            publisher,
            lifecycle,
            tracker,
            store,
            registry,
            mapper,
            scenarioClient,
            HiveJournal.noop(),
            plans,
            new ScenarioTimelineRegistry(),
            controlPlaneProperties());
        ReflectionTestUtils.setField(controller, "scenariosRuntimeRootSource", tempDir.toString());
        return controller;
    }

    private SwarmJournalController journalController(SwarmRegistry registry) {
        return new SwarmJournalController(mapper, jdbc, registry);
    }

    private static ControlPlaneProperties controlPlaneProperties() {
        ControlPlaneProperties properties = new ControlPlaneProperties();
        properties.setExchange("ph.control");
        properties.setControlQueuePrefix("ph.control.manager");
        properties.setSwarmId("default");
        properties.setInstanceId("orch-instance");
        properties.getManager().setRole("orchestrator");
        return properties;
    }
}
