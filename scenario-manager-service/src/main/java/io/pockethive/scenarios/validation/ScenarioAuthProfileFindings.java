package io.pockethive.scenarios.validation;

import io.pockethive.scenarios.ScenarioBundleLayout;
import io.pockethive.worker.sdk.auth.AuthProfileTemplateValues;
import io.pockethive.worker.sdk.auth.TemplateAuthProfileLocation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Responsibility: project selected auth-profile contracts and template dependencies into bundle findings.
 * Must not: discover request roots, decode requests, read secrets, merge profiles or prepare credentials.
 * Contract: RESP-SCENARIO-VALIDATE — docs/architecture/runtime-responsibilities.md#resp-scenario-validate.
 * Consumes: RESP-WORK-AUTH-PROFILE-LOADING and RESP-WORK-AUTH-PROFILE-PREPARATION.
 */
final class ScenarioAuthProfileFindings {
    private final RequestTemplateFindings requests;
    private final AuthProfileStorageFindings storage = new AuthProfileStorageFindings();

    ScenarioAuthProfileFindings(RequestTemplateFindings requests) {
        this.requests = requests;
    }

    List<ValidationFinding> validate(Path bundleRoot, List<String> requestRoots,
                                      Map<String, Map<?, ?>> documents,
                                      Function<Path, Map<?, ?>> reader,
                                      ScenarioTemplateAnalysis analysis) {
        Path root = bundleRoot.toAbsolutePath().normalize();
        Path defaultProfile = ScenarioBundleLayout.authProfilesFile(root);
        List<ValidationFinding> findings = new ArrayList<>();
        List<AuthRefUsage> refs = new ArrayList<>();
        documents.forEach((path, document) -> {
            var ref = requests.authReference(document, path, findings);
            if (ref != null) refs.add(new AuthRefUsage(path, ref.profileId()));
        });
        Map<Path, AuthProfilesInfo> profilesByFile = new LinkedHashMap<>();
        if (Files.isRegularFile(defaultProfile)) {
            profilesByFile.put(defaultProfile, readProfiles(defaultProfile, root, reader, findings, analysis));
        }
        for (String requestRoot : new LinkedHashSet<>(requestRoots)) {
            List<AuthRefUsage> visibleRefs = refs.stream()
                .filter(ref -> ScenarioBundleResourcePaths.isUnderRelativeRoot(ref.path(), requestRoot)).toList();
            if (visibleRefs.isEmpty()) continue;
            var selected = TemplateAuthProfileLocation.forTemplates(root.resolve(requestRoot).toString(),
                defaultProfile, candidate -> candidate.startsWith(root) && Files.isRegularFile(candidate));
            if (selected.isEmpty()) {
                findings.add(ValidationIssue.AUTH_PROFILES_MISSING.finding(
                    ValidationSeverity.ERROR, ScenarioBundleLayout.AUTH_PROFILES_FILE,
                    "Templates under '%s' declare authRef but %s is missing from their root and bundle ancestors."
                        .formatted(requestRoot, ScenarioBundleLayout.AUTH_PROFILES_FILE),
                    "Create %s in the configured template root or a bundle ancestor."
                        .formatted(ScenarioBundleLayout.AUTH_PROFILES_FILE)));
                continue;
            }
            Path file = selected.get();
            AuthProfilesInfo profiles = profilesByFile.computeIfAbsent(file,
                path -> readProfiles(path, root, reader, findings, analysis));
            if (!profiles.parsed()) continue;
            String relativePath = relativePath(root, file);
            for (AuthRefUsage ref : visibleRefs) {
                if (!profiles.profileIds().contains(ref.profileId())) {
                    findings.add(ValidationIssue.AUTH_REF_PROFILE_MISSING.finding(
                        ValidationSeverity.ERROR, ref.path() + ":authRef.profileId",
                        "authRef.profileId '%s' is not declared in %s.".formatted(ref.profileId(), relativePath),
                        "Add profile '%s' to %s or correct authRef.profileId."
                            .formatted(ref.profileId(), relativePath)));
                }
            }
        }
        return List.copyOf(findings);
    }

    private AuthProfilesInfo readProfiles(Path file, Path root, Function<Path, Map<?, ?>> reader,
                                          List<ValidationFinding> findings, ScenarioTemplateAnalysis analysis) {
        String relativePath = relativePath(root, file);
        Map<?, ?> doc;
        try {
            doc = reader.apply(file);
        } catch (Exception failure) {
            String message = failure.getMessage();
            findings.add(ValidationIssue.AUTH_PROFILES_INVALID.finding(
                ValidationSeverity.ERROR, relativePath,
                "%s could not be parsed: %s".formatted(relativePath,
                    message == null || message.isBlank() ? "Validation failed."
                        : message.replace('\n', ' ').replace('\r', ' ').trim()),
                "Fix %s YAML syntax and duplicate keys.".formatted(relativePath)));
            return new AuthProfilesInfo(Set.of(), false);
        }
        Object profilesValue = doc.get("profiles");
        if (!(profilesValue instanceof Map<?, ?> profiles) || profiles.isEmpty()) {
            findings.add(ValidationIssue.AUTH_PROFILES_INVALID.finding(
                ValidationSeverity.ERROR, relativePath + ":profiles",
                "%s must declare a non-empty profiles map.".formatted(relativePath),
                "Add profiles.<profileId> entries."));
            return new AuthProfilesInfo(Set.of(), true);
        }
        Set<String> profileIds = new LinkedHashSet<>();
        for (Map.Entry<?, ?> entry : profiles.entrySet()) {
            String profileId = String.valueOf(entry.getKey());
            profileIds.add(profileId);
            if (!(entry.getValue() instanceof Map<?, ?> profile)) {
                findings.add(ValidationIssue.AUTH_PROFILES_INVALID.finding(
                    ValidationSeverity.ERROR, relativePath + ":profiles." + profileId,
                    "Auth profile '%s' must be an object.".formatted(profileId),
                    "Declare type and storage for profile '%s'.".formatted(profileId)));
                continue;
            }
            storage.validate(relativePath, profileId, profile, findings);
            AuthProfileTemplateValues.sources(profile).forEach(source ->
                analysis.add(relativePath + ":profiles." + profileId, source));
        }
        return new AuthProfilesInfo(Set.copyOf(profileIds), true);
    }

    private static String relativePath(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    private record AuthRefUsage(String path, String profileId) {}
    private record AuthProfilesInfo(Set<String> profileIds, boolean parsed) {}
}
