package io.pockethive.work.api;

/**
 * Responsibility: observe scheduling revisions and calculate invocation quota.
 * Must not: access Control Plane, clients, or execute worker invocations.
 * Contract: RESP-WORK-SCHEDULE-CONTRACT — docs/architecture/runtime-responsibilities.md#resp-work-schedule-contract.
 */
public interface ScheduledInvocationPolicy<C> {
    Class<C> configurationType();
    /** Observe each revision in delivery order, without consuming quota or dispatching work.
     * Implementations serialize this operation with planning.
     */
    void update(SchedulingState<C> state);

    /** Consume the quota for this tick using the latest observed revision. */
    int plan(long tickMillis);
}
