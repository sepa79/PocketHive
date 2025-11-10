package io.pockethive.worker.sdk.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.controlplane.messaging.ControlPlaneEmitter;
import io.pockethive.controlplane.spring.ControlPlaneCommonAutoConfiguration;
import io.pockethive.controlplane.spring.ManagerControlPlaneAutoConfiguration;
import io.pockethive.controlplane.spring.WorkerControlPlaneAutoConfiguration;
import io.pockethive.controlplane.spring.WorkerControlPlaneProperties;
import io.pockethive.controlplane.topology.ControlPlaneTopologyDescriptor;
import io.pockethive.controlplane.worker.WorkerControlPlane;
import io.pockethive.worker.sdk.config.PocketHiveWorker;
import io.pockethive.worker.sdk.config.PocketHiveWorkerProperties;
import io.pockethive.worker.sdk.config.RabbitInputProperties;
import io.pockethive.worker.sdk.config.RabbitOutputProperties;
import io.pockethive.worker.sdk.config.SchedulerInputProperties;
import io.pockethive.worker.sdk.config.WorkInputConfig;
import io.pockethive.worker.sdk.config.WorkInputConfigBinder;
import io.pockethive.worker.sdk.input.WorkInputLifecycle;
import io.pockethive.worker.sdk.input.WorkInputRegistry;
import io.pockethive.worker.sdk.input.WorkInputRegistryInitializer;
import io.pockethive.worker.sdk.input.rabbit.RabbitWorkInputFactory;
import io.pockethive.worker.sdk.input.rabbit.RabbitWorkInputListenerConfigurer;
import io.pockethive.worker.sdk.input.SchedulerWorkInputFactory;
import io.pockethive.worker.sdk.metrics.PrometheusPushGatewayProperties;
import io.pockethive.worker.sdk.config.WorkOutputConfig;
import io.pockethive.worker.sdk.config.WorkOutputConfigBinder;
import io.pockethive.worker.sdk.config.WorkerCapability;
import io.pockethive.worker.sdk.config.WorkerInputType;
import io.pockethive.worker.sdk.runtime.DefaultWorkerContextFactory;
import io.pockethive.worker.sdk.runtime.DefaultWorkerRuntime;
import io.pockethive.worker.sdk.runtime.WorkerContextFactory;
import io.pockethive.worker.sdk.runtime.WorkerControlPlaneRuntime;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;
import io.pockethive.worker.sdk.config.WorkerOutputType;
import io.pockethive.worker.sdk.runtime.WorkerMetricsInterceptor;
import io.pockethive.worker.sdk.runtime.WorkerObservabilityInterceptor;
import io.pockethive.worker.sdk.runtime.WorkIoBindings;
import io.pockethive.worker.sdk.runtime.WorkerRegistry;
import io.pockethive.worker.sdk.runtime.WorkerRuntime;
import io.pockethive.worker.sdk.runtime.WorkerStateStore;
import io.pockethive.worker.sdk.runtime.WorkerStatusScheduler;
import io.pockethive.worker.sdk.runtime.WorkerStatusSchedulerProperties;
import io.pockethive.worker.sdk.runtime.WorkerInvocationInterceptor;
import io.pockethive.worker.sdk.output.NoopWorkOutputFactory;
import io.pockethive.worker.sdk.output.WorkOutputFactory;
import io.pockethive.worker.sdk.output.WorkOutputLifecycle;
import io.pockethive.worker.sdk.output.WorkOutputRegistry;
import io.pockethive.worker.sdk.output.WorkOutputRegistryInitializer;
import io.pockethive.worker.sdk.output.RabbitWorkOutputFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.amqp.rabbit.annotation.RabbitListenerConfigurer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * Aggregates the PocketHive control-plane auto-configuration so worker applications can
 * opt-in by depending on the Worker SDK starter.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PrometheusPushGatewayProperties.class)
@Import({
    ControlPlaneCommonAutoConfiguration.class,
    WorkerControlPlaneAutoConfiguration.class,
    ManagerControlPlaneAutoConfiguration.class
})
public class PocketHiveWorkerSdkAutoConfiguration {

