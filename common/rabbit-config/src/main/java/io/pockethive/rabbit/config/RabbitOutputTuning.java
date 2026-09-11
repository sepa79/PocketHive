package io.pockethive.rabbit.config;

import io.pockethive.work.config.WorkOutputSettings;

/**
 * Responsibility: carry validated Rabbit output AUTHORING tuning without physical destinations.
 * Must not: represent resolved topology or runtime settings.
 * Contract: RESP-WORK-RABBIT-SETTINGS — docs/architecture/runtime-responsibilities.md#resp-work-rabbit-settings.
 */
public record RabbitOutputTuning(boolean persistent, boolean publisherConfirms) implements WorkOutputSettings {}
