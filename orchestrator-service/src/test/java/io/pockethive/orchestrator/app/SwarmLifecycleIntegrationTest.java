package io.pockethive.orchestrator.app;

import io.pockethive.Topology;
import io.pockethive.orchestrator.domain.SwarmPlanRegistry;
import io.pockethive.orchestrator.domain.SwarmRegistry;
import io.pockethive.docker.DockerContainerClient;
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
        given(docker.resolveControlNetwork()).willReturn("ctrl-net");

        String body = "{\"id\":\"mock-1\",\"template\":{\"image\":\"pockethive-swarm-controller:latest\",\"bees\":[]}}";
        listener.handle(body, "sig.swarm-create.sw1");

        verify(docker).createAndStartContainer(eq("pockethive-swarm-controller:latest"), envCaptor.capture());
        java.util.Map<String, String> env = envCaptor.getValue();
        String beeName = env.get("JAVA_TOOL_OPTIONS").replace("-Dbee.name=", "");
        assertThat(planRegistry.find(beeName)).isPresent();
        assertThat(env)
                .containsEntry("PH_CONTROL_EXCHANGE", Topology.CONTROL_EXCHANGE)
                .containsEntry("RABBITMQ_HOST", "rabbitmq")
                .containsEntry("PH_LOGS_EXCHANGE", "ph.logs")
                .containsEntry("PH_SWARM_ID", "sw1")
                .containsEntry("CONTROL_NETWORK", "ctrl-net");

        listener.handle("", "ev.ready.swarm-controller." + beeName);

        ArgumentCaptor<String> planCaptor = ArgumentCaptor.forClass(String.class);
        verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE), eq("sig.swarm-template.sw1"), planCaptor.capture());
        assertThat(planCaptor.getValue()).contains("\"bees\"");
        assertThat(planRegistry.find(beeName)).isEmpty();

        listener.handle("", "sig.swarm-start.sw1");
        verify(rabbit).convertAndSend(Topology.CONTROL_EXCHANGE, "sig.swarm-start.sw1", "");

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
