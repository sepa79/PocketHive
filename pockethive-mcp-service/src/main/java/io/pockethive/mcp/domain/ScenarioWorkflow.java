package io.pockethive.mcp.domain;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;

public final class ScenarioWorkflow {
    private final String id;
    private final String agentSessionId;
    private final PrincipalKey principal;
    private final EnumMap<QaRequirementTopic, RequirementAnswer> requirements;
    private long revision;
    private ScenarioWorkflowState state;
    private CapabilityFingerprint capabilityFingerprint;
    private String generatedFileSetDigest;
    private ValidationReceipt validation;
    private String publicationReceiptDigest;

    private ScenarioWorkflow(String id, String agentSessionId, PrincipalKey principal) {
        this.id = requireText(id, "id");
        this.agentSessionId = requireText(agentSessionId, "agentSessionId");
        this.principal = Objects.requireNonNull(principal, "principal");
        this.requirements = new EnumMap<>(QaRequirementTopic.class);
        for (QaRequirementTopic topic : QaRequirementTopic.values()) {
            requirements.put(topic, RequirementAnswer.unknown());
        }
        this.state = ScenarioWorkflowState.DISCOVERING;
    }

    public static ScenarioWorkflow create(String id, String agentSessionId, PrincipalKey principal) {
        return new ScenarioWorkflow(id, agentSessionId, principal);
    }

    public static ScenarioWorkflow restore(ScenarioWorkflowSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        ScenarioWorkflow workflow = new ScenarioWorkflow(
            snapshot.id(), snapshot.agentSessionId(), snapshot.principal());
        workflow.requirements.putAll(snapshot.requirements());
        workflow.revision = snapshot.revision();
        workflow.state = snapshot.state();
        workflow.capabilityFingerprint = snapshot.capabilityFingerprint();
        workflow.generatedFileSetDigest = snapshot.generatedFileSetDigest();
        workflow.validation = snapshot.validation();
        workflow.publicationReceiptDigest = snapshot.publicationReceiptDigest();
        return workflow;
    }

    public ScenarioWorkflowSnapshot snapshot() {
        return new ScenarioWorkflowSnapshot(id, agentSessionId, principal, requirements, revision, state,
            capabilityFingerprint, generatedFileSetDigest, validation, publicationReceiptDigest);
    }

    public void answer(long expectedRevision, QaRequirementTopic topic, RequirementAnswer answer) {
        requireMutable();
        requireRevision(expectedRevision);
        validateAnswer(expectedRevision, topic, answer);
        requirements.put(topic, answer);
        finishAnswerMutation();
    }

    public void answerAll(long expectedRevision, Map<QaRequirementTopic, RequirementAnswer> answers) {
        requireMutable();
        requireRevision(expectedRevision);
        Objects.requireNonNull(answers, "answers");
        if (!answers.keySet().equals(EnumSet.allOf(QaRequirementTopic.class))) {
            throw new WorkflowRuleViolation("WORKFLOW_REQUIREMENT_SET_INCOMPLETE");
        }
        for (QaRequirementTopic topic : QaRequirementTopic.values()) {
            validateAnswer(expectedRevision, topic, answers.get(topic));
        }
        requirements.putAll(answers);
        finishAnswerMutation();
    }

    private void validateAnswer(long expectedRevision, QaRequirementTopic topic, RequirementAnswer answer) {
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(answer, "answer");
        if (answer.disposition() == RequirementDisposition.UNKNOWN || answer.provenance() == null) {
            throw new WorkflowRuleViolation("ELICITATION_ACCEPT_REQUIRED");
        }
        AnswerProvenance provenance = answer.provenance();
        if (provenance.action() != ElicitationAction.ACCEPT) {
            throw new WorkflowRuleViolation("ELICITATION_ACCEPT_REQUIRED");
        }
        if (!principal.equals(provenance.principal())) {
            throw new WorkflowRuleViolation("WORKFLOW_PRINCIPAL_MISMATCH");
        }
        if (!id.equals(provenance.workflowId())) {
            throw new WorkflowRuleViolation("ELICITATION_WORKFLOW_MISMATCH");
        }
        if (provenance.workflowRevision() != expectedRevision) {
            throw new WorkflowRuleViolation("ELICITATION_REVISION_MISMATCH");
        }
    }

