package io.pockethive.worker.sdk.input;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.worker.sdk.config.PocketHiveWorkerProperties;
import io.pockethive.worker.sdk.config.SchedulerInputProperties;
import io.pockethive.worker.sdk.config.WorkInputConfig;
import io.pockethive.worker.sdk.config.WorkerInputType;
import io.pockethive.worker.sdk.input.SchedulerStates.RateConfig;
import io.pockethive.worker.sdk.runtime.WorkerControlPlaneRuntime;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;
import io.pockethive.worker.sdk.runtime.WorkerRuntime;
import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates {@link SchedulerWorkInput} instances for workers that expose {@link RateConfig} semantics.
 */
public final class SchedulerWorkInputFactory implements WorkInputFactory {

    private final WorkerRuntime workerRuntime;
    private final WorkerControlPlaneRuntime controlPlaneRuntime;
    private final ControlPlaneIdentity identity;
    private final ObjectMapper objectMapper;
    private final List<PocketHiveWorkerProperties<?>> workerProperties;

    public SchedulerWorkInputFactory(
        WorkerRuntime workerRuntime,
        WorkerControlPlaneRuntime controlPlaneRuntime,
        ControlPlaneIdentity identity,
        ObjectMapper objectMapper,
        List<PocketHiveWorkerProperties<?>> workerProperties
    ) {
        this.workerRuntime = workerRuntime;
        this.controlPlaneRuntime = controlPlaneRuntime;
        this.identity = identity;
        this.objectMapper = objectMapper;
        this.workerProperties = workerProperties == null ? List.of() : List.copyOf(workerProperties);
    }

    @Override
    public boolean supports(WorkerDefinition definition) {
        return definition.input() == WorkerInputType.SCHEDULER
            && RateConfig.class.isAssignableFrom(definition.configType());
    }

    @SuppressWarnings("unchecked")
    @Override
    public WorkInput create(WorkerDefinition definition, WorkInputConfig config) {
        Logger logger = LoggerFactory.getLogger(definition.beanType());
        Class<? extends RateConfig> configType = (Class<? extends RateConfig>) definition.configType();
        @SuppressWarnings("unchecked")
        Class<RateConfig> typedConfigType = (Class<RateConfig>) configType;
        SchedulerState<RateConfig> schedulerState = SchedulerStates.ratePerSecond(
            typedConfigType,
            () -> fetchDefaultConfig(definition, typedConfigType),
            logger,
            defaultEnabledSupplier(definition)
        );
        SchedulerInputProperties scheduling = config instanceof SchedulerInputProperties props
            ? props
            : new SchedulerInputProperties();
        return SchedulerWorkInput.<RateConfig>builder()
            .workerDefinition(definition)
            .controlPlaneRuntime(controlPlaneRuntime)
            .workerRuntime(workerRuntime)
            .identity(identity)
            .schedulerState(schedulerState)
            .scheduling(scheduling)
            .logger(logger)
            .build();
    }

    private <C extends RateConfig> C fetchDefaultConfig(WorkerDefinition definition, Class<C> configType) {
        Optional<C> configured = controlPlaneRuntime.workerConfig(definition.beanName(), configType);
        if (configured.isPresent()) {
            return configured.get();
        }
        Optional<C> defaults = resolveConfigFromProperties(definition.role(), configType);
        return defaults.orElseGet(() -> instantiateConfig(configType));
    }

    private <C extends RateConfig> Optional<C> resolveConfigFromProperties(String role, Class<C> configType) {
        if (workerProperties.isEmpty() || role == null) {
            return Optional.empty();
        }
        return workerProperties.stream()
            .filter(props -> role.equalsIgnoreCase(props.role()))
            .findFirst()
            .flatMap(props -> convertRawConfig(props.rawConfig(), configType));
    }

    private <C extends RateConfig> Optional<C> convertRawConfig(Map<String, Object> rawConfig, Class<C> configType) {
        if (rawConfig == null || rawConfig.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.convertValue(rawConfig, configType));
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(
                "Unable to convert worker defaults to " + configType.getSimpleName(), ex);
        }
    }

    private BooleanSupplier defaultEnabledSupplier(WorkerDefinition definition) {
        boolean fallback = workerProperties.stream()
            .filter(props -> definition.role().equalsIgnoreCase(props.role()))
            .findFirst()
            .map(PocketHiveWorkerProperties::isEnabled)
            .orElse(true);
        return () -> fallback;
    }

    private static <C extends RateConfig> C instantiateConfig(Class<C> configType) {
        try {
            Constructor<C> ctor = configType.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to instantiate scheduler config of type " + configType.getName(), ex);
        }
    }
}
