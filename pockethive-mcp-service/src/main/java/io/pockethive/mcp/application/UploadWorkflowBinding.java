package io.pockethive.mcp.application;

import io.pockethive.mcp.domain.CapabilityFingerprint;
import io.pockethive.mcp.domain.ScenarioWorkflow;

/**
 * Responsibility: Carry the immutable generation identity of a workflow-bound upload.
 * Must not: Decide workflow transitions, authorize callers, or infer missing identity.
 * Contract: RESP-MCP-UPLOAD-LIFECYCLE - docs/architecture/runtime-responsibilities.md#resp-mcp-upload-lifecycle.
 */
public record UploadWorkflowBinding(UploadWorkflowMode mode, String workflowId, long preparedRevision,
                                    String generatedFileSetDigest, CapabilityFingerprint capabilityFingerprint) {
    public UploadWorkflowBinding {
        if (mode == null) {
            throw new IllegalArgumentException("UPLOAD_WORKFLOW_MODE_REQUIRED");
        }
        if (mode != UploadWorkflowMode.DIRECT) {
            if (workflowId == null || workflowId.isBlank()) {
                throw new IllegalArgumentException("UPLOAD_WORKFLOW_ID_REQUIRED");
            }
            if (mode == UploadWorkflowMode.WORKFLOW && (preparedRevision < 1 || generatedFileSetDigest == null || generatedFileSetDigest.isBlank()
                || capabilityFingerprint == null)) {
                throw new IllegalArgumentException("UPLOAD_WORKFLOW_GENERATION_REQUIRED");
            }
            if (mode == UploadWorkflowMode.LEGACY_WORKFLOW && (preparedRevision != 0
                || generatedFileSetDigest != null || capabilityFingerprint != null)) {
                throw new IllegalArgumentException("UPLOAD_WORKFLOW_LEGACY_IDENTITY_FORBIDDEN");
            }
        } else if (workflowId != null || preparedRevision != 0 || generatedFileSetDigest != null
            || capabilityFingerprint != null) {
            throw new IllegalArgumentException("UPLOAD_WORKFLOW_IDENTITY_FORBIDDEN");
        }
    }

    public static UploadWorkflowBinding direct() {
        return new UploadWorkflowBinding(UploadWorkflowMode.DIRECT, null, 0, null, null);
    }

    public static UploadWorkflowBinding workflow(ScenarioWorkflow workflow) {
        return new UploadWorkflowBinding(UploadWorkflowMode.WORKFLOW, workflow.id(), workflow.revision(),
            workflow.generatedFileSetDigest(), workflow.capabilityFingerprint());
    }
}