    @Bean
    @ConfigurationProperties(prefix = "pockethive.worker.status")
    @ConditionalOnMissingBean
    WorkerStatusSchedulerProperties workerStatusSchedulerProperties() {
        return new WorkerStatusSchedulerProperties();
    }

    @Bean
    @ConditionalOnMissingBean
    WorkerRegistry workerRegistry(
        ListableBeanFactory beanFactory,
        ObjectProvider<WorkerControlPlaneProperties> workerProperties,
        WorkInputConfigBinder workInputConfigBinder,
        WorkOutputConfigBinder workOutputConfigBinder
    ) {
        String[] beanNames = beanFactory.getBeanNamesForAnnotation(PocketHiveWorker.class);
        if (beanNames.length == 0) {
            throw new IllegalStateException("No @PocketHiveWorker beans were discovered in this service");
        }
        if (beanNames.length > 1) {
            throw new IllegalStateException(
                "Multiple @PocketHiveWorker beans are not supported. Found: %s".formatted(String.join(", ", beanNames)));
        }
        List<WorkerDefinition> definitions = new ArrayList<>(beanNames.length);
        for (String beanName : beanNames) {
            PocketHiveWorker annotation = beanFactory.findAnnotationOnBean(beanName, PocketHiveWorker.class);
            if (annotation == null) {
                continue;
            }
            Class<?> beanType = Objects.requireNonNull(beanFactory.getType(beanName),
                () -> "Unable to resolve bean type for worker '" + beanName + "'");
            Class<?> configType = annotation.config();
            Class<? extends WorkInputConfig> inputConfigType = resolveInputConfigType(annotation);
            Class<? extends WorkOutputConfig> outputConfigType = resolveOutputConfigType(annotation);
            WorkerOutputType outputType = annotation.output();
            String description = annotation.description();
            Set<WorkerCapability> capabilities = resolveCapabilities(annotation);
            WorkInputConfig inputConfig = workInputConfigBinder.bind(annotation.input(), inputConfigType);
            WorkOutputConfig outputConfig = workOutputConfigBinder.bind(outputType, outputConfigType);
            WorkIoBindings io = resolveIo(annotation, inputConfig, outputConfig, workInputConfigBinder, workOutputConfigBinder);
            definitions.add(new WorkerDefinition(
                beanName,
                beanType,
                annotation.input(),
                annotation.role(),
                io,
                configType,
                inputConfigType,
                outputConfigType,
                outputType,
                description,
                capabilities
            ));
        }
        return new WorkerRegistry(definitions);
    }

    @Bean
    @ConditionalOnMissingBean
    WorkInputRegistry workInputRegistry() {
        return new WorkInputRegistry();
    }

    @Bean
    @ConditionalOnBean({WorkerRegistry.class, WorkInputRegistry.class})
    @ConditionalOnMissingBean
    WorkInputRegistryInitializer workInputRegistryInitializer(
        WorkerRegistry workerRegistry,
        WorkInputRegistry workInputRegistry,
        WorkInputConfigBinder workInputConfigBinder,
        ObjectProvider<List<io.pockethive.worker.sdk.input.WorkInputFactory>> factoriesProvider
    ) {
        List<io.pockethive.worker.sdk.input.WorkInputFactory> factories =
            factoriesProvider.getIfAvailable(Collections::emptyList);
        return new WorkInputRegistryInitializer(workerRegistry, workInputRegistry, workInputConfigBinder, factories);
    }