    private void finishAnswerMutation() {
        invalidateDownstream();
        state = hasUnknownRequirement() ? ScenarioWorkflowState.DISCOVERING : ScenarioWorkflowState.REVIEW_REQUIRED;
        revision++;
    }

    public void readyToGenerate(long expectedRevision, CapabilityFingerprint capabilities) {
        requireMutable();
        requireRevision(expectedRevision);
        if (hasUnknownRequirement()) {
            throw new WorkflowRuleViolation("WORKFLOW_REQUIREMENTS_UNRESOLVED");
        }
        capabilityFingerprint = Objects.requireNonNull(capabilities, "capabilities");
        state = ScenarioWorkflowState.READY_TO_GENERATE;
        revision++;
    }

    public void generated(long expectedRevision, String fileSetDigest) {
        requireMutable();
        requireRevision(expectedRevision);
        requireState(ScenarioWorkflowState.READY_TO_GENERATE, "WORKFLOW_NOT_READY_TO_GENERATE");
        generatedFileSetDigest = requireText(fileSetDigest, "fileSetDigest");
        state = ScenarioWorkflowState.GENERATED;
        revision++;
    }

    public void validated(long expectedRevision, String archiveDigest, String bundleContentDigest) {
        requireMutable();
        requireRevision(expectedRevision);
        requireState(ScenarioWorkflowState.GENERATED, "WORKFLOW_NOT_GENERATED");
        validation = new ValidationReceipt(
            requireText(archiveDigest, "archiveDigest"),
            requireText(bundleContentDigest, "bundleContentDigest"));
        state = ScenarioWorkflowState.VALIDATED;
        revision++;
    }

    public void published(long expectedRevision, String receiptDigest) {
        requireMutable();
        requireRevision(expectedRevision);
        requireState(ScenarioWorkflowState.VALIDATED, "WORKFLOW_NOT_VALIDATED");
        publicationReceiptDigest = requireText(receiptDigest, "receiptDigest");
        state = ScenarioWorkflowState.PUBLISHED;
        revision++;
    }

    public void reconcileCapabilities(long expectedRevision, CapabilityFingerprint current) {
        requireMutable();
        requireRevision(expectedRevision);
        Objects.requireNonNull(current, "current");
        if (!current.equals(capabilityFingerprint)) {
            capabilityFingerprint = current;
            invalidateDownstream();
            state = hasUnknownRequirement() ? ScenarioWorkflowState.BLOCKED : ScenarioWorkflowState.REVIEW_REQUIRED;
            revision++;
        }
    }

    public void cancel(long expectedRevision) {
        requireMutable();
        requireRevision(expectedRevision);
        invalidateDownstream();
        state = ScenarioWorkflowState.CANCELLED;
        revision++;
    }

    private void invalidateDownstream() {
        generatedFileSetDigest = null;
        validation = null;
        publicationReceiptDigest = null;
    }

    private boolean hasUnknownRequirement() {
        return requirements.values().stream()
            .anyMatch(answer -> answer.disposition() == RequirementDisposition.UNKNOWN);
    }

    private void requireMutable() {
        if (state == ScenarioWorkflowState.PUBLISHED || state == ScenarioWorkflowState.CANCELLED) {
            throw new WorkflowRuleViolation("WORKFLOW_IMMUTABLE");
        }
    }

    private void requireRevision(long expectedRevision) {
        if (expectedRevision != revision) {
            throw new WorkflowRuleViolation("WORKFLOW_VERSION_CONFLICT");
        }
    }

    private void requireState(ScenarioWorkflowState expected, String code) {
        if (state != expected) {
            throw new WorkflowRuleViolation(code);
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    public String id() {
        return id;
    }

    public String agentSessionId() {
        return agentSessionId;
    }

    public PrincipalKey principal() {
        return principal;
    }

    public long revision() {
        return revision;
    }

    public ScenarioWorkflowState state() {
        return state;
    }

    public Map<QaRequirementTopic, RequirementAnswer> requirements() {
        return Collections.unmodifiableMap(requirements);
    }

    public CapabilityFingerprint capabilityFingerprint() {
        return capabilityFingerprint;
    }

    public String generatedFileSetDigest() {
        return generatedFileSetDigest;
    }

    public ValidationReceipt validation() {
        return validation;
    }

    public String publicationReceiptDigest() {
        return publicationReceiptDigest;
    }
}
