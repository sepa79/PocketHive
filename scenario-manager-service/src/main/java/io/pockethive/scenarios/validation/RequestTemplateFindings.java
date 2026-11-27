package io.pockethive.scenarios.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.requesttemplates.HttpSequencePortablePolicy;
import io.pockethive.requesttemplates.HttpSequenceStep;
import io.pockethive.requesttemplates.PortableHttpCall;
import io.pockethive.requesttemplates.RequestTemplateException;
import io.pockethive.requesttemplates.RequestTemplateParser;
import io.pockethive.requesttemplates.RequestTemplateProblem;
import io.pockethive.requesttemplates.RequestTemplateProblemKind;
import io.pockethive.requesttemplates.RequestTemplateSources;
import io.pockethive.requesttemplates.TemplateDefinition;
import io.pockethive.swarm.model.ScenarioConfigurationPreparation;
import io.pockethive.templatedata.TemplateDataException;
import io.pockethive.templating.PortableHttpRenderer;
import io.pockethive.worker.sdk.auth.AuthRef;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Responsibility: project canonical request-template and selected portable-step admission into bundle findings.
 * Must not: independently validate template fields, compile syntax, decide step policy or resolve bundle references.
 * Contract: RESP-REQUEST-TEMPLATE-PARSE — docs/architecture/runtime-responsibilities.md#resp-request-template-parse.
 */
final class RequestTemplateFindings {
    private final RequestTemplateParser parser = new RequestTemplateParser();
    private final PortableHttpRenderer portableRenderer = new PortableHttpRenderer();
    private final ObjectMapper mapper = new ObjectMapper();

    TemplateDefinition parse(Map<?, ?> document, String path, List<ValidationFinding> findings) {
        try {
            TemplateDefinition definition = parser.parse(document);
            if (definition instanceof PortableHttpCall portable) {
                portableRenderer.validate(portable);
            }
            return definition;
        } catch (RequestTemplateException e) {
            e.problems().stream()
                .filter(problem -> problem.kind() != RequestTemplateProblemKind.INLINE_AUTH
                    && problem.kind() != RequestTemplateProblemKind.AUTH_REFERENCE
                    && problem.kind() != RequestTemplateProblemKind.AUTH_APPLY_AS)
                .forEach(problem -> findings.add(finding(problem, path)));
            return null;
        } catch (TemplateDataException failure) {
            findings.add(invalid(path, failure.getMessage()));
            return null;
        }
    }

    void validatePortableSteps(PortableHttpCall call, Map<String, Object> config, String path,
                               Set<ScenarioTemplateReference> references, List<ValidationFinding> findings) {
        if (!(config.get("steps") instanceof List<?> steps)) {
            return;
        }
        String key = RequestTemplateParser.key(call.serviceId(), call.callId());
        Set<String> selectedPaths = references.stream()
            .filter(reference -> key.equals(reference.key()))
            .map(ScenarioTemplateReference::sourcePath)
            .collect(Collectors.toSet());
        for (int index = 0; index < steps.size(); index++) {
            String stepPath = stepPath(path, index);
            if (!selectedPaths.contains(stepPath) || !(steps.get(index) instanceof Map<?, ?> step)) {
                continue;
            }
            Object retry = step.get("retry");
            if (retry != null && !(retry instanceof Map<?, ?>)) {
                findings.add(invalid(stepPath + ".retry", "Sequence retry settings must be an object."));
            }
            Object attempts = retry instanceof Map<?, ?> settings ? settings.get("maxAttempts") : null;
            Integer count = concrete(attempts, Integer.class, stepPath + ".retry.maxAttempts", findings);
            Boolean continueOnNon2xx = concrete(step.get("continueOnNon2xx"), Boolean.class, stepPath + ".continueOnNon2xx", findings);
            List<?> extracts = concrete(step.get("extracts"), List.class, stepPath + ".extracts", findings);
            try {
                HttpSequencePortablePolicy.validate(count, continueOnNon2xx, extracts);
            } catch (IllegalArgumentException failure) {
                findings.add(invalid(stepPath, failure.getMessage()));
            }
        }
    }

    private <T> T concrete(Object value, Class<T> type, String path, List<ValidationFinding> findings) {
        try {
            if (value instanceof String text && ScenarioConfigurationPreparation.canResolveTypedValue(text, type == List.class)) {
                findings.add(ValidationIssue.WORK_CONFIGURATION_DEFERRED.finding(ValidationSeverity.WARNING, path,
                    "Portable option validation is deferred until bootstrap resolves its variables."));
                return null;
            }
            return mapper.convertValue(value, type);
        } catch (IllegalArgumentException failure) {
            findings.add(invalid(path, failure.getMessage()));
            return null;
        }
    }

    static String stepPath(String path, int index) {
        return path + ".steps[" + index + "]";
    }

    Optional<HttpSequenceStep> stepTarget(Map<?, ?> step, String path, List<ValidationFinding> findings) {
        Map<String, Object> targetFields = new java.util.LinkedHashMap<>();
        targetFields.put("callId", step.get("callId"));
        targetFields.put("serviceId", step.get("serviceId"));
        try {
            return Optional.of(mapper.convertValue(targetFields, HttpSequenceStep.class));
        } catch (IllegalArgumentException failure) {
            findings.add(invalid(path, failure.getMessage()));
            return Optional.empty();
        }
    }

    Map<String, String> sources(TemplateDefinition definition) {
        return RequestTemplateSources.from(definition).namedSources();
    }

    private ValidationFinding invalid(String path, String message) {
        return ValidationIssue.TEMPLATE_INVALID.finding(ValidationSeverity.ERROR, path, message,
            ValidationIssue.TEMPLATE_INVALID.fix());
    }

    AuthRef authReference(Map<?, ?> document, String path, List<ValidationFinding> findings) {
        List<RequestTemplateProblem> problems = parser.authProblems(document);
        problems.forEach(problem -> findings.add(finding(problem, path)));
        if (!problems.isEmpty() || !document.containsKey("authRef")) {
            return null;
        }
        return parser.authReference(document.get("authRef"));
    }

    private ValidationFinding finding(RequestTemplateProblem problem, String path) {
        ValidationIssue issue = switch (problem.kind()) {
            case REQUIRED_FIELD -> ValidationIssue.TEMPLATE_REQUIRED_FIELD_MISSING;
            case INVALID_VALUE -> ValidationIssue.TEMPLATE_INVALID;
            case INLINE_AUTH -> ValidationIssue.AUTH_REF_INLINE_NOT_ALLOWED;
            case AUTH_REFERENCE -> ValidationIssue.AUTH_REF_PROFILE_MISSING;
            case AUTH_APPLY_AS -> ValidationIssue.AUTH_REF_APPLY_AS_INVALID;
        };
        return issue.finding(ValidationSeverity.ERROR,
            problem.field().isBlank() ? path : path + ":" + problem.field(), problem.message(), issue.fix());
    }
}
