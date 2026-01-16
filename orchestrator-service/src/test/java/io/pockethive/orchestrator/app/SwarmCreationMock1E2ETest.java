package io.pockethive.orchestrator.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.control.ControlSignal;
import io.pockethive.control.ConfirmationScope;
import io.pockethive.control.CommandOutcome;
import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.controlplane.spring.ControlPlaneProperties;
import io.pockethive.docker.DockerContainerClient;
import io.pockethive.orchestrator.OrchestratorApplication;
import io.pockethive.orchestrator.domain.Swarm;
import io.pockethive.orchestrator.domain.SwarmPlanRegistry;
import io.pockethive.orchestrator.domain.SwarmRegistry;
import io.pockethive.orchestrator.domain.SwarmStatus;
import io.pockethive.scenarios.test.ScenarioManagerTestApplication;
import io.pockethive.swarm.model.Bee;
import io.pockethive.swarm.model.SwarmPlan;
import io.pockethive.swarm.model.Work;
import java.io.IOException;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.boot.test.mock.mockito.MockBean;
import io.pockethive.controlplane.routing.ControlPlaneRouting;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, classes = OrchestratorApplication.class)
@Testcontainers
class SwarmCreationMock1E2ETest {

    private static final RabbitMQContainer RABBIT =
        new RabbitMQContainer("rabbitmq:3.13.1-management");
    private static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("pockethive")
            .withUsername("pockethive")
            .withPassword("pockethive");
    private static ConfigurableApplicationContext scenarioManagerContext;
    private static int scenarioManagerPort;
    private static boolean dockerAvailable = true;
    private static Path scenarioRuntimeRoot;

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

    @Autowired
    @Qualifier("managerControlPlaneIdentity")
    ControlPlaneIdentity managerIdentity;

    @Autowired
    ControlPlaneProperties controlPlaneProperties;

    private final java.util.List<Message> bufferedMessages = new java.util.ArrayList<>();

