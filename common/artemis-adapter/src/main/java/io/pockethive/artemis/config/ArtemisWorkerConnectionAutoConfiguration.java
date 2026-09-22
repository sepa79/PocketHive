package io.pockethive.artemis.config;

import io.pockethive.artemis.api.ArtemisConnectionEnvironment;
import io.pockethive.artemis.transport.ArtemisSessions;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.env.Environment;

/**
 * Responsibility: compose and close the selected worker's Artemis connection through its owner.
 * Must not: create worker execution policy or borrow Rabbit CONTROL settings.
 * Contract: RESP-ARTEMIS-CONNECTION — docs/architecture/runtime-responsibilities.md#resp-artemis-connection.
 */
@AutoConfiguration
@Conditional(ArtemisWorkerIoCondition.class)
public class ArtemisWorkerConnectionAutoConfiguration {
    @Bean(destroyMethod = "close")
    ArtemisSessions artemisWorkerSessions(Environment environment) {
        return new ArtemisSessions(ArtemisConnectionEnvironment.decode(environment::getProperty));
    }
}
