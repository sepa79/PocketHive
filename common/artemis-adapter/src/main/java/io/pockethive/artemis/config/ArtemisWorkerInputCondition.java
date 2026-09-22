package io.pockethive.artemis.config;

import io.pockethive.artemis.api.ArtemisWorkIoType;
import io.pockethive.work.config.WorkIoTypeParser;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Responsibility: activate the Artemis input provider from the canonical worker IO selector.
 * Must not: select by role or infer selection from connection settings.
 * Contract: RESP-ARTEMIS-CONNECTION — docs/architecture/runtime-responsibilities.md#resp-artemis-connection.
 */
public final class ArtemisWorkerInputCondition extends SpringBootCondition {
    static boolean selected(Environment environment) {
        return WorkIoTypeParser.matches(environment.getProperty("pockethive.inputs.type"), ArtemisWorkIoType.ARTEMIS);
    }
    @Override public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return new ConditionOutcome(selected(context.getEnvironment()), "Artemis input must be explicitly selected");
    }
}
