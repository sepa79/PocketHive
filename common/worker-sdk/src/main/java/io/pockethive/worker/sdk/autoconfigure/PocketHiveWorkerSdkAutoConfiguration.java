package io.pockethive.worker.sdk.autoconfigure;

import io.pockethive.controlplane.spring.ControlPlaneCommonAutoConfiguration;
import io.pockethive.controlplane.spring.ManagerControlPlaneAutoConfiguration;
import io.pockethive.controlplane.spring.WorkerControlPlaneAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Aggregates the PocketHive control-plane auto-configuration so worker applications can
 * opt-in by depending on the Worker SDK starter.
 */
@Configuration(proxyBeanMethods = false)
@Import({
    ControlPlaneCommonAutoConfiguration.class,
    WorkerControlPlaneAutoConfiguration.class,
    ManagerControlPlaneAutoConfiguration.class
})
public class PocketHiveWorkerSdkAutoConfiguration {
}
