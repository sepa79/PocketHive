package io.pockethive.auth.contract;

import java.util.Set;

/**
 * Responsibility: define the existing PocketHive permission combinations once.
 * Must not: resolve callers, scopes or perform authorization IO.
 * Contract: RESP-UI-GLOBAL-ACCESS — docs/architecture/runtime-responsibilities.md#resp-ui-global-access.
 */
public final class PocketHivePermissionSets {
    public static final Set<String> READ = Set.of(PocketHivePermissionIds.VIEW, PocketHivePermissionIds.RUN, PocketHivePermissionIds.ALL);
    public static final Set<String> RUN = Set.of(PocketHivePermissionIds.RUN, PocketHivePermissionIds.ALL);
    public static final Set<String> MANAGE = Set.of(PocketHivePermissionIds.ALL);
    private PocketHivePermissionSets() {}
}
