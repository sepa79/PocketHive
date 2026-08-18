package io.pockethive.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import io.pockethive.mcp.adapter.persistence.AtomicCoordinationStateRepository;
import io.pockethive.mcp.config.PocketHiveMcpProperties;
import io.pockethive.mcp.domain.AgentSession;
import io.pockethive.mcp.domain.AnswerProvenance;
import io.pockethive.mcp.domain.CapabilityFingerprint;
import io.pockethive.mcp.domain.ElicitationAction;
import io.pockethive.mcp.domain.PrincipalKey;
import io.pockethive.mcp.domain.QaRequirementTopic;
import io.pockethive.mcp.domain.RequirementAnswer;
import io.pockethive.mcp.domain.ScenarioWorkflow;
import io.pockethive.mcp.domain.ScenarioWorkflowState;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class CoordinationWorkflowUploadLifecycleTest {
    @TempDir Path temporaryDirectory;

    @Test
    void validationAndPublicationAdvanceOnlyThePrincipalBoundPersistedWorkflow() {
        PrincipalKey principal = new PrincipalKey(URI.create("https://issuer.example"), "qa-lead");
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        try (AtomicCoordinationStateRepository state = new AtomicCoordinationStateRepository(mapper,
            PocketHiveMcpProperties.StateMode.MEMORY, temporaryDirectory, 1_000_000, 10, 5)) {
            ScenarioWorkflow workflow = generatedWorkflow(state, principal);
            CoordinationWorkflowUploadLifecycle lifecycle = new CoordinationWorkflowUploadLifecycle(state, mapper);

            lifecycle.validated(principal, workflow.id(), "sha256:archive", "sha256:content");
            assertThat(state.findWorkflow(workflow.id())).get().satisfies(validated -> {
                assertThat(validated.state()).isEqualTo(ScenarioWorkflowState.VALIDATED);
                assertThat(validated.validation().archiveDigest()).isEqualTo("sha256:archive");
            });

            PublicationAttempt attempt = new PublicationAttempt("pa-1", principal,
                PublicationMode.CREATE, "safe", "sha256:content", Instant.parse("2026-08-18T12:30:00Z"));
            attempt.receiving();
            attempt.verified();
            attempt.ownerCallInFlight();
            attempt.succeeded(java.util.Map.of("id", "safe"));
            lifecycle.published(principal, workflow.id(), attempt);

            assertThat(state.findWorkflow(workflow.id())).get().satisfies(published -> {
                assertThat(published.state()).isEqualTo(ScenarioWorkflowState.PUBLISHED);
                assertThat(published.publicationReceiptDigest()).matches("sha256:[0-9a-f]{64}");
            });
            assertThatThrownBy(() -> lifecycle.validated(new PrincipalKey(
                URI.create("https://issuer.example"), "other"), workflow.id(), "a", "b"))
                .isInstanceOf(ToolExecutionException.class)
                .extracting(error -> ((ToolExecutionException) error).code())
                .isEqualTo("SCENARIO_WORKFLOW_NOT_FOUND");
            assertThatThrownBy(() -> lifecycle.validated(principal, "missing", "a", "b"))
                .isInstanceOf(ToolExecutionException.class)
                .extracting(error -> ((ToolExecutionException) error).code())
                .isEqualTo("SCENARIO_WORKFLOW_NOT_FOUND");
        }
    }

    @Test
    void receiptSerializationAndRequiredDigestProviderFailuresAreExplicit() throws Exception {
        PrincipalKey principal = new PrincipalKey(URI.create("https://issuer.example"), "qa-lead");
        CoordinationStateRepository state = mock(CoordinationStateRepository.class);
        ScenarioWorkflow workflow = generatedWorkflow(state, principal);
        when(state.findWorkflow(workflow.id())).thenReturn(java.util.Optional.of(workflow));
        PublicationAttempt attempt = succeededAttempt(principal);

        ObjectMapper failingMapper = mock(ObjectMapper.class);
        when(failingMapper.writeValueAsBytes(any())).thenThrow(new JsonProcessingException("cannot serialize") { });
        CoordinationWorkflowUploadLifecycle serialization =
            new CoordinationWorkflowUploadLifecycle(state, failingMapper);
        assertThatThrownBy(() -> serialization.published(principal, workflow.id(), attempt))
            .isInstanceOf(ToolExecutionException.class)
            .extracting(error -> ((ToolExecutionException) error).code())
            .isEqualTo("PUBLICATION_RECEIPT_SERIALIZATION_FAILED");

        CoordinationWorkflowUploadLifecycle digest = new CoordinationWorkflowUploadLifecycle(
            state, new ObjectMapper().findAndRegisterModules());
        try (MockedStatic<MessageDigest> digests = mockStatic(MessageDigest.class)) {
            digests.when(() -> MessageDigest.getInstance("SHA-256"))
                .thenThrow(new NoSuchAlgorithmException("missing"));
            assertThatThrownBy(() -> digest.published(principal, workflow.id(), attempt))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("SHA-256 is required by Java");
        }
    }

    private static PublicationAttempt succeededAttempt(PrincipalKey principal) {
        PublicationAttempt attempt = new PublicationAttempt("pa-1", principal,
            PublicationMode.CREATE, "safe", "sha256:content", Instant.parse("2026-08-18T12:30:00Z"));
        attempt.receiving();
        attempt.verified();
        attempt.ownerCallInFlight();
        attempt.succeeded(java.util.Map.of("id", "safe"));
        return attempt;
    }

    private static ScenarioWorkflow generatedWorkflow(CoordinationStateRepository state,
                                                        PrincipalKey principal) {
        Instant now = Instant.parse("2026-08-18T12:00:00Z");
        AgentSession session = AgentSession.open("as-1", principal, now, Duration.ofHours(1));
        ScenarioWorkflow workflow = ScenarioWorkflow.create("wf-1", session.id(), principal);
        session.addWorkflow(0, workflow.id(), 4);
        state.createSession(session);
        state.createWorkflow(session, workflow);
        for (QaRequirementTopic topic : QaRequirementTopic.values()) {
            long revision = workflow.revision();
            AnswerProvenance provenance = new AnswerProvenance(principal, "vscode", "PocketHive", "1",
                workflow.id(), revision, topic.name(), "sha256:schema", ElicitationAction.ACCEPT,
                "sha256:content", now.plusSeconds(revision));
            workflow.answer(revision, topic, RequirementAnswer.notApplicable("Not needed", provenance));
        }
        workflow.readyToGenerate(workflow.revision(), new CapabilityFingerprint("sha256:capabilities", now));
        workflow.generated(workflow.revision(), "sha256:files");
        state.saveWorkflow(workflow, List.of());
        return workflow;
    }
}
