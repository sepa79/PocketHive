package io.pockethive.scenarios;


/**
 * Responsibility: carry scenario toolbar decisions.
 * Must not: authorize commands or define policy.
 * Contract: RESP-UI-GLOBAL-ACCESS — docs/architecture/runtime-responsibilities.md#resp-ui-global-access.
 */
public record ScenarioOperationsAccessView(boolean canReload, boolean canUpload) {

}
