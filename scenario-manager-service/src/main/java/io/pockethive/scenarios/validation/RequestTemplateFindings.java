package io.pockethive.scenarios.validation;

import io.pockethive.requesttemplates.RequestTemplateException;
import io.pockethive.requesttemplates.RequestTemplateParser;
import io.pockethive.requesttemplates.RequestTemplateProblem;
import io.pockethive.requesttemplates.RequestTemplateProblemKind;
import io.pockethive.requesttemplates.TemplateDefinition;
import io.pockethive.worker.sdk.auth.AuthRef;
import java.util.List;
import java.util.Map;

/**
 * Responsibility: project canonical request-template parsing failures into bundle findings.
 * Must not: independently validate template fields/protocol/auth or resolve bundle references.
 * Contract: RESP-REQUEST-TEMPLATE-PARSE — docs/architecture/runtime-responsibilities.md#resp-request-template-parse.
 */
final class RequestTemplateFindings {
    private final RequestTemplateParser parser = new RequestTemplateParser();

    TemplateDefinition parse(Map<?, ?> document, String path, List<ValidationFinding> findings) {
        try {
            return parser.parse(document);
        } catch (RequestTemplateException e) {
            e.problems().stream()
                .filter(problem -> problem.kind() != RequestTemplateProblemKind.INLINE_AUTH
                    && problem.kind() != RequestTemplateProblemKind.AUTH_REFERENCE
                    && problem.kind() != RequestTemplateProblemKind.AUTH_APPLY_AS)
                .forEach(problem -> findings.add(finding(problem, path)));
            return null;
        }
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
