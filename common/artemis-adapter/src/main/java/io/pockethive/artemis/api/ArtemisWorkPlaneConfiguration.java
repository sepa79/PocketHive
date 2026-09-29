package io.pockethive.artemis.api;

import io.pockethive.artemis.config.ArtemisEnvironmentKeys;
import io.pockethive.artemis.config.ArtemisWorkPlaneCondition;
import io.pockethive.artemis.work.ArtemisWorkBootstrapEnvironment;
import io.pockethive.topology.work.*;
import io.pockethive.work.config.WorkAdapterEnvironment;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.env.Environment;

/**
 * Responsibility: compose manager Work ports from the explicitly selected Artemis owner.
 * Activated by service import; not a component-scan candidate.
 * Must not: choose settings defaults, resolve names independently or own swarm lifecycle.
 * Contract: RESP-ARTEMIS-CONNECTION — docs/architecture/runtime-responsibilities.md#resp-artemis-connection.
 */
@Conditional(ArtemisWorkPlaneCondition.class)
public class ArtemisWorkPlaneConfiguration {
    @Bean public ArtemisConnectionSettings artemisConnectionSettings(Environment environment) {
        return ArtemisConnectionEnvironment.decode(environment::getProperty);
    }
    @Bean(destroyMethod = "close")
    public ArtemisWorkPlane artemisWorkPlane(ArtemisConnectionSettings settings, Environment environment) {
        return new ArtemisWorkPlane(settings,
            environment.getProperty(ArtemisEnvironmentKeys.NAMESPACE_PROPERTY));
    }
    @Bean public WorkAdapterEnvironment workAdapterEnvironment(ArtemisConnectionSettings settings) {
        return new ArtemisWorkBootstrapEnvironment(settings);
    }
    @Bean public WorkPlaneResources workPlaneResources(ArtemisWorkPlane plane) { return plane.resources(); }
    @Bean public WorkTopologyResolver workTopologyResolver(ArtemisWorkPlane plane) { return plane.topology(); }
    @Bean public WorkDebugTaps workDebugTaps(ArtemisWorkPlane plane) { return plane.debugTaps(); }
}
