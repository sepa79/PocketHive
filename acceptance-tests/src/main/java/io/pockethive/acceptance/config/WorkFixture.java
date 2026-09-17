package io.pockethive.acceptance.config;

/**
 * Responsibility: hold the explicit work journey fixture and expected result.
 * Must not: infer an adapter or compute broker addresses.
 * Contract: RESP-ACCEPTANCE-TARGET — docs/architecture/acceptance-tests.md#resp-acceptance-target.
 */
public record WorkFixture(String templateId, String sutId, String captureRole,
    String captureDirection, String captureIoName, int samples, int tapTtlSeconds,
    String expectedResponse) {}
