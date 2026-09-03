package io.pockethive.mcp.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.mcp.domain.PrincipalKey;
import io.pockethive.mcp.domain.ScenarioWorkflow;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Service;

/**
 * Responsibility: Record validated and published bundle evidence on the owning QA workflow.
 * Must not: Authorize workflow access, upload archives, or execute publication.
 * Contract: docs/mcp/README.md.
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
    public void validated(PrincipalKey principal, String workflowId, String archiveDigest,
                          String bundleContentDigest) {
        ScenarioWorkflow workflow = workflow(principal, workflowId);
        workflow.validated(workflow.revision(), archiveDigest, bundleContentDigest);
        state.saveWorkflow(workflow);
    }

    @Override
    public void published(PrincipalKey principal, String workflowId, PublicationAttempt attempt) {
        ScenarioWorkflow workflow = workflow(principal, workflowId);
        workflow.published(workflow.revision(), digest(attempt));
        state.saveWorkflow(workflow);
    }

    private ScenarioWorkflow workflow(PrincipalKey principal, String workflowId) {
        return workflows.requireWorkflow(workflowId, principal);
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
