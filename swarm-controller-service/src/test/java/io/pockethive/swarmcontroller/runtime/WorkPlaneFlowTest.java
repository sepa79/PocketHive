package io.pockethive.swarmcontroller.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.pockethive.control.ControlSignal;
import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.controlplane.codec.ControlPlaneCodec;
import io.pockethive.controlplane.messaging.ControlPlaneEmitter;
import io.pockethive.controlplane.routing.ControlPlaneRouting;
import io.pockethive.controlplane.worker.WorkerControlPlane;
import io.pockethive.manager.ports.ComputeAdapter;
import io.pockethive.observability.ObservabilityContextUtil;
import io.pockethive.rabbit.api.RabbitResources;
import io.pockethive.redis.config.RedisDatasetEnvironment;
import io.pockethive.redis.config.RedisOutputEnvironment;
import io.pockethive.swarm.model.Bee;
import io.pockethive.swarm.model.Work;
import io.pockethive.swarm.model.lifecycle.RemoveResourceType;
import io.pockethive.swarm.model.lifecycle.ResourcePlane;
import io.pockethive.swarmcontroller.config.SwarmControllerProperties;
import io.pockethive.swarmcontroller.infra.amqp.SwarmQueueMetrics;
import io.pockethive.swarmcontroller.infra.configuration.WorkerWorkConfigurationAdapter;
import io.pockethive.swarmcontroller.runtime.environment.WorkConnectionEnvironmentResolver;
import io.pockethive.work.api.*;
import io.pockethive.work.config.*;
import io.pockethive.work.config.binding.*;
import io.pockethive.work.config.policy.InputLifecyclePolicy;
import io.pockethive.work.local.csv.CsvDatasetEnvironment;
import io.pockethive.work.local.scheduler.SchedulerSettingsEnvironment;
import io.pockethive.worker.sdk.config.*;
import io.pockethive.worker.sdk.input.message.MessageWorkInputFactory;
import io.pockethive.worker.sdk.output.TransportWorkOutputFactory;
import io.pockethive.worker.sdk.output.WorkOutputRegistry;
import io.pockethive.worker.sdk.runtime.*;
import io.pockethive.worker.sdk.testing.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.awaitility.Awaitility.await;
import java.time.Duration;
import java.util.concurrent.ConcurrentLinkedQueue;

