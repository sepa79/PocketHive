package io.pockethive.worker.sdk.autoconfigure;

import io.pockethive.controlplane.spring.WorkerControlPlaneProperties;
import io.pockethive.work.api.PocketHiveWorker;
import io.pockethive.worker.sdk.config.RabbitInputProperties;
import io.pockethive.worker.sdk.config.RabbitOutputProperties;
import io.pockethive.worker.sdk.config.RedisOutputProperties;
import io.pockethive.worker.sdk.config.RedisDataSetInputProperties;
import io.pockethive.worker.sdk.input.csv.CsvDataSetInputProperties;
import io.pockethive.worker.sdk.config.SchedulerInputProperties;
import io.pockethive.worker.sdk.config.WorkInputConfig;
import io.pockethive.worker.sdk.config.WorkInputConfigBinder;
import io.pockethive.worker.sdk.config.WorkOutputConfig;
import io.pockethive.worker.sdk.config.WorkOutputConfigBinder;
import io.pockethive.work.api.WorkerCapability;
import io.pockethive.worker.sdk.config.WorkerInputType;
import io.pockethive.worker.sdk.config.WorkerInputTypeProperties;
import io.pockethive.worker.sdk.config.WorkerOutputType;
import io.pockethive.worker.sdk.config.WorkerOutputTypeProperties;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;
import io.pockethive.worker.sdk.runtime.WorkIoBindings;
import io.pockethive.worker.sdk.runtime.WorkerRegistry;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.ListableBeanFactory;

/**
 * Responsibility: discover the worker declaration and bind its selected IO configuration.
 * Must not: create clients, declare topology or select adapter implementations.
 * Contract: RESP-WORK-CAPABILITY — docs/architecture/runtime-responsibilities.md#resp-work-capability.
 */
final class WorkerDefinitionDiscovery {
    private WorkerDefinitionDiscovery() {}

