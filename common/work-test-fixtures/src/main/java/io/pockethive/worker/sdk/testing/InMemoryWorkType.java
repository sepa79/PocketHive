package io.pockethive.worker.sdk.testing;

import io.pockethive.work.config.WorkIoType;

/**
 * Responsibility: declare the test-only IO selection.
 * Must not: register a production option or impersonate Rabbit.
 * Contract: RESP-WORK-ADAPTER-SELECTION — docs/architecture/work-plane-boundaries.md#10-remaining-workplane-isolation-target--rabbit-plus-a-test-adapter.
 */
public enum InMemoryWorkType implements WorkIoType {
    MEMORY;
    @Override public String settingsKey() { return "memory"; }
}
