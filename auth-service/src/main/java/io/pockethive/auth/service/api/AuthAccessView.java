package io.pockethive.auth.service.api;


/**
 * Responsibility: carry caller-specific UI access decisions.
 * Must not: interpret grants or authorize commands.
 * Contract: RESP-UI-GLOBAL-ACCESS — docs/architecture/runtime-responsibilities.md#resp-ui-global-access.
 */
public record AuthAccessView(boolean canAccessPocketHive, boolean canRunPocketHive, boolean canManageUsers) {

}
