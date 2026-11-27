package io.pockethive.worker.sdk.auth;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Responsibility: select the first auth profile location in the existing template-root search order.
 * Must not: access files, decode profiles, resolve secrets or retry selected-file failures.
 * Contract: RESP-WORK-AUTH-PROFILE-LOADING — docs/architecture/runtime-responsibilities.md#resp-work-auth-profile-loading.
 */
public final class TemplateAuthProfileLocation {
    private TemplateAuthProfileLocation() {}

    public static Optional<Path> forTemplates(String templateRoot, Path defaultProfile,
                                               Predicate<Path> regularFile) {
        Objects.requireNonNull(defaultProfile, "defaultProfile");
        Objects.requireNonNull(regularFile, "regularFile");
        if (templateRoot != null && !templateRoot.isBlank()) {
            Path cursor = Path.of(templateRoot).toAbsolutePath().normalize();
            while (cursor != null) {
                Path candidate = cursor.resolve(defaultProfile.getFileName());
                if (regularFile.test(candidate)) return Optional.of(candidate);
                cursor = cursor.getParent();
            }
        }
        return regularFile.test(defaultProfile) ? Optional.of(defaultProfile) : Optional.empty();
    }
}
