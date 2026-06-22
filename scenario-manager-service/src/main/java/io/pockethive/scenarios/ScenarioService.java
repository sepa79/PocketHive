package io.pockethive.scenarios;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.pockethive.capabilities.CapabilityCatalogueService;
import io.pockethive.scenarios.validation.BundleValidationException;
import io.pockethive.scenarios.validation.BundleValidationInput;
import io.pockethive.scenarios.validation.BundleValidationResult;
import io.pockethive.scenarios.validation.BundleValidationSource;
import io.pockethive.scenarios.validation.ScenarioBundleValidator;
import io.pockethive.scenarios.validation.ValidationFinding;
import io.pockethive.swarm.model.SwarmTemplate;
import jakarta.annotation.PostConstruct;
import org.springframework.http.MediaType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import io.pockethive.swarm.model.SutEnvironment;

@Service
public class ScenarioService {
    private static final Logger logger = LoggerFactory.getLogger(ScenarioService.class);
    private static final String SCENARIOS_RUNTIME_ROOT = "scenarios-runtime";
    private static final String DEFAULT_UPLOAD_FOLDER = "bundles";
    private static final String QUARANTINE_FOLDER = "quarantine";
    private static final String UPLOAD_TEMP_PREFIX = "pockethive-scenario-upload-";
    private static final String NODE_TYPE_DIRECTORY = "directory";
    private static final String NODE_TYPE_FILE = "file";
    private static final String EDITOR_KIND_TEXT = "text";
    private static final String EDITOR_KIND_YAML = "yaml";
    private static final String EDITOR_KIND_JSON = "json";
    private static final String EDITOR_KIND_MARKDOWN = "markdown";
    private static final String EDITOR_KIND_UNSUPPORTED = "unsupported";
    private final Path storageDir;
    private final Path testStorageDir;
    private final Path bundleRootDir;
    private final Path runtimeRootDir;
    private final boolean showTestScenarios;
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
    private final ScenarioBundleValidator bundleValidator;
    private final Map<String, ScenarioRecord> scenarios = new ConcurrentHashMap<>();
    private volatile List<BundleCatalogEntry> bundleCatalog = List.of();
    private final Object variablesLock = new Object();

    @Autowired
    public ScenarioService(@Value("${scenarios.dir:scenarios}") String dir,
                           @Value("${scenarios.show-test:true}") boolean showTestScenarios,
                           @Value("${pockethive.images.default-tag:}") String defaultImageTag,
                           CapabilityCatalogueService capabilities) throws IOException {
        this(Paths.get(dir),
             Paths.get(SCENARIOS_RUNTIME_ROOT),
             showTestScenarios,
             normalizeTag(defaultImageTag),
             capabilities);
    }

    ScenarioService(String dir,
                    CapabilityCatalogueService capabilities) throws IOException {
        this(Paths.get(dir), Paths.get(SCENARIOS_RUNTIME_ROOT), true, null, capabilities);
    }

    ScenarioService(String dir,
                    String defaultImageTag,
                    CapabilityCatalogueService capabilities) throws IOException {
        this(Paths.get(dir),
             Paths.get(SCENARIOS_RUNTIME_ROOT),
             true,
             normalizeTag(defaultImageTag),
             capabilities);
    }

    private ScenarioService(Path dir,
                            Path runtimeRoot,
                            boolean showTestScenarios,
                            String defaultImageTag,
                            CapabilityCatalogueService capabilities) throws IOException {
        Path normalizedDir = dir.toAbsolutePath().normalize();
        this.storageDir = normalizedDir;
        this.testStorageDir = normalizedDir.resolve("e2e");
        this.bundleRootDir = normalizedDir;
        this.runtimeRootDir = runtimeRoot.toAbsolutePath().normalize();
        this.showTestScenarios = showTestScenarios;
        Files.createDirectories(this.storageDir);
        if (this.showTestScenarios) {
            Files.createDirectories(this.testStorageDir);
        }
        Files.createDirectories(this.bundleRootDir);
        Files.createDirectories(this.bundleRootDir.resolve(QUARANTINE_FOLDER));
        Files.createDirectories(this.runtimeRootDir);
        this.bundleValidator = new ScenarioBundleValidator(capabilities, defaultImageTag);
    }

    @PostConstruct
    void init() throws IOException {
        reload();
    }

    public synchronized void reload() throws IOException {
        Files.createDirectories(bundleRootDir.resolve(QUARANTINE_FOLDER));
        List<ScannedBundle> discovered = new ArrayList<>();
        Set<Path> visitedDescriptors = new HashSet<>();

        loadFromBundles(bundleRootDir, discovered, visitedDescriptors);
        if (showTestScenarios && Files.isDirectory(testStorageDir)) {
            loadFromBundles(testStorageDir, discovered, visitedDescriptors);
        }

        List<BundleCatalogEntry> catalog = buildBundleCatalog(discovered);
        Map<String, ScenarioRecord> loaded = buildScenarioIndex(catalog);

        scenarios.clear();
        scenarios.putAll(loaded);
        bundleCatalog = catalog;

        long available = loaded.values().stream().filter(record -> !record.defunct()).count();
        logger.info("Loaded {} scenario(s) from {}{} ({} available, {} bundle entries)",
            loaded.size(),
            storageDir,
            (showTestScenarios ? " and " + testStorageDir : ""),
            available,
            catalog.size());
    }

    public List<ScenarioSummary> list() {
        return listAvailableSummaries();
    }

    public List<ScenarioSummary> listAvailableSummaries() {
        return streamRecords()
                .filter(record -> !record.defunct())
                .map(this::toSummary)
                .sorted(Comparator.comparing(ScenarioSummary::name))
                .toList();
    }

    public List<ScenarioSummary> listAllSummaries() {
        return streamRecords()
                .map(this::toSummary)
                .sorted(Comparator.comparing(ScenarioSummary::name))
                .toList();
    }

    public List<ScenarioSummary> listDefunctSummaries() {
        return streamRecords()
                .filter(ScenarioRecord::defunct)
                .map(this::toSummary)
                .sorted(Comparator.comparing(ScenarioSummary::name))
                .toList();
    }

    public List<BundleTemplateSummary> listBundleTemplates() {
        return bundleCatalog.stream()
                .map(entry -> new BundleTemplateSummary(
                        entry.bundleKey(),
                        entry.bundlePath(),
                        entry.folderPath(),
                        entry.scenarioId(),
                        entry.name(),
                        entry.description(),
                        entry.controllerImage(),
                        entry.bees(),
                        entry.defunct(),
                        entry.defunctReason()))
                .sorted(Comparator
                        .comparing(BundleTemplateSummary::folderPath, Comparator.nullsFirst(String::compareTo))
                .thenComparing(BundleTemplateSummary::name)
                .thenComparing(BundleTemplateSummary::bundlePath))
                .toList();
    }

    public Optional<BundleTemplateSummary> findBundleTemplate(String scenarioId) {
        if (scenarioId == null || scenarioId.isBlank()) {
            return Optional.empty();
        }
        return bundleCatalog.stream()
                .filter(entry -> scenarioId.trim().equals(entry.scenarioId()))
                .findFirst()
                .map(this::toBundleTemplateSummary);
    }

    public Optional<ScenarioAccessDescriptor> findScenarioAccess(String scenarioId) {
        if (scenarioId == null || scenarioId.isBlank()) {
            return Optional.empty();
        }
        String normalizedScenarioId = scenarioId.trim();
        Optional<ScenarioAccessDescriptor> fromBundleCatalog = bundleCatalog.stream()
                .filter(entry -> normalizedScenarioId.equals(entry.scenarioId()))
                .findFirst()
                .map(this::toAccessDescriptor);
        if (fromBundleCatalog.isPresent()) {
            return fromBundleCatalog;
        }
        ScenarioRecord record = scenarios.get(normalizedScenarioId);
        return Optional.ofNullable(record)
                .map(value -> new ScenarioAccessDescriptor(
                        normalizedScenarioId,
                        normalizedScenarioId,
                        value.folderPath()));
    }

    public Optional<ScenarioAccessDescriptor> findBundleAccess(String bundleKey) {
        if (bundleKey == null || bundleKey.isBlank()) {
            return Optional.empty();
        }
        return bundleCatalog.stream()
                .filter(entry -> bundleKey.trim().equals(entry.bundleKey()))
                .findFirst()
                .map(this::toAccessDescriptor);
    }

    public Optional<Scenario> find(String id) {
        ScenarioRecord record = scenarios.get(id);
        return Optional.ofNullable(record).map(ScenarioRecord::scenario);
    }

    public Optional<Scenario> findAvailable(String id) {
        ScenarioRecord record = scenarios.get(id);
        if (record == null || record.defunct()) {
            return Optional.empty();
        }
        return Optional.of(record.scenario());
    }

