package io.pockethive.worker.sdk.testing;

import io.pockethive.work.config.WorkInputSettings;
import io.pockethive.work.config.binding.WorkInputConfig;

/**
 * Responsibility: carry validated memory input settings and their startup route.
 * Must not: create resources or normalize a second address.
 * Contract: RESP-WORK-IO-CONFIG — docs/architecture/work-plane-boundaries.md#10-remaining-workplane-isolation-target--rabbit-plus-a-test-adapter.
 */
public record InMemoryWorkInputSettings(String address) implements WorkInputSettings, WorkInputConfig {
    public InMemoryWorkInputSettings { address = InMemoryWorkAddress.require(address); }
    @Override public String inboundRoute() { return address; }
}
