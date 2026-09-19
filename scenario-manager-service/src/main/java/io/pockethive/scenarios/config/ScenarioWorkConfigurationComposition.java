package io.pockethive.scenarios.config;

import io.pockethive.work.config.WorkConfigurationParser;
import io.pockethive.work.config.WorkMutationPolicyRegistry;
import io.pockethive.work.config.composition.CurrentWorkConfigurationProviders;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Responsibility: compose Scenario Manager's current explicit Work parser and mutation registry.
 * Must not: validate scenario documents, mutate worker state or select a runtime adapter.
 * Contract: RESP-WORK-PATCH-POLICY — docs/architecture/runtime-responsibilities.md#resp-work-patch-policy.
 */
@Configuration(proxyBeanMethods = false)
public class ScenarioWorkConfigurationComposition {

    @Bean
    CurrentWorkConfigurationProviders currentWorkConfigurationProviders() {
        return new CurrentWorkConfigurationProviders();
    }

    @Bean
    WorkConfigurationParser scenarioWorkConfigurationParser(CurrentWorkConfigurationProviders providers) {
        return providers.workConfigurationParser();
    }

    @Bean
    WorkMutationPolicyRegistry scenarioWorkMutationPolicyRegistry(CurrentWorkConfigurationProviders providers) {
        return providers.workMutationPolicyRegistry();
    }

    public static WorkMutationPolicyRegistry createMutationPolicyRegistry() {
        return new CurrentWorkConfigurationProviders().workMutationPolicyRegistry();
    }
}
