package io.pockethive.work.config.composition;

import io.pockethive.rabbit.api.RabbitConfiguration;
import io.pockethive.artemis.api.ArtemisConfiguration;
import io.pockethive.redis.config.RedisConfigurationParser;
import io.pockethive.redis.config.RedisWorkInputMutationPolicy;
import io.pockethive.redis.config.RedisWorkInputSettingsParser;
import io.pockethive.redis.config.RedisWorkOutputMutationPolicy;
import io.pockethive.redis.config.RedisWorkOutputSettingsParser;
import io.pockethive.work.config.NoOutputMutationPolicy;
import io.pockethive.work.config.WorkConfigurationParser;
import io.pockethive.work.config.WorkInputMutationPolicy;
import io.pockethive.work.config.WorkInputSettingsParser;
import io.pockethive.work.config.WorkMutationPolicyRegistry;
import io.pockethive.work.config.WorkOutputMutationPolicy;
import io.pockethive.work.config.WorkOutputSettingsParser;
import io.pockethive.work.local.csv.CsvDatasetParser;
import io.pockethive.work.local.csv.CsvWorkInputMutationPolicy;
import io.pockethive.work.local.csv.CsvWorkInputSettingsParser;
import io.pockethive.work.local.scheduler.SchedulerSettingsParser;
import io.pockethive.work.local.scheduler.SchedulerWorkInputMutationPolicy;
import io.pockethive.work.local.scheduler.SchedulerWorkInputSettingsParser;
import java.util.List;

/**
 * Responsibility: provide the explicit current Work adapter-provider inventory for application composition.
 * Must not: discover providers, select adapters, validate candidates or depend on Spring/runtime infrastructure.
 * Contract: RESP-WORK-CONFIGURATION-PARSER —
 * docs/architecture/work-plane-boundaries.md#4-configuration-and-topology-ssot.
 */
public final class CurrentWorkConfigurationProviders {
    private final List<WorkInputSettingsParser> inputSettingsParsers;
    private final List<WorkOutputSettingsParser> outputSettingsParsers;
    private final List<WorkInputMutationPolicy> inputMutationPolicies;
    private final List<WorkOutputMutationPolicy> outputMutationPolicies;

    public CurrentWorkConfigurationProviders() {
        RedisConfigurationParser redis = new RedisConfigurationParser();
        CsvDatasetParser csv = new CsvDatasetParser();
        SchedulerSettingsParser scheduler = new SchedulerSettingsParser();

        inputSettingsParsers = List.of(
            RabbitConfiguration.inputParser(),
            ArtemisConfiguration.inputParser(),
            new RedisWorkInputSettingsParser(redis),
            new CsvWorkInputSettingsParser(csv),
            new SchedulerWorkInputSettingsParser(scheduler)
        );
        outputSettingsParsers = List.of(
            RabbitConfiguration.outputParser(),
            ArtemisConfiguration.outputParser(),
            new RedisWorkOutputSettingsParser(redis)
        );
        inputMutationPolicies = List.of(
            RabbitConfiguration.inputMutationPolicy(),
            ArtemisConfiguration.inputMutationPolicy(),
            new RedisWorkInputMutationPolicy(redis),
            new CsvWorkInputMutationPolicy(csv),
            new SchedulerWorkInputMutationPolicy()
        );
        outputMutationPolicies = List.of(
            RabbitConfiguration.outputMutationPolicy(),
            ArtemisConfiguration.outputMutationPolicy(),
            new RedisWorkOutputMutationPolicy(),
            new NoOutputMutationPolicy()
        );
    }

    public List<WorkInputSettingsParser> inputSettingsParsers() {
        return inputSettingsParsers;
    }

    public List<WorkOutputSettingsParser> outputSettingsParsers() {
        return outputSettingsParsers;
    }

    public List<WorkInputMutationPolicy> inputMutationPolicies() {
        return inputMutationPolicies;
    }

    public List<WorkOutputMutationPolicy> outputMutationPolicies() {
        return outputMutationPolicies;
    }

    public WorkConfigurationParser workConfigurationParser() {
        return new WorkConfigurationParser(inputSettingsParsers, outputSettingsParsers);
    }

    public WorkMutationPolicyRegistry workMutationPolicyRegistry() {
        return new WorkMutationPolicyRegistry(inputMutationPolicies, outputMutationPolicies);
    }
}
