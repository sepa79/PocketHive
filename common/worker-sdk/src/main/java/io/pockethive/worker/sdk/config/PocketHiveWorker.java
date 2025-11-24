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
 * <p>
 * NFF: input/output bindings are configured exclusively via {@code pockethive.inputs.*} /
 * {@code pockethive.outputs.*}. The annotation no longer exposes {@code input} or {@code output}
 * attributes; attempts to drive IO from annotations must be removed and migrated to configuration.
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
