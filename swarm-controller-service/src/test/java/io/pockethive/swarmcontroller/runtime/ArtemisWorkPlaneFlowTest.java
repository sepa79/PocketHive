package io.pockethive.swarmcontroller.runtime;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.io.TempDir;
import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.apache.activemq.artemis.core.settings.impl.AddressSettings;
import org.apache.activemq.artemis.core.server.ActiveMQServers;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import io.pockethive.swarmcontroller.config.WorkPlaneConfiguration;
import io.pockethive.artemis.api.ArtemisWorkPlane;
import io.pockethive.artemis.api.ArtemisInputSettings;
import io.pockethive.artemis.api.ArtemisOutputSettings;
import io.pockethive.artemis.config.ArtemisWorkerConnectionAutoConfiguration;
import io.pockethive.artemis.config.ArtemisWorkAutoConfiguration;
import io.pockethive.topology.work.WorkPlaneResources;
import io.pockethive.topology.work.WorkTopologyResolver;
import io.pockethive.work.config.composition.CurrentWorkConfigurationProviders;
import io.pockethive.work.api.transport.WorkDeliveryHandler;
import io.pockethive.work.api.transport.WorkInputTransportFactory;
import io.pockethive.work.api.transport.WorkOutputTransportFactory;
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

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.awaitility.Awaitility.await;

