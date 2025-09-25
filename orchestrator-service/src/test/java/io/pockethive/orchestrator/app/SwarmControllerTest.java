package io.pockethive.orchestrator.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.Topology;
import io.pockethive.control.ControlSignal;
import io.pockethive.orchestrator.domain.SwarmCreateRequest;
import io.pockethive.orchestrator.domain.SwarmCreateTracker;
import io.pockethive.orchestrator.domain.SwarmCreateTracker.Phase;
import io.pockethive.orchestrator.domain.Swarm;
import io.pockethive.orchestrator.domain.SwarmPlanRegistry;
import io.pockethive.orchestrator.domain.SwarmRegistry;
import io.pockethive.orchestrator.domain.SwarmStatus;
import io.pockethive.orchestrator.infra.InMemoryIdempotencyStore;
import io.pockethive.swarm.model.Bee;
import io.pockethive.swarm.model.SwarmPlan;
import io.pockethive.swarm.model.SwarmTemplate;
import io.pockethive.swarm.model.Work;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
        SwarmController ctrl = new SwarmController(
            rabbit,
            lifecycle,
            tracker,
            new InMemoryIdempotencyStore(),
            registry,
            mapper,
            scenarioClient,
            new SwarmPlanRegistry());
        SwarmController.ControlRequest req = new SwarmController.ControlRequest("idem", null);

        ResponseEntity<ControlResponse> resp = ctrl.start("sw1", req);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE), eq("sig.swarm-start.sw1"), captor.capture());
        ControlSignal sig = mapper.readValue(captor.getValue(), ControlSignal.class);
        assertThat(sig.signal()).isEqualTo("swarm-start");
        assertThat(sig.swarmId()).isEqualTo("sw1");
        assertThat(sig.idempotencyKey()).isEqualTo("idem");
        assertThat(resp.getBody().watch().successTopic()).isEqualTo("ev.ready.swarm-start.sw1");
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
        SwarmController ctrl = new SwarmController(
            rabbit,
            lifecycle,
            tracker,
            new InMemoryIdempotencyStore(),
            new SwarmRegistry(),
            mapper,
            scenarioClient,
            plans);
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
        SwarmController ctrl = new SwarmController(
            rabbit,
            lifecycle,
            new SwarmCreateTracker(),
            new InMemoryIdempotencyStore(),
            new SwarmRegistry(),
            mapper,
            scenarioClient,
            new SwarmPlanRegistry());
        SwarmController.ControlRequest req = new SwarmController.ControlRequest("idem", null);

        ResponseEntity<ControlResponse> r1 = ctrl.start("sw1", req);
        ResponseEntity<ControlResponse> r2 = ctrl.start("sw1", req);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(rabbit, times(1)).convertAndSend(eq(Topology.CONTROL_EXCHANGE), eq("sig.swarm-start.sw1"), captor.capture());
        assertThat(r1.getBody().correlationId()).isEqualTo(r2.getBody().correlationId());
    }

    @Test
    void exposesSwarmView() {
        SwarmRegistry registry = new SwarmRegistry();
        registry.register(new Swarm("sw1", "inst", "c"));
        SwarmController ctrl = new SwarmController(
            rabbit,
            lifecycle,
            new SwarmCreateTracker(),
            new InMemoryIdempotencyStore(),
            registry,
            mapper,
            scenarioClient,
            new SwarmPlanRegistry());

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
        SwarmController ctrl = new SwarmController(
            rabbit,
            lifecycle,
            tracker,
            new InMemoryIdempotencyStore(),
            new SwarmRegistry(),
            mapper,
            scenarioClient,
            plans);
        SwarmCreateRequest req = new SwarmCreateRequest("tpl-missing", "idem", null);

        assertThatThrownBy(() -> ctrl.create("sw1", req))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Failed to fetch template tpl-missing");

        verifyNoInteractions(lifecycle);
        assertThat(tracker.remove("anything")).isEmpty();
    }

    @Test
    void createRejectsRequestWhenTemplateIdMissing() throws Exception {
        SwarmController ctrl = new SwarmController(
            rabbit,
            lifecycle,
            new SwarmCreateTracker(),
            new InMemoryIdempotencyStore(),
            new SwarmRegistry(),
            mapper,
            scenarioClient,
            new SwarmPlanRegistry());

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
        SwarmController ctrl = new SwarmController(
            rabbit,
            lifecycle,
            new SwarmCreateTracker(),
            new InMemoryIdempotencyStore(),
            new SwarmRegistry(),
            mapper,
            scenarioClient,
            new SwarmPlanRegistry());

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
}
