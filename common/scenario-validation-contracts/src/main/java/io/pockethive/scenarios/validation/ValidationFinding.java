package io.pockethive.scenarios.validation;

public record ValidationFinding(
    ValidationCategory category,
    String code,
    ValidationSeverity severity,
    String path,
    String message,
    String fix
) { }
