package io.pockethive.swarmcontroller.config;

import io.pockethive.artemis.api.ArtemisWorkPlaneConfiguration;
import io.pockethive.rabbit.work.RabbitWorkPlaneConfiguration;
import io.pockethive.work.config.WorkPlaneSelection;
import io.pockethive.work.config.composition.CurrentWorkPlaneSelection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;

/**
 * Responsibility: compose the explicitly selected deployment WorkPlane through adapter entrypoints.
 * Must not: infer selection, implement broker operations or resolve settings/names independently.
 * Contract: RESP-WORK-ADAPTER-SELECTION — docs/architecture/runtime-responsibilities.md#resp-work-adapter-selection.
 */
@Configuration(proxyBeanMethods = false)
@Import({RabbitWorkPlaneConfiguration.class, ArtemisWorkPlaneConfiguration.class})
public class WorkPlaneConfiguration {
    @Bean WorkPlaneSelection workPlaneSelection(Environment environment) {
        return CurrentWorkPlaneSelection.resolve(environment::getProperty);
    }
}
