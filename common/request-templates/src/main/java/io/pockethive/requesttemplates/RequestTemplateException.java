package io.pockethive.requesttemplates;

import java.util.List;

/**
 * Responsibility: carry canonical request-template validation failures to callers.
 * Must not: independently validate templates or select error transport.
 * Contract: RESP-REQUEST-TEMPLATE-PARSE — docs/architecture/runtime-responsibilities.md#resp-request-template-parse.
 */
public final class RequestTemplateException extends IllegalArgumentException {
    private final List<RequestTemplateProblem> problems;

    public RequestTemplateException(List<RequestTemplateProblem> problems) {
        super(problems.stream().map(RequestTemplateProblem::message)
            .collect(java.util.stream.Collectors.joining("; ")));
        this.problems = List.copyOf(problems);
    }

    public List<RequestTemplateProblem> problems() {
        return problems;
    }
}
