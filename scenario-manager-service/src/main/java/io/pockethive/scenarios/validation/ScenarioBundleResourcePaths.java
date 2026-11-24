package io.pockethive.scenarios.validation;

import io.pockethive.scenarios.ScenarioBundleLayout;
import io.pockethive.swarm.model.ScenarioConfigurationPreparation;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Responsibility: map declared resource roots, check relative membership and report deferred bootstrap roots.
 * Must not: read files, select templates or resolve external paths.
 * Contract: RESP-SCENARIO-VALIDATE — docs/architecture/runtime-responsibilities.md#resp-scenario-validate.
 * Consumes: RESP-SCENARIO-BUNDLE-LAYOUT and RESP-SCENARIO-CONFIGURATION-PREPARATION.
 */
final class ScenarioBundleResourcePaths {
    private ScenarioBundleResourcePaths() {}

    static Optional<String> relativeRoot(String location, String path, List<ValidationFinding> findings) {
        if (location != null && !ScenarioConfigurationPreparation.variableNames(Map.of("root", location)).isEmpty()) {
            findings.add(ValidationIssue.WORK_CONFIGURATION_DEFERRED.finding(ValidationSeverity.WARNING, path,
                "Resource source validation is deferred until configuration variables in the root are resolved."));
            return Optional.empty();
        }
        String root = normalizeContainerPath(location);
        if (root == null || root.isBlank()) return Optional.empty();
        if (ScenarioBundleLayout.CONTAINER_ROOT.equals(root)) return Optional.of("");
        String prefix = ScenarioBundleLayout.CONTAINER_ROOT + "/";
        if (!root.startsWith(prefix)) return Optional.empty();
        Path relative = Path.of(root.substring(prefix.length())).normalize();
        String normalized = relative.toString().replace('\\', '/');
        if (".".equals(normalized)) normalized = "";
        if (relative.isAbsolute() || relative.startsWith("..")) {
            findings.add(ValidationIssue.TEMPLATE_INVALID.finding(ValidationSeverity.ERROR, path,
                "Resource root '" + location + "' escapes " + ScenarioBundleLayout.CONTAINER_ROOT,
                "Set a contained bundle-relative resource directory."));
            return Optional.empty();
        }
        return Optional.of(normalized);
    }

    static boolean isUnderRelativeRoot(String relativePath, String relativeRoot) {
        if (relativeRoot == null || relativeRoot.isBlank()) return true;
        return relativePath.equals(relativeRoot) || relativePath.startsWith(relativeRoot + "/");
    }

    private static String normalizeContainerPath(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().replace('\\', '/');
        while (normalized.contains("//")) {
            normalized = normalized.replace("//", "/");
        }
        while (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

}
