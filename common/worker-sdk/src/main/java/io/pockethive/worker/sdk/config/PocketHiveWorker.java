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
 * records. Usage examples are documented in {@code docs/sdk/worker-sdk-quickstart.md}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface PocketHiveWorker {

    /**
     * Logical role of the worker (e.g. {@code processor}).
     */
    String role();

    /**
     * Input binding that feeds messages to the worker runtime.
     */
    WorkerInputType input() default WorkerInputType.RABBIT;

    /**
     * Optional inbound queue name. Typically used by message-driven workers.
     */
    String inQueue() default "";

    /**
     * Optional outbound queue name for workers that emit downstream traffic.
     */
    String outQueue() default "";

    /**
     * Optional configuration class resolved from the Spring context when the control plane does not supply
     * a typed payload.
     */
    Class<?> config() default Void.class;
}
