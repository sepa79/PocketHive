package io.pockethive.rabbit.config;

import io.pockethive.work.config.WorkIoTypeParser;
import io.pockethive.work.config.WorkerInputType;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Responsibility: expose canonical Rabbit input selection to worker bootstrap conditions.
 * Must not: normalize selectors, validate settings or infer an adapter from credentials.
 * Contract: RESP-WORK-ADAPTER-SELECTION — docs/architecture/work-plane-boundaries.md#10-remaining-workplane-isolation-target--rabbit-plus-a-test-adapter.
 */
public final class RabbitWorkerInputCondition extends SpringBootCondition {
    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return new ConditionOutcome(isSelected(context.getEnvironment()), "Rabbit WORK input must be explicitly selected");
    }

    static boolean isSelected(Environment environment) {
        return WorkIoTypeParser.matches(environment.getProperty("pockethive.inputs.type"), WorkerInputType.RABBITMQ);
    }
}
