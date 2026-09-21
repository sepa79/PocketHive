package io.pockethive.worker.sdk.autoconfigure;

import io.pockethive.controlplane.spring.WorkerControlTopology;

import io.pockethive.templating.api.SequenceAccess;
import io.pockethive.work.api.ScheduledInvocationPolicy;

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
import io.pockethive.controlplane.worker.WorkerControlPlane;
import io.pockethive.worker.sdk.config.PocketHiveWorkerProperties;
import io.pockethive.worker.sdk.config.RedisSequenceProperties;
import io.pockethive.worker.sdk.config.WorkInputConfigBinder;
import io.pockethive.worker.sdk.input.WorkInputLifecycle;
import io.pockethive.worker.sdk.input.WorkInputRegistry;
import io.pockethive.worker.sdk.input.WorkInputRegistryInitializer;
import io.pockethive.worker.sdk.input.message.MessageWorkInputFactory;
import io.pockethive.work.api.transport.WorkInputTransportFactory;
import io.pockethive.work.api.transport.WorkOutputTransportFactory;
import io.pockethive.worker.sdk.input.SchedulerWorkInputFactory;
import io.pockethive.worker.sdk.input.redis.RedisDataSetWorkInputFactory;
import io.pockethive.worker.sdk.input.csv.CsvDataSetWorkInputFactory;
import io.pockethive.worker.sdk.config.WorkOutputConfigBinder;
import io.pockethive.worker.sdk.config.WorkerInputTypeProperties;
import io.pockethive.worker.sdk.config.WorkerOutputTypeProperties;
import io.pockethive.worker.sdk.runtime.DefaultWorkerContextFactory;
import io.pockethive.worker.sdk.runtime.DefaultWorkerRuntime;
import io.pockethive.worker.sdk.runtime.WorkerContextFactory;
import io.pockethive.worker.sdk.runtime.WorkerControlPlaneRuntime;
import io.pockethive.worker.sdk.runtime.WorkerMetricsInterceptor;
import io.pockethive.worker.sdk.runtime.WorkerObservabilityInterceptor;
import io.pockethive.worker.sdk.runtime.RedisUploaderInterceptor;
import io.pockethive.worker.sdk.runtime.TemplatingInterceptor;
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
import io.pockethive.worker.sdk.output.TransportWorkOutputFactory;
import io.pockethive.worker.sdk.output.RedisWorkOutputFactory;
import io.pockethive.templating.PebbleTemplateRenderer;
import io.pockethive.templating.api.TemplateRenderer;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Responsibility: compose worker discovery, runtime, policies and the selected I/O capabilities.
 * Must not: implement worker behavior, adapter settings or broker operations.
 * Contract: RESP-WORK-COMPOSITION — docs/architecture/runtime-responsibilities.md#resp-work-composition.
 */
