package io.pockethive.httpsequence;

import io.pockethive.work.api.WorkItem;
import io.pockethive.work.api.WorkPayloadEncoding;
import io.pockethive.worker.sdk.input.SchedulerWorkInputFactory;
import java.util.List;
import java.util.function.UnaryOperator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.apache.hc.core5.http.ContentType;

/**
 * Responsibility: bind the HTTP Sequence scheduled-input shape to the SDK seed customizer.
 * Must not: parse payloads, create input adapters or own scheduling and journey state.
 * Contract: RESP-HTTP-SEQUENCE-WORK — docs/architecture/runtime-responsibilities.md#resp-http-sequence-work.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pockethive.inputs", name = "type", havingValue = "SCHEDULER")
class HttpSequenceSchedulingConfiguration {

    @Bean(name = SchedulerWorkInputFactory.SEED_CUSTOMIZER_BEAN_NAME)
    UnaryOperator<WorkItem> httpSequenceScheduledSeed() {
        return seed -> seed.toBuilder()
            .steps(List.of(seed.steps().iterator().next().withPayload("{}", WorkPayloadEncoding.UTF_8)))
            .contentType(ContentType.APPLICATION_JSON.getMimeType())
            .build();
    }
}
