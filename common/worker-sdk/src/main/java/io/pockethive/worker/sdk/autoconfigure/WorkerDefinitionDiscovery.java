package io.pockethive.worker.sdk.autoconfigure;

import io.pockethive.controlplane.spring.WorkerControlPlaneProperties;
import io.pockethive.work.api.PocketHiveWorker;
import io.pockethive.work.config.binding.WorkInputConfig;
import io.pockethive.worker.sdk.config.WorkInputConfigBinder;
import io.pockethive.work.config.binding.WorkOutputConfig;
import io.pockethive.worker.sdk.config.WorkOutputConfigBinder;
import io.pockethive.work.api.WorkerCapability;
import io.pockethive.work.config.WorkIoType;
import io.pockethive.worker.sdk.config.WorkerInputTypeProperties;
import io.pockethive.worker.sdk.config.WorkIoConfigurationCatalog;
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
        ObjectProvider<WorkerOutputTypeProperties> outputTypePropertiesProvider,
        WorkIoConfigurationCatalog configurationCatalog
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
            WorkIoType inputType = configurationCatalog.inputType(requireInputType(inputTypeProperties));
            WorkIoType outputType = configurationCatalog.outputType(requireOutputType(outputTypeProperties));
            Class<? extends WorkInputConfig> inputConfigType = configurationCatalog.inputClass(inputType);
            Class<? extends WorkOutputConfig> outputConfigType = configurationCatalog.outputClass(outputType);
            String description = annotation.description();
            Set<WorkerCapability> capabilities = resolveCapabilities(annotation);
            WorkInputConfig inputConfig = workInputConfigBinder.bind(inputType, inputConfigType);
            WorkOutputConfig outputConfig = workOutputConfigBinder.bind(outputType, outputConfigType);
            WorkIoBindings io = new WorkIoBindings(inputConfig == null ? null : inputConfig.inboundRoute(),
                outputConfig == null ? null : outputConfig.outboundRoute(),
                outputConfig == null ? null : outputConfig.outboundGroup());
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

    private static String requireInputType(WorkerInputTypeProperties inputTypeProperties) {
        if (inputTypeProperties == null || inputTypeProperties.getType() == null) {
            throw new IllegalStateException(
                "pockethive.inputs.type must be configured (no fallback to @PocketHiveWorker.input)");
        }
        return inputTypeProperties.getType();
    }

    private static String requireOutputType(WorkerOutputTypeProperties outputTypeProperties) {
        if (outputTypeProperties == null || outputTypeProperties.getType() == null) {
            throw new IllegalStateException(
                "pockethive.outputs.type must be configured (no fallback to @PocketHiveWorker.output)");
        }
        return outputTypeProperties.getType();
    }

}