    public boolean isDefunct(String id) {
        ScenarioRecord record = scenarios.get(id);
        return record != null && record.defunct();
    }

    public Scenario create(Scenario scenario) throws IOException {
        Scenario resolved = bundleValidator.applyDefaultImageTag(scenario);
        String id = resolved.getId();
        Path bundleDir = bundleDir(id);
        if (hasDiscoveredScenarioId(id)) {
            throw new IllegalArgumentException("Scenario already exists");
        }

        writeDescriptor(resolved, bundleDir);
        reload();
        ScenarioRecord record = scenarios.get(id);
        return record != null ? record.scenario() : resolved;
    }

    public Scenario update(String id, Scenario scenario) throws IOException {
        scenario.setId(id);
        Scenario resolved = bundleValidator.applyDefaultImageTag(scenario);
        ScenarioRecord existing = scenarios.get(id);
        Path bundleDir = existing != null && existing.bundleDir() != null ? existing.bundleDir() : bundleDir(id);
        writeDescriptor(resolved, bundleDir);
        reload();
        ScenarioRecord record = scenarios.get(id);
        return record != null ? record.scenario() : resolved;
    }

    public void delete(String id) throws IOException {
        ScenarioRecord removed = scenarios.get(id);
        if (removed == null) {
            return;
        }
        Path bundleDir = removed.bundleDir();
        if (bundleDir != null && Files.isDirectory(bundleDir)) {
            clearDirectory(bundleDir);
            Files.deleteIfExists(bundleDir);
        } else {
            Path descriptor = removed.descriptorFile();
            if (descriptor != null) {
            Files.deleteIfExists(descriptor);
            }
        }
        reload();
    }

	    public synchronized List<String> listBundleFolders() throws IOException {
	        Files.createDirectories(bundleRootDir);
	        Set<Path> scenarioRoots = scenarioBundleRoots();
	        List<String> folders = new ArrayList<>();
	        try (Stream<Path> stream = Files.walk(bundleRootDir)) {
	            for (Path path : (Iterable<Path>) stream::iterator) {
	                if (!Files.isDirectory(path)) {
	                    continue;
	                }
	                Path normalized = path.toAbsolutePath().normalize();
	                if (normalized.equals(bundleRootDir.toAbsolutePath().normalize())) {
	                    continue;
	                }
	                if (isReservedWorkspaceDirectory(normalized)) {
	                    continue;
	                }
	                if (isUnderAnyScenarioRoot(normalized, scenarioRoots)) {
	                    continue;
	                }
	                String rel = bundleRootDir.toAbsolutePath().normalize().relativize(normalized).toString().replace('\\', '/');
	                if (!rel.isBlank()) {
	                    folders.add(rel);
                }
            }
        }
        folders.sort(String::compareTo);
        return folders;
    }

    public synchronized void createBundleFolder(String folderPath) throws IOException {
        Path dir = resolveBundleFolder(folderPath, false);
        Set<Path> scenarioRoots = scenarioBundleRoots();
        if (isUnderAnyScenarioRoot(dir, scenarioRoots)) {
            throw new IllegalArgumentException("Folder path is inside a scenario bundle");
        }
        Files.createDirectories(dir);
    }

