package io.pockethive.rabbit.api;

import io.pockethive.rabbit.config.RabbitInputSettingsParser;
import io.pockethive.rabbit.config.RabbitOutputSettingsParser;
import io.pockethive.rabbit.config.RabbitInputMutationPolicy;
import io.pockethive.rabbit.config.RabbitOutputMutationPolicy;
import io.pockethive.work.config.WorkInputSettingsParser;
import io.pockethive.work.config.WorkOutputSettingsParser;
import io.pockethive.work.config.WorkInputMutationPolicy;
import io.pockethive.work.config.WorkOutputMutationPolicy;

/**
 * Responsibility: expose Rabbit's canonical configuration providers through neutral parser/policy ports.
 * Must not: repeat field rules, select worker adapters or construct transport clients.
 * Contract: RESP-WORK-RABBIT-SETTINGS — docs/architecture/runtime-responsibilities.md#resp-work-rabbit-settings.
 */
public final class RabbitConfiguration {
    private RabbitConfiguration() { }
    public static WorkInputSettingsParser inputParser() { return new RabbitInputSettingsParser(); }
    public static WorkOutputSettingsParser outputParser() { return new RabbitOutputSettingsParser(); }
    public static WorkInputMutationPolicy inputMutationPolicy() { return new RabbitInputMutationPolicy(); }
    public static WorkOutputMutationPolicy outputMutationPolicy() { return new RabbitOutputMutationPolicy(); }

    public static RabbitInputSettings resolveInput(String queue, int prefetch, int concurrentConsumers,
                                                   boolean exclusive, String deadLetterQueue) {
        var fields = new java.util.LinkedHashMap<String, Object>();
        fields.put(RabbitInputSettingsParser.QUEUE_FIELD, queue);
        fields.put(RabbitInputSettingsParser.PREFETCH_FIELD, prefetch);
        fields.put(RabbitInputSettingsParser.CONCURRENT_CONSUMERS_FIELD, concurrentConsumers);
        fields.put(RabbitInputSettingsParser.EXCLUSIVE_FIELD, exclusive);
        if (deadLetterQueue != null) fields.put("deadLetterQueue", deadLetterQueue);
        var result = inputParser().validate(fields, "inputs.rabbit", io.pockethive.work.config.WorkConfigurationMode.RESOLVED);
        if (!result.problems().isEmpty()) throw new io.pockethive.work.config.WorkConfigurationException(result.problems());
        return (RabbitInputSettings) result.settings();
    }
    public static RabbitOutputSettings resolveOutput(String exchange, String routingKey,
                                                     boolean persistent, boolean publisherConfirms) {
        var fields = new java.util.LinkedHashMap<String, Object>();
        fields.put(RabbitOutputSettingsParser.EXCHANGE_FIELD, exchange);
        fields.put(RabbitOutputSettingsParser.ROUTING_KEY_FIELD, routingKey);
        fields.put(RabbitOutputSettingsParser.PERSISTENT_FIELD, persistent);
        fields.put(RabbitOutputSettingsParser.PUBLISHER_CONFIRMS_FIELD, publisherConfirms);
        var result = outputParser().validate(fields, "outputs.rabbit", io.pockethive.work.config.WorkConfigurationMode.RESOLVED);
        if (!result.problems().isEmpty()) throw new io.pockethive.work.config.WorkConfigurationException(result.problems());
        return (RabbitOutputSettings) result.settings();
    }
}
