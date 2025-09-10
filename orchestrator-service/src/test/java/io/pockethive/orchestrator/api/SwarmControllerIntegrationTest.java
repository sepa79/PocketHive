package io.pockethive.orchestrator.api;

import io.pockethive.Topology;
import io.pockethive.orchestrator.app.SwarmSignalListener;
import io.pockethive.orchestrator.domain.SwarmPlan;
import io.pockethive.orchestrator.domain.SwarmPlanRegistry;
import io.pockethive.orchestrator.infra.docker.DockerContainerClient;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import org.mockito.ArgumentCaptor;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.rabbitmq.listener.simple.auto-startup=false")
@AutoConfigureMockMvc
class SwarmControllerIntegrationTest {
    @Autowired
    MockMvc mvc;
    @Autowired
    SwarmSignalListener listener;
    @Autowired
    SwarmPlanRegistry planRegistry;
    @Autowired
    @Qualifier("instanceId")
    String instanceId;

    @MockBean
    DockerContainerClient docker;
    @MockBean
    AmqpTemplate rabbit;
    @MockBean
    AmqpAdmin amqpAdmin;

    @Test
    void createStartStopFlow() throws Exception {
        ArgumentCaptor<java.util.Map<String, String>> envCaptor = ArgumentCaptor.forClass(java.util.Map.class);
        given(docker.createAndStartContainer(anyString(), anyMap())).willReturn("c1");

        mvc.perform(post("/swarms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"sw1\",\"scenarioId\":\"default\"}"))
                .andExpect(status().isAccepted());

        verify(docker).createAndStartContainer(eq("swarm-controller-service:latest"), envCaptor.capture());
        String beeName = envCaptor.getValue().get("JAVA_TOOL_OPTIONS").replace("-Dbee.name=", "");
        assertThat(planRegistry.find(beeName)).isPresent();

        listener.handle("ev.ready.swarm-controller." + beeName);

        ArgumentCaptor<SwarmPlan> captor = ArgumentCaptor.forClass(SwarmPlan.class);
        verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE), eq("sig.swarm-start.sw1"), captor.capture());
        assertThat(captor.getValue().id()).isEqualTo("sw1");
        assertThat(planRegistry.find(beeName)).isEmpty();

        mvc.perform(delete("/swarms/sw1"))
                .andExpect(status().isNoContent());

        verify(docker).stopAndRemoveContainer("c1");
    }

    @Test
    void emitsStatusOnRequest() {
        reset(rabbit);
        listener.handle("sig.status-request.orchestrator." + instanceId);
        verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE), eq("ev.status-full.orchestrator." + instanceId), any());
    }
}

