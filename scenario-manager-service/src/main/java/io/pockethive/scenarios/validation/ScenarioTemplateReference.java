package io.pockethive.scenarios.validation;

/**
 * Responsibility: carry a statically known request target and its authored source path.
 * Must not: discover selectors, normalize configuration or decide template visibility.
 * Contract: RESP-SCENARIO-VALIDATE — docs/architecture/runtime-responsibilities.md#resp-scenario-validate.
 */
record ScenarioTemplateReference(String serviceId, String callId, String sourcePath) {
    String key() { return io.pockethive.requesttemplates.RequestTemplateParser.key(serviceId, callId); }
}
