package io.pockethive.mcp.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.mcp.domain.PrincipalKey;
import io.pockethive.mcp.domain.ScenarioWorkflow;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Service;

/**
 * Responsibility: Record validated and published bundle evidence on the owning QA workflow.
 * Must not: Authorize workflow access, upload archives, or execute publication.
 * Contract: RESP-MCP-UPLOAD-LIFECYCLE - docs/architecture/runtime-responsibilities.md#resp-mcp-upload-lifecycle.
 */
@Service
public final class CoordinationWorkflowUploadLifecycle implements BundleUploadLifecycle {
    private final CoordinationStateRepository state;
    private final ObjectMapper mapper;
    private final WorkflowAccess workflows;

    CoordinationWorkflowUploadLifecycle(CoordinationStateRepository state, ObjectMapper mapper,
                                        WorkflowAccess workflows) {
        this.state = state;
        this.mapper = mapper;
        this.workflows = workflows;
    }

    @Override
    public void validated(PrincipalKey principal, UploadWorkflowBinding binding, String archiveDigest,
                          String bundleContentDigest, UploadCoordinationSnapshot uploadState) {
        ScenarioWorkflow workflow = workflows.requireWorkflow(binding.workflowId(), principal);
        workflow.requireGeneration(binding.preparedRevision(), binding.generatedFileSetDigest(),
            binding.capabilityFingerprint());
        workflow.validated(binding.preparedRevision(), archiveDigest, bundleContentDigest);
        state.saveWorkflowAndUploadCoordination(workflow, binding.preparedRevision(), uploadState);
    }

    @Override
    public void requirePublicationCurrent(PrincipalKey principal, UploadWorkflowBinding binding) {
        publicationWorkflow(principal, binding);
    }

    @Override
    public void published(PrincipalKey principal, UploadWorkflowBinding binding, PublicationAttempt attempt) {
        ScenarioWorkflow workflow = publicationWorkflow(principal, binding);
        workflow.published(binding.preparedRevision() + 1, digest(attempt));
        state.saveWorkflow(workflow, binding.preparedRevision() + 1);
    }

    private ScenarioWorkflow publicationWorkflow(PrincipalKey principal, UploadWorkflowBinding binding) {
        ScenarioWorkflow workflow = workflows.requireWorkflow(binding.workflowId(), principal);
        workflow.requireValidatedGeneration(binding.preparedRevision() + 1, binding.generatedFileSetDigest(),
            binding.capabilityFingerprint());
        return workflow;
    }

    private String digest(PublicationAttempt attempt) {
        try {
            byte[] canonical = mapper.writeValueAsBytes(attempt.snapshot());
            return "sha256:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (JsonProcessingException exception) {
            throw new ToolExecutionException("PUBLICATION_RECEIPT_SERIALIZATION_FAILED", exception.getMessage());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by Java", exception);
        }
    }
}
