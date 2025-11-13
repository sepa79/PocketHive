package io.pockethive.worker.sdk.runtime;

import io.pockethive.worker.sdk.api.WorkerContext;
import io.pockethive.worker.sdk.config.PocketHiveWorker;
import io.pockethive.worker.sdk.config.WorkInputConfig;
import io.pockethive.worker.sdk.config.WorkOutputConfig;
import io.pockethive.worker.sdk.config.WorkerCapability;
import io.pockethive.worker.sdk.config.WorkerInputType;
import io.pockethive.worker.sdk.config.WorkerOutputType;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Captures metadata extracted from {@link PocketHiveWorker} annotations plus control-plane configuration
 * (the worker role is resolved from {@code WorkerControlPlaneProperties}). Refer to
 * {@code docs/sdk/worker-sdk-quickstart.md} for how definitions drive runtime discovery.
 */
public record WorkerDefinition(
    String beanName,
    Class<?> beanType,
    WorkerInputType input,
    String role,
    WorkIoBindings io,
    Class<?> configType,
    Class<? extends WorkInputConfig> inputConfigType,
    Class<? extends WorkOutputConfig> outputConfigType,
    WorkerOutputType outputType,
    String description,
    Set<WorkerCapability> capabilities
) {

    /**
     * Creates a new worker definition.
     *
     * @param beanName   Spring bean name
     * @param beanType   underlying class of the bean
     * @param input      input binding declared on the annotation
     * @param role       control-plane role identifier resolved from {@code WorkerControlPlaneProperties}
     * @param io         resolved work-queue bindings
     * @param configType         worker-domain configuration exposed to {@link WorkerContext#config(Class)}
     * @param inputConfigType    infrastructure input configuration class (scheduler, Rabbit, etc.)
     * @param outputConfigType   infrastructure output configuration class
     * @param outputType         output transport descriptor
     * @param description        human readable description
     * @param capabilities       worker capabilities consumed by scenario-manager/UI
     */
    public WorkerDefinition {
        beanName = requireText(beanName, "beanName");
        beanType = Objects.requireNonNull(beanType, "beanType");
        input = Objects.requireNonNull(input, "input");
        role = requireText(role, "role").trim().toLowerCase(Locale.ROOT);
        io = io == null ? WorkIoBindings.none() : io;
        configType = configType == null || configType == Void.class ? Void.class : configType;
        inputConfigType = normalizeConfigClass(inputConfigType, WorkInputConfig.class);
        outputConfigType = normalizeConfigClass(outputConfigType, WorkOutputConfig.class);
        outputType = Objects.requireNonNull(outputType, "outputType");
        description = normalize(description);
        capabilities = normalizeCapabilities(capabilities);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static <T> Class<? extends T> normalizeConfigClass(Class<? extends T> candidate, Class<T> fallback) {
        if (candidate == null) {
            return fallback;
        }
        return candidate;
    }

    private static Set<WorkerCapability> normalizeCapabilities(Set<WorkerCapability> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        return values.stream().filter(Objects::nonNull).collect(Collectors.toUnmodifiableSet());
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

}
