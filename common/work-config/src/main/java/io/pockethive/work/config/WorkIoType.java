package io.pockethive.work.config;

/**
 * Responsibility: identify an explicitly declared IO selection and its settings block.
 * Must not: infer a provider, parse settings or create adapter resources.
 * Contract: RESP-WORK-ADAPTER-SELECTION — docs/architecture/work-plane-boundaries.md#10-remaining-workplane-isolation-target--rabbit-plus-a-test-adapter.
 */
public interface WorkIoType {
    String name();
    String settingsKey();
}