    @Autowired
    JdbcTemplate jdbc;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add(
            "pockethive.control-plane.orchestrator.metrics.pushgateway.enabled",
            () -> "true");
        registry.add(
            "pockethive.control-plane.orchestrator.metrics.pushgateway.base-url",
            () -> "http://pushgateway:9091");
        registry.add(
            "pockethive.control-plane.orchestrator.metrics.pushgateway.push-rate",
            () -> "PT1M");
        registry.add(
            "pockethive.control-plane.orchestrator.metrics.pushgateway.shutdown-operation",
            () -> "DELETE");
        registry.add(
            "pockethive.control-plane.orchestrator.metrics.pushgateway.job",
            () -> "swarm-job");
        registry.add(
            "pockethive.control-plane.orchestrator.metrics.pushgateway.grouping-key.instance",
            () -> "controller-instance");
        registry.add(
            "POCKETHIVE_CONTROL_PLANE_ORCHESTRATOR_METRICS_PUSHGATEWAY_ENABLED",
            () -> "true");
        registry.add(
            "POCKETHIVE_CONTROL_PLANE_ORCHESTRATOR_METRICS_PUSHGATEWAY_BASE_URL",
            () -> "http://pushgateway:9091");
        registry.add(
            "POCKETHIVE_CONTROL_PLANE_ORCHESTRATOR_METRICS_PUSHGATEWAY_PUSH_RATE",
            () -> "PT1M");
        registry.add(
            "POCKETHIVE_CONTROL_PLANE_ORCHESTRATOR_METRICS_PUSHGATEWAY_SHUTDOWN_OPERATION",
            () -> "DELETE");
        registry.add(
            "POCKETHIVE_CONTROL_PLANE_ORCHESTRATOR_METRICS_PUSHGATEWAY_JOB",
            () -> "swarm-job");
        registry.add(
            "POCKETHIVE_CONTROL_PLANE_ORCHESTRATOR_METRICS_PUSHGATEWAY_GROUPING_KEY_INSTANCE",
            () -> "controller-instance");
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
        if (!POSTGRES.isRunning()) {
            try {
                POSTGRES.start();
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
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("POCKETHIVE_JOURNAL_SINK", () -> "postgres");
        ensureScenarioManagerRunning();
        registry.add(
            "pockethive.control-plane.orchestrator.scenario-manager.url",
            () -> "http://127.0.0.1:" + scenarioManagerPort);
        registry.add(
            "POCKETHIVE_SCENARIOS_RUNTIME_ROOT",
            () -> scenarioRuntimeRoot != null ? scenarioRuntimeRoot.toString() : "");
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
        if (POSTGRES.isRunning()) {
            POSTGRES.stop();
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
            Map.of("idempotencyKey", idempotencyKey, "templateId", "local-rest", "notes", "local-rest"));

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

        assertThat(managerIdentity.instanceId()).isNotBlank();
        assertThat(controlPlaneProperties.getInstanceId()).isEqualTo(managerIdentity.instanceId());

        Swarm swarm = swarmRegistry.find(swarmId).orElseThrow();
        assertThat(swarm.getContainerId()).isEqualTo("container-123");
        assertThat(swarm.getStatus()).isEqualTo(SwarmStatus.CREATING);
        String instanceId = swarm.getInstanceId();
        assertThat(instanceId).isNotBlank();

        assertThat(swarmPlanRegistry.find(instanceId)).isPresent();

        verify(docker).createAndStartContainer(eq("swarm-controller:latest"), anyMap(), eq(instanceId), any());

        AnonymousQueue captureQueue = new AnonymousQueue();
        String captureName = admin.declareQueue(captureQueue);
        Binding templateBinding = BindingBuilder.bind(captureQueue)
            .to(controlExchange)
            .with(ControlPlaneRouting.signal("swarm-template", swarmId, "swarm-controller", instanceId));
        admin.declareBinding(templateBinding);
        Binding createBinding = BindingBuilder.bind(captureQueue)
            .to(controlExchange)
            .with(ControlPlaneRouting.event("outcome", "swarm-create",
                new ConfirmationScope(swarmId, "orchestrator", managerIdentity.instanceId())));
        admin.declareBinding(createBinding);

        String statusPayload = """
            {
              "timestamp": "2024-01-01T00:00:00Z",
              "version": "1",
              "kind": "metric",
              "type": "status-full",
              "origin": "%s",
              "scope": {"swarmId":"%s","role":"swarm-controller","instance":"%s"},
              "correlationId": null,
              "idempotencyKey": null,
              "data": {"enabled": false, "context": {"swarmStatus": "READY"}}
            }
            """.formatted(instanceId, swarmId, instanceId);

        rabbitTemplate.convertAndSend(
            controlPlaneProperties.getExchange(),
            ControlPlaneRouting.event("metric", "status-full",
                new ConfirmationScope(swarmId, "swarm-controller", instanceId)),
            statusPayload);

        String templateRoutingKey = ControlPlaneRouting.signal("swarm-template", swarmId, "swarm-controller", instanceId);
        Message templateMessage = awaitMessage(captureName, Duration.ofSeconds(15), templateRoutingKey);
        assertThat(templateMessage).isNotNull();
        assertThat(templateMessage.getMessageProperties().getReceivedRoutingKey())
            .isEqualTo(templateRoutingKey);
        ControlSignal controlSignal = objectMapper.readValue(templateMessage.getBody(), ControlSignal.class);
        assertThat(controlSignal.type()).isEqualTo("swarm-template");
        assertThat(controlSignal.scope().swarmId()).isEqualTo(swarmId);
        assertThat(controlSignal.scope().role()).isEqualTo("swarm-controller");
        assertThat(controlSignal.scope().instance()).isEqualTo(instanceId);
        assertThat(controlSignal.correlationId()).isEqualTo(correlationId);
        assertThat(controlSignal.idempotencyKey()).isEqualTo(idempotencyKey);
        assertThat(controlSignal.data()).isNotNull();

        SwarmPlan publishedPlan = objectMapper.convertValue(controlSignal.data(), SwarmPlan.class);
        String runtimeVolume = locateScenariosDirectory()
            .resolve("runtime")
            .resolve(swarmId)
            .toAbsolutePath()
            .normalize()
            .toString() + ":/app/scenario:ro";
        Map<String, Object> dockerConfig = Map.of("volumes", java.util.List.of(runtimeVolume));

        assertThat(publishedPlan.id()).isEqualTo(swarmId);
        assertThat(publishedPlan.bees()).containsExactly(
            new Bee(
                "generator",
                "generator:latest",
                Work.ofDefaults(null, "gen"),
                Map.of(),
                Map.of(
                    "docker", dockerConfig,
                    "ratePerSec", 50,
                    "message", Map.of("path", "/api/guarded", "body", "guarded-request")
                )
            ),
            new Bee("moderator", "moderator:latest", Work.ofDefaults("gen", "mod"), Map.of(), Map.of("docker", dockerConfig)),
            new Bee(
                "processor",
                "processor:latest",
                Work.ofDefaults("mod", "final"),
                Map.of(),
                Map.of("docker", dockerConfig, "baseUrl", "http://sut:8080")),
            new Bee("postprocessor", "postprocessor:latest", Work.ofDefaults("final", null), Map.of(), Map.of("docker", dockerConfig))
        );

        String outcomeRoutingKey = ControlPlaneRouting.event("outcome", "swarm-create",
            new ConfirmationScope(swarmId, "orchestrator", managerIdentity.instanceId()));
        Message readyMessage = awaitMessage(captureName, Duration.ofSeconds(15), outcomeRoutingKey);
        assertThat(readyMessage).isNotNull();
        assertThat(readyMessage.getMessageProperties().getReceivedRoutingKey())
            .isEqualTo(outcomeRoutingKey);
        CommandOutcome outcome =
            objectMapper.readValue(readyMessage.getBody(), CommandOutcome.class);
        assertThat(outcome.correlationId()).isEqualTo(correlationId);
        assertThat(outcome.idempotencyKey()).isEqualTo(idempotencyKey);
        assertThat(outcome.type()).isEqualTo("swarm-create");
        assertThat(outcome.scope().swarmId()).isEqualTo(swarmId);
        assertThat(outcome.data()).isNotNull();
        assertThat(outcome.data().get("status")).isEqualTo("Ready");

        assertThat(swarmPlanRegistry.find(instanceId)).isEmpty();

        rabbitTemplate.convertAndSend(
            controlPlaneProperties.getExchange(),
            ControlPlaneRouting.event("outcome", "swarm-template",
                new ConfirmationScope(swarmId, "swarm-controller", instanceId)),
            "{\"data\":{\"status\":\"Ready\"}}");
        awaitStatus(swarmId, SwarmStatus.READY, Duration.ofSeconds(15));

        admin.deleteQueue(captureName);
    }

    @Test
    void journalEndpointReadsFromPostgres() {
        Assumptions.assumeTrue(dockerAvailable, "Docker is required to run this test");

        jdbc.update(
            """
            INSERT INTO journal_event (
              ts,
              scope,
              swarm_id,
              run_id,
              scope_role,
              scope_instance,
              severity,
              direction,
              kind,
              type,
              origin,
              correlation_id,
              idempotency_key,
              routing_key,
              data,
              raw,
              extra
            ) VALUES (
              ?,
              'SWARM',
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?::jsonb,
              ?::jsonb,
              ?::jsonb
            )
            """,
            java.sql.Timestamp.from(Instant.now()),
            "journal-swarm",
            "run-1",
            "swarm-controller",
            "swarm-controller-1",
            "INFO",
            "IN",
            "signal",
            "swarm-start",
            "orchestrator-test",
            "c-1",
            "i-1",
            "signal.swarm-start.journal-swarm.swarm-controller.swarm-controller-1",
            "{\"hello\":\"world\"}",
            null,
            null);

        @SuppressWarnings("unchecked")
        ResponseEntity<java.util.List> response =
            rest.getForEntity("/api/swarms/{swarmId}/journal", java.util.List.class, "journal-swarm");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody()).hasSize(1);
        Map<String, Object> entry = (Map<String, Object>) response.getBody().getFirst();
        assertThat(entry.get("swarmId")).isEqualTo("journal-swarm");
        assertThat(entry.get("kind")).isEqualTo("signal");
        assertThat(entry.get("type")).isEqualTo("swarm-start");
        assertThat(entry.get("correlationId")).isEqualTo("c-1");
        assertThat(entry.get("idempotencyKey")).isEqualTo("i-1");
        assertThat(entry.get("scope")).isInstanceOf(Map.class);
    }

