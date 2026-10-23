package io.pockethive.httpsequence;

/**
 * Responsibility: carry one native result projection in its declared update order.
 * Must not: apply updates or own journey state.
 * Contract: RESP-HTTP-SEQUENCE-NATIVE-RESULTS — docs/architecture/runtime-responsibilities.md#resp-http-sequence-native-results.
 */
record HttpSequencePayloadUpdate(String path, Object value) {
}
