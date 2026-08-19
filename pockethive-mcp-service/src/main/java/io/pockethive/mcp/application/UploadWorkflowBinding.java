package io.pockethive.mcp.application;

/** Explicitly distinguishes direct publication from workflow-bound publication. */
public record UploadWorkflowBinding(UploadWorkflowMode mode, String workflowId) {
    public UploadWorkflowBinding {
        if (mode == null) {
            throw new IllegalArgumentException("UPLOAD_WORKFLOW_MODE_REQUIRED");
        }
        if (mode == UploadWorkflowMode.WORKFLOW && (workflowId == null || workflowId.isBlank())) {
            throw new IllegalArgumentException("UPLOAD_WORKFLOW_ID_REQUIRED");
        }
        if (mode == UploadWorkflowMode.DIRECT && workflowId != null) {
            throw new IllegalArgumentException("UPLOAD_WORKFLOW_ID_FORBIDDEN");
        }
    }

    public static UploadWorkflowBinding direct() {
        return new UploadWorkflowBinding(UploadWorkflowMode.DIRECT, null);
    }

    public static UploadWorkflowBinding workflow(String workflowId) {
        return new UploadWorkflowBinding(UploadWorkflowMode.WORKFLOW, workflowId);
    }
}
