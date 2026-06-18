package io.pockethive.orchestrator.runtime;

public final class RuntimeDebugContracts {
    public static final String RUNTIME_DEBUG_CONTRACT_VERSION = "1";
    public static final String CLEANUP_CONTRACT_VERSION = "1";

    private RuntimeDebugContracts() {
    }

    public record Capabilities(
        String runtimeDebugContractVersion,
        String cleanupContractVersion,
        boolean cleanupPlanHasExecutionRisk,
        boolean cleanupPlanUsesApprovalFields,
        boolean cleanupExecuteRequiresCandidateSetHash,
        boolean rabbitTopologyExactByDefault) {

        public static Capabilities current() {
            return new Capabilities(
                RUNTIME_DEBUG_CONTRACT_VERSION,
                CLEANUP_CONTRACT_VERSION,
                true,
                false,
                true,
                true);
        }
    }
}
