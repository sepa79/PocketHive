package io.pockethive.artemis.config;

import io.pockethive.artemis.api.ArtemisWorkIoType;
import io.pockethive.work.config.*;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Responsibility: activate manager Artemis WORK composition for its explicit deployment selection.
 * Must not: provide a default adapter or inspect CONTROL settings.
 * Contract: RESP-ARTEMIS-CONNECTION — docs/architecture/runtime-responsibilities.md#resp-artemis-connection.
 */
public final class ArtemisWorkPlaneCondition extends SpringBootCondition {
    @Override public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return new ConditionOutcome(WorkIoTypeParser.matches(
            context.getEnvironment().getProperty(WorkPlaneSelection.PROPERTY), ArtemisWorkIoType.ARTEMIS),
            "Artemis WorkPlane must be explicitly selected");
    }
}
