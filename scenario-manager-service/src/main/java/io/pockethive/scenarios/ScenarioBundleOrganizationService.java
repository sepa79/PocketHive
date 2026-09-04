package io.pockethive.scenarios;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;

/**
 * Responsibility: Own top-level scenario bundle folder creation, deletion, and bundle relocation.
 * Must not: Discover bundles, own catalogue state, or edit content inside a bundle.
 * Contract: docs/scenarios/SCENARIO_MANAGER_BUNDLE_REST.md.
 */
@Service
public class ScenarioBundleOrganizationService {
    private static final String DEFAULT_UPLOAD_FOLDER = "bundles";

    private final ScenarioService scenarios;

    public ScenarioBundleOrganizationService(ScenarioService scenarios) {
        this.scenarios = scenarios;
    }

    public List<String> listFolders() throws IOException {
        synchronized (scenarios) {
            Path root = scenarios.bundleRootDirectory();
            Files.createDirectories(root);
            Set<Path> bundleRoots = scenarios.scenarioBundleRoots();
            List<String> folders = new ArrayList<>();
            try (Stream<Path> stream = Files.walk(root)) {
                for (Path path : (Iterable<Path>) stream::iterator) {
                    if (!Files.isDirectory(path)) {
                        continue;
                    }
                    Path normalized = path.toAbsolutePath().normalize();
                    if (normalized.equals(root) || isReserved(normalized) || isUnderBundle(normalized, bundleRoots)) {
                        continue;
                    }
                    String relative = root.relativize(normalized).toString().replace('\\', '/');
                    if (!relative.isBlank()) {
                        folders.add(relative);
                    }
                }
            }
            folders.sort(String::compareTo);
            return folders;
        }
    }

    public void createFolder(String folderPath) throws IOException {
        synchronized (scenarios) {
            Path directory = resolveFolder(folderPath, false);
            if (isUnderBundle(directory, scenarios.scenarioBundleRoots())) {
                throw new IllegalArgumentException("Folder path is inside a scenario bundle");
            }
            Files.createDirectories(directory);
        }
    }

    public void deleteFolder(String folderPath) throws IOException {
        synchronized (scenarios) {
            Path directory = resolveFolder(folderPath, false);
            if (!Files.isDirectory(directory)) {
                throw new IllegalArgumentException("Folder not found");
            }
            if (isUnderBundle(directory, scenarios.scenarioBundleRoots())) {
                throw new IllegalArgumentException("Folder path is inside a scenario bundle");
            }
            try (Stream<Path> entries = Files.list(directory)) {
                if (entries.findAny().isPresent()) {
                    throw new IllegalArgumentException("Folder must be empty");
                }
            }
            Files.delete(directory);
        }
    }

    public void moveScenario(String scenarioId, String folderPath) throws IOException {
        if (scenarioId == null || scenarioId.isBlank()) {
            throw new IllegalArgumentException("scenarioId must not be blank");
        }
        synchronized (scenarios) {
            Path current = scenarios.bundleDirFor(scenarioId);
            if (!Files.isDirectory(current)) {
                throw new IllegalArgumentException("Scenario '%s' has no bundle directory".formatted(scenarioId));
            }
            move(current, resolveFolder(folderPath, true), scenarios.bundleDir(scenarioId).getFileName());
        }
    }

    public void moveBundle(String bundleKey, String folderPath) throws IOException {
        synchronized (scenarios) {
            ScenarioBundleWorkspaceLocation location = scenarios.bundleWorkspaceLocation(bundleKey);
            if (location.root() == null || !Files.isDirectory(location.root())) {
                throw new IllegalArgumentException("Bundle '%s' not found".formatted(bundleKey));
            }
            move(location.root(), resolveFolder(folderPath, true), location.root().getFileName());
        }
    }

    public void deleteBundle(String bundleKey) throws IOException {
        synchronized (scenarios) {
            ScenarioBundleWorkspaceLocation location = scenarios.bundleWorkspaceLocation(bundleKey);
            if (location.root() == null || !Files.isDirectory(location.root())) {
                throw new IllegalArgumentException("Bundle '%s' not found".formatted(bundleKey));
            }
            ScenarioFileTreeOperations.clear(location.root());
            Files.deleteIfExists(location.root());
            scenarios.reload();
        }
    }

    Path defaultUploadDirectory(String scenarioId) {
        Path parent = resolveFolder(DEFAULT_UPLOAD_FOLDER, false);
        Path target = parent.resolve(scenarios.bundleDir(scenarioId).getFileName()).normalize();
        if (!target.startsWith(scenarios.bundleRootDirectory())) {
            throw new IllegalArgumentException("Invalid scenario id");
        }
        return target;
    }

    private void move(Path current, Path targetParent, Path targetName) throws IOException {
        Set<Path> roots = scenarios.scenarioBundleRoots();
        if (isUnderBundle(targetParent, roots)) {
            throw new IllegalArgumentException("Target folder is inside a scenario bundle");
        }
        Files.createDirectories(targetParent);
        Path target = targetParent.resolve(targetName).toAbsolutePath().normalize();
        if (target.equals(current.toAbsolutePath().normalize())) {
            return;
        }
        if (Files.exists(target)) {
            throw new IllegalArgumentException("Target already exists");
        }
        Files.move(current, target);
        scenarios.reload();
    }

    private Path resolveFolder(String folderPath, boolean allowRoot) {
        String trimmed = folderPath == null ? "" : folderPath.trim();
        if (trimmed.isEmpty()) {
            if (!allowRoot) {
                throw new IllegalArgumentException("Folder path must not be empty");
            }
            return scenarios.bundleRootDirectory();
        }
        if (trimmed.startsWith("/") || trimmed.contains("..")) {
            throw new IllegalArgumentException("Invalid folder path");
        }
        Path resolved = scenarios.bundleRootDirectory();
        for (String raw : trimmed.split("/")) {
            String segment = raw.trim();
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("Invalid folder path");
            }
            if (!segment.matches("[a-zA-Z0-9._-]+")) {
                throw new IllegalArgumentException("Invalid folder path segment '%s'".formatted(segment));
            }
            resolved = resolved.resolve(segment);
        }
        Path normalized = resolved.normalize();
        if (!normalized.startsWith(scenarios.bundleRootDirectory())) {
            throw new IllegalArgumentException("Invalid folder path");
        }
        if (isReserved(normalized)) {
            throw new IllegalArgumentException("Folder path is reserved");
        }
        return normalized;
    }

    private boolean isUnderBundle(Path path, Set<Path> bundleRoots) {
        if (path == null || bundleRoots.isEmpty()) {
            return false;
        }
        Path current = path.toAbsolutePath().normalize();
        while (current != null && current.startsWith(scenarios.bundleRootDirectory())) {
            if (bundleRoots.contains(current)) {
                return true;
            }
            current = current.getParent();
        }
        return false;
    }

    private boolean isReserved(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (normalized.startsWith(scenarios.testStorageDirectory())) {
            return true;
        }
        Path runtimeRoot = scenarios.runtimeRootDirectory();
        return runtimeRoot.startsWith(scenarios.bundleRootDirectory()) && normalized.startsWith(runtimeRoot);
    }
}
