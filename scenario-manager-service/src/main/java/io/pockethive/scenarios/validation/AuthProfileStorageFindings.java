package io.pockethive.scenarios.validation;

import io.pockethive.worker.sdk.auth.AuthStorageMode;
import io.pockethive.worker.sdk.auth.AuthTokenKeys;
import io.pockethive.worker.sdk.auth.AuthType;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Responsibility: interpret authored profile type/storage fields and project shared policy failures into bundle findings.
 * Must not: read secrets, validate resolved signing configuration or acquire tokens.
 * Contract: RESP-SCENARIO-AUTH-STORAGE-FINDINGS — docs/architecture/runtime-responsibilities.md#resp-scenario-auth-storage-findings.
 */
final class AuthProfileStorageFindings {
    void validate(
        String relativePath,
        String profileId,
        Map<?, ?> profile,
        List<ValidationFinding> findings
    ) {
        String rawType = stringValue(profile.get("type"));
        AuthType type;
        try {
            type = AuthType.parse(rawType);
        } catch (IllegalArgumentException e) {
            findings.add(ValidationIssue.AUTH_PROFILES_INVALID.finding(
                ValidationSeverity.ERROR,
                relativePath + ":profiles." + profileId + ".type",
                "Auth profile '%s' declares unsupported type '%s'.".formatted(profileId, nullToBlank(rawType)),
                "Use one of: %s.".formatted(String.join(", ", supportedAuthTypeValues()))));
            return;
        }
        if (type == AuthType.NONE) {
            findings.add(ValidationIssue.AUTH_PROFILES_INVALID.finding(
                ValidationSeverity.ERROR,
                relativePath + ":profiles." + profileId + ".type",
                "Auth profile '%s' must declare type.".formatted(profileId),
                "Set a concrete auth profile type."));
            return;
        }

        Object storageValue = profile.get("storage");
        Map<?, ?> storage = storageValue instanceof Map<?, ?> map ? map : Map.of();
        String rawMode = stringValue(storage.get("mode"));
        AuthStorageMode mode = AuthStorageMode.NONE;
        if (rawMode != null && !rawMode.isBlank()) {
            try {
                mode = AuthStorageMode.valueOf(normalizeEnumName(rawMode));
            } catch (IllegalArgumentException e) {
                findings.add(ValidationIssue.AUTH_STORAGE_INVALID.finding(
                    ValidationSeverity.ERROR,
                    relativePath + ":profiles." + profileId + ".storage.mode",
                    "Auth profile '%s' declares unsupported storage.mode '%s'.".formatted(profileId, rawMode),
                    "Use one of: %s.".formatted(String.join(", ", supportedAuthStorageModeValues()))));
                return;
            }
        }
        boolean refreshable = type.requiredStorageMode() == AuthStorageMode.REDIS;
        if (refreshable && mode != AuthStorageMode.REDIS) {
            findings.add(ValidationIssue.AUTH_STORAGE_INVALID.finding(
                ValidationSeverity.ERROR,
                relativePath + ":profiles." + profileId + ".storage.mode",
                "Refreshable auth profile '%s' must use storage.mode=REDIS.".formatted(profileId),
                "Set storage.mode: REDIS and provide a tokenKey."));
        }
        if (mode == AuthStorageMode.REDIS) {
            String tokenKey = stringValue(storage.get("tokenKey"));
            try {
                AuthTokenKeys.validateTokenKey(tokenKey);
            } catch (IllegalArgumentException e) {
                findings.add(ValidationIssue.AUTH_STORAGE_INVALID.finding(
                    ValidationSeverity.ERROR,
                    relativePath + ":profiles." + profileId + ".storage.tokenKey",
                    "Auth profile '%s' must declare a valid storage.tokenKey.".formatted(profileId),
                    "Use a non-blank tokenKey matching [A-Za-z0-9._:-]{1,128} without '..'."));
            }
        }
        if (!refreshable && mode != AuthStorageMode.NONE) {
            findings.add(ValidationIssue.AUTH_STORAGE_INVALID.finding(
                ValidationSeverity.ERROR,
                relativePath + ":profiles." + profileId + ".storage.mode",
                "Non-refresh auth profile '%s' must use storage.mode=NONE.".formatted(profileId),
                "Set storage.mode: NONE."));
        }
    }

    private String stringValue(Object value) {
        return value instanceof String text ? text.trim() : null;
    }

    private String normalizeEnumName(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
    }

    private List<String> supportedAuthTypeValues() {
        return Arrays.stream(AuthType.values()).filter(type -> type != AuthType.NONE).map(AuthType::key).toList();
    }

    private List<String> supportedAuthStorageModeValues() {
        return Arrays.stream(AuthStorageMode.values()).map(Enum::name).toList();
    }

    private String nullToBlank(String value) {
        return value == null ? "" : value;
    }
}
