package io.pockethive.trigger;

import io.pockethive.work.api.ScheduledInvocationPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Responsibility: supply the trigger scheduling policy at composition time.
 * Must not: create input adapters or select workers by role name.
 * Contract: RESP-TRIGGER-POLICY — docs/architecture/runtime-responsibilities.md#resp-trigger-policy.
 */
@Configuration(proxyBeanMethods = false)
class TriggerSchedulingConfiguration {
    @Bean
    ScheduledInvocationPolicy<TriggerWorkerConfig> triggerSchedulePolicy() {
        return new TriggerSchedulePolicy();
    }
}
