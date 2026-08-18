package io.pockethive.mcp.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ScenarioWorkflowTest {
    private static final PrincipalKey PRINCIPAL =
        new PrincipalKey(URI.create("https://issuer.example"), "user-1");
    private static final CapabilityFingerprint CAPABILITIES =
        new CapabilityFingerprint("sha256:capabilities-v1", Instant.parse("2026-08-18T12:00:00Z"));

    @Test
    void startsWithEveryQaTopicUnknownAndBlocksGeneration() {
        ScenarioWorkflow workflow = ScenarioWorkflow.create("workflow-1", "session-1", PRINCIPAL);

        assertThat(workflow.id()).isEqualTo("workflow-1");
        assertThat(workflow.agentSessionId()).isEqualTo("session-1");
        assertThat(workflow.principal()).isEqualTo(PRINCIPAL);
        assertThat(workflow.state()).isEqualTo(ScenarioWorkflowState.DISCOVERING);
        assertThat(workflow.requirements()).hasSize(QaRequirementTopic.values().length)
            .allSatisfy((topic, answer) -> assertThat(answer.disposition()).isEqualTo(RequirementDisposition.UNKNOWN));
        assertThatThrownBy(() -> workflow.readyToGenerate(0, CAPABILITIES))
            .isInstanceOf(WorkflowRuleViolation.class)
            .hasMessage("WORKFLOW_REQUIREMENTS_UNRESOLVED");
    }

    @Test
    void acceptedClientAnswerIsPrincipalAndRevisionBound() {
        ScenarioWorkflow workflow = ScenarioWorkflow.create("workflow-1", "session-1", PRINCIPAL);
        AnswerProvenance provenance = acceptedProvenance(PRINCIPAL, workflow.id(), workflow.revision());

        workflow.answer(0, QaRequirementTopic.GOAL_AND_RISK,
            RequirementAnswer.userProvided("Exercise checkout under peak load", provenance));

        assertThat(workflow.revision()).isEqualTo(1);
        assertThat(workflow.requirements().get(QaRequirementTopic.GOAL_AND_RISK).value())
            .isEqualTo("Exercise checkout under peak load");
        assertThatThrownBy(() -> workflow.answer(0, QaRequirementTopic.SUT_AND_ENDPOINTS,
            RequirementAnswer.userProvided("checkout", acceptedProvenance(PRINCIPAL, workflow.id(), 0))))
            .isInstanceOf(WorkflowRuleViolation.class)
            .hasMessage("WORKFLOW_VERSION_CONFLICT");
    }

    @Test
    void declineCancelWrongPrincipalAndWrongWorkflowCannotBecomeAnswers() {
        ScenarioWorkflow workflow = ScenarioWorkflow.create("workflow-1", "session-1", PRINCIPAL);
        PrincipalKey attacker = new PrincipalKey(URI.create("https://issuer.example"), "user-2");

        assertThatThrownBy(() -> workflow.answer(0, QaRequirementTopic.GOAL_AND_RISK,
            RequirementAnswer.userProvided("invented", provenance(ElicitationAction.DECLINE, PRINCIPAL, "workflow-1", 0))))
            .isInstanceOf(WorkflowRuleViolation.class)
            .hasMessage("ELICITATION_ACCEPT_REQUIRED");
        assertThatThrownBy(() -> workflow.answer(0, QaRequirementTopic.GOAL_AND_RISK,
            RequirementAnswer.userProvided("invented", provenance(ElicitationAction.CANCEL, PRINCIPAL, "workflow-1", 0))))
            .isInstanceOf(WorkflowRuleViolation.class)
            .hasMessage("ELICITATION_ACCEPT_REQUIRED");
        assertThatThrownBy(() -> workflow.answer(0, QaRequirementTopic.GOAL_AND_RISK,
            RequirementAnswer.userProvided("stolen", acceptedProvenance(attacker, "workflow-1", 0))))
            .isInstanceOf(WorkflowRuleViolation.class)
            .hasMessage("WORKFLOW_PRINCIPAL_MISMATCH");
        assertThatThrownBy(() -> workflow.answer(0, QaRequirementTopic.GOAL_AND_RISK,
            RequirementAnswer.userProvided("misbound", acceptedProvenance(PRINCIPAL, "workflow-2", 0))))
            .isInstanceOf(WorkflowRuleViolation.class)
            .hasMessage("ELICITATION_WORKFLOW_MISMATCH");
    }

    @Test
    void changingAnAcceptedRequirementInvalidatesAllDownstreamEvidence() {
        ScenarioWorkflow workflow = completeRequirements();
        workflow.readyToGenerate(workflow.revision(), CAPABILITIES);
        workflow.generated(workflow.revision(), "sha256:files-v1");
        workflow.validated(workflow.revision(), "sha256:archive-v1", "sha256:content-v1");

        workflow.answer(workflow.revision(), QaRequirementTopic.SLA_AND_STOPPING,
            RequirementAnswer.userProvided("p95 below 200 ms", acceptedProvenance(
                PRINCIPAL, workflow.id(), workflow.revision())));

        assertThat(workflow.state()).isEqualTo(ScenarioWorkflowState.REVIEW_REQUIRED);
        assertThat(workflow.generatedFileSetDigest()).isNull();
        assertThat(workflow.validation()).isNull();
        assertThat(workflow.publicationReceiptDigest()).isNull();
    }

    @Test
    void capabilityChangeInvalidatesEvidenceAndPublishedWorkflowIsImmutable() {
        ScenarioWorkflow workflow = completeRequirements();
        workflow.readyToGenerate(workflow.revision(), CAPABILITIES);
        workflow.generated(workflow.revision(), "sha256:files-v1");
        workflow.validated(workflow.revision(), "sha256:archive-v1", "sha256:content-v1");

        workflow.reconcileCapabilities(workflow.revision(),
            new CapabilityFingerprint("sha256:capabilities-v2", Instant.parse("2026-08-18T13:00:00Z")));

        assertThat(workflow.state()).isEqualTo(ScenarioWorkflowState.REVIEW_REQUIRED);
        assertThat(workflow.generatedFileSetDigest()).isNull();

        workflow.readyToGenerate(workflow.revision(), workflow.capabilityFingerprint());
        workflow.generated(workflow.revision(), "sha256:files-v2");
        workflow.validated(workflow.revision(), "sha256:archive-v2", "sha256:content-v2");
        workflow.published(workflow.revision(), "sha256:receipt-v2");

        assertThat(workflow.state()).isEqualTo(ScenarioWorkflowState.PUBLISHED);
        assertThat(workflow.publicationReceiptDigest()).isEqualTo("sha256:receipt-v2");
        assertThatThrownBy(() -> workflow.answer(workflow.revision(), QaRequirementTopic.GOAL_AND_RISK,
            RequirementAnswer.userProvided("change", acceptedProvenance(
                PRINCIPAL, workflow.id(), workflow.revision()))))
            .isInstanceOf(WorkflowRuleViolation.class)
            .hasMessage("WORKFLOW_IMMUTABLE");
        assertThatThrownBy(() -> workflow.readyToGenerate(workflow.revision(), CAPABILITIES))
            .isInstanceOf(WorkflowRuleViolation.class).hasMessage("WORKFLOW_IMMUTABLE");
        assertThatThrownBy(() -> workflow.generated(workflow.revision(), "sha256:new"))
            .isInstanceOf(WorkflowRuleViolation.class).hasMessage("WORKFLOW_IMMUTABLE");
        assertThatThrownBy(() -> workflow.validated(workflow.revision(), "sha256:new", "sha256:new"))
            .isInstanceOf(WorkflowRuleViolation.class).hasMessage("WORKFLOW_IMMUTABLE");
        assertThatThrownBy(() -> workflow.published(workflow.revision(), "sha256:new"))
            .isInstanceOf(WorkflowRuleViolation.class).hasMessage("WORKFLOW_IMMUTABLE");
        assertThatThrownBy(() -> workflow.reconcileCapabilities(workflow.revision(), CAPABILITIES))
            .isInstanceOf(WorkflowRuleViolation.class).hasMessage("WORKFLOW_IMMUTABLE");
    }

    @Test
    void rejectsInvalidConstructionAndInvalidAnswerBindings() {
        assertThatThrownBy(() -> ScenarioWorkflow.create(" ", "session", PRINCIPAL))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("id must not be blank");
        assertThatThrownBy(() -> ScenarioWorkflow.create("workflow", null, PRINCIPAL))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("agentSessionId must not be blank");
        assertThatThrownBy(() -> ScenarioWorkflow.create("workflow", "session", null))
            .isInstanceOf(NullPointerException.class).hasMessage("principal");

        ScenarioWorkflow workflow = ScenarioWorkflow.create(" workflow ", " session ", PRINCIPAL);
        assertThat(workflow.id()).isEqualTo("workflow");
        assertThat(workflow.agentSessionId()).isEqualTo("session");
        assertThatThrownBy(() -> workflow.answer(0, null,
            RequirementAnswer.userProvided("value", acceptedProvenance(PRINCIPAL, workflow.id(), 0))))
            .isInstanceOf(NullPointerException.class).hasMessage("topic");
        assertThatThrownBy(() -> workflow.answer(0, QaRequirementTopic.GOAL_AND_RISK, null))
            .isInstanceOf(NullPointerException.class).hasMessage("answer");
        assertThatThrownBy(() -> workflow.answer(0, QaRequirementTopic.GOAL_AND_RISK,
            RequirementAnswer.unknown()))
            .isInstanceOf(WorkflowRuleViolation.class).hasMessage("ELICITATION_ACCEPT_REQUIRED");
        assertThatThrownBy(() -> workflow.answer(0, QaRequirementTopic.GOAL_AND_RISK,
            new RequirementAnswer(RequirementDisposition.USER_PROVIDED, "value", null)))
            .isInstanceOf(WorkflowRuleViolation.class).hasMessage("ELICITATION_ACCEPT_REQUIRED");
        assertThatThrownBy(() -> workflow.answer(0, QaRequirementTopic.GOAL_AND_RISK,
            RequirementAnswer.userProvided("value", acceptedProvenance(PRINCIPAL, workflow.id(), 1))))
            .isInstanceOf(WorkflowRuleViolation.class).hasMessage("ELICITATION_REVISION_MISMATCH");
    }

    @Test
    void everyTransitionChecksStateRevisionAndRequiredDigests() {
        ScenarioWorkflow workflow = completeRequirements();
        long reviewRevision = workflow.revision();
        assertThatThrownBy(() -> workflow.readyToGenerate(reviewRevision - 1, CAPABILITIES))
            .isInstanceOf(WorkflowRuleViolation.class).hasMessage("WORKFLOW_VERSION_CONFLICT");
        assertThatThrownBy(() -> workflow.readyToGenerate(reviewRevision, null))
            .isInstanceOf(NullPointerException.class).hasMessage("capabilities");
        workflow.readyToGenerate(reviewRevision, CAPABILITIES);
        assertThat(workflow.revision()).isEqualTo(reviewRevision + 1);

        assertThatThrownBy(() -> workflow.generated(reviewRevision, "sha256:files"))
            .isInstanceOf(WorkflowRuleViolation.class).hasMessage("WORKFLOW_VERSION_CONFLICT");
        assertThatThrownBy(() -> workflow.generated(workflow.revision(), " "))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("fileSetDigest must not be blank");
        workflow.generated(workflow.revision(), " sha256:files ");
        assertThat(workflow.generatedFileSetDigest()).isEqualTo("sha256:files");
        assertThat(workflow.revision()).isEqualTo(reviewRevision + 2);

        long generatedRevision = workflow.revision();
        assertThatThrownBy(() -> workflow.validated(generatedRevision - 1, "sha256:archive", "sha256:content"))
            .isInstanceOf(WorkflowRuleViolation.class).hasMessage("WORKFLOW_VERSION_CONFLICT");
        assertThatThrownBy(() -> workflow.validated(generatedRevision, null, "sha256:content"))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("archiveDigest must not be blank");
        assertThatThrownBy(() -> workflow.validated(generatedRevision, "sha256:archive", " "))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("bundleContentDigest must not be blank");
        workflow.validated(generatedRevision, " sha256:archive ", " sha256:content ");
        assertThat(workflow.validation()).isEqualTo(new ValidationReceipt("sha256:archive", "sha256:content"));
        assertThat(workflow.revision()).isEqualTo(generatedRevision + 1);

        long validatedRevision = workflow.revision();
        assertThatThrownBy(() -> workflow.published(validatedRevision - 1, "sha256:receipt"))
            .isInstanceOf(WorkflowRuleViolation.class).hasMessage("WORKFLOW_VERSION_CONFLICT");
        assertThatThrownBy(() -> workflow.published(validatedRevision, null))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("receiptDigest must not be blank");
        workflow.published(validatedRevision, " sha256:receipt ");
        assertThat(workflow.revision()).isEqualTo(validatedRevision + 1);
        assertThat(workflow.publicationReceiptDigest()).isEqualTo("sha256:receipt");
    }

    @Test
    void operationsFromWrongStatesFailWithStableCodes() {
        ScenarioWorkflow workflow = completeRequirements();
        assertThatThrownBy(() -> workflow.generated(workflow.revision(), "sha256:files"))
            .isInstanceOf(WorkflowRuleViolation.class).hasMessage("WORKFLOW_NOT_READY_TO_GENERATE");
        assertThatThrownBy(() -> workflow.validated(workflow.revision(), "sha256:archive", "sha256:content"))
            .isInstanceOf(WorkflowRuleViolation.class).hasMessage("WORKFLOW_NOT_GENERATED");
        assertThatThrownBy(() -> workflow.published(workflow.revision(), "sha256:receipt"))
            .isInstanceOf(WorkflowRuleViolation.class).hasMessage("WORKFLOW_NOT_VALIDATED");
    }

    @Test
    void capabilityReconciliationIsExplicitAndRevisionChecked() {
        ScenarioWorkflow incomplete = ScenarioWorkflow.create("workflow-1", "session-1", PRINCIPAL);
        assertThatThrownBy(() -> incomplete.reconcileCapabilities(1, CAPABILITIES))
            .isInstanceOf(WorkflowRuleViolation.class).hasMessage("WORKFLOW_VERSION_CONFLICT");
        assertThatThrownBy(() -> incomplete.reconcileCapabilities(0, null))
            .isInstanceOf(NullPointerException.class).hasMessage("current");
        incomplete.reconcileCapabilities(0, CAPABILITIES);
        assertThat(incomplete.state()).isEqualTo(ScenarioWorkflowState.BLOCKED);
        assertThat(incomplete.revision()).isEqualTo(1);

        ScenarioWorkflow complete = completeRequirements();
        complete.readyToGenerate(complete.revision(), CAPABILITIES);
        long revision = complete.revision();
        complete.reconcileCapabilities(revision, CAPABILITIES);
        assertThat(complete.revision()).isEqualTo(revision);
        assertThat(complete.state()).isEqualTo(ScenarioWorkflowState.READY_TO_GENERATE);
    }

    @Test
    void cancellationIsRevisionCheckedAndTerminal() {
        ScenarioWorkflow workflow = completeRequirements();
        workflow.readyToGenerate(workflow.revision(), CAPABILITIES);
        workflow.generated(workflow.revision(), "sha256:generated");
        long revision = workflow.revision();
        assertThatThrownBy(() -> workflow.cancel(revision + 1))
            .isInstanceOf(WorkflowRuleViolation.class).hasMessage("WORKFLOW_VERSION_CONFLICT");

        workflow.cancel(revision);

        assertThat(workflow.state()).isEqualTo(ScenarioWorkflowState.CANCELLED);
        assertThat(workflow.revision()).isEqualTo(revision + 1);
        assertThat(workflow.generatedFileSetDigest()).isNull();
        assertThat(workflow.capabilityFingerprint()).isEqualTo(CAPABILITIES);
        assertThatThrownBy(() -> workflow.cancel(revision + 1))
            .isInstanceOf(WorkflowRuleViolation.class).hasMessage("WORKFLOW_IMMUTABLE");
    }

    @Test
    void restoresRequirementsAndAllWorkflowEvidenceExactly() {
        ScenarioWorkflow original = completeRequirements();
        original.readyToGenerate(original.revision(), CAPABILITIES);
        original.generated(original.revision(), "sha256:files");
        original.validated(original.revision(), "sha256:archive", "sha256:content");
        ScenarioWorkflowSnapshot snapshot = original.snapshot();

        ScenarioWorkflow restored = ScenarioWorkflow.restore(snapshot);

        assertThat(restored.snapshot()).isEqualTo(snapshot);
        assertThat(restored.requirements()).containsExactlyInAnyOrderEntriesOf(snapshot.requirements());
    }

    private static ScenarioWorkflow completeRequirements() {
        ScenarioWorkflow workflow = ScenarioWorkflow.create("workflow-1", "session-1", PRINCIPAL);
        for (QaRequirementTopic topic : QaRequirementTopic.values()) {
            long revision = workflow.revision();
            workflow.answer(revision, topic, RequirementAnswer.notApplicable(
                "Explicitly outside this test", acceptedProvenance(PRINCIPAL, workflow.id(), revision)));
        }
        return workflow;
    }

    private static AnswerProvenance acceptedProvenance(PrincipalKey principal, String workflowId, long revision) {
        return provenance(ElicitationAction.ACCEPT, principal, workflowId, revision);
    }

    private static AnswerProvenance provenance(ElicitationAction action, PrincipalKey principal,
                                                String workflowId, long revision) {
        return new AnswerProvenance(
            principal,
            "pockethive-vscode",
            "PocketHive VS Code",
            "1.0.0",
            workflowId,
            revision,
            "goal-and-risk",
            "sha256:schema",
            action,
            action == ElicitationAction.ACCEPT ? "sha256:content" : null,
            Instant.parse("2026-08-18T12:00:00Z"));
    }
}
