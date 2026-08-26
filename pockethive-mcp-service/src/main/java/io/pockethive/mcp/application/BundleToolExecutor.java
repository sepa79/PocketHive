package io.pockethive.mcp.application;

import static io.pockethive.mcp.application.ToolArguments.number;
import static io.pockethive.mcp.application.ToolArguments.require;
import static io.pockethive.mcp.application.ToolArguments.text;

import io.pockethive.mcp.adapter.mcp.McpCaller;
import io.pockethive.mcp.config.PocketHiveMcpProperties;
import io.pockethive.mcp.domain.BundleFileManifest;
import io.pockethive.mcp.domain.BundleFileManifestEntry;
import io.pockethive.mcp.domain.ScenarioWorkflow;
import io.pockethive.mcp.domain.SourceMetadata;
import io.pockethive.mcp.domain.SourceVerification;
import java.net.URI;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Responsibility: Execute MCP-owned bundle validation and publication-ticket tools.
 * Must not: Capture QA requirements, manage sessions, or publish directly to Scenario Manager.
 * Contract: docs/mcp/README.md.
 */
@Component
final class BundleToolExecutor {
    private final BundleUploadCoordinator uploads;
    private final PocketHiveMcpProperties properties;
    private final WorkflowAccess workflows;
    private final Clock clock;
    private final Map<McpToolId, ToolAction> actions;

    BundleToolExecutor(BundleUploadCoordinator uploads, PocketHiveMcpProperties properties,
                       WorkflowAccess workflows, Clock clock) {
        this.uploads = uploads;
        this.properties = properties;
        this.workflows = workflows;
        this.clock = clock;
        this.actions = Map.of(
            McpToolId.SCENARIO_BUNDLE_VALIDATION_PREPARE, this::prepareValidation,
            McpToolId.SCENARIO_BUNDLE_DIRECT_VALIDATION_PREPARE, (input, caller) -> validationTicketView(
                uploads.prepareDirectValidationWithCapability(caller.principal(), source(input), manifest(input),
                    clock.instant())),
            McpToolId.SCENARIO_BUNDLE_VALIDATION_RECEIPT_GET, (input, caller) -> BundleValidationReceiptView.from(
                uploads.validationReceipt(text(input, "receiptId"), caller.principal())),
            McpToolId.SCENARIO_BUNDLE_PUBLICATION_PREPARE, this::preparePublication,
            McpToolId.SCENARIO_BUNDLE_PUBLICATION_ATTEMPT_GET, (input, caller) -> PublicationAttemptView.from(
                uploads.publicationAttempt(text(input, "attemptId"), caller.principal())),
            McpToolId.SCENARIO_BUNDLE_PUBLICATION_RECONCILE, (input, caller) -> PublicationAttemptView.from(
                uploads.reconcile(text(input, "attemptId"), caller.principal())));
    }

    boolean supports(McpToolId toolId) {
        return actions.containsKey(toolId);
    }

    Object execute(McpToolId toolId, Map<String, Object> input, McpCaller caller) {
        ToolAction action = actions.get(toolId);
        if (action == null) {
            throw new ToolExecutionException("TOOL_HANDLER_MISSING", toolId.externalName());
        }
        return action.execute(input, caller);
    }

    private Object prepareValidation(Map<String, Object> input, McpCaller caller) {
        ScenarioWorkflow workflow = workflows.requireMutableWorkflow(text(input, "workflowId"), caller);
        long expectedRevision = number(input, "expectedRevision");
        if (workflow.revision() != expectedRevision) {
            throw new ToolExecutionException("WORKFLOW_VERSION_CONFLICT", workflow.id());
        }
        if (workflow.state() != io.pockethive.mcp.domain.ScenarioWorkflowState.GENERATED) {
            throw new ToolExecutionException("WORKFLOW_NOT_GENERATED", workflow.id());
        }
        return validationTicketView(uploads.prepareValidationWithCapability(caller.principal(), workflow.id(),
            source(input), manifest(input), clock.instant()));
    }

    private Object preparePublication(Map<String, Object> input, McpCaller caller) {
        String scenarioId = input.containsKey("scenarioId") ? text(input, "scenarioId") : null;
        try {
            String receiptId = text(input, "validationReceiptId");
            BundleValidationReceipt receipt = uploads.validationReceipt(receiptId, caller.principal());
            if (receipt.workflowBinding().mode() == UploadWorkflowMode.WORKFLOW) {
                workflows.requireMutableWorkflow(receipt.workflowBinding().workflowId(), caller);
            }
            return publicationTicketView(uploads.preparePublicationWithCapability(caller.principal(), receiptId,
                PublicationMode.valueOf(text(input, "mode").toUpperCase(Locale.ROOT)), scenarioId,
                source(input), manifest(input), text(input, "archiveDigest"),
                text(input, "bundleContentDigest"), clock.instant()));
        } catch (IllegalArgumentException exception) {
            throw new ToolExecutionException("PUBLICATION_INTENT_INVALID", exception.getMessage());
        }
    }

    private ValidationUploadTicketView validationTicketView(PreparedUpload<ValidationUploadTicket> prepared) {
        ValidationUploadTicket ticket = prepared.ticket();
        return new ValidationUploadTicketView(ticket.id(), uploadUrl(ticket.id()),
            prepared.uploadCapability(), ticket.expiresAt());
    }

    private PublicationUploadTicketView publicationTicketView(PreparedUpload<PublicationUploadTicket> prepared) {
        PublicationUploadTicket ticket = prepared.ticket();
        return new PublicationUploadTicketView(ticket.id(), uploadUrl(ticket.id()), prepared.uploadCapability(),
            ticket.expiresAt(), ticket.attemptId(), ticket.validationReceiptId(), ticket.mode(), ticket.scenarioId());
    }

    private URI uploadUrl(String ticketId) {
        return properties.pocketHiveIngress().resolve(BundleUploadContract.PATH_PREFIX + ticketId);
    }

    @SuppressWarnings("unchecked")
    private static SourceMetadata source(Map<String, Object> input) {
        Object value = require(input, "source");
        if (!(value instanceof Map<?, ?> raw)) {
            throw new ToolExecutionException("SOURCE_METADATA_INVALID", "source must be an object");
        }
        Map<String, Object> source = (Map<String, Object>) raw;
        try {
            SourceVerification verification = SourceVerification.valueOf(text(source, "verification"));
            return new SourceMetadata(text(source, "repository"), text(source, "commit"),
                text(source, "bundlePath"), verification);
        } catch (IllegalArgumentException exception) {
            throw new ToolExecutionException("SOURCE_METADATA_INVALID", exception.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static BundleFileManifest manifest(Map<String, Object> input) {
        Object value = require(input, "fileManifest");
        if (!(value instanceof List<?> list)) {
            throw new ToolExecutionException("BUNDLE_MANIFEST_INVALID", "fileManifest must be an array");
        }
        try {
            return new BundleFileManifest(list.stream().map(item -> {
                if (!(item instanceof Map<?, ?> raw)) {
                    throw new IllegalArgumentException("entry must be an object");
                }
                Map<String, Object> entry = (Map<String, Object>) raw;
                return new BundleFileManifestEntry(text(entry, "path"), number(entry, "byteCount"),
                    text(entry, "sha256"));
            }).toList());
        } catch (IllegalArgumentException exception) {
            throw new ToolExecutionException("BUNDLE_MANIFEST_INVALID", exception.getMessage());
        }
    }

    @FunctionalInterface
    private interface ToolAction {
        Object execute(Map<String, Object> input, McpCaller caller);
    }
}
