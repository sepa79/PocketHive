package io.pockethive.orchestrator.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import io.pockethive.controlplane.routing.ControlPlaneRouting;
import io.pockethive.controlplane.spring.ControlPlaneProperties;
import io.pockethive.orchestrator.domain.IdempotencyStore;
import io.pockethive.orchestrator.domain.Swarm;
import io.pockethive.orchestrator.domain.SwarmCreateRequest;
import io.pockethive.orchestrator.domain.SwarmCreateTracker;
import io.pockethive.orchestrator.domain.SwarmCreateTracker.Phase;
import io.pockethive.orchestrator.domain.SwarmPlanRegistry;
import io.pockethive.orchestrator.domain.SwarmRegistry;
import io.pockethive.orchestrator.domain.SwarmStatus;
import io.pockethive.orchestrator.infra.InMemoryIdempotencyStore;
import io.pockethive.swarm.model.Bee;
import io.pockethive.swarm.model.SwarmPlan;
import io.pockethive.swarm.model.SwarmTemplate;
import io.pockethive.swarm.model.Work;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class SwarmControllerTest {

    @Mock
    AmqpTemplate rabbit;

    @Mock
    ContainerLifecycleManager lifecycle;

    @Mock
    ScenarioClient scenarioClient;

    private final ObjectMapper mapper = new JacksonConfiguration().objectMapper();

    @Test
    void startPublishesControlSignal() throws Exception {
        SwarmCreateTracker tracker = new SwarmCreateTracker();
        SwarmRegistry registry = new SwarmRegistry();
        registry.register(new Swarm("sw1", "inst", "c"));
        registry.updateStatus("sw1", SwarmStatus.CREATING);
        registry.updateStatus("sw1", SwarmStatus.READY);
        SwarmController ctrl = controller(tracker, registry, new SwarmPlanRegistry());
        SwarmController.ControlRequest req = new SwarmController.ControlRequest("idem", null);

        ResponseEntity<ControlResponse> resp = ctrl.start("sw1", req);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(rabbit).convertAndSend(eq("ph.control"),
            eq(ControlPlaneRouting.signal(ControlPlaneSignals.SWARM_START, "sw1", "swarm-controller", "ALL")), captor.capture());
        ControlSignal sig = mapper.readValue(captor.getValue(), ControlSignal.class);
        assertThat(sig.signal()).isEqualTo(ControlPlaneSignals.SWARM_START);
        assertThat(sig.swarmId()).isEqualTo("sw1");
        assertThat(sig.idempotencyKey()).isEqualTo("idem");
        assertThat(resp.getBody().watch().successTopic()).isEqualTo(
            ControlPlaneRouting.event("ready." + ControlPlaneSignals.SWARM_START,
                new ConfirmationScope("sw1", "swarm-controller", "inst")));
        assertThat(tracker.complete("sw1", Phase.START)).isPresent();
        assertThat(registry.find("sw1").get().getStatus()).isEqualTo(SwarmStatus.STARTING);
    }

    @Test
    void createFetchesTemplateAndRegistersPlan() throws Exception {
        SwarmCreateTracker tracker = new SwarmCreateTracker();
        SwarmPlanRegistry plans = new SwarmPlanRegistry();
        SwarmTemplate template = new SwarmTemplate("ctrl-image", List.of(
            new Bee("generator", "img", new Work(null, "out"), java.util.Map.of())
        ));
        when(scenarioClient.fetchTemplate("tpl-1")).thenReturn(template);
        AtomicReference<String> capturedInstance = new AtomicReference<>();
        when(lifecycle.startSwarm(eq("sw1"), eq("ctrl-image"), anyString())).thenAnswer(inv -> {
            String instanceId = inv.getArgument(2);
            capturedInstance.set(instanceId);
            return new Swarm("sw1", instanceId, "c1");
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
        verify(scenarioClient).fetchTemplate("tpl-1");
    }

    @Test
    void startIsIdempotent() {
        SwarmRegistry registry = new SwarmRegistry();
        registry.register(new Swarm("sw1", "controller-inst", "ctrl"));
        SwarmController ctrl = controller(new SwarmCreateTracker(), registry, new SwarmPlanRegistry());
        SwarmController.ControlRequest req = new SwarmController.ControlRequest("idem", null);

        ResponseEntity<ControlResponse> r1 = ctrl.start("sw1", req);
        ResponseEntity<ControlResponse> r2 = ctrl.start("sw1", req);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(rabbit, times(1)).convertAndSend(eq("ph.control"),
            eq(ControlPlaneRouting.signal(ControlPlaneSignals.SWARM_START, "sw1", "swarm-controller", "ALL")), captor.capture());
        assertThat(r1.getBody().correlationId()).isEqualTo(r2.getBody().correlationId());
    }

    @Test
    void concurrentCreateRequestsReuseReservation() throws Exception {
        SwarmCreateTracker tracker = new SwarmCreateTracker();
        SwarmPlanRegistry plans = new SwarmPlanRegistry();
        SwarmRegistry registry = new SwarmRegistry();
        SwarmTemplate template = new SwarmTemplate("ctrl-image", List.of(
            new Bee("generator", "img", new Work(null, "out"), java.util.Map.of())
        ));
        when(scenarioClient.fetchTemplate("tpl-1")).thenReturn(template);
        when(lifecycle.startSwarm(eq("sw1"), eq("ctrl-image"), anyString()))
            .thenAnswer(invocation -> new Swarm("sw1", invocation.getArgument(2), "corr"));
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

        verify(lifecycle, times(1)).startSwarm(eq("sw1"), eq("ctrl-image"), anyString());
        verify(scenarioClient, times(1)).fetchTemplate("tpl-1");
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
            new Bee("generator", "img", new Work(null, "out"), java.util.Map.of())
        ));
        when(scenarioClient.fetchTemplate("tpl-1")).thenReturn(template);
        when(lifecycle.startSwarm(eq("sw1"), eq("ctrl-image"), anyString()))
            .thenAnswer(invocation -> new Swarm("sw1", invocation.getArgument(2), "corr"));
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

        verify(lifecycle, times(1)).startSwarm(eq("sw1"), eq("ctrl-image"), anyString());
        verify(scenarioClient, times(1)).fetchTemplate("tpl-1");
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
    void exposesSwarmView() {
        SwarmRegistry registry = new SwarmRegistry();
        registry.register(new Swarm("sw1", "inst", "c"));
        SwarmController ctrl = controller(new SwarmCreateTracker(), registry, new SwarmPlanRegistry());

        ResponseEntity<SwarmController.SwarmView> resp = ctrl.view("sw1");
        assertThat(resp.getBody().id()).isEqualTo("sw1");
        assertThat(resp.getBody().workEnabled()).isTrue();
        assertThat(resp.getBody().controllerEnabled()).isFalse();
    }

    @Test
    void createFailsWhenTemplateLookupFails() throws Exception {
        SwarmCreateTracker tracker = new SwarmCreateTracker();
        SwarmPlanRegistry plans = new SwarmPlanRegistry();
        when(scenarioClient.fetchTemplate("tpl-missing")).thenThrow(new RuntimeException("boom"));
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
        registry.register(new Swarm("sw1", "inst", "c"));
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
        registry.register(new Swarm("sw1", "inst", "c"));
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
            ControlPlaneRouting.event("ready.swarm-create",
                new ConfirmationScope("sw1", "orchestrator", "ALL")));
        verifyNoInteractions(lifecycle);
        verifyNoInteractions(scenarioClient);
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
        return new SwarmController(
            rabbit,
            lifecycle,
            tracker,
            store,
            registry,
            mapper,
            scenarioClient,
            plans,
            controlPlaneProperties());
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
