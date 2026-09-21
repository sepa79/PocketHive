package io.pockethive.worker.sdk.testing;

import io.pockethive.work.config.WorkOutputSettings;
import io.pockethive.work.config.binding.WorkOutputConfig;

/**
 * Responsibility: carry validated memory output settings and their startup route.
 * Must not: create resources or normalize a second address.
 * Contract: RESP-WORK-IO-CONFIG — docs/architecture/work-plane-boundaries.md#10-remaining-workplane-isolation-target--rabbit-plus-a-test-adapter.
 */
public record InMemoryWorkOutputSettings(String address) implements WorkOutputSettings, WorkOutputConfig {
    public InMemoryWorkOutputSettings { address = InMemoryWorkAddress.require(address); }
    @Override public String outboundRoute() { return address; }
}
