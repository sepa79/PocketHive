package io.pockethive.controlplane.worker;

/**
 * Shared runtime identity contract between Swarm Controller and worker runtime.
 */
public final class WorkerRuntimeIdentity {

    public static final String BEE_ID_ENV = "POCKETHIVE_BEE_ID";
    public static final String BEE_ID_CONTEXT_FIELD = "beeId";

    private WorkerRuntimeIdentity() {
    }
}
