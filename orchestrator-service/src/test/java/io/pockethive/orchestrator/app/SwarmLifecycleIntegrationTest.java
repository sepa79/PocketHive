package io.pockethive.orchestrator.app;

import io.pockethive.Topology;
import io.pockethive.orchestrator.domain.SwarmPlanRegistry;
import io.pockethive.orchestrator.domain.SwarmRegistry;
import io.pockethive.orchestrator.infra.docker.DockerContainerClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = "spring.rabbitmq.listener.simple.auto-startup=false")
class SwarmLifecycleIntegrationTest {
    @Autowired
    SwarmSignalListener listener;
    @Autowired
    SwarmPlanRegistry planRegistry;
    @Autowired
    SwarmRegistry swarmRegistry;
    @Autowired
    @Qualifier("instanceId")
    String instanceId;

    @MockBean
    DockerContainerClient docker;
    @MockBean
    RabbitTemplate rabbit;
    @MockBean
    AmqpAdmin amqpAdmin;

    @Test
    void createStartStopFlow() {
        ArgumentCaptor<java.util.Map<String,String>> envCaptor = ArgumentCaptor.forClass(java.util.Map.class);
        given(docker.createAndStartContainer(anyString(), anyMap())).willReturn("c1");

        String body = "{\"id\":\"mock-1\",\"template\":{\"image\":\"swarm-controller-service:latest\",\"bees\":[]}}";
        listener.handle(body, "sig.swarm-create.sw1");

        verify(docker).createAndStartContainer(eq("swarm-controller-service:latest"), envCaptor.capture());
        String beeName = envCaptor.getValue().get("JAVA_TOOL_OPTIONS").replace("-Dbee.name=", "");
        assertThat(planRegistry.find(beeName)).isPresent();

        listener.handle("", "ev.ready.swarm-controller." + beeName);

        ArgumentCaptor<java.util.Map<String, Object>> planCaptor = ArgumentCaptor.forClass(java.util.Map.class);
        verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE), eq("sig.swarm-start.sw1"), planCaptor.capture());
        assertThat(planCaptor.getValue().get("bees")).isNotNull();
        assertThat(planRegistry.find(beeName)).isEmpty();

        listener.handle("", "sig.swarm-stop.sw1");

        verify(docker).stopAndRemoveContainer("c1");
        assertThat(planRegistry.find(beeName)).isEmpty();
        assertThat(swarmRegistry.find("sw1")).isEmpty();
    }

    @Test
    void emitsStatusOnRequest() {
        reset(rabbit);
        listener.handle("", "sig.status-request.orchestrator." + instanceId);
        verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE), eq("ev.status-full.orchestrator." + instanceId), any(Object.class));
    }
}
