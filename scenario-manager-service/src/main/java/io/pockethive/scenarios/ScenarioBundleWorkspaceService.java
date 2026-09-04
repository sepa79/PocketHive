package io.pockethive.scenarios;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

/**
 * Responsibility: Own safe tree, file, folder, and download operations inside a discovered scenario bundle.
 * Must not: Discover bundles, own catalogue state, authorize callers, or interpret scenario domain content.
 * Contract: docs/scenarios/SCENARIO_MANAGER_BUNDLE_REST.md.
 */
@Service
public class ScenarioBundleWorkspaceService {
    private static final String NODE_TYPE_DIRECTORY = "directory";
    private static final String NODE_TYPE_FILE = "file";
    private static final String EDITOR_KIND_TEXT = "text";
    private static final String EDITOR_KIND_YAML = "yaml";
    private static final String EDITOR_KIND_JSON = "json";
    private static final String EDITOR_KIND_MARKDOWN = "markdown";
    private static final String EDITOR_KIND_UNSUPPORTED = "unsupported";

    private final ScenarioService scenarios;

    public ScenarioBundleWorkspaceService(ScenarioService scenarios) {
        this.scenarios = scenarios;
    }

    public BundleDownload download(String bundleKey) throws IOException {
        synchronized (scenarios) {
            ScenarioBundleWorkspaceLocation location = scenarios.bundleWorkspaceLocation(bundleKey);
            requireDirectory(location);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(out);
                 Stream<Path> paths = Files.walk(location.root())) {
                for (Path path : (Iterable<Path>) paths::iterator) {
                    if (Files.isDirectory(path)) {
                        continue;
                    }
                    String entryName = relativePath(location.root(), path);
                    zip.putNextEntry(new ZipEntry(entryName));
                    Files.copy(path, zip);
                    zip.closeEntry();
                }
            }
            return new BundleDownload(
                out.toByteArray(),
                scenarios.fallbackBundleName(location.bundlePath()) + "-bundle.zip");
        }
    }

    public BundleTree readTree(String bundleKey) throws IOException {
        synchronized (scenarios) {
            ScenarioBundleWorkspaceLocation location = scenarios.bundleWorkspaceLocation(bundleKey);
            requireDirectory(location);
            List<BundleTreeNode> nodes = new ArrayList<>();
            try (Stream<Path> paths = Files.walk(location.root())) {
                paths.filter(path -> !path.equals(location.root()))
                    .sorted(Comparator
                        .comparing((Path path) -> !Files.isDirectory(path))
                        .thenComparing(path -> relativePath(location.root(), path)))
                    .forEach(path -> nodes.add(treeNode(location.bundleKey(), location.root(), path)));
            }
            return new BundleTree(location.bundleKey(), nodes);
        }
    }

    public BundleFilePayload readFile(String bundleKey, String relativePath) throws IOException {
        synchronized (scenarios) {
            return readFile(scenarios.bundleWorkspaceLocation(bundleKey), relativePath);
        }
    }

    public BundleFileWriteResult writeFile(
        String bundleKey,
        String relativePath,
        String content,
        String expectedRevision
    ) throws IOException {
        synchronized (scenarios) {
            ScenarioBundleWorkspaceLocation location = scenarios.bundleWorkspaceLocation(bundleKey);
            Path file = existingPath(location, relativePath);
            if (!Files.isRegularFile(file)) {
                throw new IllegalArgumentException("Bundle path is not a file");
            }
            requireEditable(file);
            String currentRevision = revision(file);
            if (expectedRevision != null
                && !expectedRevision.isBlank()
                && !currentRevision.equals(expectedRevision.trim())) {
                throw new WorkspaceConflictException("File revision is stale");
            }
            Files.writeString(file, content != null ? content : "");
            scenarios.reload();
            return new BundleFileWriteResult(revision(file));
        }
    }

    public BundleFilePayload createFile(String bundleKey, String relativePath, String content) throws IOException {
        synchronized (scenarios) {
            ScenarioBundleWorkspaceLocation location = scenarios.bundleWorkspaceLocation(bundleKey);
            Path file = targetPath(location, relativePath);
            requireEditable(file);
            if (Files.exists(file)) {
                throw new WorkspaceConflictException("Bundle path already exists");
            }
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, content != null ? content : "");
            scenarios.reload();
            return readFile(scenarios.bundleWorkspaceLocation(bundleKey), relativePath(location.root(), file));
        }
    }

    public void createFolder(String bundleKey, String relativePath) throws IOException {
        synchronized (scenarios) {
            ScenarioBundleWorkspaceLocation location = scenarios.bundleWorkspaceLocation(bundleKey);
            Path folder = targetPath(location, relativePath);
            if (Files.exists(folder)) {
                throw new WorkspaceConflictException("Bundle path already exists");
            }
            Files.createDirectories(folder);
            scenarios.reload();
        }
    }

    public void renameEntry(String bundleKey, String relativePath, String name) throws IOException {
        synchronized (scenarios) {
            ScenarioBundleWorkspaceLocation location = scenarios.bundleWorkspaceLocation(bundleKey);
            Path source = existingPath(location, relativePath);
            String targetName = normalizedEntryName(name);
            Path parent = source.getParent();
            if (parent == null) {
                throw new IllegalArgumentException("Cannot rename bundle root");
            }
            Path target = parent.resolve(targetName).normalize();
            if (!target.startsWith(location.root())) {
                throw new IllegalArgumentException("Invalid bundle path");
            }
            if (source.equals(target)) {
                return;
            }
            if (Files.exists(target)) {
                throw new WorkspaceConflictException("Bundle path already exists");
            }
            Files.move(source, target);
            scenarios.reload();
        }
    }

    public void deleteEntry(String bundleKey, String relativePath) throws IOException {
        synchronized (scenarios) {
            ScenarioBundleWorkspaceLocation location = scenarios.bundleWorkspaceLocation(bundleKey);
            Path target = existingPath(location, relativePath);
            if (target.equals(location.root())) {
                throw new IllegalArgumentException("Cannot delete bundle root");
            }
            if (Files.isDirectory(target)) {
                try (Stream<Path> children = Files.list(target)) {
                    if (children.findAny().isPresent()) {
                        throw new WorkspaceConflictException("Bundle folder is not empty");
                    }
                }
            }
            Files.delete(target);
            scenarios.reload();
        }
    }

    private BundleFilePayload readFile(ScenarioBundleWorkspaceLocation location, String relativePath) throws IOException {
        Path file = existingPath(location, relativePath);
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("Bundle path is not a file");
        }
        String editorKind = editorKind(file);
        byte[] bytes = Files.readAllBytes(file);
        return new BundleFilePayload(
            location.bundleKey(),
            relativePath(location.root(), file),
            file.getFileName().toString(),
            mediaType(file, editorKind),
            editorKind,
            !EDITOR_KIND_UNSUPPORTED.equals(editorKind),
            bytes.length,
            "sha256:" + sha256Hex(bytes),
            EDITOR_KIND_UNSUPPORTED.equals(editorKind) ? null : Files.readString(file));
    }

    private BundleTreeNode treeNode(String bundleKey, Path root, Path path) {
        boolean directory = Files.isDirectory(path);
        String editorKind = directory ? EDITOR_KIND_UNSUPPORTED : editorKind(path);
        return new BundleTreeNode(
            bundleKey,
            relativePath(root, path),
            path.getFileName().toString(),
            directory ? NODE_TYPE_DIRECTORY : NODE_TYPE_FILE,
            directory ? null : mediaType(path, editorKind),
            editorKind,
            !directory && !EDITOR_KIND_UNSUPPORTED.equals(editorKind),
            directory ? null : safeSize(path));
    }

    private Path existingPath(ScenarioBundleWorkspaceLocation location, String relativePath) {
        Path resolved = targetPath(location, relativePath);
        if (!Files.exists(resolved)) {
            throw new IllegalArgumentException("Bundle path not found");
        }
        return resolved;
    }

    private Path targetPath(ScenarioBundleWorkspaceLocation location, String relativePath) {
        requireDirectory(location);
        String normalized = normalizedRelativePath(relativePath);
        Path resolved = location.root().resolve(normalized).normalize();
        if (!resolved.startsWith(location.root())) {
            throw new IllegalArgumentException("Invalid bundle path");
        }
        return resolved;
    }

    private void requireDirectory(ScenarioBundleWorkspaceLocation location) {
        if (location.root() == null || !Files.isDirectory(location.root())) {
            throw new IllegalArgumentException("Bundle '%s' not found".formatted(location.bundleKey()));
        }
    }

    private String normalizedRelativePath(String relativePath) {
        String trimmed = relativePath == null ? "" : relativePath.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("/") || trimmed.contains("\\") || trimmed.contains("..")) {
            throw new IllegalArgumentException("Invalid bundle path");
        }
        for (String segment : trimmed.split("/")) {
            if (segment.isBlank() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("Invalid bundle path");
            }
        }
        return trimmed;
    }

    private String normalizedEntryName(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()
            || trimmed.contains("/")
            || trimmed.contains("\\")
            || trimmed.equals(".")
            || trimmed.equals("..")
            || trimmed.contains("..")) {
            throw new IllegalArgumentException("Invalid bundle entry name");
        }
        return trimmed;
    }

    private void requireEditable(Path file) {
        if (EDITOR_KIND_UNSUPPORTED.equals(editorKind(file))) {
            throw new WorkspaceUnsupportedMediaTypeException("Bundle file type is not editable");
        }
    }

    private static String relativePath(Path root, Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }

    private static Long safeSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return null;
        }
    }

    private static String editorKind(Path path) {
        String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (fileName.endsWith(".yaml") || fileName.endsWith(".yml")) {
            return EDITOR_KIND_YAML;
        }
        if (fileName.endsWith(".json")) {
            return EDITOR_KIND_JSON;
        }
        if (fileName.endsWith(".md") || fileName.endsWith(".markdown")) {
            return EDITOR_KIND_MARKDOWN;
        }
        if (fileName.endsWith(".txt")
            || fileName.endsWith(".csv")
            || fileName.endsWith(".properties")
            || fileName.endsWith(".env")
            || fileName.endsWith(".xml")
            || fileName.endsWith(".http")) {
            return EDITOR_KIND_TEXT;
        }
        return EDITOR_KIND_UNSUPPORTED;
    }

    private static String mediaType(Path path, String editorKind) {
        if (EDITOR_KIND_YAML.equals(editorKind)) {
            return "application/x-yaml";
        }
        if (EDITOR_KIND_JSON.equals(editorKind)) {
            return MediaType.APPLICATION_JSON_VALUE;
        }
        if (EDITOR_KIND_MARKDOWN.equals(editorKind)) {
            return "text/markdown";
        }
        if (EDITOR_KIND_TEXT.equals(editorKind)) {
            return MediaType.TEXT_PLAIN_VALUE;
        }
        try {
            String probed = Files.probeContentType(path);
            return probed != null && !probed.isBlank() ? probed : "application/octet-stream";
        } catch (IOException e) {
            return "application/octet-stream";
        }
    }

    private static String revision(Path file) throws IOException {
        return "sha256:" + sha256Hex(Files.readAllBytes(file));
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest is unavailable", e);
        }
    }

}