    private void declareOrchestratorBindings(RabbitAdmin admin) {
        Queue controlQueue = QueueBuilder.durable(controlQueueName).build();
        Queue statusQueue = QueueBuilder.durable(controllerStatusQueueName).build();
        Binding outcome = BindingBuilder.bind(controlQueue)
            .to(controlExchange)
            .with(outcomePattern());
        Binding statusFull = BindingBuilder.bind(statusQueue)
            .to(controlExchange)
            .with(statusPattern("status-full"));
        Binding statusDelta = BindingBuilder.bind(statusQueue)
            .to(controlExchange)
            .with(statusPattern("status-delta"));

        admin.declareExchange(controlExchange);
        admin.declareQueue(controlQueue);
        admin.declareQueue(statusQueue);
        admin.declareBinding(outcome);
        admin.declareBinding(statusFull);
        admin.declareBinding(statusDelta);
        admin.purgeQueue(controlQueueName, true);
        admin.purgeQueue(controllerStatusQueueName, true);
    }

    private String outcomePattern() {
        return ControlPlaneRouting.event("outcome", ConfirmationScope.EMPTY)
            .replace(".ALL.ALL.ALL", ".#");
    }

    private String statusPattern(String type) {
        ConfirmationScope scope = new ConfirmationScope("*", "swarm-controller", "*");
        return ControlPlaneRouting.event("metric", type, scope);
    }

