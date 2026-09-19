package io.pockethive.rabbit.config;

import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Responsibility: activate WORK bootstrap for an explicitly declared Rabbit worker input or output.
 * Must not: reinterpret selectors or select from roles, credentials or fallback protocols.
 * Contract: RESP-RABBIT-CONNECTION — docs/architecture/runtime-responsibilities.md#resp-rabbit-connection.
 */
public final class RabbitWorkerIoCondition extends SpringBootCondition {
    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        var environment = context.getEnvironment();
        boolean selected = RabbitWorkerInputCondition.isSelected(environment)
            || RabbitWorkerOutputCondition.isSelected(environment);
        return new ConditionOutcome(selected, "Rabbit WORK must be explicitly selected by worker IO");
    }
}
