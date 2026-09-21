package io.pockethive.work.api;

/** Explicit availability of business configuration in a scheduling projection. * <p>
 * Responsibility: define the SchedulingConfigState contract.
 * Must not: select infrastructure clients or own adapter lifecycle.
 * Contract: RESP-WORK-SCHEDULE-CONTRACT — docs/architecture/runtime-responsibilities.md#resp-work-schedule-contract.
 */
public enum SchedulingConfigState { UNCONFIGURED, CONFIGURED }
