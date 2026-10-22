package io.pockethive.scenarios.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.requesttemplates.HttpSequencePortablePolicy;
import io.pockethive.requesttemplates.PortableHttpCall;
import io.pockethive.requesttemplates.RequestTemplateException;
import io.pockethive.requesttemplates.RequestTemplateParser;
import io.pockethive.requesttemplates.RequestTemplateProblem;
import io.pockethive.requesttemplates.RequestTemplateProblemKind;
import io.pockethive.requesttemplates.TemplateDefinition;
import io.pockethive.worker.sdk.auth.AuthRef;
import io.pockethive.templatedata.TemplateDataException;
import io.pockethive.templating.PortableHttpRenderer;
import java.util.List;
import java.util.Map;

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
                               List<ValidationFinding> findings) {
        if (!(config.get("steps") instanceof List<?> steps)) {
            return;
        }
        for (int index = 0; index < steps.size(); index++) {
            if (!(steps.get(index) instanceof Map<?, ?> step) || !(step.get("callId") instanceof String callId)) {
                continue;
            }
            Object serviceId = step.get("serviceId");
            if (serviceId == null || serviceId instanceof String text && text.isBlank()) {
                serviceId = config.get("serviceId");
            }
            if (!(serviceId instanceof String service)
                || !RequestTemplateParser.key(call.serviceId(), call.callId())
                    .equals(RequestTemplateParser.key(service, callId))) {
                continue;
            }
            try {
                Object retry = step.get("retry");
                if (retry != null && !(retry instanceof Map<?, ?>)) {
                    throw new IllegalArgumentException("Sequence retry settings must be an object.");
                }
                Object attempts = retry instanceof Map<?, ?> settings ? settings.get("maxAttempts") : null;
                HttpSequencePortablePolicy.validate(mapper.convertValue(attempts, Integer.class),
                    mapper.convertValue(step.get("continueOnNon2xx"), Boolean.class),
                    mapper.convertValue(step.get("extracts"), List.class));
            } catch (IllegalArgumentException failure) {
                findings.add(invalid(path + ".steps[" + index + "]", failure.getMessage()));
            }
        }
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
