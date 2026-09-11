package io.pockethive.rabbit.config;

import io.pockethive.work.config.WorkInputSettings;

/**
 * Responsibility: carry validated Rabbit input AUTHORING tuning without physical destinations.
 * Must not: represent resolved topology or runtime settings.
 * Contract: RESP-WORK-RABBIT-SETTINGS — docs/architecture/runtime-responsibilities.md#resp-work-rabbit-settings.
 */
public record RabbitInputTuning(int prefetch, int concurrentConsumers, boolean exclusive) implements WorkInputSettings {}
