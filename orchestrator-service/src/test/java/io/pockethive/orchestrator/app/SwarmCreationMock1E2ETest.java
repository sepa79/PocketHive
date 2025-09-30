package io.pockethive.orchestrator.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.Topology;
import io.pockethive.control.ControlSignal;
import io.pockethive.control.ConfirmationScope;
import io.pockethive.control.ReadyConfirmation;
import io.pockethive.docker.DockerContainerClient;
import io.pockethive.orchestrator.domain.Swarm;
import io.pockethive.orchestrator.domain.SwarmPlanRegistry;
import io.pockethive.orchestrator.domain.SwarmRegistry;
import io.pockethive.orchestrator.domain.SwarmStatus;
import io.pockethive.scenarios.ScenarioManagerApplication;
import io.pockethive.swarm.model.Bee;
import io.pockethive.swarm.model.SwarmPlan;
import io.pockethive.swarm.model.Work;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.AnonymousQueue;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.boot.test.mock.mockito.MockBean;
import io.pockethive.controlplane.routing.ControlPlaneRouting;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers
class SwarmCreationMock1E2ETest {

    private static final RabbitMQContainer RABBIT =
        new RabbitMQContainer("rabbitmq:3.13.1-management");
    private static ConfigurableApplicationContext scenarioManagerContext;
    private static int scenarioManagerPort;
    private static boolean dockerAvailable = true;

    @MockBean
    DockerContainerClient docker;

    @MockBean
    AmqpAdmin amqpAdmin;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    SwarmRegistry swarmRegistry;

    @Autowired
    SwarmPlanRegistry swarmPlanRegistry;

    @Autowired
    RabbitTemplate rabbitTemplate;

    @Autowired
    ConnectionFactory connectionFactory;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    @Qualifier("controlPlaneExchange")
    TopicExchange controlExchange;

    @Autowired
    @Qualifier("managerControlQueueName")
    String controlQueueName;

