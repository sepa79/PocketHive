package io.pockethive.scenarios.validation;

import static io.pockethive.scenarios.ScenarioBundleLayout.AUTH_PROFILES_FILE;
import static io.pockethive.scenarios.ScenarioBundleLayout.HTTP_TEMPLATES_ROOT;
import static io.pockethive.scenarios.ScenarioBundleLayout.SCENARIO_DESCRIPTOR_FILE;
import static io.pockethive.scenarios.ScenarioBundleLayout.SUT_DESCRIPTOR_PATTERN;
import static io.pockethive.scenarios.ScenarioBundleLayout.TEMPLATES_ROOT;
import static io.pockethive.scenarios.ScenarioBundleLayout.VARIABLES_FILE;

public enum ValidationIssue {
    AUTH_PROFILES_INVALID(
        ValidationCategory.AUTH,
        AUTH_PROFILES_FILE,
        "Fix " + AUTH_PROFILES_FILE + " YAML syntax, profile shape, and duplicate keys."),
    AUTH_PROFILES_MISSING(
        ValidationCategory.AUTH,
        AUTH_PROFILES_FILE,
        "Create " + AUTH_PROFILES_FILE + " in the bundle root with a profiles map."),
    AUTH_REF_INLINE_NOT_ALLOWED(
        ValidationCategory.AUTH,
        TEMPLATES_ROOT,
        "Replace inline auth with authRef and declare the profile in " + AUTH_PROFILES_FILE + "."),
    AUTH_REF_PROFILE_MISSING(
        ValidationCategory.AUTH,
        AUTH_PROFILES_FILE,
        "Add the missing profile to " + AUTH_PROFILES_FILE + " or correct authRef.profileId."),
    AUTH_REF_APPLY_AS_INVALID(
        ValidationCategory.AUTH,
        AUTH_PROFILES_FILE,
        "Use a supported authRef.applyAs value."),
    AUTH_STORAGE_INVALID(
        ValidationCategory.AUTH,
        AUTH_PROFILES_FILE,
        "Fix auth profile storage settings."),
    BUNDLE_DEFUNCT(
        ValidationCategory.BUNDLE,
        "bundle",
        "Repair or move the bundle before using it to create a swarm."),
    BUNDLE_DIRECTORY_MISSING(
        ValidationCategory.BUNDLE,
        "bundle",
        "Restore the bundle directory or re-import the scenario bundle."),
    BUNDLE_INVALID(
        ValidationCategory.BUNDLE,
        "bundle",
        "Review the bundle contract and repair the reported path."),
    CAPABILITY_MANIFEST_MISSING(
        ValidationCategory.CAPABILITIES,
        "bundle",
        "Install the matching capability manifest or update the image reference."),
    DUPLICATE_SCENARIO_ID(
        ValidationCategory.SCENARIO,
        SCENARIO_DESCRIPTOR_FILE + ":id",
        "Rename one scenario id or move one conflicting bundle to quarantine."),
    SCENARIO_DESCRIPTOR_INVALID(
        ValidationCategory.SCENARIO,
        SCENARIO_DESCRIPTOR_FILE,
        "Repair " + SCENARIO_DESCRIPTOR_FILE + " so it has a valid id and swarm template."),
    SUT_INVALID(
        ValidationCategory.SUT,
        "sut",
        "Repair " + SUT_DESCRIPTOR_PATTERN + " and ensure its id matches the directory name."),
    TEMPLATE_CALL_ID_DUPLICATE(
        ValidationCategory.TEMPLATES,
        HTTP_TEMPLATES_ROOT,
        "Keep one template per callId or rename the duplicate callIds."),
    TEMPLATE_CALL_ID_MISSING(
        ValidationCategory.TEMPLATES,
        SCENARIO_DESCRIPTOR_FILE + ":plan",
        "Add the referenced HTTP template or update the x-ph-call-id reference."),
    TEMPLATE_INVALID(
        ValidationCategory.TEMPLATES,
        TEMPLATES_ROOT,
        "Review the bundle contract and repair the reported path."),
    TEMPLATE_PARSE_ERROR(
        ValidationCategory.TEMPLATES,
        TEMPLATES_ROOT,
        "Fix the template YAML/JSON syntax."),
    TEMPLATE_PROTOCOL_MISMATCH(
        ValidationCategory.TEMPLATES,
        HTTP_TEMPLATES_ROOT,
        "Set protocol: HTTP or move the template under the matching protocol folder."),
    TEMPLATE_REQUIRED_FIELD_MISSING(
        ValidationCategory.TEMPLATES,
        HTTP_TEMPLATES_ROOT,
        "Add the required field to the HTTP template."),
    VARIABLES_INVALID(
        ValidationCategory.VARIABLES,
        VARIABLES_FILE,
        "Repair " + VARIABLES_FILE + " according to Scenario Variables v1."),
    VARIABLES_MISSING(
        ValidationCategory.VARIABLES,
        VARIABLES_FILE,
        "Create " + VARIABLES_FILE + " with definitions for the referenced vars."),
    VARIABLE_REFERENCE_UNKNOWN(
        ValidationCategory.VARIABLES,
        VARIABLES_FILE,
        "Add the missing " + VARIABLES_FILE + " definition or correct the vars reference.");

    private final ValidationCategory category;
    private final String path;
    private final String fix;

    ValidationIssue(ValidationCategory category, String path, String fix) {
        this.category = category;
        this.path = path;
        this.fix = fix;
    }

    public ValidationCategory category() {
        return category;
    }

    public String code() {
        return name();
    }

    public String path() {
        return path;
    }

    public String fix() {
        return fix;
    }

    public ValidationFinding finding(ValidationSeverity severity, String message) {
        return finding(severity, path, message, fix);
    }

    public ValidationFinding finding(ValidationSeverity severity, String path, String message) {
        return finding(severity, path, message, fix);
    }

    public ValidationFinding finding(ValidationSeverity severity, String path, String message, String fix) {
        return new ValidationFinding(
            category,
            code(),
            severity != null ? severity : ValidationSeverity.ERROR,
            path != null && !path.isBlank() ? path : this.path,
            message != null && !message.isBlank() ? message : "Validation failed.",
            fix != null && !fix.isBlank() ? fix : this.fix);
    }
}
