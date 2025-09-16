package io.pockethive.orchestrator.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.Topology;
import io.pockethive.control.ReadyConfirmation;
import io.pockethive.docker.DockerContainerClient;
import io.pockethive.orchestrator.domain.Swarm;
import io.pockethive.orchestrator.domain.SwarmPlan;
import io.pockethive.orchestrator.domain.SwarmPlanRegistry;
import io.pockethive.orchestrator.domain.SwarmRegistry;
import io.pockethive.orchestrator.domain.SwarmStatus;
import io.pockethive.orchestrator.domain.SwarmTemplate;
import io.pockethive.orchestrator.infra.scenario.ScenarioManagerClient;
import io.pockethive.scenarios.ScenarioManagerApplication;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.AnonymousQueue;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.boot.test.mock.mockito.MockBean;

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
    ScenarioManagerClient scenarioManagerClient;

    @Autowired
    RabbitTemplate rabbitTemplate;

    @Autowired
    ConnectionFactory connectionFactory;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    @Qualifier("controlExchange")
    TopicExchange controlExchange;

    @Autowired
    @Qualifier("controlQueue")
    Queue controlQueue;

    @Autowired
    @Qualifier("controllerStatusQueue")
    Queue controllerStatusQueue;

    @Autowired
    @Qualifier("bindReady")
    Binding readyBinding;

    @Autowired
    @Qualifier("bindError")
    Binding errorBinding;

    @Autowired
    @Qualifier("bindControllerStatusFull")
    Binding statusFullBinding;

    @Autowired
    @Qualifier("bindControllerStatusDelta")
    Binding statusDeltaBinding;

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
        when(docker.createAndStartContainer(anyString(), anyMap(), anyString()))
            .thenReturn("container-123");

        RabbitAdmin admin = new RabbitAdmin(connectionFactory);
        declareOrchestratorBindings(admin);

        String swarmId = "mock-swarm";
        String idempotencyKey = UUID.randomUUID().toString();
        HttpEntity<Map<String, String>> request = jsonRequest(
            Map.of("idempotencyKey", idempotencyKey, "notes", "mock-1"));

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

        ReflectionTestUtils.setField(
            scenarioManagerClient,
            "baseUrl",
            "http://127.0.0.1:" + scenarioManagerPort);

        SwarmTemplate template = scenarioManagerClient.fetchTemplate("mock-1");
        SwarmPlan plan = new SwarmPlan(swarmId, List.copyOf(template.getBees()));
        swarmPlanRegistry.register(instanceId, plan);

        AnonymousQueue captureQueue = new AnonymousQueue();
        String captureName = admin.declareQueue(captureQueue);
        Binding templateBinding = BindingBuilder.bind(captureQueue)
            .to(controlExchange)
            .with("sig.swarm-template." + swarmId);
        admin.declareBinding(templateBinding);
        Binding createBinding = BindingBuilder.bind(captureQueue)
            .to(controlExchange)
            .with("ev.ready.swarm-create." + swarmId);
        admin.declareBinding(createBinding);

        rabbitTemplate.convertAndSend(
            Topology.CONTROL_EXCHANGE,
            "ev.ready.swarm-controller." + instanceId,
            "{}");

        Message templateMessage = awaitMessage(captureName, Duration.ofSeconds(5));
        assertThat(templateMessage).isNotNull();
        assertThat(templateMessage.getMessageProperties().getReceivedRoutingKey())
            .isEqualTo("sig.swarm-template." + swarmId);
        SwarmPlan publishedPlan = objectMapper.readValue(templateMessage.getBody(), SwarmPlan.class);
        assertThat(publishedPlan.id()).isEqualTo(swarmId);
        assertThat(publishedPlan.bees()).containsExactly(
            new SwarmPlan.Bee("generator", "pockethive-generator:latest", new SwarmPlan.Work(null, "gen")),
            new SwarmPlan.Bee("moderator", "pockethive-moderator:latest", new SwarmPlan.Work("gen", "mod")),
            new SwarmPlan.Bee("processor", "pockethive-processor:latest", new SwarmPlan.Work("mod", "final")),
            new SwarmPlan.Bee("postprocessor", "pockethive-postprocessor:latest", new SwarmPlan.Work("final", null))
        );

        Message readyMessage = awaitMessage(captureName, Duration.ofSeconds(5));
        assertThat(readyMessage).isNotNull();
        assertThat(readyMessage.getMessageProperties().getReceivedRoutingKey())
            .isEqualTo("ev.ready.swarm-create." + swarmId);
        ReadyConfirmation confirmation =
            objectMapper.readValue(readyMessage.getBody(), ReadyConfirmation.class);
        assertThat(confirmation.correlationId()).isEqualTo(correlationId);
        assertThat(confirmation.idempotencyKey()).isEqualTo(idempotencyKey);
        assertThat(confirmation.signal()).isEqualTo("swarm-create");
        assertThat(confirmation.scope().swarmId()).isEqualTo(swarmId);
        assertThat(confirmation.state()).isEqualTo("Ready");

        assertThat(swarmPlanRegistry.find(instanceId)).isEmpty();

        rabbitTemplate.convertAndSend(
            Topology.CONTROL_EXCHANGE,
            "ev.ready.swarm-template." + swarmId,
            "{}");
        awaitStatus(swarmId, SwarmStatus.READY, Duration.ofSeconds(5));

        admin.deleteQueue(captureName);
    }

    private void declareOrchestratorBindings(RabbitAdmin admin) {
        admin.declareExchange(controlExchange);
        admin.declareQueue(controlQueue);
        admin.declareQueue(controllerStatusQueue);
        admin.declareBinding(readyBinding);
        admin.declareBinding(errorBinding);
        admin.declareBinding(statusFullBinding);
        admin.declareBinding(statusDeltaBinding);
        admin.purgeQueue(controlQueue.getName(), true);
        admin.purgeQueue(controllerStatusQueue.getName(), true);
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