    @Autowired
    @Qualifier("controllerStatusQueueName")
    String controllerStatusQueueName;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        if (!RABBIT.isRunning()) {
            try {
                RABBIT.start();
            } catch (IllegalStateException ex) {
                if (ex.getMessage() != null
                    && ex.getMessage().contains("Could not find a valid Docker environment")) {
                    dockerAvailable = false;
                    return;
                }
                throw ex;
            }
        }
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.listener.simple.missingQueuesFatal", () -> "false");
        ensureScenarioManagerRunning();
        registry.add("scenario-manager.url", () -> "http://127.0.0.1:" + scenarioManagerPort);
    }

    @AfterAll
    static void tearDown() {
        if (scenarioManagerContext != null) {
            scenarioManagerContext.close();
            scenarioManagerContext = null;
        }
        if (RABBIT.isRunning()) {
            RABBIT.stop();
        }
    }

    @Test
    void orchestratorPublishesSwarmTemplateFromScenarioManager() throws Exception {
        Assumptions.assumeTrue(dockerAvailable, "Docker is required to run this test");

        when(docker.resolveControlNetwork()).thenReturn("ph-test-net");
        when(docker.createAndStartContainer(anyString(), anyMap(), anyString(), any()))
            .thenReturn("container-123");

        RabbitAdmin admin = new RabbitAdmin(connectionFactory);
        declareOrchestratorBindings(admin);

        String swarmId = "mock-swarm";
        String idempotencyKey = UUID.randomUUID().toString();
        HttpEntity<Map<String, String>> request = jsonRequest(
            Map.of("idempotencyKey", idempotencyKey, "templateId", "mock-1", "notes", "mock-1"));

        ResponseEntity<ControlResponse> response = rest.exchange(
            "/api/swarms/{swarmId}/create",
            HttpMethod.POST,
            request,
            ControlResponse.class,
            swarmId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        ControlResponse body = response.getBody();
        assertThat(body).isNotNull();
        String correlationId = body.correlationId();
        assertThat(correlationId).isNotBlank();

        Swarm swarm = swarmRegistry.find(swarmId).orElseThrow();
        assertThat(swarm.getContainerId()).isEqualTo("container-123");
        assertThat(swarm.getStatus()).isEqualTo(SwarmStatus.CREATING);
        String instanceId = swarm.getInstanceId();
        assertThat(instanceId).isNotBlank();

        assertThat(swarmPlanRegistry.find(instanceId)).isPresent();

        verify(docker).createAndStartContainer(eq("pockethive-swarm-controller:latest"), anyMap(), eq(instanceId), any());

        AnonymousQueue captureQueue = new AnonymousQueue();
        String captureName = admin.declareQueue(captureQueue);
        Binding templateBinding = BindingBuilder.bind(captureQueue)
            .to(controlExchange)
            .with(ControlPlaneRouting.signal("swarm-template", swarmId, "swarm-controller", "ALL"));
        admin.declareBinding(templateBinding);
        Binding createBinding = BindingBuilder.bind(captureQueue)
            .to(controlExchange)
            .with(ControlPlaneRouting.event("ready.swarm-create",
                new ConfirmationScope(swarmId, "orchestrator", "ALL")));
        admin.declareBinding(createBinding);

        rabbitTemplate.convertAndSend(
            Topology.CONTROL_EXCHANGE,
            ControlPlaneRouting.event("ready.swarm-controller",
                new ConfirmationScope(swarmId, "swarm-controller", instanceId)),
            "{}");

        Message templateMessage = awaitMessage(captureName, Duration.ofSeconds(5));
        assertThat(templateMessage).isNotNull();
        assertThat(templateMessage.getMessageProperties().getReceivedRoutingKey())
            .isEqualTo(ControlPlaneRouting.signal("swarm-template", swarmId, "swarm-controller", "ALL"));
        ControlSignal controlSignal = objectMapper.readValue(templateMessage.getBody(), ControlSignal.class);
        assertThat(controlSignal.signal()).isEqualTo("swarm-template");
        assertThat(controlSignal.swarmId()).isEqualTo(swarmId);
        assertThat(controlSignal.correlationId()).isEqualTo(correlationId);
        assertThat(controlSignal.idempotencyKey()).isEqualTo(idempotencyKey);
        assertThat(controlSignal.args()).isNotNull();

        SwarmPlan publishedPlan = objectMapper.convertValue(controlSignal.args(), SwarmPlan.class);
        assertThat(publishedPlan.id()).isEqualTo(swarmId);
        assertThat(publishedPlan.bees()).containsExactly(
            new Bee("generator", "pockethive-generator:latest", new Work(null, "gen"), java.util.Map.of()),
            new Bee("moderator", "pockethive-moderator:latest", new Work("gen", "mod"), java.util.Map.of()),
            new Bee("processor", "pockethive-processor:latest", new Work("mod", "final"), java.util.Map.of()),
            new Bee("postprocessor", "pockethive-postprocessor:latest", new Work("final", null), java.util.Map.of())
        );

        Message readyMessage = awaitMessage(captureName, Duration.ofSeconds(5));
        assertThat(readyMessage).isNotNull();
        assertThat(readyMessage.getMessageProperties().getReceivedRoutingKey())
            .isEqualTo(ControlPlaneRouting.event("ready.swarm-create",
                new ConfirmationScope(swarmId, "orchestrator", "ALL")));
        ReadyConfirmation confirmation =
            objectMapper.readValue(readyMessage.getBody(), ReadyConfirmation.class);
        assertThat(confirmation.correlationId()).isEqualTo(correlationId);
        assertThat(confirmation.idempotencyKey()).isEqualTo(idempotencyKey);
        assertThat(confirmation.signal()).isEqualTo("swarm-create");
        assertThat(confirmation.scope().swarmId()).isEqualTo(swarmId);
        assertThat(confirmation.state()).isNotNull();
        assertThat(confirmation.state().status()).isEqualTo("Ready");

        assertThat(swarmPlanRegistry.find(instanceId)).isEmpty();

        rabbitTemplate.convertAndSend(
            Topology.CONTROL_EXCHANGE,
            ControlPlaneRouting.event("ready.swarm-template",
                new ConfirmationScope(swarmId, "swarm-controller", instanceId)),
            "{}");
        awaitStatus(swarmId, SwarmStatus.READY, Duration.ofSeconds(5));

        admin.deleteQueue(captureName);
    }

    private void declareOrchestratorBindings(RabbitAdmin admin) {
        Queue controlQueue = QueueBuilder.durable(controlQueueName).build();
        Queue statusQueue = QueueBuilder.durable(controllerStatusQueueName).build();
        Binding ready = BindingBuilder.bind(controlQueue)
            .to(controlExchange)
            .with(readyPattern());
        Binding error = BindingBuilder.bind(controlQueue)
            .to(controlExchange)
            .with(errorPattern());
        Binding statusFull = BindingBuilder.bind(statusQueue)
            .to(controlExchange)
            .with(statusPattern("status-full"));
        Binding statusDelta = BindingBuilder.bind(statusQueue)
            .to(controlExchange)
            .with(statusPattern("status-delta"));

        admin.declareExchange(controlExchange);
        admin.declareQueue(controlQueue);
        admin.declareQueue(statusQueue);
        admin.declareBinding(ready);
        admin.declareBinding(error);
        admin.declareBinding(statusFull);
        admin.declareBinding(statusDelta);
        admin.purgeQueue(controlQueueName, true);
        admin.purgeQueue(controllerStatusQueueName, true);
    }

    private String readyPattern() {
        return ControlPlaneRouting.event("ready", ConfirmationScope.EMPTY)
            .replace(".ALL.ALL.ALL", ".#");
    }

    private String errorPattern() {
        return ControlPlaneRouting.event("error", ConfirmationScope.EMPTY)
            .replace(".ALL.ALL.ALL", ".#");
    }

    private String statusPattern(String type) {
        ConfirmationScope scope = new ConfirmationScope(null, "swarm-controller", "*");
        return ControlPlaneRouting.event(type, scope)
            .replace(".ALL.swarm-controller", ".swarm-controller");
    }

    private HttpEntity<Map<String, String>> jsonRequest(Map<String, String> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private Message awaitMessage(String queue, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        Message message;
        do {
            message = rabbitTemplate.receive(queue);
            if (message != null) {
                return message;
            }
            Thread.sleep(50L);
        } while (System.nanoTime() < deadline);
        return null;
    }

    private void awaitStatus(String swarmId, SwarmStatus expected, Duration timeout)
        throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            SwarmStatus current = swarmRegistry.find(swarmId)
                .map(Swarm::getStatus)
                .orElse(null);
            if (expected.equals(current)) {
                return;
            }
            Thread.sleep(50L);
        }
        assertThat(swarmRegistry.find(swarmId)
            .map(Swarm::getStatus)).contains(expected);
    }

    private static void ensureScenarioManagerRunning() {
        if (scenarioManagerContext != null) {
            return;
        }
        int port = findFreePort();
        Path scenariosDir = locateScenariosDirectory();
        scenarioManagerContext = new SpringApplicationBuilder(ScenarioManagerApplication.class)
            .properties(Map.of(
                "server.port", port,
                "server.address", "127.0.0.1",
                "scenarios.dir", scenariosDir.toString(),
                "logging.level.root", "WARN"
            ))
            .run();
        scenarioManagerPort = port;
    }

    private static Path locateScenariosDirectory() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve("scenario-manager-service").resolve("scenarios");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Unable to locate scenario-manager-service/scenarios directory");
    }

    private static int findFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to find free port", e);
        }
    }

}
