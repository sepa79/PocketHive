package io.pockethive.acceptance.config;

/**
 * Responsibility: hold the explicit HTTP journey fixture and expected result.
 * Must not: infer an adapter or compute broker addresses.
 * Contract: RESP-ACCEPTANCE-TARGET — docs/architecture/acceptance-tests.md#resp-acceptance-target.
 */
public record HttpFixture(String templateId, String sutId, String captureRole,
    String captureDirection, String captureIoName, int samples, int tapTtlSeconds,
    String expectedResponse) {}
