package io.pockethive.work.config.composition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pockethive.work.config.WorkerInputType;
import io.pockethive.work.config.WorkerOutputType;
import org.junit.jupiter.api.Test;

class CurrentWorkConfigurationProvidersTest {
    @Test
    void exposesTheExactCurrentSettingsProviderInventory() {
        CurrentWorkConfigurationProviders providers = new CurrentWorkConfigurationProviders();

        assertThat(providers.inputSettingsParsers().stream().map(parser -> parser.type()).toList())
            .containsExactly(
                WorkerInputType.RABBITMQ,
                WorkerInputType.REDIS_DATASET,
                WorkerInputType.CSV_DATASET,
                WorkerInputType.SCHEDULER
            );
        assertThat(providers.outputSettingsParsers().stream().map(parser -> parser.type()).toList())
            .containsExactly(WorkerOutputType.RABBITMQ, WorkerOutputType.REDIS);
    }

    @Test
    void exposesTheExactCurrentMutationProviderInventory() {
        CurrentWorkConfigurationProviders providers = new CurrentWorkConfigurationProviders();

        assertThat(providers.inputMutationPolicies().stream().map(policy -> policy.type()).toList())
            .containsExactly(
                WorkerInputType.RABBITMQ,
                WorkerInputType.REDIS_DATASET,
                WorkerInputType.CSV_DATASET,
                WorkerInputType.SCHEDULER
            );
        assertThat(providers.outputMutationPolicies().stream().map(policy -> policy.type()).toList())
            .containsExactly(WorkerOutputType.RABBITMQ, WorkerOutputType.REDIS, WorkerOutputType.NONE);
    }

    @Test
    void providerListsAreImmutableAndConstructNeutralAggregates() {
        CurrentWorkConfigurationProviders providers = new CurrentWorkConfigurationProviders();

        assertThatThrownBy(() -> providers.inputSettingsParsers().clear())
            .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> providers.outputMutationPolicies().clear())
            .isInstanceOf(UnsupportedOperationException.class);
        assertThat(providers.workConfigurationParser()).isNotNull();
        assertThat(providers.workMutationPolicyRegistry().inputPolicy(WorkerInputType.RABBITMQ)).isNotNull();
        assertThat(providers.workMutationPolicyRegistry().outputPolicy(WorkerOutputType.NONE)).isNotNull();
    }
}
