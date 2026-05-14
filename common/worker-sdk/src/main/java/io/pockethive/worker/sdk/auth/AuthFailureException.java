package io.pockethive.worker.sdk.auth;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

public final class AuthFailureException extends RuntimeException {
    private static final Pattern BEARER = Pattern.compile("Bearer\\s+[A-Za-z0-9._:-]+");
    private static final Pattern SENSITIVE_ASSIGNMENT = Pattern.compile(
        "(?i)(token|secret|password|privateKey|certificate|clientSecret)(\\s*['\\\"]?[:=]\\s*['\\\"]?)[^\\s,'\\\"]+"
    );

    private final String reasonCode;
    private final String stage;
    private final String profileId;
    private final String applyAs;

    private AuthFailureException(
        String reasonCode,
        String stage,
        String profileId,
        String applyAs,
        String message,
        Throwable cause
    ) {
        super(redact(message), cause);
        this.reasonCode = normalize(reasonCode, "auth-failure");
        this.stage = normalize(stage, "unknown");
        this.profileId = normalize(profileId, "unknown");
        this.applyAs = normalize(applyAs, "unknown");
    }

    public static AuthFailureException application(AuthRef ref, String stage, Throwable cause) {
        if (cause instanceof AuthFailureException authFailure) {
            return authFailure;
        }
        String profileId = ref == null ? "unknown" : ref.profileId();
        String applyAs = ref == null || ref.applyAs() == null ? "unknown" : ref.applyAs().name();
        return new AuthFailureException(
            "auth-application",
            stage,
            profileId,
            applyAs,
            message(cause),
            cause
        );
    }

    public static AuthFailureException configuration(String reasonCode, String message, Throwable cause) {
        if (cause instanceof AuthFailureException authFailure) {
            return authFailure;
        }
        return new AuthFailureException(reasonCode, "configuration", "unknown", "unknown", message, cause);
    }

    public static Optional<AuthFailureException> find(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor != null) {
            if (cursor instanceof AuthFailureException authFailure) {
                return Optional.of(authFailure);
            }
            cursor = cursor.getCause();
        }
        return Optional.empty();
    }

    public String reasonCode() {
        return reasonCode;
    }

    public String stage() {
        return stage;
    }

    public String profileId() {
        return profileId;
    }

    public String applyAs() {
        return applyAs;
    }

    public AuthFailureException summary(int count) {
        return new AuthFailureException(
            reasonCode,
            stage,
            profileId,
            applyAs,
            getMessage() + " (repeated " + Math.max(1, count) + " times)",
            this
        );
    }

    public String dedupeKey() {
        return reasonCode + ":" + stage + ":" + profileId + ":" + applyAs + ":" + getMessage();
    }

    private static String message(Throwable cause) {
        if (cause == null || cause.getMessage() == null || cause.getMessage().isBlank()) {
            return "Auth failure";
        }
        return cause.getMessage();
    }

    private static String redact(String value) {
        if (value == null || value.isBlank()) {
            return "Auth failure";
        }
        String redacted = BEARER.matcher(value).replaceAll("Bearer <redacted>");
        redacted = SENSITIVE_ASSIGNMENT.matcher(redacted).replaceAll("$1$2<redacted>");
        return redacted;
    }

    private static String normalize(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
