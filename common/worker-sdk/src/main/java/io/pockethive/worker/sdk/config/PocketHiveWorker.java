package io.pockethive.worker.sdk.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a bean as a PocketHive worker and provides routing metadata.
 * <p>
 * The runtime scans for this annotation when building {@link io.pockethive.worker.sdk.runtime.WorkerDefinition}
 * records. Usage examples are documented in {@code docs/sdk/worker-sdk-quickstart.md}. The worker role itself is
 * resolved from {@code WorkerControlPlaneProperties} (typically sourced from the
 * {@code POCKETHIVE_CONTROL_PLANE_WORKER_ROLE} environment variable) rather than the annotation.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface PocketHiveWorker {

    /**
     * Optional human-friendly description added to status payloads/UI.
     */
    String description() default "";

    /**
     * Capabilities exposed by the worker so scenario-manager/UI can reason about it.
     */
    WorkerCapability[] capabilities() default {};

    /**
     * Input binding that feeds messages to the worker runtime.
     */
    WorkerInputType input() default WorkerInputType.RABBITMQ;

    /**
     * Declares which output transport should be wired for this worker.
     */
    WorkerOutputType output() default WorkerOutputType.NONE;

    /**
     * When set to {@code true}, the worker's input/output types are taken exclusively
     * from configuration ({@code pockethive.worker.io.*}) rather than the {@link #input()}
     * and {@link #output()} attributes. NFF: if enabled, both
     * {@code pockethive.worker.io.input-type} and {@code ...output-type} must be set.
     */
    boolean ioFromConfig() default false;

    /**
     * Optional infrastructure configuration type that overrides the default input config binding.
     */
    Class<? extends WorkInputConfig> inputConfig() default WorkInputConfig.class;

    /**
     * Optional infrastructure configuration type that overrides the default output config binding.
     */
    Class<? extends WorkOutputConfig> outputConfig() default WorkOutputConfig.class;

    /**
     * Optional configuration class resolved from the Spring context when the control plane does not supply
     * a typed payload.
     */
    Class<?> config() default Void.class;
}
