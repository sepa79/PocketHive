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

@Service
public final class CoordinationWorkflowUploadLifecycle implements BundleUploadLifecycle {
    private final CoordinationStateRepository state;
    private final ObjectMapper mapper;

    public CoordinationWorkflowUploadLifecycle(CoordinationStateRepository state, ObjectMapper mapper) {
        this.state = state;
        this.mapper = mapper;
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
        ScenarioWorkflow workflow = state.findWorkflow(workflowId)
            .orElseThrow(() -> new ToolExecutionException("SCENARIO_WORKFLOW_NOT_FOUND", workflowId));
        if (!workflow.principal().equals(principal)) {
            throw new ToolExecutionException("SCENARIO_WORKFLOW_NOT_FOUND", workflowId);
        }
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
