package io.pockethive.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pockethive.mcp.domain.CapabilityFingerprint;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class UploadWorkflowBindingTest {
    private static final CapabilityFingerprint CAPABILITIES = new CapabilityFingerprint("sha256:caps", Instant.EPOCH);

    @Test
    void workflowRequiresCompletePreparedIdentity() {
        for (String id : new String[] {null, " "}) {
            assertThatThrownBy(() -> new UploadWorkflowBinding(UploadWorkflowMode.WORKFLOW, id, 1, "files", CAPABILITIES))
                .hasMessage("UPLOAD_WORKFLOW_ID_REQUIRED");
        }
        for (long revision : new long[] {-1, 0}) {
            assertThatThrownBy(() -> new UploadWorkflowBinding(UploadWorkflowMode.WORKFLOW, "wf", revision, "files", CAPABILITIES))
                .hasMessage("UPLOAD_WORKFLOW_GENERATION_REQUIRED");
        }
        for (String digest : new String[] {null, " "}) {
            assertThatThrownBy(() -> new UploadWorkflowBinding(UploadWorkflowMode.WORKFLOW, "wf", 1, digest, CAPABILITIES))
                .hasMessage("UPLOAD_WORKFLOW_GENERATION_REQUIRED");
        }
        assertThatThrownBy(() -> new UploadWorkflowBinding(UploadWorkflowMode.WORKFLOW, "wf", 1, "files", null))
            .hasMessage("UPLOAD_WORKFLOW_GENERATION_REQUIRED");
        UploadWorkflowBinding current = new UploadWorkflowBinding(UploadWorkflowMode.WORKFLOW, "wf", 1, "files", CAPABILITIES);
        assertThat(current.preparedRevision()).isEqualTo(1);
        assertThat(current.generatedFileSetDigest()).isEqualTo("files");
        assertThat(current.capabilityFingerprint()).isEqualTo(CAPABILITIES);
    }

    @Test
    void directAndLegacyBindingsCannotPretendToCarryGenerationIdentity() {
        assertThat(UploadWorkflowBinding.direct()).isEqualTo(new UploadWorkflowBinding(UploadWorkflowMode.DIRECT, null, 0, null, null));
        assertThatThrownBy(() -> new UploadWorkflowBinding(UploadWorkflowMode.DIRECT, null, 1, null, null))
            .hasMessage("UPLOAD_WORKFLOW_IDENTITY_FORBIDDEN");
        assertThatThrownBy(() -> new UploadWorkflowBinding(UploadWorkflowMode.DIRECT, null, 0, "files", null))
            .hasMessage("UPLOAD_WORKFLOW_IDENTITY_FORBIDDEN");
        assertThatThrownBy(() -> new UploadWorkflowBinding(UploadWorkflowMode.DIRECT, null, 0, null, CAPABILITIES))
            .hasMessage("UPLOAD_WORKFLOW_IDENTITY_FORBIDDEN");
        UploadWorkflowBinding legacy = new UploadWorkflowBinding(UploadWorkflowMode.LEGACY_WORKFLOW, "wf", 0, null, null);
        assertThat(legacy.mode()).isEqualTo(UploadWorkflowMode.LEGACY_WORKFLOW);
        assertThat(legacy.workflowId()).isEqualTo("wf");
        assertThatThrownBy(() -> new UploadWorkflowBinding(UploadWorkflowMode.LEGACY_WORKFLOW, "wf", 1, null, null))
            .hasMessage("UPLOAD_WORKFLOW_LEGACY_IDENTITY_FORBIDDEN");
        assertThatThrownBy(() -> new UploadWorkflowBinding(UploadWorkflowMode.LEGACY_WORKFLOW, "wf", 0, "files", null))
            .hasMessage("UPLOAD_WORKFLOW_LEGACY_IDENTITY_FORBIDDEN");
        assertThatThrownBy(() -> new UploadWorkflowBinding(UploadWorkflowMode.LEGACY_WORKFLOW, "wf", 0, null, CAPABILITIES))
            .hasMessage("UPLOAD_WORKFLOW_LEGACY_IDENTITY_FORBIDDEN");
    }
}
