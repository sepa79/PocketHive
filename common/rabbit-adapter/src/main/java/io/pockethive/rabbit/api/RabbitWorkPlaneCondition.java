package io.pockethive.rabbit.api;

import io.pockethive.work.config.*;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Responsibility: activate manager Rabbit WORK composition for its explicit deployment selection.
 * Must not: provide a default adapter or read Work settings for CONTROL.
 * Contract: RESP-RABBIT-CONNECTION — docs/architecture/runtime-responsibilities.md#resp-rabbit-connection.
 */
public final class RabbitWorkPlaneCondition extends SpringBootCondition {
    @Override public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return new ConditionOutcome(WorkIoTypeParser.matches(
            context.getEnvironment().getProperty(WorkPlaneSelection.PROPERTY), WorkerInputType.RABBITMQ),
            "Rabbit WorkPlane must be explicitly selected");
    }
}
