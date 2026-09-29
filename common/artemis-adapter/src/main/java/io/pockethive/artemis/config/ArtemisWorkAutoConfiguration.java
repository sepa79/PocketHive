package io.pockethive.artemis.config;

import io.pockethive.artemis.api.*;
import io.pockethive.artemis.transport.ArtemisSessions;
import io.pockethive.artemis.work.ArtemisWorkInputFactory;
import io.pockethive.artemis.work.ArtemisWorkOutputFactory;
import io.pockethive.work.api.transport.*;
import io.pockethive.work.config.binding.*;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;

/**
 * Responsibility: expose Artemis-owned binding and selected transport providers to the SDK.
 * Must not: inspect worker definitions, parse settings again or dispatch worker work.
 * Contract: RESP-ARTEMIS-CONNECTION — docs/architecture/runtime-responsibilities.md#resp-artemis-connection.
 */
@AutoConfiguration(after = ArtemisWorkerConnectionAutoConfiguration.class)
public class ArtemisWorkAutoConfiguration {
    @Bean WorkInputConfigProvider artemisInputBinding() {
        return new WorkInputConfigProvider(ArtemisWorkIoType.ARTEMIS, ArtemisInputSettings.class);
    }
    @Bean WorkOutputConfigProvider artemisOutputBinding() {
        return new WorkOutputConfigProvider(ArtemisWorkIoType.ARTEMIS, ArtemisOutputSettings.class);
    }
    @Bean @Conditional(ArtemisWorkerInputCondition.class)
    WorkInputTransportFactory artemisInputFactory(ArtemisSessions sessions) {
        return new ArtemisWorkInputFactory(sessions);
    }
    @Bean @Conditional(ArtemisWorkerOutputCondition.class)
    WorkOutputTransportFactory artemisOutputFactory(ArtemisSessions sessions) {
        return new ArtemisWorkOutputFactory(sessions);
    }
}