    public synchronized void deleteBundleFolder(String folderPath) throws IOException {
        Path dir = resolveBundleFolder(folderPath, false);
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("Folder not found");
        }
        Set<Path> scenarioRoots = scenarioBundleRoots();
        if (isUnderAnyScenarioRoot(dir, scenarioRoots)) {
            throw new IllegalArgumentException("Folder path is inside a scenario bundle");
        }
        try (Stream<Path> entries = Files.list(dir)) {
            if (entries.findAny().isPresent()) {
                throw new IllegalArgumentException("Folder must be empty");
            }
        }
        Files.delete(dir);
    }

    public synchronized void moveScenarioToFolder(String scenarioId, String folderPath) throws IOException {
        if (scenarioId == null || scenarioId.isBlank()) {
            throw new IllegalArgumentException("scenarioId must not be blank");
        }
        ScenarioRecord record = scenarios.get(scenarioId);
        if (record == null) {
            throw new IllegalArgumentException("Scenario '%s' not found".formatted(scenarioId));
        }
        Path currentDir = record.bundleDir();
        if (currentDir == null || !Files.isDirectory(currentDir)) {
            throw new IllegalArgumentException("Scenario '%s' has no bundle directory".formatted(scenarioId));
        }
        Path targetParent = resolveBundleFolder(folderPath, true);
        Set<Path> scenarioRoots = scenarioBundleRoots();
        if (isUnderAnyScenarioRoot(targetParent, scenarioRoots)) {
            throw new IllegalArgumentException("Target folder is inside a scenario bundle");
        }
        Files.createDirectories(targetParent);
        Path targetDir = targetParent.resolve(sanitize(scenarioId)).toAbsolutePath().normalize();
        if (targetDir.equals(currentDir.toAbsolutePath().normalize())) {
            return;
        }
        if (Files.exists(targetDir)) {
            throw new IllegalArgumentException("Target already exists");
        }
        Files.move(currentDir, targetDir);
        reload();
    }

    public synchronized void moveBundleToFolder(String bundleKey, String folderPath) throws IOException {
        BundleCatalogEntry entry = bundleEntry(bundleKey);
        Path targetParent = resolveBundleFolder(folderPath, true);
        Set<Path> scenarioRoots = scenarioBundleRoots();
        if (isUnderAnyScenarioRoot(targetParent, scenarioRoots)) {
            throw new IllegalArgumentException("Target folder is inside a scenario bundle");
        }
        Files.createDirectories(targetParent);

        Path currentDir = entry.bundleDir();
        if (currentDir == null || !Files.isDirectory(currentDir)) {
            throw new IllegalArgumentException("Bundle '%s' not found".formatted(bundleKey));
        }
        Path targetDir = targetParent.resolve(currentDir.getFileName()).toAbsolutePath().normalize();
        if (targetDir.equals(currentDir.toAbsolutePath().normalize())) {
            return;
        }
        if (Files.exists(targetDir)) {
            throw new IllegalArgumentException("Target already exists");
        }
        Files.move(currentDir, targetDir);
        reload();
    }

    public synchronized void deleteBundle(String bundleKey) throws IOException {
        BundleCatalogEntry entry = bundleEntry(bundleKey);
        Path bundleDir = entry.bundleDir();
        if (bundleDir == null || !Files.isDirectory(bundleDir)) {
            throw new IllegalArgumentException("Bundle '%s' not found".formatted(bundleKey));
        }
        clearDirectory(bundleDir);
        Files.deleteIfExists(bundleDir);
        reload();
    }

    public synchronized BundleDownload downloadBundle(String bundleKey) throws IOException {
        BundleCatalogEntry entry = bundleEntry(bundleKey);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Path bundleDir = entry.bundleDir();
        if (bundleDir == null || !Files.isDirectory(bundleDir)) {
            throw new IllegalArgumentException("Bundle '%s' not found".formatted(bundleKey));
        }
        try (ZipOutputStream zip = new ZipOutputStream(out);
             Stream<Path> paths = Files.walk(bundleDir)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                if (Files.isDirectory(path)) {
                    continue;
                }
                Path relative = bundleDir.relativize(path);
                String entryName = relative.toString().replace('\\', '/');
                zip.putNextEntry(new ZipEntry(entryName));
                Files.copy(path, zip);
                zip.closeEntry();
            }
        }
        return new BundleDownload(out.toByteArray(), fallbackBundleName(entry.bundlePath()) + "-bundle.zip");
    }

    public synchronized BundleTree readBundleTree(String bundleKey) throws IOException {
        BundleCatalogEntry entry = bundleEntry(bundleKey);
        BundleRoot root = bundleRoot(entry);
        List<BundleTreeNode> nodes = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(root.root())) {
            paths
                    .filter(path -> !path.equals(root.root()))
                    .sorted(Comparator
                            .comparing((Path path) -> !Files.isDirectory(path))
                            .thenComparing(path -> root.root().relativize(path).toString().replace('\\', '/')))
                    .forEach(path -> nodes.add(bundleTreeNode(entry.bundleKey(), root.root(), path)));
        }
        return new BundleTree(entry.bundleKey(), nodes);
    }

    public synchronized BundleFilePayload readBundleWorkspaceFile(String bundleKey, String relativePath) throws IOException {
        BundleCatalogEntry entry = bundleEntry(bundleKey);
        BundleRoot root = bundleRoot(entry);
        Path file = resolveBundleEntryPath(root, relativePath);
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("Bundle path is not a file");
        }

        String editorKind = editorKind(file);
        byte[] bytes = Files.readAllBytes(file);
        String content = EDITOR_KIND_UNSUPPORTED.equals(editorKind) ? null : Files.readString(file);
        boolean writable = !EDITOR_KIND_UNSUPPORTED.equals(editorKind);
        return new BundleFilePayload(
                entry.bundleKey(),
                relativeBundlePath(root.root(), file),
                file.getFileName().toString(),
                mediaType(file, editorKind),
                editorKind,
                writable,
                bytes.length,
                "sha256:" + sha256Hex(bytes),
                content);
    }

    public synchronized BundleFileWriteResult writeBundleWorkspaceFile(String bundleKey,
                                                                       String relativePath,
                                                                       String content,
                                                                       String expectedRevision) throws IOException {
        BundleCatalogEntry entry = bundleEntry(bundleKey);
        BundleRoot root = bundleRoot(entry);
        Path file = resolveBundleEntryPath(root, relativePath);
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("Bundle path is not a file");
        }
        requireEditableBundleFile(file);
        String currentRevision = "sha256:" + sha256Hex(Files.readAllBytes(file));
        if (expectedRevision != null && !expectedRevision.isBlank() && !currentRevision.equals(expectedRevision.trim())) {
            throw new WorkspaceConflictException("File revision is stale");
        }
        Files.writeString(file, content != null ? content : "");
        reload();
        return new BundleFileWriteResult("sha256:" + sha256Hex(Files.readAllBytes(file)));
    }

    public synchronized BundleFilePayload createBundleWorkspaceFile(String bundleKey,
                                                                    String relativePath,
                                                                    String content) throws IOException {
        BundleCatalogEntry entry = bundleEntry(bundleKey);
        BundleRoot root = bundleRoot(entry);
        Path file = resolveBundleTargetPath(root, relativePath);
        requireEditableBundleFile(file);
        if (Files.exists(file)) {
            throw new WorkspaceConflictException("Bundle path already exists");
        }
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(file, content != null ? content : "");
        reload();
        return readBundleWorkspaceFile(bundleKey, relativeBundlePath(root.root(), file));
    }

    public synchronized void createBundleWorkspaceFolder(String bundleKey, String relativePath) throws IOException {
        BundleCatalogEntry entry = bundleEntry(bundleKey);
        BundleRoot root = bundleRoot(entry);
        Path folder = resolveBundleTargetPath(root, relativePath);
        if (Files.exists(folder)) {
            throw new WorkspaceConflictException("Bundle path already exists");
        }
        Files.createDirectories(folder);
        reload();
    }

    public synchronized void renameBundleWorkspaceEntry(String bundleKey, String relativePath, String name) throws IOException {
        BundleCatalogEntry entry = bundleEntry(bundleKey);
        BundleRoot root = bundleRoot(entry);
        Path source = resolveBundleEntryPath(root, relativePath);
        String targetName = normalizeBundleEntryName(name);
        Path parent = source.getParent();
        if (parent == null) {
            throw new IllegalArgumentException("Cannot rename bundle root");
        }
        Path target = parent.resolve(targetName).normalize();
        if (!target.startsWith(root.root())) {
            throw new IllegalArgumentException("Invalid bundle path");
        }
        if (source.equals(target)) {
            return;
        }
        if (Files.exists(target)) {
            throw new WorkspaceConflictException("Bundle path already exists");
        }
        Files.move(source, target);
        reload();
    }

    public synchronized void deleteBundleWorkspaceEntry(String bundleKey, String relativePath) throws IOException {
        BundleCatalogEntry entry = bundleEntry(bundleKey);
        BundleRoot root = bundleRoot(entry);
        Path target = resolveBundleEntryPath(root, relativePath);
        if (target.equals(root.root())) {
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
        reload();
    }

	    private Path resolveBundleFolder(String folderPath, boolean allowRoot) {
	        String trimmed = folderPath == null ? "" : folderPath.trim();
	        if (trimmed.isEmpty()) {
	            if (!allowRoot) {
	                throw new IllegalArgumentException("Folder path must not be empty");
	            }
	            return bundleRootDir.toAbsolutePath().normalize();
	        }
	        if (trimmed.startsWith("/") || trimmed.contains("..")) {
	            throw new IllegalArgumentException("Invalid folder path");
	        }
	        Path resolved = bundleRootDir.toAbsolutePath().normalize();
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
	        if (!normalized.startsWith(bundleRootDir.toAbsolutePath().normalize())) {
	            throw new IllegalArgumentException("Invalid folder path");
	        }
	        if (isReservedWorkspaceDirectory(normalized)) {
	            throw new IllegalArgumentException("Folder path is reserved");
	        }
	        return normalized;
	    }

    private BundleRoot bundleRoot(BundleCatalogEntry entry) {
        Path bundleDir = entry.bundleDir();
        if (bundleDir == null || !Files.isDirectory(bundleDir)) {
            throw new IllegalArgumentException("Bundle '%s' not found".formatted(entry.bundleKey()));
        }
        return new BundleRoot(bundleDir.toAbsolutePath().normalize());
    }

    private BundleTreeNode bundleTreeNode(String bundleKey, Path root, Path path) {
        boolean directory = Files.isDirectory(path);
        String editorKind = directory ? EDITOR_KIND_UNSUPPORTED : editorKind(path);
        return new BundleTreeNode(
                bundleKey,
                relativeBundlePath(root, path),
                path.getFileName().toString(),
                directory ? NODE_TYPE_DIRECTORY : NODE_TYPE_FILE,
                directory ? null : mediaType(path, editorKind),
                editorKind,
                !directory && !EDITOR_KIND_UNSUPPORTED.equals(editorKind),
                directory ? null : safeSize(path));
    }

    private Path resolveBundleEntryPath(BundleRoot root, String relativePath) {
        String trimmed = normalizeBundleRelativePath(relativePath);
        Path resolved = root.root().resolve(trimmed).normalize();
        if (!resolved.startsWith(root.root())) {
            throw new IllegalArgumentException("Invalid bundle path");
        }
        if (!Files.exists(resolved)) {
            throw new IllegalArgumentException("Bundle path not found");
        }
        return resolved;
    }

    private Path resolveBundleTargetPath(BundleRoot root, String relativePath) {
        String trimmed = normalizeBundleRelativePath(relativePath);
        Path resolved = root.root().resolve(trimmed).normalize();
        if (!resolved.startsWith(root.root())) {
            throw new IllegalArgumentException("Invalid bundle path");
        }
        return resolved;
    }

    private String normalizeBundleRelativePath(String relativePath) {
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

    private String normalizeBundleEntryName(String name) {
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

    private void requireEditableBundleFile(Path file) {
        if (EDITOR_KIND_UNSUPPORTED.equals(editorKind(file))) {
            throw new WorkspaceUnsupportedMediaTypeException("Bundle file type is not editable");
        }
    }

    private static String relativeBundlePath(Path root, Path path) {
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

    private static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest is unavailable", e);
        }
    }

    private Set<Path> scenarioBundleRoots() {
        Set<Path> roots = new LinkedHashSet<>();
        for (BundleCatalogEntry entry : bundleCatalog) {
            Path dir = entry.bundleDir();
            if (dir != null) {
                roots.add(dir.toAbsolutePath().normalize());
            }
        }
        return roots;
    }

    private BundleCatalogEntry bundleEntry(String bundleKey) {
        if (bundleKey == null || bundleKey.isBlank()) {
            throw new IllegalArgumentException("bundleKey must not be blank");
        }
        return bundleCatalog.stream()
                .filter(entry -> bundleKey.equals(entry.bundleKey()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Bundle '%s' not found".formatted(bundleKey)));
    }

	    private boolean isUnderAnyScenarioRoot(Path path, Set<Path> scenarioRoots) {
	        if (path == null || scenarioRoots == null || scenarioRoots.isEmpty()) {
	            return false;
	        }
	        Path current = path.toAbsolutePath().normalize();
	        while (current != null && current.startsWith(bundleRootDir.toAbsolutePath().normalize())) {
	            if (scenarioRoots.contains(current)) {
	                return true;
	            }
	            current = current.getParent();
	        }
	        return false;
	    }

	    private boolean isReservedWorkspaceDirectory(Path path) {
	        if (path == null) {
	            return false;
	        }
	        Path normalized = path.toAbsolutePath().normalize();
	        Path testRoot = testStorageDir.toAbsolutePath().normalize();
	        if (normalized.startsWith(testRoot)) {
	            return true;
	        }
	        Path root = bundleRootDir.toAbsolutePath().normalize();
	        Path runtimeRoot = runtimeRootDir != null ? runtimeRootDir.toAbsolutePath().normalize() : null;
	        return runtimeRoot != null && runtimeRoot.startsWith(root) && normalized.startsWith(runtimeRoot);
	    }

    private static String normalizeTag(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private void writeDescriptor(Scenario scenario, Path bundleDir) throws IOException {
        String id = scenario.getId();
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Scenario id must not be null or blank");
        }
        Files.createDirectories(bundleDir);
        Path descriptor = scenarioDescriptorFile(bundleDir);
        if (!descriptor.startsWith(bundleDir)) {
            throw new IllegalArgumentException("Invalid scenario id");
        }
        yamlMapper.writerWithDefaultPrettyPrinter().writeValue(descriptor.toFile(), scenario);
    }

    private ScenarioSummary toSummary(ScenarioRecord record) {
        Scenario scenario = record.scenario();
        return new ScenarioSummary(scenario.getId(), scenario.getName(), record.folderPath());
    }

    Path bundleDir(String id) {
        String cleaned = sanitize(id);
        Path dir = bundleRootDir.resolve(cleaned).normalize();
        if (!dir.startsWith(bundleRootDir)) {
            throw new IllegalArgumentException("Invalid scenario id");
        }
        return dir;
    }

    Path bundleDirFor(String scenarioId) {
        ScenarioRecord record = scenarios.get(scenarioId);
        if (record == null) {
            throw new IllegalArgumentException("Scenario '%s' not found".formatted(scenarioId));
        }
        Path bundleDir = record.bundleDir();
        if (bundleDir == null) {
            throw new IllegalArgumentException("Scenario '%s' has no bundle directory".formatted(scenarioId));
        }
        return bundleDir;
    }

    private Path scenarioDescriptorFile(Path bundleDir) {
        return ScenarioBundleLayout.scenarioDescriptorFile(bundleDir);
    }

    private String folderPath(Path bundleDir) {
        if (bundleDir == null) {
            return null;
        }
        Path normalized = bundleDir.normalize();
        if (!normalized.startsWith(bundleRootDir)) {
            return null;
        }
        Path rel = bundleRootDir.relativize(normalized);
        Path parent = rel.getParent();
        if (parent == null) {
            return null;
        }
        String path = parent.toString().replace('\\', '/').trim();
        return path.isEmpty() ? null : path;
    }

    private ScenarioRecord recordForLoaded(Scenario scenario, Path descriptorFile, Path bundleDir) {
        Scenario resolved = bundleValidator.applyDefaultImageTag(scenario);
        Optional<String> defunct = bundleValidator.defunctReason(resolved);
        Path descriptor = descriptorFile != null ? descriptorFile.toAbsolutePath().normalize() : null;
        Path bundle = bundleDir != null ? bundleDir.toAbsolutePath().normalize() : null;
        return new ScenarioRecord(resolved, defunct.isPresent(), defunct.orElse(null), descriptor, bundle, folderPath(bundle));
    }

    Path runtimeDir(String swarmId) {
        String cleaned = sanitize(swarmId);
        Path dir = runtimeRootDir.resolve(cleaned).normalize();
        if (!dir.startsWith(runtimeRootDir)) {
            throw new IllegalArgumentException("Invalid swarm id");
        }
        return dir;
    }

    public Path prepareRuntimeDirectory(String scenarioId, String swarmId) throws IOException {
        if (scenarioId == null || scenarioId.isBlank()) {
            throw new IllegalArgumentException("scenarioId must not be null or blank");
        }
        if (swarmId == null || swarmId.isBlank()) {
            throw new IllegalArgumentException("swarmId must not be null or blank");
        }
        if (!scenarios.containsKey(scenarioId)) {
            throw new IllegalArgumentException("Scenario '%s' not found".formatted(scenarioId));
        }

        Path target = runtimeDir(swarmId);
        if (Files.exists(target)) {
            clearDirectory(target);
        }
        Files.createDirectories(target);

        ScenarioRecord record = scenarios.get(scenarioId);
        Path source = record != null ? record.bundleDir() : null;
        if (source != null && Files.isDirectory(source)) {
            copyDirectory(source, target);
        } else {
            logger.info("No bundle directory found for scenario '{}'; runtime directory {} will be empty",
                scenarioId, target);
        }

        return target;
    }

    void clearDirectory(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (Stream<Path> paths = Files.list(directory)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                if (Files.isDirectory(path)) {
                    clearDirectory(path);
                }
                Files.deleteIfExists(path);
            }
        }
    }

    void copyDirectory(Path source, Path target) throws IOException {
        try (Stream<Path> stream = Files.walk(source)) {
            for (Path path : (Iterable<Path>) stream::iterator) {
                Path relative = source.relativize(path);
                Path dest = target.resolve(relative);
                if (Files.isDirectory(path)) {
                    Files.createDirectories(dest);
                } else {
                    Files.createDirectories(dest.getParent());
                    Files.copy(path, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    private void loadFromBundles(Path bundleRoot, List<ScannedBundle> target, Set<Path> visitedDescriptors) throws IOException {
        if (!Files.isDirectory(bundleRoot)) {
            return;
        }
        Path normalizedRoot = bundleRoot.toAbsolutePath().normalize();
        Path normalizedStorageDir = storageDir.toAbsolutePath().normalize();
        Path normalizedTestDir = testStorageDir.toAbsolutePath().normalize();
        Path normalizedRuntimeRoot = runtimeRootDir.toAbsolutePath().normalize();
        boolean isMainScan = normalizedRoot.equals(normalizedStorageDir);
        boolean runtimeUnderRoot = normalizedRuntimeRoot.startsWith(normalizedRoot);
        try (Stream<Path> stream = Files.walk(bundleRoot)) {
            for (Path path : (Iterable<Path>) stream::iterator) {
                Path normalized = path.toAbsolutePath().normalize();
                if (runtimeUnderRoot && normalized.startsWith(normalizedRuntimeRoot)) {
                    continue;
                }
                if (isMainScan && normalized.startsWith(normalizedTestDir)) {
                    continue;
                }
                if (!Files.isRegularFile(path)) {
                    continue;
                }
                if (!ScenarioBundleLayout.isScenarioDescriptor(path)) {
                    continue;
                }
                Path parent = normalized.getParent();
                if (parent == null || parent.equals(normalizedRoot)) {
                    continue;
                }
                if (!visitedDescriptors.add(normalized)) {
                    continue;
                }
                scanDescriptor(path, path.getParent(), target);
            }
        }
    }

    private void scanDescriptor(Path descriptorFile, Path bundleDir, List<ScannedBundle> target) {
        String bundlePath = relativeEntryPath(bundleDir != null ? bundleDir : descriptorFile);
        String folderPath = folderPathForBundleEntry(bundlePath);
        String fallbackName = fallbackBundleName(bundlePath);
        try {
            Scenario scenario = bundleValidator.readScenarioDescriptor(descriptorFile);
            target.add(new ScannedBundle(
                    bundlePath,
                    bundlePath,
                    folderPath,
                    fallbackName,
                    descriptorFile.toAbsolutePath().normalize(),
                    bundleDir != null ? bundleDir.toAbsolutePath().normalize() : null,
                    scenario,
                    null));
        } catch (Exception e) {
            logger.warn("Failed to load bundle descriptor at {}: {}", descriptorFile, e.getMessage());
            target.add(new ScannedBundle(
                    bundlePath,
                    bundlePath,
                    folderPath,
                    fallbackName,
                    descriptorFile.toAbsolutePath().normalize(),
                    bundleDir != null ? bundleDir.toAbsolutePath().normalize() : null,
                    null,
                    "Could not read scenario file: " + cleanError(e.getMessage())));
        }
    }

    private List<BundleCatalogEntry> buildBundleCatalog(List<ScannedBundle> discovered) {
        List<BundleCatalogEntry> entries = new ArrayList<>(discovered.size());
        Map<String, List<Integer>> byScenarioId = new LinkedHashMap<>();

        for (ScannedBundle scanned : discovered) {
            boolean quarantined = isQuarantineBundlePath(scanned.bundlePath());
            if (scanned.scenario() == null) {
                String reason = appendReason(scanned.loadError(), quarantined ? quarantineReason() : null);
                entries.add(new BundleCatalogEntry(
                        scanned.bundleKey(),
                        scanned.bundlePath(),
                        scanned.folderPath(),
                        null,
                        scanned.fallbackName(),
                        null,
                        null,
                        List.of(),
                        true,
                        reason,
                        false,
                        quarantined,
                        null,
                        scanned.descriptorFile(),
                        scanned.bundleDir()));
                continue;
            }

            ScenarioRecord record = recordForLoaded(scanned.scenario(), scanned.descriptorFile(), scanned.bundleDir());
            Scenario scenario = record.scenario();
            String scenarioId = scenario.getId();
            String name = scenario.getName() != null && !scenario.getName().isBlank() ? scenario.getName().trim() : scanned.fallbackName();
            String description = scenario.getDescription();
            SwarmTemplate template = scenario.getTemplate();
            String controllerImage = template != null ? template.image() : null;
            List<BundleBeeSummary> bees = template == null ? List.of() : template.bees().stream()
                    .map(bee -> new BundleBeeSummary(bee.role(), bee.image()))
                    .toList();
            boolean defunct = record.defunct() || quarantined;
            String reason = appendReason(record.defunctReason(), quarantined ? quarantineReason() : null);
            entries.add(new BundleCatalogEntry(
                    scanned.bundleKey(),
                    scanned.bundlePath(),
                    scanned.folderPath(),
                    scenarioId != null && !scenarioId.isBlank() ? scenarioId : null,
                    name,
                    description,
                    controllerImage,
                    bees,
                    defunct,
                    reason,
                    false,
                    quarantined,
                    record,
                    scanned.descriptorFile(),
                    scanned.bundleDir()));
            if (!quarantined && scenarioId != null && !scenarioId.isBlank()) {
                byScenarioId.computeIfAbsent(scenarioId, ignored -> new ArrayList<>()).add(entries.size() - 1);
            }
        }

        for (Map.Entry<String, List<Integer>> duplicate : byScenarioId.entrySet()) {
            List<Integer> indexes = duplicate.getValue();
            if (indexes.size() < 2) {
                continue;
            }
            List<String> paths = indexes.stream().map(index -> entries.get(index).bundlePath()).sorted().toList();
            for (Integer index : indexes) {
                BundleCatalogEntry entry = entries.get(index);
                String reason = appendReason(entry.defunctReason(),
                        "Duplicate scenario id '" + duplicate.getKey() + "' found in bundles: " + String.join(", ", paths));
                entries.set(index, entry.withConflict(reason));
            }
        }

        entries.sort(Comparator
                .comparing(BundleCatalogEntry::folderPath, Comparator.nullsFirst(String::compareTo))
                .thenComparing(BundleCatalogEntry::name)
                .thenComparing(BundleCatalogEntry::bundlePath));
        return List.copyOf(entries);
    }

    private Map<String, ScenarioRecord> buildScenarioIndex(List<BundleCatalogEntry> catalog) {
        Map<String, ScenarioRecord> loaded = new HashMap<>();
        for (BundleCatalogEntry entry : catalog) {
            ScenarioRecord record = entry.scenarioRecord();
            if (record == null || entry.duplicateIdConflict() || entry.quarantined()) {
                continue;
            }
            String scenarioId = entry.scenarioId();
            if (scenarioId == null || scenarioId.isBlank()) {
                continue;
            }
            loaded.put(scenarioId, record);
        }
        return loaded;
    }

    private String relativeEntryPath(Path path) {
        try {
            return bundleRootDir.toAbsolutePath().normalize()
                    .relativize(path.toAbsolutePath().normalize())
                    .toString()
                    .replace('\\', '/');
        } catch (Exception e) {
            return path.getFileName() != null ? path.getFileName().toString() : path.toString().replace('\\', '/');
        }
    }

    private String folderPathForBundleEntry(String bundlePath) {
        if (bundlePath == null || bundlePath.isBlank()) {
            return null;
        }
        int slashIndex = bundlePath.lastIndexOf('/');
        if (slashIndex < 0) {
            return null;
        }
        String path = bundlePath.substring(0, slashIndex).trim();
        return path.isEmpty() ? null : path;
    }

    private String fallbackBundleName(String bundlePath) {
        if (bundlePath == null || bundlePath.isBlank()) {
            return "Unknown bundle";
        }
        String leaf = bundlePath;
        int slashIndex = leaf.lastIndexOf('/');
        if (slashIndex >= 0) {
            leaf = leaf.substring(slashIndex + 1);
        }
        if (leaf.endsWith(".yaml") || leaf.endsWith(".yml") || leaf.endsWith(".json")) {
            int dotIndex = leaf.lastIndexOf('.');
            if (dotIndex > 0) {
                leaf = leaf.substring(0, dotIndex);
            }
        }
        return leaf.isBlank() ? bundlePath : leaf;
    }

    private static String cleanError(String message) {
        if (message == null || message.isBlank()) {
            return "unknown error";
        }
        String cleaned = message.replaceAll("\\(through reference chain:.*", "").trim();
        return cleaned.length() > 300 ? cleaned.substring(0, 300) + "..." : cleaned;
    }

    private static String appendReason(String existing, String addition) {
        if (addition == null || addition.isBlank()) {
            return existing;
        }
        if (existing == null || existing.isBlank()) {
            return addition;
        }
        return existing + "; " + addition;
    }

    private boolean isQuarantineBundlePath(String bundlePath) {
        if (bundlePath == null || bundlePath.isBlank()) {
            return false;
        }
        return bundlePath.equals(QUARANTINE_FOLDER) || bundlePath.startsWith(QUARANTINE_FOLDER + "/");
    }

    private static String quarantineReason() {
        return "Bundle is quarantined under 'quarantine/' and cannot be used to create a swarm";
    }

    private String sanitize(String id) {
        String cleaned = Paths.get(id).getFileName().toString();
        if (!cleaned.equals(id) || cleaned.contains("..") || cleaned.isBlank()) {
            throw new IllegalArgumentException("Invalid scenario id");
        }
        return cleaned;
    }

    private Stream<ScenarioRecord> streamRecords() {
        return scenarios.values().stream();
    }

    private boolean hasDiscoveredScenarioId(String scenarioId) {
        if (scenarioId == null || scenarioId.isBlank()) {
            return false;
        }
        return bundleCatalog.stream().anyMatch(entry -> scenarioId.equals(entry.scenarioId()));
    }

    public Map<String, Object> getPlan(String id) {
        ScenarioRecord record = scenarios.get(id);
        if (record == null) {
            throw new IllegalArgumentException("Scenario '" + id + "' not found");
        }
        Scenario scenario = record.scenario();
        Map<String, Object> plan = scenario.getPlan();
        if (plan == null) {
            return Map.of();
        }
        return plan;
    }

    public String readScenarioRaw(String id) throws IOException {
        Path file = scenarioDescriptorFile(id);
        return Files.readString(file);
    }

    public void updateScenarioFromRaw(String id, String body) throws IOException {
        if (body == null) {
            throw new IllegalArgumentException("Scenario body must not be null");
        }
        String trimmed = body.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Scenario body must not be empty");
        }

        Path file = scenarioDescriptorFile(id);
        Scenario scenario;
        try {
            scenario = bundleValidator.readScenarioDescriptorContent(trimmed);
        } catch (IOException e) {
            throw new IOException("Failed to parse scenario content: " + e.getMessage(), e);
        }

        if (scenario.getId() == null || scenario.getId().isBlank()) {
            throw new IllegalArgumentException("Scenario id must not be null or blank");
        }
        if (!id.equals(scenario.getId())) {
            throw new IllegalArgumentException(
                    "Scenario id '" + scenario.getId() + "' does not match path id '" + id + "'");
        }

        Files.writeString(file, body);

        reload();
    }

    public Scenario updatePlan(String id, Map<String, Object> plan) throws IOException {
        Path file = scenarioDescriptorFile(id);
        Scenario scenario = bundleValidator.readScenarioDescriptor(file);
        if (scenario.getId() == null || scenario.getId().isBlank()) {
            throw new IllegalArgumentException("Scenario id must not be null or blank");
        }
        if (!id.equals(scenario.getId())) {
            throw new IllegalArgumentException(
                "Scenario id '" + scenario.getId() + "' does not match path id '" + id + "'");
        }

        Map<String, Object> effectivePlan = plan == null || plan.isEmpty() ? null : plan;
        scenario.setPlan(effectivePlan);

        yamlMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), scenario);

        reload();
        ScenarioRecord record = scenarios.get(id);
        return record != null ? record.scenario() : scenario;
    }

    public List<String> listSchemaFiles(String id) throws IOException {
        Path bundle = bundleDirFor(id);
        Path schemasDir = bundle.resolve("schemas").normalize();
        if (!schemasDir.startsWith(bundle) || !Files.isDirectory(schemasDir)) {
            return List.of();
        }
        List<String> files = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(schemasDir)) {
            for (Path path : (Iterable<Path>) stream::iterator) {
                if (!Files.isRegularFile(path)) {
                    continue;
                }
                Path relative = bundle.relativize(path);
                files.add(relative.toString().replace('\\', '/'));
            }
        }
        files.sort(String::compareTo);
        return files;
    }

    public void writeSchemaFile(String id, String relativePath, String content) throws IOException {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("Schema path must not be null or blank");
        }
        Path bundle = bundleDirFor(id);
        Path file = bundle.resolve(relativePath).normalize();
        if (!file.startsWith(bundle)) {
            throw new IllegalArgumentException("Invalid schema path");
        }
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(file, content);
    }

    public String readBundleFile(String id, String relativePath) throws IOException {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("File path must not be null or blank");
        }
        Path bundle = bundleDirFor(id);
        Path file = bundle.resolve(relativePath).normalize();
        if (!file.startsWith(bundle)) {
            throw new IllegalArgumentException("Invalid file path");
        }
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("File '%s' not found in bundle for scenario '%s'".formatted(relativePath, id));
        }
        return Files.readString(file);
    }

    public List<String> listTemplateFiles(String id) throws IOException {
        Path bundle = bundleDirFor(id);
        Path templatesDir = bundle.resolve("templates").normalize();
        if (!templatesDir.startsWith(bundle) || !Files.isDirectory(templatesDir)) {
            return List.of();
        }
        List<String> files = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(templatesDir)) {
            for (Path path : (Iterable<Path>) stream::iterator) {
                if (!Files.isRegularFile(path)) {
                    continue;
                }
                Path relative = bundle.relativize(path);
                files.add(relative.toString().replace('\\', '/'));
            }
        }
        files.sort(String::compareTo);
        return files;
    }

    public void writeTemplate(String id, String relativePath, String content) throws IOException {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("Template path must not be null or blank");
        }
        Path bundle = bundleDirFor(id);
        Path file = bundle.resolve(relativePath).normalize();
        if (!file.startsWith(bundle)) {
            throw new IllegalArgumentException("Invalid template path");
        }
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (Files.exists(file) && !Files.isRegularFile(file)) {
            throw new IllegalArgumentException(
                "Template '%s' is not a file in bundle for scenario '%s'".formatted(relativePath, id));
        }
        Files.writeString(file, content);
    }

    public void renameTemplate(String id, String fromPath, String toPath) throws IOException {
        if (fromPath == null || fromPath.isBlank() || toPath == null || toPath.isBlank()) {
            throw new IllegalArgumentException("Template paths must not be null or blank");
        }
        Path bundle = bundleDirFor(id);
        Path templatesDir = bundle.resolve("templates").normalize();
        Path source = bundle.resolve(fromPath).normalize();
        Path target = bundle.resolve(toPath).normalize();
        if (!source.startsWith(bundle) || !target.startsWith(bundle)) {
            throw new IllegalArgumentException("Invalid template path");
        }
        if (!source.startsWith(templatesDir) || !target.startsWith(templatesDir)) {
            throw new IllegalArgumentException("Template paths must live under templates/");
        }
        if (source.equals(target)) {
            throw new IllegalArgumentException("Template paths must differ");
        }
        if (!Files.isRegularFile(source)) {
            throw new IllegalArgumentException(
                "Template '%s' not found in bundle for scenario '%s'".formatted(fromPath, id));
        }
        if (Files.exists(target)) {
            throw new IllegalArgumentException(
                "Template '%s' already exists in bundle for scenario '%s'".formatted(toPath, id));
        }
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.move(source, target);
    }

    public void deleteTemplate(String id, String relativePath) throws IOException {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("Template path must not be null or blank");
        }
        Path bundle = bundleDirFor(id);
        Path templatesDir = bundle.resolve("templates").normalize();
        Path file = bundle.resolve(relativePath).normalize();
        if (!file.startsWith(bundle)) {
            throw new IllegalArgumentException("Invalid template path");
        }
        if (!file.startsWith(templatesDir)) {
            throw new IllegalArgumentException("Template paths must live under templates/");
        }
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException(
                "Template '%s' not found in bundle for scenario '%s'".formatted(relativePath, id));
        }
        Files.delete(file);
    }

    public String readVariablesRaw(String id) throws IOException {
        ScenarioRecord record = scenarios.get(id);
        if (record == null) {
            throw new IllegalArgumentException("Scenario '%s' not found".formatted(id));
        }
        Path bundle = record.bundleDir();
        if (bundle == null || !Files.isDirectory(bundle)) {
            return null;
        }
        Path file = ScenarioBundleLayout.variablesFile(bundle);
        if (!file.startsWith(bundle)) {
            throw new IllegalArgumentException("Invalid variables path");
        }
        if (!Files.isRegularFile(file)) {
            return null;
        }
        return Files.readString(file);
    }

    public VariablesDocument parseVariables(String raw) {
        return bundleValidator.parseVariables(raw);
    }

    public VariablesValidationResult validateVariables(String scenarioId, VariablesDocument doc) throws IOException {
        return bundleValidator.validateVariables(doc, listCanonicalBundleSutIds(scenarioId));
    }

    public VariablesResolutionResult resolveVariables(
        String scenarioId,
        String variablesProfileId,
        String sutId
    ) throws IOException {
        String raw = readVariablesRaw(scenarioId);
        if (raw == null) {
            return new VariablesResolutionResult(Map.of(), List.of());
        }
        VariablesDocument doc = parseVariables(raw);
        VariablesValidationResult validation = validateVariables(scenarioId, doc);

        String profile = variablesProfileId == null ? null : variablesProfileId.trim();
        if (profile != null && profile.isEmpty()) {
            profile = null;
        }
        String sut = sutId == null ? null : sutId.trim();
        if (sut != null && sut.isEmpty()) {
            sut = null;
        }

        Map<String, VariablesDocument.VariableDefinition> byName = new LinkedHashMap<>();
        for (VariablesDocument.VariableDefinition def : doc.definitions()) {
            byName.put(def.name().trim(), def);
        }
        boolean needsProfile = byName.values().stream().anyMatch(d -> d.scope() == VariablesDocument.Scope.GLOBAL
            || d.scope() == VariablesDocument.Scope.SUT);
        boolean needsSut = byName.values().stream().anyMatch(d -> d.scope() == VariablesDocument.Scope.SUT);
        if (needsProfile && profile == null) {
            throw new IllegalArgumentException("variablesProfileId is required for this scenario");
        }
        if (needsSut && sut == null) {
            throw new IllegalArgumentException("sutId is required for this scenario (sut-scoped variables exist)");
        }

        VariablesDocument.Values values = doc.values();
        Map<String, Map<String, Object>> global =
            values == null || values.global() == null ? Map.of() : values.global();
        Map<String, Map<String, Map<String, Object>>> sutValues =
            values == null || values.sut() == null ? Map.of() : values.sut();

        Map<String, Object> resolved = new LinkedHashMap<>();
        for (VariablesDocument.VariableDefinition def : doc.definitions()) {
            String name = def.name().trim();
            Object value = null;
            if (def.scope() == VariablesDocument.Scope.GLOBAL) {
                Map<String, Object> perProfile = global.getOrDefault(profile, Map.of());
                value = perProfile.get(name);
            } else if (def.scope() == VariablesDocument.Scope.SUT) {
                Map<String, Map<String, Object>> perProfile = sutValues.getOrDefault(profile, Map.of());
                Map<String, Object> perSut = perProfile.getOrDefault(sut, Map.of());
                value = perSut.get(name);
            }
            if (value == null) {
                if (Boolean.TRUE.equals(def.required())) {
                    throw new IllegalArgumentException("Missing required variable '%s'".formatted(name));
                }
                continue;
            }
            // Ensure floats are consistently numeric (YAML may parse ints/longs as integral types).
            if (def.type() == VariablesDocument.Type.FLOAT && value instanceof Number number && !(value instanceof Double)) {
                value = number.doubleValue();
            }
            resolved.put(name, value);
        }

        return new VariablesResolutionResult(Map.copyOf(resolved), validation.warnings());
    }

    public VariablesValidationResult writeVariables(String scenarioId, String raw) throws IOException {
        synchronized (variablesLock) {
            if (raw == null || raw.isBlank()) {
                throw new IllegalArgumentException("%s must not be empty".formatted(ScenarioBundleLayout.VARIABLES_FILE));
            }
            VariablesDocument doc = parseVariables(raw);
            VariablesValidationResult validation = validateVariables(scenarioId, doc);

            Path bundle = bundleDirFor(scenarioId);
            Path file = ScenarioBundleLayout.variablesFile(bundle);
            if (!file.startsWith(bundle)) {
                throw new IllegalArgumentException("Invalid variables path");
            }
            Files.createDirectories(bundle);
            Files.writeString(file, raw);
            return validation;
        }
    }

    public List<String> listSutIds(String scenarioId) throws IOException {
        return listCanonicalBundleSutIds(scenarioId);
    }

    private List<String> listCanonicalBundleSutIds(String scenarioId) throws IOException {
        return listCanonicalBundleSutIds(bundleDirFor(scenarioId), scenarioId);
    }

    private List<String> listCanonicalBundleSutIds(Path bundle, String scenarioId) throws IOException {
        return bundleValidator.listCanonicalBundleSutIds(bundle, scenarioId);
    }

    public SutEnvironment readBundleSut(String scenarioId, String sutId) throws IOException {
        if (sutId == null || sutId.isBlank()) {
            throw new IllegalArgumentException("sutId must not be blank");
        }
        sutId = sanitizeSutId(sutId);
        Path bundle = bundleDirFor(scenarioId);
        Path sutDir = bundle.resolve("sut").resolve(sutId).normalize();
        if (!sutDir.startsWith(bundle)) {
            throw new IllegalArgumentException("Invalid sutId");
        }
        if (!Files.isDirectory(sutDir)) {
            throw new IllegalArgumentException("SUT '%s' not found in bundle for scenario '%s'".formatted(sutId, scenarioId));
        }
        try {
            return bundleValidator.readBundleSutDescriptor(sutDir, sutId);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                "Failed to parse SUT '%s' for scenario '%s': %s".formatted(sutId, scenarioId, e.getMessage()), e);
        }
    }

    /**
     * Reads the raw bundle-local {@code sut/<sutId>/sut.yaml}.
     *
     * @return raw YAML if present, otherwise {@code null}
     */
    public String readBundleSutRaw(String scenarioId, String sutId) throws IOException {
        if (sutId == null || sutId.isBlank()) {
            throw new IllegalArgumentException("sutId must not be blank");
        }
        sutId = sanitizeSutId(sutId);
        Path bundle = bundleDirFor(scenarioId);
        Path sutDir = bundle.resolve("sut").resolve(sutId).normalize();
        if (!sutDir.startsWith(bundle)) {
            throw new IllegalArgumentException("Invalid sutId");
        }
        if (!Files.isDirectory(sutDir)) {
            return null;
        }
        Path file = ScenarioBundleLayout.sutDescriptorFile(sutDir);
        if (!file.startsWith(sutDir) || !Files.isRegularFile(file)) {
            return null;
        }
        return Files.readString(file);
    }

    public void writeBundleSutRaw(String scenarioId, String sutId, String raw) throws IOException {
        if (sutId == null || sutId.isBlank()) {
            throw new IllegalArgumentException("sutId must not be blank");
        }
        sutId = sanitizeSutId(sutId);
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("sut.yaml must not be empty");
        }

        SutEnvironment env;
        try {
            env = bundleValidator.readSutEnvironmentYaml(raw);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse sut.yaml", e);
        }
        bundleValidator.requireCanonicalSutDescriptor(env, sutId);

        Path bundle = bundleDirFor(scenarioId);
        Path sutDir = bundle.resolve("sut").resolve(sutId).normalize();
        if (!sutDir.startsWith(bundle)) {
            throw new IllegalArgumentException("Invalid sutId");
        }
        Files.createDirectories(sutDir);
        Path file = ScenarioBundleLayout.sutDescriptorFile(sutDir);
        if (!file.startsWith(sutDir)) {
            throw new IllegalArgumentException("Invalid sut.yaml path");
        }
        Files.writeString(file, raw);
    }

    public void deleteBundleSut(String scenarioId, String sutId) throws IOException {
        if (sutId == null || sutId.isBlank()) {
            throw new IllegalArgumentException("sutId must not be blank");
        }
        sutId = sanitizeSutId(sutId);
        Path bundle = bundleDirFor(scenarioId);
        Path sutDir = bundle.resolve("sut").resolve(sutId).normalize();
        if (!sutDir.startsWith(bundle)) {
            throw new IllegalArgumentException("Invalid sutId");
        }
        if (!Files.isDirectory(sutDir)) {
            throw new IllegalArgumentException("SUT '%s' not found in bundle for scenario '%s'".formatted(sutId, scenarioId));
        }
        clearDirectory(sutDir);
        Files.deleteIfExists(sutDir);
    }

    private String sanitizeSutId(String sutId) {
        String cleaned = Paths.get(sutId).getFileName().toString();
        if (!cleaned.equals(sutId) || cleaned.contains("..") || cleaned.isBlank()) {
            throw new IllegalArgumentException("Invalid sutId");
        }
        return cleaned;
    }

    public Scenario createBundleFromZip(byte[] zipBytes) throws IOException {
        UploadedBundle uploaded = unpackUploadedBundle(zipBytes, null);
        try {
            BundleValidationResult validation = validateScenarioBundle(
                uploaded.scenario(),
                uploaded.rootDir(),
                BundleValidationSource.UPLOADED_ZIP,
                null,
                null,
                List.of());
            if (!validation.ok()) {
                throw new BundleValidationException(validation);
            }
            String id = uploaded.scenario().getId();
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("Scenario id must not be null or blank");
            }
            if (hasDiscoveredScenarioId(id)) {
                throw new BundleValidationException(bundleValidator.duplicateScenarioValidationResult(id));
            }
            writeBundle(uploaded, defaultUploadBundleDir(id));
            reload();
            ScenarioRecord record = scenarios.get(id);
            return record != null ? record.scenario() : uploaded.scenario();
        } finally {
            cleanupUploaded(uploaded);
        }
    }

    private Path scenarioDescriptorFile(String id) throws IOException {
        ScenarioRecord record = scenarios.get(id);
        if (record == null) {
            throw new IllegalArgumentException("Scenario '%s' not found".formatted(id));
        }
        Path descriptor = record.descriptorFile();
        if (descriptor != null && Files.isRegularFile(descriptor)) {
            return descriptor;
        }
        Path bundleDir = record.bundleDir();
        if (bundleDir != null && Files.isDirectory(bundleDir)) {
            Path candidate = scenarioDescriptorFile(bundleDir);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("Scenario descriptor not found for scenario '%s'".formatted(id));
    }

    public Scenario replaceBundleFromZip(String expectedId, byte[] zipBytes) throws IOException {
        UploadedBundle uploaded = unpackUploadedBundle(zipBytes, expectedId);
        try {
            BundleValidationResult validation = validateScenarioBundle(
                uploaded.scenario(),
                uploaded.rootDir(),
                BundleValidationSource.UPLOADED_ZIP,
                null,
                null,
                List.of());
            if (!validation.ok()) {
                throw new BundleValidationException(validation);
            }
            String id = uploaded.scenario().getId();
            ScenarioRecord existing = scenarios.get(id);
            Path targetDir = existing != null && existing.bundleDir() != null ? existing.bundleDir() : bundleDir(id);
            writeBundle(uploaded, targetDir);
            reload();
            ScenarioRecord record = scenarios.get(id);
            return record != null ? record.scenario() : uploaded.scenario();
        } finally {
            cleanupUploaded(uploaded);
        }
    }

    private UploadedBundle unpackUploadedBundle(byte[] zipBytes, String expectedId) throws IOException {
        try {
            return unpackBundle(zipBytes, expectedId);
        } catch (IllegalArgumentException e) {
            throw new BundleValidationException(bundleValidator.uploadedBundleValidationResult(e));
        }
    }

    public BundleValidationResult validateBundleZip(byte[] zipBytes) throws IOException {
        UploadedBundle uploaded = null;
        try {
            uploaded = unpackBundle(zipBytes, null);
            return validateScenarioBundle(
                uploaded.scenario(),
                uploaded.rootDir(),
                BundleValidationSource.UPLOADED_ZIP,
                null,
                null,
                List.of());
        } catch (IllegalArgumentException e) {
            return bundleValidator.uploadedBundleValidationResult(e);
        } finally {
            if (uploaded != null) {
                cleanupUploaded(uploaded);
            }
        }
    }

    public BundleValidationResult validateExistingBundle(String bundleKey) throws IOException {
        BundleCatalogEntry entry = bundleCatalog.stream()
            .filter(candidate -> Objects.equals(candidate.bundleKey(), bundleKey))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Bundle '%s' not found".formatted(bundleKey)));

        ScenarioRecord record = entry.scenarioRecord();
        if (record == null) {
            ValidationFinding finding = bundleValidator.defunctBundleFinding(entry.bundlePath(), entry.defunctReason());
            return BundleValidationResult.of(
                BundleValidationSource.SCENARIO_MANAGER,
                entry.bundleKey(),
                entry.bundlePath(),
                null,
                List.of(finding));
        }

        List<ValidationFinding> seedFindings = catalogOnlyDefunctFindings(entry);
        return validateScenarioBundle(
            record.scenario(),
            record.bundleDir(),
            BundleValidationSource.SCENARIO_MANAGER,
            entry.bundleKey(),
            entry.bundlePath(),
            seedFindings);
    }

    private List<ValidationFinding> catalogOnlyDefunctFindings(BundleCatalogEntry entry) {
        if (entry == null || (!entry.duplicateIdConflict() && !entry.quarantined())) {
            return List.of();
        }
        List<ValidationFinding> findings = new ArrayList<>();
        if (entry.duplicateIdConflict()) {
            findings.add(bundleValidator.duplicateScenarioFinding(entry.scenarioId(), duplicateScenarioReason(entry.scenarioId())));
        }
        if (entry.quarantined()) {
            findings.add(bundleValidator.quarantinedFinding(entry.bundlePath(), quarantineReason()));
        }
        return List.copyOf(findings);
    }

    private String duplicateScenarioReason(String scenarioId) {
        List<String> paths = bundleCatalog.stream()
            .filter(entry -> Objects.equals(entry.scenarioId(), scenarioId))
            .map(BundleCatalogEntry::bundlePath)
            .filter(Objects::nonNull)
            .sorted()
            .toList();
        return "Duplicate scenario id '%s' found in bundles: %s".formatted(scenarioId, String.join(", ", paths));
    }

    private BundleValidationResult validateScenarioBundle(
        Scenario scenario,
        Path bundleRoot,
        BundleValidationSource source,
        String bundleKey,
        String bundlePath,
        List<ValidationFinding> seedFindings
    ) throws IOException {
        return bundleValidator.validate(new BundleValidationInput(
            source,
            bundleRoot,
            bundleKey,
            bundlePath,
            scenario,
            seedFindings));
    }

    private List<BundleBeeSummary> bundleBees(Scenario scenario) {
        if (scenario == null || scenario.getTemplate() == null || scenario.getTemplate().bees() == null) {
            return List.of();
        }
        return scenario.getTemplate().bees().stream()
            .map(bee -> new BundleBeeSummary(bee.role(), bee.image()))
            .toList();
    }

    private UploadedBundle unpackBundle(byte[] zipBytes, String expectedId) throws IOException {
        if (zipBytes == null || zipBytes.length == 0) {
            throw new IllegalArgumentException("Zip payload must not be empty");
        }
        Path tempRoot = Files.createTempDirectory(UPLOAD_TEMP_PREFIX);
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                if (name == null || name.isBlank()) {
                    continue;
                }
                if (name.startsWith("/") || name.contains("..")) {
                    throw new IllegalArgumentException("Invalid entry path '%s'".formatted(name));
                }
                Path dest = tempRoot.resolve(name).normalize();
                if (!dest.startsWith(tempRoot)) {
                    throw new IllegalArgumentException("Invalid entry path '%s'".formatted(name));
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(dest);
                } else {
                    Path parent = dest.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    Files.copy(zis, dest, StandardCopyOption.REPLACE_EXISTING);
                }
            }

            ScenarioBundleValidator.ScenarioDescriptor descriptor = bundleValidator.findScenarioDescriptor(tempRoot);
            Scenario scenario = descriptor.scenario();
            String id = scenario.getId();
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("Scenario id must not be null or blank");
            }
            if (expectedId != null && !expectedId.equals(id)) {
                throw new IllegalArgumentException(
                    "Scenario id '%s' in bundle does not match expected id '%s'".formatted(id, expectedId));
            }
            return new UploadedBundle(scenario, descriptor.rootDir(), tempRoot);
        } catch (IOException | RuntimeException e) {
            clearDirectory(tempRoot);
            Files.deleteIfExists(tempRoot);
            throw e;
        }
    }

    private void writeBundle(UploadedBundle uploaded, Path targetDir) throws IOException {
        Path sourceDir = uploaded.rootDir();
        if (Files.exists(targetDir)) {
            clearDirectory(targetDir);
        }
        Files.createDirectories(targetDir);
        copyDirectory(sourceDir, targetDir);
    }

    private Path defaultUploadBundleDir(String id) {
        Path parent = resolveBundleFolder(DEFAULT_UPLOAD_FOLDER, false);
        Path targetDir = parent.resolve(sanitize(id)).normalize();
        if (!targetDir.startsWith(bundleRootDir)) {
            throw new IllegalArgumentException("Invalid scenario id");
        }
        return targetDir;
    }

    private void cleanupUploaded(UploadedBundle uploaded) throws IOException {
        Path tempRoot = uploaded.tempRoot();
        clearDirectory(tempRoot);
        Files.deleteIfExists(tempRoot);
    }

    private record ScenarioRecord(
        Scenario scenario,
        boolean defunct,
        String defunctReason,
        Path descriptorFile,
        Path bundleDir,
        String folderPath
    ) { }

    private record ScannedBundle(
        String bundleKey,
        String bundlePath,
        String folderPath,
        String fallbackName,
        Path descriptorFile,
        Path bundleDir,
        Scenario scenario,
        String loadError
    ) { }

    private record BundleCatalogEntry(
        String bundleKey,
        String bundlePath,
        String folderPath,
        String scenarioId,
        String name,
        String description,
        String controllerImage,
        List<BundleBeeSummary> bees,
        boolean defunct,
        String defunctReason,
        boolean duplicateIdConflict,
        boolean quarantined,
        ScenarioRecord scenarioRecord,
        Path descriptorFile,
        Path bundleDir
    ) {
        BundleCatalogEntry withConflict(String reason) {
            return new BundleCatalogEntry(
                    bundleKey,
                    bundlePath,
                    folderPath,
                    scenarioId,
                    name,
                    description,
                    controllerImage,
                    bees,
                    true,
                    reason,
                    true,
                    quarantined,
                    scenarioRecord,
                    descriptorFile,
                    bundleDir);
        }
    }

    private record BundleRoot(Path root) { }

    private record UploadedBundle(Scenario scenario, Path rootDir, Path tempRoot) { }

    public record BundleBeeSummary(String role, String image) { }

    public record ScenarioAccessDescriptor(String scenarioId, String bundlePath, String folderPath) { }

    public record BundleTemplateSummary(
        String bundleKey,
        String bundlePath,
        String folderPath,
        String id,
        String name,
        String description,
        String controllerImage,
        List<BundleBeeSummary> bees,
        boolean defunct,
        String defunctReason
    ) { }

    private BundleTemplateSummary toBundleTemplateSummary(BundleCatalogEntry entry) {
        return new BundleTemplateSummary(
                entry.bundleKey(),
                entry.bundlePath(),
                entry.folderPath(),
                entry.scenarioId(),
                entry.name(),
                entry.description(),
                entry.controllerImage(),
                entry.bees(),
                entry.defunct(),
                entry.defunctReason());
    }

    private ScenarioAccessDescriptor toAccessDescriptor(BundleCatalogEntry entry) {
        return new ScenarioAccessDescriptor(
                entry.scenarioId(),
                entry.bundlePath(),
                entry.folderPath());
    }

    public record BundleDownload(byte[] bytes, String fileName) { }

    public record BundleTree(String bundleKey, List<BundleTreeNode> nodes) { }

    public record BundleFileWriteResult(String revision) { }

    public record BundleTreeNode(
        String bundleKey,
        String path,
        String name,
        String nodeType,
        String mediaType,
        String editorKind,
        boolean writable,
        Long size
    ) { }

    public record BundleFilePayload(
        String bundleKey,
        String path,
        String name,
        String mediaType,
        String editorKind,
        boolean writable,
        long size,
        String revision,
        String content
    ) { }

    public static class WorkspaceConflictException extends RuntimeException {
        public WorkspaceConflictException(String message) {
            super(message);
        }
    }

    public static class WorkspaceUnsupportedMediaTypeException extends RuntimeException {
        public WorkspaceUnsupportedMediaTypeException(String message) {
            super(message);
        }
    }

    public record VariablesValidationResult(List<String> warnings) { }

    public record VariablesResolutionResult(Map<String, Object> vars, List<String> warnings) { }

    public record VariablesDocument(
        int version,
        List<VariableDefinition> definitions,
        List<Profile> profiles,
        Values values
    ) {
        public enum Scope {
            GLOBAL,
            SUT;

            @com.fasterxml.jackson.annotation.JsonCreator
            public static Scope fromJson(String value) {
                if (value == null) {
                    return null;
                }
                String v = value.trim();
                if (v.isEmpty()) {
                    return null;
                }
                return Scope.valueOf(v.toUpperCase(Locale.ROOT));
            }
        }

        public enum Type {
            STRING,
            INT,
            FLOAT,
            BOOL,
            OBJECT;

            @com.fasterxml.jackson.annotation.JsonCreator
            public static Type fromJson(String value) {
                if (value == null) {
                    return null;
                }
                String v = value.trim();
                if (v.isEmpty()) {
                    return null;
                }
                return Type.valueOf(v.toUpperCase(Locale.ROOT));
            }
        }

        public record VariableDefinition(
            String name,
            Scope scope,
            Type type,
            Boolean required
        ) { }

        public record Profile(
            String id,
            String name
        ) { }

        public record Values(
            Map<String, Map<String, Object>> global,
            Map<String, Map<String, Map<String, Object>>> sut
        ) { }
    }
}