class ArtemisWorkPlaneFlowTest {
    @TempDir Path directory;
    private static final AtomicInteger IDS = new AtomicInteger(3000);

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(longs = {0, 1500})
    void controllerBootstrapWorkerExecutionAndRemovalShareOneNativeWorkOwner(long delayMs) throws Exception {
        var delivery = delayMs == 0 ? io.pockethive.work.config.WorkDelivery.IMMEDIATE
            : new io.pockethive.work.config.WorkDelivery(io.pockethive.work.config.WorkDeliveryMode.DELAYED, delayMs);
        String url = "vm://" + IDS.incrementAndGet();
        var brokerConfig = new ConfigurationImpl()
            .setPersistenceEnabled(false).setSecurityEnabled(false).setJMXManagementEnabled(false)
            .setThreadPoolMaxSize(4).setScheduledThreadPoolMaxSize(2)
            .setJournalDirectory(directory.resolve("journal").toString())
            .setBindingsDirectory(directory.resolve("bindings").toString())
            .setPagingDirectory(directory.resolve("paging").toString())
            .setLargeMessagesDirectory(directory.resolve("large").toString())
            .addAcceptorConfiguration("test-core", url)
            .addAddressSetting("#", new AddressSettings()
                .setAutoCreateAddresses(false).setAutoCreateQueues(false));
        var broker = ActiveMQServers.newActiveMQServer(brokerConfig);
        broker.start();
        try (var composition = new AnnotationConfigApplicationContext();
             var workerComposition = new AnnotationConfigApplicationContext()) {
            composition.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                "pockethive.work.type", "ARTEMIS", "pockethive.work.artemis.broker-url", url,
                "pockethive.work.artemis.username", "user", "pockethive.work.artemis.password", "password",
                "pockethive.work.artemis.call-timeout-millis", "2000", "pockethive.work.artemis.namespace", "ph")));
            composition.register(WorkPlaneConfiguration.class);
            composition.refresh();
            var plane = composition.getBean(ArtemisWorkPlane.class);
            var resources = composition.getBean(WorkPlaneResources.class);
            var environment = composition.getBean(WorkAdapterEnvironment.class);
            var topology = composition.getBean(WorkTopologyResolver.class)
                .resolve("swarm", Set.of("intake", "result"));
            var providers = new CurrentWorkConfigurationProviders();
            var parser = providers.workConfigurationParser();
            var adapter = new WorkerWorkConfigurationAdapter(environment, new InputLifecyclePolicy(),
                new CsvDatasetEnvironment(), new SchedulerSettingsEnvironment(), new RedisDatasetEnvironment(),
                new WorkConnectionEnvironmentResolver(environment), parser, new RedisOutputEnvironment());
            var authoring = Map.<String, Object>of("inputs", Map.of("type", "ARTEMIS", "artemis", Map.of("consumerWindowBytes", 0)),
                "outputs", Map.of("type", "ARTEMIS", "artemis", Map.of("persistent", true),
                    "delivery", new io.pockethive.work.config.WorkDeliveryParser().configuration(delivery)));
            var bee = new Bee("processor", "image", Work.ofDefaults("intake", "result"), Map.of(), authoring);
            var controlEnvironment = Map.of("SPRING_RABBITMQ_HOST", "control", "SPRING_RABBITMQ_PORT", "5672",
                "SPRING_RABBITMQ_USERNAME", "control", "SPRING_RABBITMQ_PASSWORD", "secret", "SPRING_RABBITMQ_VIRTUAL_HOST", "/control");
            var configuration = adapter.compose(bee, authoring, controlEnvironment, topology);
            assertThat(configuration.environment().keySet()).noneMatch(key -> key.startsWith("POCKETHIVE_RABBIT_WORK"));
            assertThat(configuration.environment()).containsEntry("POCKETHIVE_WORK_TYPE", "ARTEMIS");
            var invalid = new LinkedHashMap<>(authoring);
            invalid.put("inputs", Map.of("type", "ARTEMIS", "artemis", Map.of("consumerWindowBytes", 0, "queue", "foreign")));
            assertThatThrownBy(() -> adapter.compose(bee, invalid, controlEnvironment, topology)).isInstanceOf(IllegalArgumentException.class);
            assertThat(resources.appliedResources()).isEmpty();

            var controlResources = mock(RabbitResources.class);
            var properties = mock(SwarmControllerProperties.class);
            when(properties.getSwarmId()).thenReturn("swarm");
            var infrastructure = new SwarmRuntimeInfrastructure(controlResources, properties, resources,
                mock(ComputeAdapter.class), mock(SwarmQueueMetrics.class));
            infrastructure.declareWorkTopology(topology);
            var resolved = parser.validate(configuration.bootstrapConfig(), WorkConfigurationMode.RESOLVED).configuration();
            var startup = new StandardEnvironment();
            startup.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                Map.copyOf(configuration.environment())));
            ConfigurationPropertySources.attach(startup);
            workerComposition.setEnvironment(startup);
            workerComposition.register(ArtemisWorkerConnectionAutoConfiguration.class,
                ArtemisWorkAutoConfiguration.class);
            workerComposition.refresh();
            var ioCatalog = new WorkIoConfigurationCatalog(
                List.copyOf(workerComposition.getBeansOfType(WorkInputConfigProvider.class).values()),
                List.copyOf(workerComposition.getBeansOfType(WorkOutputConfigProvider.class).values()));
            var binder = Binder.get(startup);
            var inputType = ioCatalog.inputType("ARTEMIS");
            var outputType = ioCatalog.outputType("ARTEMIS");
            var inputSettings = new WorkInputConfigBinder(binder).bind(inputType, ioCatalog.inputClass(inputType));
            var outputSettings = new WorkOutputConfigBinder(binder).bind(outputType, ioCatalog.outputClass(outputType));
            assertThat(inputSettings).isEqualTo(resolved.inputSettings());
            assertThat(outputSettings).isEqualTo(resolved.outputSettings());
            var definition = new WorkerDefinition("worker", Object.class, inputType, "processor",
                new WorkIoBindings(inputSettings.inboundRoute(), outputSettings.outboundRoute(), outputSettings.outboundGroup(), new WorkOutputConfigBinder(binder).bindDelivery(outputType)),
                Void.class, ioCatalog.inputClass(inputType), ioCatalog.outputClass(outputType), outputType, "flow", Set.of(WorkerCapability.MESSAGE_DRIVEN));
            assertThat(definition.io().outputDelivery()).isEqualTo(delivery);
            var store = new WorkerStateStore();
            store.getOrCreate(definition);
            var identity = new ControlPlaneIdentity("swarm", "processor", "instance");
            var control = spy(new WorkerControlPlaneRuntime(WorkerControlPlane.builder(ControlPlaneCodec.create()).identity(identity).build(),
                store, new ObjectMapper().findAndRegisterModules(), mock(ControlPlaneEmitter.class), identity,
                ControlPlaneTestFixtures.workerProperties("swarm", "processor", "instance").getControlPlane(), null,
                providers.workMutationPolicyRegistry(), parser, new io.pockethive.worker.sdk.config.RedisSequenceConfiguration(new io.pockethive.worker.sdk.config.RedisSequenceProperties())));
            var latest = new AtomicReference<WorkerControlPlaneRuntime.WorkerStateSnapshot>();
            control.registerStateListener("worker", latest::set);
            update(control, identity, configuration.bootstrapConfig());
            var accepted = latest.get().rawConfig();
            assertThat(parser.validate(accepted, WorkConfigurationMode.RESOLVED).configuration()).isEqualTo(resolved);
            // Delivery remains startup-only even while disabled and after rejection.
            update(control, identity, Map.of("outputs", Map.of("delivery", Map.of("mode", "DELAYED", "delayMs", 9999))));
            assertThat(latest.get().rawConfig()).isEqualTo(accepted);
            assertThat(accepted.get("inputs")).isEqualTo(configuration.bootstrapConfig().get("inputs"));
            update(control, identity, Map.of("inputs", Map.of("artemis", Map.of("queue", "foreign"))));
            assertThat(latest.get().rawConfig()).isEqualTo(accepted);

            var outputs = new WorkOutputRegistry();
            outputs.register(definition, new TransportWorkOutputFactory(workerComposition.getBean(WorkOutputTransportFactory.class)).create(definition, outputSettings));
            var info = new WorkerInfo("processor", "swarm", "instance", null, null);
            var context = mock(WorkerContext.class);
            when(context.info()).thenReturn(info);
            when(context.meterRegistry()).thenReturn(new SimpleMeterRegistry());
            when(context.observationRegistry()).thenReturn(ObservationRegistry.create());
            var statusPublisher = control.statusPublisher("worker");
            when(context.statusPublisher()).thenReturn(statusPublisher);
            when(context.observabilityContext()).thenReturn(ObservabilityContextUtil.init("processor", "instance", "swarm"));
            var executedAt = new java.util.concurrent.atomic.AtomicLong();
            var executions = new ConcurrentLinkedQueue<String>();
            PocketHiveWorkerFunction worker = (item, ctx) -> {
                executedAt.set(System.currentTimeMillis());
                executions.add(item.asString());
                if (item.asString().equals("invalid template")) throw new IllegalArgumentException("invalid template");
                return item;
            };
            var runtime = new DefaultWorkerRuntime(new WorkerRegistry(List.of(definition)), type -> worker,
                (def, state, item) -> context, store, List.of(), outputs);
            var input = new MessageWorkInputFactory(runtime, control, identity, workerComposition.getBean(WorkInputTransportFactory.class)).create(definition, inputSettings);
            input.start();
            var stats = new SwarmQueueStatsPortAdapter(resources);
            var inputAddress = topology.channel("intake").inputAddress();
            var outputAddress = topology.channel("result").inputAddress();
            var producer = plane.outputs().create(new ArtemisOutputSettings(
                topology.channel("intake").outputAddress(), true));
            producer.publish(item(info, "payload"));
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                assertThat(stats.getQueueStats(inputAddress).depth()).isEqualTo(1));
            assertThat(executions).isEmpty();
            update(control, identity, Map.of("enabled", true));
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(executions).containsExactly("payload"));
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                assertThat(stats.getQueueStats(inputAddress).depth()).isZero());
            assertThat(stats.getQueueStats(inputAddress).consumers()).isEqualTo(1);
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                assertThat(stats.getQueueStats(outputAddress).depth()).isEqualTo(1));
            var arrivedAt = new java.util.concurrent.atomic.AtomicLong();
            var results = new ConcurrentLinkedQueue<String>();
            var resultInput = plane.inputs().create("result-check", new ArtemisInputSettings(outputAddress, 0));
            resultInput.register(new WorkDeliveryHandler() {
                @Override public void onWork(WorkItem item) { arrivedAt.set(System.currentTimeMillis()); results.add(item.asString()); }
                @Override public void onDecodeFailure(byte[] body, Exception failure) { throw new AssertionError(failure); }
            });
            resultInput.start();
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(results).containsExactly("payload"));
            assertThat(arrivedAt.get() - executedAt.get()).isGreaterThanOrEqualTo(delayMs);
            // A live attempt cannot alter subsequent sends either.
            update(control, identity, Map.of("outputs", Map.of("delivery", Map.of("mode", "DELAYED", "delayMs", 9999))));
            assertThat(latest.get().rawConfig().get("outputs")).isEqualTo(accepted.get("outputs"));
            producer.publish(item(info, "invalid template"));
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                verify(control).publishWorkError(eq("worker"), any(WorkItem.class), any(IllegalArgumentException.class)));
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                assertThat(stats.getQueueStats(inputAddress).depth()).isZero());
            assertThat(stats.getQueueStats(outputAddress).depth()).isZero();
            assertThat(results).containsExactly("payload");
            assertThat(resources.observe(topology.channel("intake").resource())).isPresent();
            update(control, identity, Map.of("enabled", false));
            producer.publish(item(info, "pending after disable"));
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                assertThat(stats.getQueueStats(inputAddress).depth()).isEqualTo(1));
            assertThat(executions).containsExactly("payload", "invalid template");
            assertThat(stats.getQueueStats(outputAddress).depth()).isZero();
            assertThat(results).containsExactly("payload");
            input.stop();
            resultInput.stop();
            var removed = infrastructure.removeWorkTopology(topology);
            assertThat(removed).hasSize(4).allSatisfy(target -> {
                assertThat(target.type()).isEqualTo(RemoveResourceType.WORK_RESOURCE);
                assertThat(target.plane()).isEqualTo(ResourcePlane.WORK);
                assertThat(resources.observe(resources.identify(target))).isEmpty();
            });
            verifyNoInteractions(controlResources);
        } finally { broker.stop(); }
    }

    private static WorkItem item(WorkerInfo info, String payload) {
        return WorkItem.text(info, payload).observabilityContext(
            ObservabilityContextUtil.init(info.role(), info.instanceId(), info.swarmId())).build();
    }

    private static void update(WorkerControlPlaneRuntime control, ControlPlaneIdentity identity, Map<String, Object> config) throws Exception {
        var signal = ControlSignal.forInstance("config-update", identity.swarmId(), identity.role(), identity.instanceId(),
            "orchestrator", UUID.randomUUID().toString(), UUID.randomUUID().toString(), config);
        String route = ControlPlaneRouting.signal("config-update", identity.swarmId(), identity.role(), identity.instanceId());
        assertThat(control.handle(ControlPlaneCodec.create().encode(signal, route), route)).isTrue();
    }
}