    static WorkerRegistry discover(
        ListableBeanFactory beanFactory,
        ObjectProvider<WorkerControlPlaneProperties> workerProperties,
        WorkInputConfigBinder workInputConfigBinder,
        WorkOutputConfigBinder workOutputConfigBinder,
        ObjectProvider<WorkerInputTypeProperties> inputTypePropertiesProvider,
        ObjectProvider<WorkerOutputTypeProperties> outputTypePropertiesProvider
    ) {
        String[] beanNames = beanFactory.getBeanNamesForAnnotation(PocketHiveWorker.class);
        if (beanNames.length == 0) {
            throw new IllegalStateException("No @PocketHiveWorker beans were discovered in this service");
        }
        if (beanNames.length > 1) {
            throw new IllegalStateException(
                "Multiple @PocketHiveWorker beans are not supported. Found: %s".formatted(String.join(", ", beanNames)));
        }
        String workerRole = resolveWorkerRole(workerProperties.getIfAvailable());
        WorkerInputTypeProperties inputTypeProperties = inputTypePropertiesProvider.getIfAvailable();
        WorkerOutputTypeProperties outputTypeProperties = outputTypePropertiesProvider.getIfAvailable();
        List<WorkerDefinition> definitions = new ArrayList<>(beanNames.length);
        for (String beanName : beanNames) {
            PocketHiveWorker annotation = beanFactory.findAnnotationOnBean(beanName, PocketHiveWorker.class);
            if (annotation == null) {
                continue;
            }
            Class<?> beanType = Objects.requireNonNull(beanFactory.getType(beanName),
                () -> "Unable to resolve bean type for worker '" + beanName + "'");
            Class<?> configType = annotation.config();
            WorkerInputType inputType = resolveEffectiveInputType(annotation, inputTypeProperties);
            WorkerOutputType outputType = resolveEffectiveOutputType(annotation, outputTypeProperties);
            Class<? extends WorkInputConfig> inputConfigType = resolveInputConfigType(annotation, inputType);
            Class<? extends WorkOutputConfig> outputConfigType = resolveOutputConfigType(annotation, outputType);
            String description = annotation.description();
            Set<WorkerCapability> capabilities = resolveCapabilities(annotation);
            WorkInputConfig inputConfig = workInputConfigBinder.bind(inputType, inputConfigType);
            WorkOutputConfig outputConfig = workOutputConfigBinder.bind(outputType, outputConfigType);
            WorkIoBindings io = resolveIo(inputType, outputType, inputConfig, outputConfig, workInputConfigBinder, workOutputConfigBinder);
            definitions.add(new WorkerDefinition(
                beanName,
                beanType,
                inputType,
                workerRole,
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

    private static String resolveWorkerRole(WorkerControlPlaneProperties properties) {
        if (properties == null || properties.getWorker() == null) {
            throw new IllegalStateException("WorkerControlPlaneProperties must be configured to resolve the worker role");
        }
        String role = properties.getWorker().getRole();
        if (role == null || role.isBlank()) {
            throw new IllegalStateException("pockethive.control-plane.worker.role must not be blank");
        }
        return role.trim();
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

    private static WorkIoBindings resolveIo(
        WorkerInputType inputType,
        WorkerOutputType outputType,
        WorkInputConfig inputConfig,
        WorkOutputConfig outputConfig,
        WorkInputConfigBinder inputBinder,
        WorkOutputConfigBinder outputBinder
    ) {
        String inQueue = null;
        if (inputType == WorkerInputType.RABBITMQ) {
            if (!(inputConfig instanceof RabbitInputProperties rabbit)) {
                throw new IllegalStateException(
                    "Rabbit inputs require " + RabbitInputProperties.class.getSimpleName() + " configuration");
            }
            String queue = normalise(rabbit.getQueue());
            if (queue == null) {
                throw new IllegalStateException(
                    "Rabbit workers must configure an input queue via %s.queue".formatted(
                        inputBinder.prefix(inputType)));
            }
            inQueue = queue;
        }
        String outQueue = null;
        String exchange = null;
        if (outputType == WorkerOutputType.RABBITMQ) {
            if (!(outputConfig instanceof RabbitOutputProperties rabbit)) {
                throw new IllegalStateException(
                    "Rabbit outputs require " + RabbitOutputProperties.class.getSimpleName() + " configuration");
            }
            String routingKey = normalise(rabbit.getRoutingKey());
            String configuredExchange = normalise(rabbit.getExchange());
            if (routingKey == null) {
                throw new IllegalStateException(
                    "Rabbit workers must configure an output routing key via %s.routingKey".formatted(
                        outputBinder.prefix(outputType)));
            }
            if (configuredExchange == null) {
                throw new IllegalStateException(
                    "Rabbit workers must configure an output exchange via %s.exchange".formatted(
                        outputBinder.prefix(outputType)));
            }
            outQueue = routingKey;
            exchange = configuredExchange;
        }
        return new WorkIoBindings(inQueue, outQueue, exchange);
    }

    private static Class<? extends WorkInputConfig> resolveInputConfigType(PocketHiveWorker annotation,
                                                                          WorkerInputType inputType) {
        return switch (inputType) {
            case SCHEDULER -> SchedulerInputProperties.class;
            case RABBITMQ -> RabbitInputProperties.class;
            case REDIS_DATASET -> RedisDataSetInputProperties.class;
            case CSV_DATASET -> CsvDataSetInputProperties.class;
            default -> WorkInputConfig.class;
        };
    }

    private static Class<? extends WorkOutputConfig> resolveOutputConfigType(PocketHiveWorker annotation,
                                                                            WorkerOutputType outputType) {
        return switch (outputType) {
            case RABBITMQ -> RabbitOutputProperties.class;
            case REDIS -> RedisOutputProperties.class;
            default -> WorkOutputConfig.class;
        };
    }

    private static WorkerInputType resolveEffectiveInputType(PocketHiveWorker annotation,
                                                             WorkerInputTypeProperties inputTypeProperties) {
        if (inputTypeProperties == null || inputTypeProperties.getType() == null) {
            throw new IllegalStateException(
                "pockethive.inputs.type must be configured (no fallback to @PocketHiveWorker.input)");
        }
        return inputTypeProperties.getType();
    }

    private static WorkerOutputType resolveEffectiveOutputType(PocketHiveWorker annotation,
                                                               WorkerOutputTypeProperties outputTypeProperties) {
        if (outputTypeProperties == null || outputTypeProperties.getType() == null) {
            throw new IllegalStateException(
                "pockethive.outputs.type must be configured (no fallback to @PocketHiveWorker.output)");
        }
        return outputTypeProperties.getType();
    }

    private static String normalise(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