class WorkPlaneFlowTest {
    @Test
    void controllerBootstrapWorkerExecutionAndRemovalShareOneNativeWorkOwner() throws Exception {
        var transport = new InMemoryWorkTransport();
        var resources = new InMemoryWorkResources(transport, "flow");
        var environment = new InMemoryWorkEnvironment("flow");
        var topology = new InMemoryWorkTopologyResolver().resolve("swarm", Set.of("intake", "result"));
        var parser = new WorkConfigurationParser(List.of(new InMemoryWorkInputParser()), List.of(new InMemoryWorkOutputParser()));
        var adapter = new WorkerWorkConfigurationAdapter(environment, new InputLifecyclePolicy(),
            new CsvDatasetEnvironment(), new SchedulerSettingsEnvironment(), new RedisDatasetEnvironment(),
            new WorkConnectionEnvironmentResolver(environment), parser, new RedisOutputEnvironment());
        var authoring = Map.<String, Object>of("inputs", Map.of("type", "MEMORY", "memory", Map.of()),
            "outputs", Map.of("type", "MEMORY", "memory", Map.of()));
        var bee = new Bee("processor", "image", Work.ofDefaults("intake", "result"), Map.of(), authoring);
        var controlEnvironment = Map.of("SPRING_RABBITMQ_HOST", "control", "SPRING_RABBITMQ_PORT", "5672",
            "SPRING_RABBITMQ_USERNAME", "control", "SPRING_RABBITMQ_PASSWORD", "secret", "SPRING_RABBITMQ_VIRTUAL_HOST", "/control");
        var configuration = adapter.compose(bee, authoring, controlEnvironment, topology);
        assertThat(configuration.environment().keySet()).noneMatch(key -> key.startsWith("POCKETHIVE_RABBIT_WORK"));
        assertThat(configuration.environment()).containsEntry(InMemoryWorkAddress.INPUT_ENV, "memory://swarm/intake")
            .containsEntry(InMemoryWorkAddress.OUTPUT_ENV, "memory://swarm/result");
        var invalid = new LinkedHashMap<>(authoring);
        invalid.put("inputs", Map.of("type", "MEMORY", "memory", Map.of("address", "memory://foreign/input")));
        assertThatThrownBy(() -> adapter.compose(bee, invalid, controlEnvironment, topology)).isInstanceOf(IllegalArgumentException.class);
        assertThat(resources.appliedResources()).isEmpty();

        var controlResources = mock(RabbitResources.class);
        var properties = mock(SwarmControllerProperties.class);
        when(properties.getSwarmId()).thenReturn("swarm");
        var infrastructure = new SwarmRuntimeInfrastructure(controlResources, properties, resources,
            mock(ComputeAdapter.class), mock(SwarmQueueMetrics.class));
        infrastructure.declareWorkTopology(topology);
        var resolved = parser.validate(configuration.bootstrapConfig(), WorkConfigurationMode.RESOLVED).configuration();
        var ioCatalog = new WorkIoConfigurationCatalog(
            List.of(new WorkInputConfigProvider(InMemoryWorkType.MEMORY, InMemoryWorkInputSettings.class)),
            List.of(new WorkOutputConfigProvider(InMemoryWorkType.MEMORY, InMemoryWorkOutputSettings.class)));
        var startup = new MapConfigurationPropertySource(Map.of(
            "pockethive.inputs.memory.address", configuration.environment().get(InMemoryWorkAddress.INPUT_ENV),
            "pockethive.outputs.memory.address", configuration.environment().get(InMemoryWorkAddress.OUTPUT_ENV)));
        var binder = new Binder(startup);
        var inputType = ioCatalog.inputType("MEMORY");
        var outputType = ioCatalog.outputType("MEMORY");
        var inputSettings = new WorkInputConfigBinder(binder).bind(inputType, ioCatalog.inputClass(inputType));
        var outputSettings = new WorkOutputConfigBinder(binder).bind(outputType, ioCatalog.outputClass(outputType));
        assertThat(inputSettings).isEqualTo(resolved.inputSettings());
        assertThat(outputSettings).isEqualTo(resolved.outputSettings());
        var definition = new WorkerDefinition("worker", Object.class, inputType, "processor",
            new WorkIoBindings(inputSettings.inboundRoute(), outputSettings.outboundRoute(), outputSettings.outboundGroup(), io.pockethive.work.config.WorkDelivery.IMMEDIATE),
            Void.class, ioCatalog.inputClass(inputType), ioCatalog.outputClass(outputType), outputType, "flow", Set.of(WorkerCapability.MESSAGE_DRIVEN));
        var store = new WorkerStateStore();
        store.getOrCreate(definition);
        var identity = new ControlPlaneIdentity("swarm", "processor", "instance");
        var policy = new InMemoryWorkMutationPolicy();
        var control = spy(new WorkerControlPlaneRuntime(WorkerControlPlane.builder(ControlPlaneCodec.create()).identity(identity).build(),
            store, new ObjectMapper().findAndRegisterModules(), mock(ControlPlaneEmitter.class), identity,
            ControlPlaneTestFixtures.workerProperties("swarm", "processor", "instance").getControlPlane(), null,
            new WorkMutationPolicyRegistry(List.of(policy), List.of(policy)), parser));
        var latest = new AtomicReference<WorkerControlPlaneRuntime.WorkerStateSnapshot>();
        control.registerStateListener("worker", latest::set);
        update(control, identity, configuration.bootstrapConfig());
        var accepted = latest.get().rawConfig();
        assertThat(accepted.get("inputs")).isEqualTo(configuration.bootstrapConfig().get("inputs"));
        update(control, identity, Map.of("inputs", Map.of("memory", Map.of("address", "memory://other/input"))));
        assertThat(latest.get().rawConfig()).isEqualTo(accepted);

        var factory = new InMemoryWorkTransportFactory(transport);
        var outputs = new WorkOutputRegistry();
        outputs.register(definition, new TransportWorkOutputFactory(factory).create(definition, outputSettings));
        var info = new WorkerInfo("processor", "swarm", "instance", null, null);
        var context = mock(WorkerContext.class);
        when(context.info()).thenReturn(info);
        when(context.meterRegistry()).thenReturn(new SimpleMeterRegistry());
        when(context.observationRegistry()).thenReturn(ObservationRegistry.create());
        var statusPublisher = control.statusPublisher("worker");
        when(context.statusPublisher()).thenReturn(statusPublisher);
        when(context.observabilityContext()).thenReturn(ObservabilityContextUtil.init("processor", "instance", "swarm"));
        var executions = new ConcurrentLinkedQueue<String>();
        PocketHiveWorkerFunction worker = (item, ctx) -> {
            executions.add(item.asString());
            if (item.asString().equals("invalid template")) throw new IllegalArgumentException("invalid template");
            return item;
        };
        var runtime = new DefaultWorkerRuntime(new WorkerRegistry(List.of(definition)), type -> worker,
            (def, state, item) -> context, store, List.of(), outputs);
        var input = new MessageWorkInputFactory(runtime, control, identity, factory).create(definition, inputSettings);
        input.start();
        var stats = new SwarmQueueStatsPortAdapter(resources);
        var inputAddress = topology.channel("intake").inputAddress();
        var outputAddress = topology.channel("result").inputAddress();
        transport.output(inputAddress).publish(WorkItem.text(info, "payload").build());
        assertThat(stats.getQueueStats(inputAddress).depth()).isEqualTo(1);
        assertThat(executions).isEmpty();
        update(control, identity, Map.of("enabled", true));
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(executions).containsExactly("payload"));
        assertThat(stats.getQueueStats(inputAddress).depth()).isZero();
        assertThat(stats.getQueueStats(inputAddress).consumers()).isEqualTo(1);
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
            assertThat(transport.pending(outputAddress)).singleElement().satisfies(item -> assertThat(item.asString()).isEqualTo("payload")));
        transport.output(inputAddress).publish(WorkItem.text(info, "invalid template").build());
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
            verify(control).publishWorkError(eq("worker"), any(WorkItem.class), any(IllegalArgumentException.class)));
        assertThat(stats.getQueueStats(inputAddress).depth()).isZero();
        assertThat(stats.getQueueStats(outputAddress).depth()).isEqualTo(1);
        assertThatThrownBy(() -> resources.remove(topology.channel("intake").resource())).hasMessage("Input is running");
        assertThat(resources.observe(topology.channel("intake").resource())).isPresent();
        update(control, identity, Map.of("enabled", false));
        transport.output(inputAddress).publish(WorkItem.text(info, "pending after disable").build());
        assertThat(stats.getQueueStats(inputAddress).depth()).isEqualTo(1);
        assertThat(executions).containsExactly("payload", "invalid template");
        assertThat(stats.getQueueStats(outputAddress).depth()).isEqualTo(1);
        input.stop();
        var removed = infrastructure.removeWorkTopology(topology);
        assertThat(removed).hasSize(2).allSatisfy(target -> {
            assertThat(target.type()).isEqualTo(RemoveResourceType.WORK_RESOURCE);
            assertThat(target.plane()).isEqualTo(ResourcePlane.WORK);
            assertThat(resources.observe(resources.identify(target))).isEmpty();
        });
        verifyNoInteractions(controlResources);
    }

    private static void update(WorkerControlPlaneRuntime control, ControlPlaneIdentity identity, Map<String, Object> config) throws Exception {
        var signal = ControlSignal.forInstance("config-update", identity.swarmId(), identity.role(), identity.instanceId(),
            "orchestrator", UUID.randomUUID().toString(), UUID.randomUUID().toString(), config);
        String route = ControlPlaneRouting.signal("config-update", identity.swarmId(), identity.role(), identity.instanceId());
        assertThat(control.handle(ControlPlaneCodec.create().encode(signal, route), route)).isTrue();
    }
}
