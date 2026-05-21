package io.pockethive.auth.contract;

import java.util.Objects;
import java.util.Set;

public final class PocketHiveGrantChecks {
    private PocketHiveGrantChecks() {
    }

    public static boolean hasAnyPermission(AuthenticatedUserDto user, Set<String> permissions) {
        if (user == null) {
            return false;
        }
        return user.grants().stream().anyMatch(grant -> isPocketHiveGrant(grant, permissions));
    }

    public static boolean hasPermissionInScope(AuthenticatedUserDto user,
                                               Set<String> permissions,
                                               String bundlePath,
                                               String folderPath) {
        if (user == null) {
            return false;
        }
        String normalizedBundlePath = normalize(bundlePath);
        String normalizedFolderPath = normalize(folderPath);
        return user.grants().stream().anyMatch(grant ->
            isPocketHiveGrant(grant, permissions)
                && matchesScope(grant, normalizedBundlePath, normalizedFolderPath));
    }

    private static boolean isPocketHiveGrant(AuthGrantDto grant, Set<String> permissions) {
        return grant.product() == AuthProduct.POCKETHIVE
            && permissions.contains(grant.permission());
    }

    private static boolean matchesScope(AuthGrantDto grant, String bundlePath, String folderPath) {
        return switch (grant.resourceType()) {
            case PocketHiveResourceTypes.DEPLOYMENT ->
                Objects.equals(grant.resourceSelector(), PocketHiveResourceSelectors.GLOBAL);
            case PocketHiveResourceTypes.FOLDER ->
                matchesFolderSelector(grant.resourceSelector(), folderPath);
            case PocketHiveResourceTypes.BUNDLE ->
                Objects.equals(grant.resourceSelector(), bundlePath);
            default -> false;
        };
    }

    private static boolean matchesFolderSelector(String selector, String folderPath) {
        if (selector == null || selector.isBlank() || folderPath == null || folderPath.isBlank()) {
            return false;
        }
        return folderPath.equals(selector) || folderPath.startsWith(selector + "/");
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
