package io.pockethive.acceptance.resources;

/**
 * Responsibility: describe certainty of a test resource acquisition.
 * Must not: represent the product swarm lifecycle.
 * Contract: RESP-ACCEPTANCE-RESOURCES — docs/architecture/acceptance-tests.md#resp-acceptance-resources.
 */
public enum AcquisitionState { NOT_REQUESTED, UNCONFIRMED, REJECTED, ACQUIRED, RELEASED }
