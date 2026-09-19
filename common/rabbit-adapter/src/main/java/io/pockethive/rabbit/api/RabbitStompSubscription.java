package io.pockethive.rabbit.api;

/**
 * Responsibility: expose the read-only STOMP addresses derived by RabbitResourceNames.
 * Must not: resolve configuration, carry credentials or decide routing grammar.
 * Contract: RESP-CONTROL-STOMP-INFO — docs/architecture/runtime-responsibilities.md#resp-control-stomp-info.
 */
public record RabbitStompSubscription(String subscriptionDestination, String destinationPrefix) { }
