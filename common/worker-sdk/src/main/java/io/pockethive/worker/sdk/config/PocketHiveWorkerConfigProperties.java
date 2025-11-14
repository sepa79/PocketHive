package io.pockethive.worker.sdk.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Meta-annotation that binds worker default properties to the shared {@code pockethive.worker} prefix.
 * <p>
 * Worker services can annotate their {@link PocketHiveWorkerProperties} beans with this annotation so
 * the SDK owns the prefix and we avoid repeating it across every service.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ConfigurationProperties(prefix = PocketHiveWorkerConfigProperties.PREFIX)
public @interface PocketHiveWorkerConfigProperties {

    String PREFIX = "pockethive.worker";
}
