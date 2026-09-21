package io.pockethive.worker.sdk.autoconfigure;

import io.pockethive.work.config.WorkConfigurationParser;
import io.pockethive.work.config.WorkMutationPolicyRegistry;
import io.pockethive.work.config.composition.CurrentWorkConfigurationProviders;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Responsibility: compose the current explicit Work parser and mutation registry for worker control runtime.
 * Must not: select a worker IO type, validate complete candidates or apply runtime state.
 * Contract: RESP-WORK-CONFIGURATION-PARSER —
 * docs/architecture/work-plane-boundaries.md#4-configuration-and-topology-ssot.
 */
@Configuration(proxyBeanMethods = false)
public class WorkerWorkConfigurationComposition {
    @Bean
    CurrentWorkConfigurationProviders currentWorkConfigurationProviders() {
        return new CurrentWorkConfigurationProviders();
    }

    @Bean
    WorkConfigurationParser workConfigurationParser(CurrentWorkConfigurationProviders providers) {
        return providers.workConfigurationParser();
    }

    @Bean
    WorkMutationPolicyRegistry workMutationPolicyRegistry(CurrentWorkConfigurationProviders providers) {
        return providers.workMutationPolicyRegistry();
    }
}
