package io.pockethive.artemis.config;

import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Responsibility: activate the shared Artemis worker connection when either IO direction selects it.
 * Must not: create clients for CONTROL-only services or supply settings defaults.
 * Contract: RESP-ARTEMIS-CONNECTION — docs/architecture/runtime-responsibilities.md#resp-artemis-connection.
 */
public final class ArtemisWorkerIoCondition extends SpringBootCondition {
    @Override public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return new ConditionOutcome(ArtemisWorkerInputCondition.selected(context.getEnvironment())
            || ArtemisWorkerOutputCondition.selected(context.getEnvironment()), "Artemis IO must be explicitly selected");
    }
}