@org.springframework.boot.autoconfigure.AutoConfigureAfter(name = {
    "org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration",
    "io.pockethive.rabbit.transport.RabbitTransportAutoConfiguration"
})
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
    WorkerInputTypeProperties.class,
    WorkerOutputTypeProperties.class,
    RedisSequenceProperties.class
})
@Import({
    WorkIoBindingConfiguration.class,
    ControlPlaneCommonAutoConfiguration.class,
    WorkerControlPlaneAutoConfiguration.class,
    ManagerControlPlaneAutoConfiguration.class,
    WorkerWorkConfigurationComposition.class
})
public class PocketHiveWorkerSdkAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(SequenceAccess.class)
    SequenceAccess sequenceAccess() {
        return new io.pockethive.templating.ConfiguredRedisSequenceAccess();
    }

    @Bean
    @ConditionalOnMissingBean(ScheduledInvocationPolicy.class)
    @ConditionalOnProperty(prefix = "pockethive.inputs", name = "type", havingValue = "SCHEDULER")
    ScheduledInvocationPolicy<Object> rateSchedulePolicy() {
        return new io.pockethive.worker.sdk.input.RateSchedulePolicy();
    }

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
        WorkOutputConfigBinder workOutputConfigBinder,
        ObjectProvider<WorkerInputTypeProperties> inputTypePropertiesProvider,
        ObjectProvider<WorkerOutputTypeProperties> outputTypePropertiesProvider,
        io.pockethive.worker.sdk.config.WorkIoConfigurationCatalog configurationCatalog
    ) {
        return WorkerDefinitionDiscovery.discover(beanFactory, workerProperties, workInputConfigBinder,
            workOutputConfigBinder, inputTypePropertiesProvider, outputTypePropertiesProvider, configurationCatalog);
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
        ObjectProvider<List<io.pockethive.worker.sdk.input.WorkInputFactory>> factoriesProvider,
        ObjectProvider<WorkInputTransportFactory> transports, ObjectProvider<WorkerRuntime> runtime,
        ObjectProvider<WorkerControlPlaneRuntime> control,
        @Qualifier("workerControlPlaneIdentity") ObjectProvider<ControlPlaneIdentity> identity
    ) {
        List<io.pockethive.worker.sdk.input.WorkInputFactory> factories =
            new java.util.ArrayList<>(factoriesProvider.getIfAvailable(Collections::emptyList));
        transports.stream().map(transport -> new MessageWorkInputFactory(
            runtime.getObject(), control.getObject(), identity.getObject(), transport)).forEach(factories::add);
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
        ObjectProvider<ControlPlaneIdentity> controlPlaneIdentity,
        ObjectProvider<List<PocketHiveWorkerProperties<?>>> propertiesProvider
    ) {
        MeterRegistry meters = meterRegistry.getIfAvailable(SimpleMeterRegistry::new);
        ObservationRegistry observations = observationRegistry.getIfAvailable(ObservationRegistry::create);
        ControlPlaneIdentity identity = controlPlaneIdentity.getIfAvailable();
        List<PocketHiveWorkerProperties<?>> properties = propertiesProvider.getIfAvailable(Collections::emptyList);
        return new DefaultWorkerContextFactory(beanFactory::getBean, meters, observations, identity, properties);
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
	        io.pockethive.work.config.WorkMutationPolicyRegistry mutationPolicies,
	        io.pockethive.work.config.WorkConfigurationParser workConfigurationParser,
	        ObjectProvider<TemplateRenderer> templateRendererProvider,
	        ObjectProvider<ObjectMapper> objectMapperProvider
	    ) {
	        ObjectMapper mapper = objectMapperProvider.getIfAvailable(() -> new ObjectMapper().findAndRegisterModules());
	        WorkerControlTopology controlPlane = Objects
	            .requireNonNull(workerControlPlaneProperties, "workerControlPlaneProperties must not be null")
	            .getControlPlane();
	        Objects.requireNonNull(controlPlane, "workerControlPlaneProperties.controlPlane must not be null");
	        TemplateRenderer renderer = templateRendererProvider.getIfAvailable();
	        return new WorkerControlPlaneRuntime(workerControlPlane, workerStateStore, mapper, controlPlaneEmitter, identity,
	            controlPlane, renderer, mutationPolicies, workConfigurationParser);
	    }

    @Bean
    @ConditionalOnBean(WorkerControlPlaneRuntime.class)
    @ConditionalOnMissingBean(WorkerControlQueueListener.class)
    WorkerControlQueueListener workerControlQueueListener(WorkerControlPlaneRuntime controlPlaneRuntime) {
        return new WorkerControlQueueListener(controlPlaneRuntime);
    }

    @Bean
    @ConditionalOnBean(WorkerControlPlaneRuntime.class)
    io.pockethive.rabbit.api.RabbitListenerBinding workerControlRabbitBinding(
        WorkerControlQueueListener listener,
        io.pockethive.controlplane.spring.ControlPlaneRabbitBindings bindings,
        @org.springframework.beans.factory.annotation.Qualifier("workerControlQueueName") String queue) {
        return bindings.bind("workerControl", queue, message -> listener.onControl(
            message.text(), message.receivedRoutingKey(),
            (String) message.headers().get(io.pockethive.observability.ObservabilityContextUtil.HEADER)));
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
        ObjectProvider<List<WorkOutputFactory>> factoriesProvider,
        ObjectProvider<WorkOutputTransportFactory> transports
    ) {
        List<WorkOutputFactory> factories = new java.util.ArrayList<>(factoriesProvider.getIfAvailable(Collections::emptyList));
        transports.stream().map(TransportWorkOutputFactory::new).forEach(factories::add);
        return new WorkOutputRegistryInitializer(workerRegistry, workOutputRegistry, binder, factories);
    }

    @Bean
    @ConditionalOnBean(WorkOutputRegistry.class)
    @ConditionalOnMissingBean
    WorkOutputLifecycle workOutputLifecycle(WorkOutputRegistry workOutputRegistry) {
        return new WorkOutputLifecycle(workOutputRegistry);
    }

    @Bean
    @ConditionalOnProperty(prefix = "pockethive.outputs", name = "type", havingValue = "NONE")
    WorkOutputFactory noopWorkOutputFactory() {
        return new NoopWorkOutputFactory();
    }

    @Bean
    @ConditionalOnBean(WorkerControlPlaneRuntime.class)
    @ConditionalOnProperty(prefix = "pockethive.outputs", name = "type", havingValue = "REDIS")
    WorkOutputFactory redisWorkOutputFactory(
        WorkerControlPlaneRuntime controlPlaneRuntime,
        TemplateRenderer templateRenderer
    ) {
        return new RedisWorkOutputFactory(controlPlaneRuntime, templateRenderer);
    }

    @Bean
    @ConditionalOnBean({WorkerRuntime.class, WorkerControlPlaneRuntime.class})
    @ConditionalOnProperty(prefix = "pockethive.inputs", name = "type", havingValue = "SCHEDULER")
    io.pockethive.worker.sdk.input.WorkInputFactory schedulerWorkInputFactory(
        WorkerRuntime workerRuntime,
        WorkerControlPlaneRuntime controlPlaneRuntime,
        @Qualifier("workerControlPlaneIdentity") ControlPlaneIdentity identity,
        ScheduledInvocationPolicy<?> policy
    ) {
        return new SchedulerWorkInputFactory(workerRuntime, controlPlaneRuntime, identity, policy);
    }

    @Bean
    @ConditionalOnBean({WorkerRuntime.class, WorkerControlPlaneRuntime.class})
    @ConditionalOnProperty(prefix = "pockethive.inputs", name = "type", havingValue = "REDIS_DATASET")
    io.pockethive.worker.sdk.input.WorkInputFactory redisDataSetWorkInputFactory(
        WorkerRuntime workerRuntime,
        WorkerControlPlaneRuntime controlPlaneRuntime,
        @Qualifier("workerControlPlaneIdentity") ControlPlaneIdentity identity
    ) {
        return new RedisDataSetWorkInputFactory(workerRuntime, controlPlaneRuntime, identity);
    }

    @Bean
    @ConditionalOnBean({WorkerRuntime.class, WorkerControlPlaneRuntime.class})
    @ConditionalOnProperty(prefix = "pockethive.inputs", name = "type", havingValue = "CSV_DATASET")
    io.pockethive.worker.sdk.input.WorkInputFactory csvDataSetWorkInputFactory(
        WorkerRuntime workerRuntime,
        WorkerControlPlaneRuntime controlPlaneRuntime,
        @Qualifier("workerControlPlaneIdentity") ControlPlaneIdentity identity
    ) {
        return new CsvDataSetWorkInputFactory(workerRuntime, controlPlaneRuntime, identity);
    }

    @Bean
    @ConditionalOnMissingBean
    WorkerInvocationInterceptor workerObservabilityInterceptor() {
        return new WorkerObservabilityInterceptor();
    }

    @Bean
    @ConditionalOnMissingBean(RedisUploaderInterceptor.class)
    WorkerInvocationInterceptor redisUploaderInterceptor(TemplateRenderer templateRenderer) {
        return new RedisUploaderInterceptor(templateRenderer);
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

    @Bean
    @ConditionalOnMissingBean(TemplateRenderer.class)
    TemplateRenderer templatingRenderer(SequenceAccess sequences) {
        return new PebbleTemplateRenderer(sequences);
    }

    @Bean
    @ConditionalOnMissingBean(TemplatingInterceptor.class)
    WorkerInvocationInterceptor templatingInterceptor(TemplateRenderer renderer) {
        return new TemplatingInterceptor(renderer, context -> {
            Map<String, Object> rawConfig = context.state().rawConfig();
            if (rawConfig.isEmpty()) {
                return null;
            }
            Object interceptorsObj = rawConfig.get("interceptors");
            if (!(interceptorsObj instanceof Map<?, ?> interceptors)) {
                return null;
            }
            Object templatingObj = interceptors.get("templating");
            if (!(templatingObj instanceof Map<?, ?> templatingMap)) {
                return null;
            }
            Object templateValue = templatingMap.get("template");
            if (templateValue == null) {
                return null;
            }
            String template = templateValue.toString();
            return (template == null || template.isBlank()) ? null : template;
        });
    }

}