    @Bean
    @ConditionalOnBean(WorkInputRegistry.class)
    @ConditionalOnMissingBean
    WorkInputLifecycle workInputLifecycle(WorkInputRegistry workInputRegistry) {
        return new WorkInputLifecycle(workInputRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    WorkerStateStore workerStateStore(WorkerRegistry workerRegistry) {
        WorkerStateStore store = new WorkerStateStore();
        workerRegistry.all().forEach(store::getOrCreate);
        return store;
    }

    @Bean
    @ConditionalOnMissingBean
    WorkerContextFactory workerContextFactory(
        ConfigurableListableBeanFactory beanFactory,
        ObjectProvider<MeterRegistry> meterRegistry,
        ObjectProvider<ObservationRegistry> observationRegistry,
        ObjectProvider<ControlPlaneIdentity> controlPlaneIdentity
    ) {
        MeterRegistry meters = meterRegistry.getIfAvailable(SimpleMeterRegistry::new);
        ObservationRegistry observations = observationRegistry.getIfAvailable(ObservationRegistry::create);
        ControlPlaneIdentity identity = controlPlaneIdentity.getIfAvailable();
        return new DefaultWorkerContextFactory(beanFactory::getBean, meters, observations, identity);
    }

    @Bean
    @ConditionalOnMissingBean
    WorkerRuntime workerRuntime(
        WorkerRegistry workerRegistry,
        ConfigurableListableBeanFactory beanFactory,
        WorkerContextFactory workerContextFactory,
        WorkerStateStore workerStateStore,
        ObjectProvider<WorkerInvocationInterceptor> interceptorProvider,
        ObjectProvider<io.pockethive.worker.sdk.output.WorkOutputRegistry> outputRegistryProvider
    ) {
        List<WorkerInvocationInterceptor> interceptors = interceptorProvider.orderedStream().toList();
        io.pockethive.worker.sdk.output.WorkOutputRegistry outputs = outputRegistryProvider.getIfAvailable();
        return new DefaultWorkerRuntime(workerRegistry, beanFactory::getBean, workerContextFactory, workerStateStore, interceptors, outputs);
    }

    @Bean
    @ConditionalOnBean(value = WorkerControlPlane.class, name = "workerControlPlaneEmitter")
    @ConditionalOnMissingBean
    WorkerControlPlaneRuntime workerControlPlaneRuntime(
        WorkerControlPlane workerControlPlane,
        WorkerStateStore workerStateStore,
        @Qualifier("workerControlPlaneIdentity") ControlPlaneIdentity identity,
        @Qualifier("workerControlPlaneEmitter") ControlPlaneEmitter controlPlaneEmitter,
        WorkerControlPlaneProperties workerControlPlaneProperties,
        ObjectProvider<ObjectMapper> objectMapperProvider
    ) {
        ObjectMapper mapper = objectMapperProvider.getIfAvailable(() -> new ObjectMapper().findAndRegisterModules());
        WorkerControlPlaneProperties.ControlPlane controlPlane = Objects
            .requireNonNull(workerControlPlaneProperties, "workerControlPlaneProperties must not be null")
            .getControlPlane();
        Objects.requireNonNull(controlPlane, "workerControlPlaneProperties.controlPlane must not be null");
        return new WorkerControlPlaneRuntime(workerControlPlane, workerStateStore, mapper, controlPlaneEmitter, identity,
            controlPlane);
    }

    @Bean
    @ConditionalOnBean(WorkerControlPlaneRuntime.class)
    @ConditionalOnMissingBean(WorkerControlQueueListener.class)
    WorkerControlQueueListener workerControlQueueListener(WorkerControlPlaneRuntime controlPlaneRuntime) {
        return new WorkerControlQueueListener(controlPlaneRuntime);
    }

    @Bean
    @ConditionalOnBean(WorkerControlPlaneRuntime.class)
    @ConditionalOnMissingBean
    WorkerStatusScheduler workerStatusScheduler(
        WorkerControlPlaneRuntime controlPlaneRuntime,
        WorkerStatusSchedulerProperties properties
    ) {
        return new WorkerStatusScheduler(controlPlaneRuntime, properties);
    }

    @Bean
    @ConditionalOnBean({WorkerControlPlaneRuntime.class, WorkerRegistry.class})
    @ConditionalOnMissingBean
    PocketHiveWorkerDefaultsInitializer pocketHiveWorkerDefaultsInitializer(
        WorkerRegistry workerRegistry,
        WorkerControlPlaneRuntime controlPlaneRuntime,
        ObjectProvider<List<PocketHiveWorkerProperties<?>>> propertiesProvider
    ) {
        List<PocketHiveWorkerProperties<?>> properties = propertiesProvider.getIfAvailable(Collections::emptyList);
        return new PocketHiveWorkerDefaultsInitializer(workerRegistry, controlPlaneRuntime, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    WorkInputConfigBinder workInputConfigBinder(ConfigurableEnvironment environment) {
        return new WorkInputConfigBinder(Binder.get(environment));
    }

    @Bean
    @ConditionalOnMissingBean
    WorkOutputRegistry workOutputRegistry() {
        return new WorkOutputRegistry();
    }

    @Bean
    @ConditionalOnMissingBean
    WorkOutputConfigBinder workOutputConfigBinder(ConfigurableEnvironment environment) {
        return new WorkOutputConfigBinder(Binder.get(environment));
    }

    @Bean
    @ConditionalOnBean({WorkOutputRegistry.class, WorkerRegistry.class})
    @ConditionalOnMissingBean
    WorkOutputRegistryInitializer workOutputRegistryInitializer(
        WorkerRegistry workerRegistry,
        WorkOutputRegistry workOutputRegistry,
        WorkOutputConfigBinder binder,
        ObjectProvider<List<WorkOutputFactory>> factoriesProvider
    ) {
        List<WorkOutputFactory> factories = factoriesProvider.getIfAvailable(Collections::emptyList);
        return new WorkOutputRegistryInitializer(workerRegistry, workOutputRegistry, binder, factories);
    }

    @Bean
    @ConditionalOnBean(WorkOutputRegistry.class)
    @ConditionalOnMissingBean
    WorkOutputLifecycle workOutputLifecycle(WorkOutputRegistry workOutputRegistry) {
        return new WorkOutputLifecycle(workOutputRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    WorkOutputFactory noopWorkOutputFactory() {
        return new NoopWorkOutputFactory();
    }

    @Bean
    @ConditionalOnBean(RabbitTemplate.class)
    WorkOutputFactory rabbitWorkOutputFactory(RabbitTemplate rabbitTemplate) {
        return new RabbitWorkOutputFactory(rabbitTemplate);
    }

    @Bean
    @ConditionalOnBean({WorkerRuntime.class, WorkerControlPlaneRuntime.class})
    @ConditionalOnProperty(prefix = "pockethive.worker.inputs", name = "autowire", havingValue = "true")
    io.pockethive.worker.sdk.input.WorkInputFactory schedulerWorkInputFactory(
        WorkerRuntime workerRuntime,
        WorkerControlPlaneRuntime controlPlaneRuntime,
        @Qualifier("workerControlPlaneIdentity") ControlPlaneIdentity identity,
        ObjectMapper objectMapper,
        ObjectProvider<List<PocketHiveWorkerProperties<?>>> propertiesProvider
    ) {
        List<PocketHiveWorkerProperties<?>> properties = propertiesProvider.getIfAvailable(Collections::emptyList);
        return new SchedulerWorkInputFactory(workerRuntime, controlPlaneRuntime, identity, objectMapper, properties);
    }

    @Bean
    @ConditionalOnBean({WorkerRuntime.class, WorkerControlPlaneRuntime.class, RabbitTemplate.class, RabbitListenerEndpointRegistry.class})
    @ConditionalOnProperty(prefix = "pockethive.worker.inputs", name = "autowire", havingValue = "true")
    io.pockethive.worker.sdk.input.WorkInputFactory rabbitWorkInputFactory(
        WorkerRuntime workerRuntime,
        WorkerControlPlaneRuntime controlPlaneRuntime,
        @Qualifier("workerControlPlaneIdentity") ControlPlaneIdentity identity,
        RabbitTemplate rabbitTemplate,
        RabbitListenerEndpointRegistry listenerRegistry,
        ObjectProvider<List<PocketHiveWorkerProperties<?>>> propertiesProvider
    ) {
        List<PocketHiveWorkerProperties<?>> properties = propertiesProvider.getIfAvailable(Collections::emptyList);
        return new RabbitWorkInputFactory(workerRuntime, controlPlaneRuntime, identity, rabbitTemplate, listenerRegistry, properties);
    }

    @Bean
    @ConditionalOnBean({WorkerRegistry.class, WorkInputRegistry.class})
    @ConditionalOnProperty(prefix = "pockethive.worker.inputs", name = "autowire", havingValue = "true")
    RabbitListenerConfigurer rabbitWorkInputListenerConfigurer(
        WorkerRegistry workerRegistry,
        WorkInputRegistry workInputRegistry
    ) {
        return new RabbitWorkInputListenerConfigurer(workerRegistry, workInputRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    WorkerInvocationInterceptor workerObservabilityInterceptor() {
        return new WorkerObservabilityInterceptor();
    }

    @Bean
    @ConditionalOnBean(MeterRegistry.class)
    @ConditionalOnProperty(
        prefix = "pockethive.control-plane.worker.metrics",
        name = "enabled",
        havingValue = "true")
    WorkerInvocationInterceptor workerMetricsInterceptor(MeterRegistry meterRegistry) {
        return new WorkerMetricsInterceptor(meterRegistry);
    }

    private static Set<WorkerCapability> resolveCapabilities(PocketHiveWorker annotation) {
        WorkerCapability[] values = annotation.capabilities();
        if (values == null || values.length == 0) {
            return Set.of();
        }
        EnumSet<WorkerCapability> set = EnumSet.noneOf(WorkerCapability.class);
        for (WorkerCapability capability : values) {
            if (capability != null) {
                set.add(capability);
            }
        }
        return Set.copyOf(set);
    }

    private WorkIoBindings resolveIo(
        PocketHiveWorker annotation,
        WorkInputConfig inputConfig,
        WorkOutputConfig outputConfig,
        WorkInputConfigBinder inputBinder,
        WorkOutputConfigBinder outputBinder
    ) {
        String inQueue = null;
        if (annotation.input() == WorkerInputType.RABBIT) {
            if (!(inputConfig instanceof RabbitInputProperties rabbit)) {
                throw new IllegalStateException(
                    "Rabbit inputs require " + RabbitInputProperties.class.getSimpleName() + " configuration");
            }
            String queue = normalise(rabbit.getQueue());
            if (queue == null) {
                throw new IllegalStateException(
                    "Rabbit workers must configure an input queue via %s.queue".formatted(
                        inputBinder.prefix(annotation.input())));
            }
            inQueue = queue;
        }
        String outQueue = null;
        String exchange = null;
        if (annotation.output() == WorkerOutputType.RABBITMQ) {
            if (!(outputConfig instanceof RabbitOutputProperties rabbit)) {
                throw new IllegalStateException(
                    "Rabbit outputs require " + RabbitOutputProperties.class.getSimpleName() + " configuration");
            }
            String routingKey = normalise(rabbit.getRoutingKey());
            String configuredExchange = normalise(rabbit.getExchange());
            if (routingKey == null) {
                throw new IllegalStateException(
                    "Rabbit workers must configure an output routing key via %s.routingKey".formatted(
                        outputBinder.prefix(annotation.output())));
            }
            if (configuredExchange == null) {
                throw new IllegalStateException(
                    "Rabbit workers must configure an output exchange via %s.exchange".formatted(
                        outputBinder.prefix(annotation.output())));
            }
            outQueue = routingKey;
            exchange = configuredExchange;
        }
        return new WorkIoBindings(inQueue, outQueue, exchange);
    }

    private static Class<? extends WorkInputConfig> resolveInputConfigType(PocketHiveWorker annotation) {
        Class<? extends WorkInputConfig> configured = annotation.inputConfig();
        if (configured != WorkInputConfig.class) {
            return configured;
        }
        return switch (annotation.input()) {
            case SCHEDULER -> SchedulerInputProperties.class;
            case RABBIT -> RabbitInputProperties.class;
            default -> WorkInputConfig.class;
        };
    }

    private static Class<? extends WorkOutputConfig> resolveOutputConfigType(PocketHiveWorker annotation) {
        Class<? extends WorkOutputConfig> configured = annotation.outputConfig();
        if (configured != WorkOutputConfig.class) {
            return configured;
        }
        return switch (annotation.output()) {
            case RABBITMQ -> RabbitOutputProperties.class;
            default -> WorkOutputConfig.class;
        };
    }

    private static String normalise(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
