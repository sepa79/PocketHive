package io.pockethive.worker.sdk.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a bean as a PocketHive worker and provides routing metadata.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface PocketHiveWorker {

    String role();

    WorkerType type();

    String inQueue() default "";

    String outQueue() default "";

    Class<?> config() default Void.class;
}