    private HttpEntity<Map<String, String>> jsonRequest(Map<String, String> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private Message awaitMessage(String queue, Duration timeout, String routingKey) throws InterruptedException {
        if (routingKey == null || routingKey.isBlank()) {
            throw new IllegalArgumentException("routingKey must not be blank");
        }

        Message buffered = removeBuffered(routingKey);
        if (buffered != null) {
            return buffered;
        }

        long deadline = System.nanoTime() + timeout.toNanos();
        do {
            Message message = rabbitTemplate.receive(queue);
            if (message != null) {
                String received = message.getMessageProperties().getReceivedRoutingKey();
                if (routingKey.equals(received)) {
                    return message;
                }
                bufferedMessages.add(message);
            }
            Thread.sleep(50L);
            buffered = removeBuffered(routingKey);
            if (buffered != null) {
                return buffered;
            }
        } while (System.nanoTime() < deadline);
        return null;
    }

    private Message removeBuffered(String routingKey) {
        for (int i = 0; i < bufferedMessages.size(); i++) {
            Message message = bufferedMessages.get(i);
            String received = message.getMessageProperties().getReceivedRoutingKey();
            if (routingKey.equals(received)) {
                bufferedMessages.remove(i);
                return message;
            }
        }
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
        Path runtimeRoot = scenariosDir.resolve("runtime");
        try {
            Files.createDirectories(runtimeRoot);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to create runtime root directory at " + runtimeRoot, e);
        }
        scenarioManagerContext = new SpringApplicationBuilder(ScenarioManagerTestApplication.class)
            .properties(Map.of(
                "spring.autoconfigure.exclude",
                "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                    + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
                "server.port", port,
                "server.address", "127.0.0.1",
                "scenarios.dir", scenariosDir.toString(),
                "POCKETHIVE_SCENARIOS_RUNTIME_ROOT", runtimeRoot.toString(),
                "logging.level.root", "WARN"
            ))
            .run();
        scenarioManagerPort = port;
        scenarioRuntimeRoot = runtimeRoot;
    }

    private static Path locateScenariosDirectory() {
        Path candidate = Path.of("src", "test", "resources", "scenarios").toAbsolutePath().normalize();
        if (Files.isDirectory(candidate)) {
            return candidate;
        }
        throw new IllegalStateException("Unable to locate test scenarios directory at " + candidate);
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
