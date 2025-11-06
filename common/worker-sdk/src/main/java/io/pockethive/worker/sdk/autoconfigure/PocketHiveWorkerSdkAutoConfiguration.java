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
import io.pockethive.worker.sdk.input.WorkInputLifecycle;
import io.pockethive.worker.sdk.input.WorkInputRegistry;
import io.pockethive.worker.sdk.input.WorkInputRegistryInitializer;
import io.pockethive.worker.sdk.metrics.PrometheusPushGatewayProperties;
import io.pockethive.worker.sdk.runtime.DefaultWorkerContextFactory;
import io.pockethive.worker.sdk.runtime.DefaultWorkerRuntime;
import io.pockethive.worker.sdk.runtime.WorkerContextFactory;
import io.pockethive.worker.sdk.runtime.WorkerControlPlaneRuntime;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;
import io.pockethive.worker.sdk.runtime.WorkerMetricsInterceptor;
import io.pockethive.worker.sdk.runtime.WorkerObservabilityInterceptor;
import io.pockethive.worker.sdk.runtime.WorkerRegistry;
import io.pockethive.worker.sdk.runtime.WorkerRuntime;
import io.pockethive.worker.sdk.runtime.WorkerStateStore;
import io.pockethive.worker.sdk.runtime.WorkerStatusScheduler;
import io.pockethive.worker.sdk.runtime.WorkerStatusSchedulerProperties;
import io.pockethive.worker.sdk.runtime.WorkerInvocationInterceptor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

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
        ObjectProvider<WorkerControlPlaneProperties> workerProperties
    ) {
        WorkerControlPlaneProperties properties = workerProperties.getIfAvailable();
        Map<String, String> queueAliases = buildQueueAliasMap(properties);
        String[] beanNames = beanFactory.getBeanNamesForAnnotation(PocketHiveWorker.class);
        List<WorkerDefinition> definitions = new ArrayList<>(beanNames.length);
        for (String beanName : beanNames) {
            PocketHiveWorker annotation = beanFactory.findAnnotationOnBean(beanName, PocketHiveWorker.class);
            if (annotation == null) {
                continue;
            }
            Class<?> beanType = Objects.requireNonNull(beanFactory.getType(beanName),
                () -> "Unable to resolve bean type for worker '" + beanName + "'");
            Class<?> configType = annotation.config();
            definitions.add(new WorkerDefinition(
                beanName,
                beanType,
                annotation.input(),
                annotation.role(),
                resolveQueue(annotation.inQueue(), queueAliases),
                resolveQueue(annotation.outQueue(), queueAliases),
                resolveExchange(properties),
                configType
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
        WorkInputRegistry workInputRegistry
    ) {
        return new WorkInputRegistryInitializer(workerRegistry, workInputRegistry);
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
        ObjectProvider<WorkerInvocationInterceptor> interceptorProvider
    ) {
        List<WorkerInvocationInterceptor> interceptors = interceptorProvider.orderedStream().toList();
        return new DefaultWorkerRuntime(workerRegistry, beanFactory::getBean, workerContextFactory, workerStateStore, interceptors);
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

    private static Map<String, String> buildQueueAliasMap(
        WorkerControlPlaneProperties properties
    ) {
        if (properties == null) {
            return Map.of();
        }
        Map<String, String> aliases = new LinkedHashMap<>();
        properties.getQueues().names().forEach((key, value) -> {
            String alias = normalise(key);
            if (alias != null) {
                aliases.put(alias, value);
            }
        });
        WorkerControlPlaneProperties.ControlPlane controlPlane = properties.getControlPlane();
        if (controlPlane != null) {
            String controlQueue = controlPlane.getControlQueueName();
            if (controlQueue != null) {
                String trimmed = controlQueue.trim();
                if (!trimmed.isEmpty()) {
                    aliases.putIfAbsent("control", trimmed);
                }
            }
        }
        return aliases;
    }

    private static String resolveExchange(WorkerControlPlaneProperties properties) {
        if (properties == null) {
            return null;
        }
        return normalise(properties.getTrafficExchange());
    }

    private static String resolveQueue(String queue, Map<String, String> aliases) {
        String candidate = normalise(queue);
        if (candidate == null) {
            return null;
        }
        String resolved = aliases.get(candidate);
        return resolved != null ? resolved : candidate;
    }

    private static String normalise(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
