package io.pockethive.scenarios;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.pockethive.capabilities.CapabilityCatalogueService;
import io.pockethive.swarm.model.Bee;
import io.pockethive.swarm.model.SwarmTemplate;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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
    public enum Format { JSON, YAML }

    private static final Logger logger = LoggerFactory.getLogger(ScenarioService.class);
    private static final String SCENARIOS_RUNTIME_ROOT = "scenarios-runtime";
    private static final String DEFAULT_UPLOAD_FOLDER = "bundles";
    private static final String QUARANTINE_FOLDER = "quarantine";
    private static final String UPLOAD_TEMP_PREFIX = "pockethive-scenario-upload-";

    private final Path storageDir;
    private final Path testStorageDir;
    private final Path bundleRootDir;
    private final Path runtimeRootDir;
    private final boolean showTestScenarios;
    private final ObjectMapper jsonMapper = new ObjectMapper();
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
    private final CapabilityCatalogueService capabilities;
    private final Map<String, ScenarioRecord> scenarios = new ConcurrentHashMap<>();
    private volatile List<BundleCatalogEntry> bundleCatalog = List.of();
    private final String defaultImageTag;
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
	        this.defaultImageTag = defaultImageTag;
	        Files.createDirectories(this.storageDir);
	        if (this.showTestScenarios) {
	            Files.createDirectories(this.testStorageDir);
	        }
	        Files.createDirectories(this.bundleRootDir);
	        Files.createDirectories(this.bundleRootDir.resolve(QUARANTINE_FOLDER));
	        Files.createDirectories(this.runtimeRootDir);
	        this.capabilities = capabilities;
	    }

    @PostConstruct
    void init() throws IOException {
        reload();
    }

    public synchronized void reload() throws IOException {
        Files.createDirectories(bundleRootDir.resolve(QUARANTINE_FOLDER));
        List<ScannedBundle> discovered = new ArrayList<>();
        Set<Path> visitedDescriptors = new HashSet<>();

        loadFromDirectory(storageDir, discovered, visitedDescriptors);
        if (showTestScenarios && Files.isDirectory(testStorageDir)) {
            loadFromDirectory(testStorageDir, discovered, visitedDescriptors);
        }
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

    public Scenario create(Scenario scenario, Format format) throws IOException {
        Scenario resolved = applyDefaultImageTag(scenario);
        String id = resolved.getId();
        Path bundleDir = bundleDir(id);
        if (hasDiscoveredScenarioId(id)) {
            throw new IllegalArgumentException("Scenario already exists");
        }

        writeDescriptor(resolved, format, bundleDir);
        reload();
        ScenarioRecord record = scenarios.get(id);
        return record != null ? record.scenario() : resolved;
    }

    public Scenario update(String id, Scenario scenario, Format format) throws IOException {
        scenario.setId(id);
        Scenario resolved = applyDefaultImageTag(scenario);
        ScenarioRecord existing = scenarios.get(id);
        Path bundleDir = existing != null && existing.bundleDir() != null ? existing.bundleDir() : bundleDir(id);
        writeDescriptor(resolved, format, bundleDir);
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
        if (currentDir != null && Files.isDirectory(currentDir)) {
            Path targetDir = targetParent.resolve(currentDir.getFileName()).toAbsolutePath().normalize();
            if (targetDir.equals(currentDir.toAbsolutePath().normalize())) {
                return;
            }
            if (Files.exists(targetDir)) {
                throw new IllegalArgumentException("Target already exists");
            }
            Files.move(currentDir, targetDir);
            reload();
            return;
        }

        Path descriptor = entry.descriptorFile();
        if (descriptor == null || !Files.isRegularFile(descriptor)) {
            throw new IllegalArgumentException("Bundle '%s' has no descriptor file".formatted(bundleKey));
        }
        Path targetFile = targetParent.resolve(descriptor.getFileName()).toAbsolutePath().normalize();
        if (targetFile.equals(descriptor.toAbsolutePath().normalize())) {
            return;
        }
        if (Files.exists(targetFile)) {
            throw new IllegalArgumentException("Target already exists");
        }
        Files.move(descriptor, targetFile);
        reload();
    }

    public synchronized void deleteBundle(String bundleKey) throws IOException {
        BundleCatalogEntry entry = bundleEntry(bundleKey);
        Path bundleDir = entry.bundleDir();
        if (bundleDir != null && Files.isDirectory(bundleDir)) {
            clearDirectory(bundleDir);
            Files.deleteIfExists(bundleDir);
            reload();
            return;
        }
        Path descriptor = entry.descriptorFile();
        if (descriptor == null || !Files.isRegularFile(descriptor)) {
            throw new IllegalArgumentException("Bundle '%s' not found".formatted(bundleKey));
        }
        Files.deleteIfExists(descriptor);
        reload();
    }

    public synchronized BundleDownload downloadBundle(String bundleKey) throws IOException {
        BundleCatalogEntry entry = bundleEntry(bundleKey);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Path bundleDir = entry.bundleDir();
        if (bundleDir != null && Files.isDirectory(bundleDir)) {
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
        } else {
            Path descriptor = entry.descriptorFile();
            if (descriptor == null || !Files.isRegularFile(descriptor)) {
                throw new IllegalArgumentException("Bundle '%s' not found".formatted(bundleKey));
            }
            try (ZipOutputStream zip = new ZipOutputStream(out)) {
                String entryName = descriptor.getFileName() != null ? descriptor.getFileName().toString() : "scenario.yaml";
                zip.putNextEntry(new ZipEntry(entryName));
                Files.copy(descriptor, zip);
                zip.closeEntry();
            }
        }
        return new BundleDownload(out.toByteArray(), fallbackBundleName(entry.bundlePath()) + "-bundle.zip");
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

    private DefunctResult determineDefunct(Scenario scenario) {
        String scenarioId = scenario.getId();
        if (scenarioId == null || scenarioId.isBlank()) {
            return DefunctResult.because("Scenario is missing a required 'id' field");
        }

        SwarmTemplate template = scenario.getTemplate();
        if (template == null) {
            logger.warn("Scenario '{}' has no swarm template defined; marking as defunct", scenarioId);
            return DefunctResult.because("Scenario has no swarm template defined");
        }

        List<String> reasons = new ArrayList<>();
        checkImageReference(scenarioId, "controller", template.image(), reasons);

        if (template.bees() != null) {
            for (Bee bee : template.bees()) {
                checkImageReference(scenarioId, "bee '" + bee.role() + "'", bee.image(), reasons);
            }
        }

        if (!reasons.isEmpty()) {
            String reason = String.join("; ", reasons);
            logger.warn("Scenario '{}' marked as defunct: {}", scenarioId, reason);
            return DefunctResult.because(reason);
        }

        return DefunctResult.ok();
    }

    private Scenario applyDefaultImageTag(Scenario scenario) {
        if (defaultImageTag == null || scenario == null) {
            return scenario;
        }
        SwarmTemplate template = scenario.getTemplate();
        if (template == null) {
            return scenario;
        }

        String controllerImage = appendDefaultTag(template.image());
        boolean changed = !Objects.equals(controllerImage, template.image());

        List<Bee> bees = template.bees();
        List<Bee> updatedBees = bees;
        if (bees != null && !bees.isEmpty()) {
            updatedBees = new ArrayList<>(bees.size());
            for (Bee bee : bees) {
                String updatedImage = appendDefaultTag(bee.image());
                if (!Objects.equals(updatedImage, bee.image())) {
                    changed = true;
                    updatedBees.add(new Bee(
                            bee.id(),
                            bee.role(),
                            updatedImage,
                            bee.work(),
                            bee.ports(),
                            bee.env(),
                            bee.config()
                    ));
                } else {
                    updatedBees.add(bee);
                }
            }
        }

        if (!changed) {
            return scenario;
        }

        SwarmTemplate updatedTemplate = new SwarmTemplate(controllerImage, updatedBees);
        return new Scenario(
                scenario.getId(),
                scenario.getName(),
                scenario.getDescription(),
                updatedTemplate,
                scenario.getTopology(),
                scenario.getTrafficPolicy(),
                scenario.getPlan()
        );
    }

    private String appendDefaultTag(String imageReference) {
        if (defaultImageTag == null || imageReference == null) {
            return imageReference;
        }
        String trimmed = imageReference.trim();
        if (trimmed.isEmpty()) {
            return imageReference;
        }
        if (hasTagOrDigest(trimmed)) {
            return trimmed;
        }
        return trimmed + ":" + defaultImageTag;
    }

    private static boolean hasTagOrDigest(String imageReference) {
        if (imageReference == null || imageReference.isBlank()) {
            return false;
        }
        int digestSep = imageReference.indexOf('@');
        if (digestSep >= 0) {
            return true;
        }
        int lastColon = imageReference.lastIndexOf(':');
        int lastSlash = imageReference.lastIndexOf('/');
        return lastColon > lastSlash;
    }

    private static String normalizeTag(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private void checkImageReference(String scenarioId,
                                     String component,
                                     String imageReference,
                                     List<String> reasons) {
        if (imageReference == null || imageReference.isBlank()) {
            logger.warn("Scenario '{}' {} image reference is missing", scenarioId, component);
            String label = component.startsWith("bee") ? component + " has no image defined" : "Controller image is not defined";
            reasons.add(label);
            return;
        }

        Optional<io.pockethive.capabilities.CapabilityCatalogueService.CapabilityResolution> resolution =
                capabilities.resolveByImageReference(imageReference);
        if (resolution.isPresent()) {
            io.pockethive.capabilities.CapabilityCatalogueService.CapabilityResolution matched = resolution.get();
            if (matched.fallbackUsed()) {
                logger.warn(
                        "Scenario '{}' {} image '{}' is using fallback capability manifest tag '{}' instead of requested tag '{}'",
                        scenarioId,
                        component,
                        imageReference,
                        matched.resolvedTag(),
                        matched.requestedTag());
            }
            return;
        }

        if (capabilities.findByImageReference(imageReference).isEmpty()) {
            logger.warn("Scenario '{}' missing capability manifest for {} image '{}'", scenarioId, component, imageReference);
            reasons.add("No capability manifest found for image '" + imageReference + "' (" + component + "). Check that this image version is installed.");
        }
    }

    private Format detectFormat(Path path) {
        String lower = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (lower.endsWith(".json")) {
            return Format.JSON;
        }
        return Format.YAML;
    }

    private Scenario read(Path path, Format format) throws IOException {
        return (format == Format.JSON ? jsonMapper : yamlMapper).readValue(path.toFile(), Scenario.class);
    }

    private void writeDescriptor(Scenario scenario, Format format, Path bundleDir) throws IOException {
        String id = scenario.getId();
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Scenario id must not be null or blank");
        }
        Files.createDirectories(bundleDir);
        Path descriptor = descriptorFile(bundleDir, format);
        if (!descriptor.startsWith(bundleDir)) {
            throw new IllegalArgumentException("Invalid scenario id");
        }
        (format == Format.JSON ? jsonMapper : yamlMapper)
            .writerWithDefaultPrettyPrinter()
            .writeValue(descriptor.toFile(), scenario);
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

    private Path descriptorFile(Path bundleDir, Format format) {
        String fileName = (format == Format.JSON) ? "scenario.json" : "scenario.yaml";
        return bundleDir.resolve(fileName).normalize();
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

    private ScenarioRecord recordForLoaded(Scenario scenario, Format format, Path descriptorFile, Path bundleDir) {
        Scenario resolved = applyDefaultImageTag(scenario);
        DefunctResult defunct = determineDefunct(resolved);
        Path descriptor = descriptorFile != null ? descriptorFile.toAbsolutePath().normalize() : null;
        Path bundle = bundleDir != null ? bundleDir.toAbsolutePath().normalize() : null;
        return new ScenarioRecord(resolved, format, defunct.defunct(), defunct.reason(), descriptor, bundle, folderPath(bundle));
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

    private void loadFromDirectory(Path directory, List<ScannedBundle> target, Set<Path> visitedDescriptors) throws IOException {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.{json,yaml,yml}")) {
            for (Path path : stream) {
                Path normalized = path.toAbsolutePath().normalize();
                if (!visitedDescriptors.add(normalized)) {
                    continue;
                }
                scanDescriptor(path, null, target);
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
                String name = path.getFileName().toString();
                if (!name.equals("scenario.yaml")
                    && !name.equals("scenario.yml")
                    && !name.equals("scenario.json")) {
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
        String folderPath = folderPathForBundleEntry(bundlePath, bundleDir == null);
        String fallbackName = fallbackBundleName(bundlePath);
        try {
            Format format = detectFormat(descriptorFile);
            Scenario scenario = read(descriptorFile, format);
            target.add(new ScannedBundle(
                    bundlePath,
                    bundlePath,
                    folderPath,
                    fallbackName,
                    format,
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
                    detectFormat(descriptorFile),
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

            ScenarioRecord record = recordForLoaded(scanned.scenario(), scanned.format(), scanned.descriptorFile(), scanned.bundleDir());
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

    private String folderPathForBundleEntry(String bundlePath, boolean descriptorOnly) {
        if (bundlePath == null || bundlePath.isBlank()) {
            return null;
        }
        int slashIndex = bundlePath.lastIndexOf('/');
        if (slashIndex < 0) {
            return null;
        }
        if (!descriptorOnly && slashIndex == bundlePath.length() - 1) {
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

    private Path pathFor(String id, Format format) {
        String fileName = sanitize(id) + (format == Format.JSON ? ".json" : ".yaml");
        Path path = storageDir.resolve(fileName).normalize();
        if (!path.startsWith(storageDir)) {
            throw new IllegalArgumentException("Invalid scenario id");
        }
        return path;
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

    public static Format formatFrom(String contentType) {
        if (contentType != null && contentType.toLowerCase().contains("yaml")) {
            return Format.YAML;
        }
        return Format.JSON;
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
        Format format = detectFormat(file);
        Scenario scenario;
        try {
            scenario = (format == Format.JSON ? jsonMapper : yamlMapper).readValue(trimmed, Scenario.class);
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
        Format format = detectFormat(file);
        Scenario scenario = read(file, format);
        if (scenario.getId() == null || scenario.getId().isBlank()) {
            throw new IllegalArgumentException("Scenario id must not be null or blank");
        }
        if (!id.equals(scenario.getId())) {
            throw new IllegalArgumentException(
                "Scenario id '" + scenario.getId() + "' does not match path id '" + id + "'");
        }

        Map<String, Object> effectivePlan = plan == null || plan.isEmpty() ? null : plan;
        scenario.setPlan(effectivePlan);

        (format == Format.JSON ? jsonMapper : yamlMapper)
            .writerWithDefaultPrettyPrinter()
            .writeValue(file.toFile(), scenario);

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

    /**
     * Reads {@code variables.yaml} for a scenario bundle.
     *
     * @return raw YAML if present, otherwise {@code null}
     */
    public String readVariablesRaw(String id) throws IOException {
        ScenarioRecord record = scenarios.get(id);
        if (record == null) {
            throw new IllegalArgumentException("Scenario '%s' not found".formatted(id));
        }
        Path bundle = record.bundleDir();
        if (bundle == null || !Files.isDirectory(bundle)) {
            return null;
        }
        Path file = bundle.resolve("variables.yaml").normalize();
        if (!file.startsWith(bundle)) {
            throw new IllegalArgumentException("Invalid variables path");
        }
        if (!Files.isRegularFile(file)) {
            return null;
        }
        return Files.readString(file);
    }

    public VariablesDocument parseVariables(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("variables.yaml must not be empty");
        }
        try {
            VariablesDocument doc = yamlMapper.readValue(raw, VariablesDocument.class);
            if (doc == null) {
                throw new IllegalArgumentException("variables.yaml parsed as null");
            }
            return doc;
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse variables.yaml", e);
        }
    }

    public VariablesValidationResult validateVariables(String scenarioId, VariablesDocument doc) throws IOException {
        Objects.requireNonNull(doc, "doc");
        List<String> warnings = new ArrayList<>();

        if (doc.version() != 1) {
            throw new IllegalArgumentException("variables.yaml version must be 1");
        }
        List<VariablesDocument.VariableDefinition> definitions =
            doc.definitions() == null ? List.of() : doc.definitions();
        if (definitions.isEmpty()) {
            throw new IllegalArgumentException("variables.yaml must contain non-empty definitions[]");
        }

        Map<String, VariablesDocument.VariableDefinition> byName = new LinkedHashMap<>();
        for (VariablesDocument.VariableDefinition def : definitions) {
            if (def == null || def.name() == null || def.name().isBlank()) {
                throw new IllegalArgumentException("variables.yaml definitions[].name must not be blank");
            }
            String name = def.name().trim();
            if (byName.put(name, def) != null) {
                throw new IllegalArgumentException("Duplicate variable definition name '%s'".formatted(name));
            }
            VariablesDocument.Scope scope = def.scope();
            if (scope == null) {
                throw new IllegalArgumentException("Variable '%s' missing scope".formatted(name));
            }
            VariablesDocument.Type type = def.type();
            if (type == null) {
                throw new IllegalArgumentException("Variable '%s' missing type".formatted(name));
            }
        }

        List<VariablesDocument.Profile> profiles = doc.profiles() == null ? List.of() : doc.profiles();
        Map<String, VariablesDocument.Profile> profilesById = new LinkedHashMap<>();
        for (VariablesDocument.Profile profile : profiles) {
            if (profile == null || profile.id() == null || profile.id().isBlank()) {
                throw new IllegalArgumentException("variables.yaml profiles[].id must not be blank");
            }
            String id = profile.id().trim();
            if (profilesById.put(id, profile) != null) {
                throw new IllegalArgumentException("Duplicate profile id '%s'".formatted(id));
            }
        }

        VariablesDocument.Values values = doc.values();
        Map<String, Map<String, Object>> global =
            values == null || values.global() == null ? Map.of() : values.global();
        Map<String, Map<String, Map<String, Object>>> sut =
            values == null || values.sut() == null ? Map.of() : values.sut();

        Set<String> knownProfiles = profilesById.keySet();
        if (!knownProfiles.isEmpty()) {
            for (String profileId : global.keySet()) {
                if (!knownProfiles.contains(profileId)) {
                    throw new IllegalArgumentException(
                        "values.global contains unknown profile '%s' (not present in profiles[])".formatted(profileId));
                }
            }
            for (String profileId : sut.keySet()) {
                if (!knownProfiles.contains(profileId)) {
                    throw new IllegalArgumentException(
                        "values.sut contains unknown profile '%s' (not present in profiles[])".formatted(profileId));
                }
            }
        }

        Set<String> allowedSutIds = new LinkedHashSet<>(listSutIds(scenarioId));
        for (Map.Entry<String, Map<String, Map<String, Object>>> entry : sut.entrySet()) {
            String profileId = entry.getKey();
            Map<String, Map<String, Object>> perSut = entry.getValue() == null ? Map.of() : entry.getValue();
            for (String sutId : perSut.keySet()) {
                if (!allowedSutIds.contains(sutId)) {
                    warnings.add("values.sut[%s] references unknown sutId '%s' (not present in bundle sut/)".formatted(
                        profileId, sutId));
                }
            }
        }

        // Reject unknown variable keys and enforce types.
        validateValueMaps(byName, global, "values.global");
        for (Map.Entry<String, Map<String, Map<String, Object>>> entry : sut.entrySet()) {
            String profileId = entry.getKey();
            Map<String, Map<String, Object>> perSut = entry.getValue() == null ? Map.of() : entry.getValue();
            for (Map.Entry<String, Map<String, Object>> sutEntry : perSut.entrySet()) {
                validateValueMap(byName, sutEntry.getValue(), "values.sut[%s][%s]".formatted(profileId, sutEntry.getKey()));
            }
        }

        boolean hasGlobal = byName.values().stream().anyMatch(d -> d.scope() == VariablesDocument.Scope.GLOBAL);
        boolean hasSut = byName.values().stream().anyMatch(d -> d.scope() == VariablesDocument.Scope.SUT);
        if ((hasGlobal || hasSut) && profilesById.isEmpty()) {
            throw new IllegalArgumentException("variables.yaml must declare profiles[] when definitions[] are present");
        }

        List<String> requiredGlobalVars = byName.values().stream()
            .filter(def -> def.scope() == VariablesDocument.Scope.GLOBAL)
            .filter(def -> Boolean.TRUE.equals(def.required()))
            .map(def -> def.name().trim())
            .toList();
        List<String> requiredSutVars = byName.values().stream()
            .filter(def -> def.scope() == VariablesDocument.Scope.SUT)
            .filter(def -> Boolean.TRUE.equals(def.required()))
            .map(def -> def.name().trim())
            .toList();

        if (!profilesById.isEmpty()) {
            for (String profileId : profilesById.keySet()) {
                if (!requiredGlobalVars.isEmpty()) {
                    Map<String, Object> perProfile = global.getOrDefault(profileId, Map.of());
                    List<String> missing = requiredGlobalVars.stream()
                        .filter(name -> perProfile.get(name) == null)
                        .toList();
                    if (!missing.isEmpty()) {
                        warnings.add("profile '%s' is missing required global variables: %s".formatted(
                            profileId, String.join(", ", missing)));
                    }
                }
                if (!requiredSutVars.isEmpty()) {
                    Map<String, Map<String, Object>> perProfile = sut.getOrDefault(profileId, Map.of());
                    for (String sutId : allowedSutIds) {
                        Map<String, Object> perSut = perProfile.getOrDefault(sutId, Map.of());
                        List<String> missing = requiredSutVars.stream()
                            .filter(name -> perSut.get(name) == null)
                            .toList();
                        if (!missing.isEmpty()) {
                            warnings.add("profile '%s' sut '%s' is missing required sut variables: %s".formatted(
                                profileId, sutId, String.join(", ", missing)));
                        }
                    }
                }
            }
        }

        return new VariablesValidationResult(List.copyOf(warnings));
    }

    private void validateValueMaps(
        Map<String, VariablesDocument.VariableDefinition> byName,
        Map<String, Map<String, Object>> valuesByProfile,
        String label
    ) {
        if (valuesByProfile == null || valuesByProfile.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Map<String, Object>> entry : valuesByProfile.entrySet()) {
            validateValueMap(byName, entry.getValue(), "%s[%s]".formatted(label, entry.getKey()));
        }
    }

    private void validateValueMap(
        Map<String, VariablesDocument.VariableDefinition> byName,
        Map<String, Object> values,
        String label
    ) {
        Map<String, Object> map = values == null ? Map.of() : values;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("%s contains blank variable name".formatted(label));
            }
            VariablesDocument.VariableDefinition def = byName.get(key);
            if (def == null) {
                throw new IllegalArgumentException("%s contains unknown variable '%s'".formatted(label, key));
            }
            Object value = entry.getValue();
            if (value == null) {
                continue;
            }
            requireType(def, value, "%s.%s".formatted(label, key));
        }
    }

    private void requireType(VariablesDocument.VariableDefinition def, Object value, String label) {
        VariablesDocument.Type type = def.type();
        switch (type) {
            case STRING -> {
                if (!(value instanceof String)) {
                    throw new IllegalArgumentException("%s must be a string".formatted(label));
                }
            }
            case BOOL -> {
                if (!(value instanceof Boolean)) {
                    throw new IllegalArgumentException("%s must be a bool".formatted(label));
                }
            }
            case INT -> {
                if (!isIntValue(value)) {
                    throw new IllegalArgumentException("%s must be an int".formatted(label));
                }
            }
            case FLOAT -> {
                if (!(value instanceof Number)) {
                    throw new IllegalArgumentException("%s must be a float".formatted(label));
                }
            }
            case OBJECT -> {
                if (!(value instanceof Map<?, ?>)) {
                    throw new IllegalArgumentException("%s must be an object".formatted(label));
                }
            }
        }
    }

    private static boolean isIntValue(Object value) {
        if (!(value instanceof Number number)) {
            return false;
        }
        if (number instanceof Integer || number instanceof Short || number instanceof Byte) {
            return true;
        }
        if (number instanceof Long l) {
            return l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE;
        }
        if (number instanceof java.math.BigInteger bigInteger) {
            return bigInteger.compareTo(java.math.BigInteger.valueOf(Integer.MIN_VALUE)) >= 0
                && bigInteger.compareTo(java.math.BigInteger.valueOf(Integer.MAX_VALUE)) <= 0;
        }
        if (number instanceof java.math.BigDecimal bigDecimal) {
            java.math.BigDecimal normalized = bigDecimal.stripTrailingZeros();
            if (normalized.scale() > 0) {
                return false;
            }
            return normalized.compareTo(java.math.BigDecimal.valueOf(Integer.MIN_VALUE)) >= 0
                && normalized.compareTo(java.math.BigDecimal.valueOf(Integer.MAX_VALUE)) <= 0;
        }
        // Reject Float/Double and other exotic Number impls to keep int strict.
        return false;
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
                throw new IllegalArgumentException("variables.yaml must not be empty");
            }
            VariablesDocument doc = parseVariables(raw);
            VariablesValidationResult validation = validateVariables(scenarioId, doc);

            Path bundle = bundleDirFor(scenarioId);
            Path file = bundle.resolve("variables.yaml").normalize();
            if (!file.startsWith(bundle)) {
                throw new IllegalArgumentException("Invalid variables path");
            }
            Files.createDirectories(bundle);
            Files.writeString(file, raw);
            return validation;
        }
    }

    public List<String> listSutIds(String scenarioId) throws IOException {
        Path bundle = bundleDirFor(scenarioId);
        Path sutDir = bundle.resolve("sut").normalize();
        if (!sutDir.startsWith(bundle) || !Files.isDirectory(sutDir)) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(sutDir)) {
            for (Path path : stream) {
                if (!Files.isDirectory(path)) {
                    continue;
                }
                String id = path.getFileName().toString();
                if (id.isBlank()) {
                    continue;
                }
                ids.add(id);
            }
        }
        ids.sort(String::compareTo);
        return List.copyOf(ids);
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
        List<String> candidates = List.of("sut.yaml", "sut.yml", "sut.json");
        Path file = null;
        for (String candidate : candidates) {
            Path path = sutDir.resolve(candidate);
            if (Files.isRegularFile(path)) {
                file = path;
                break;
            }
        }
        if (file == null) {
            throw new IllegalArgumentException("SUT '%s' in scenario '%s' has no sut.yaml".formatted(sutId, scenarioId));
        }
        try {
            ObjectMapper mapper = detectFormat(file) == Format.JSON ? jsonMapper : yamlMapper;
            SutEnvironment env = mapper.readValue(file.toFile(), SutEnvironment.class);
            if (env == null) {
                throw new IllegalArgumentException("sut.yaml parsed as null");
            }
            if (env.id() == null || env.id().isBlank()) {
                throw new IllegalArgumentException("sut.yaml id must not be blank");
            }
            if (!sutId.equals(env.id())) {
                throw new IllegalArgumentException(
                    "sut.yaml id '%s' does not match directory name '%s'".formatted(env.id(), sutId));
            }
            return env;
        } catch (Exception e) {
            throw new IllegalArgumentException(
                "Failed to parse SUT '%s' for scenario '%s'".formatted(sutId, scenarioId), e);
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
        Path file = sutDir.resolve("sut.yaml").normalize();
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
            env = yamlMapper.readValue(raw, SutEnvironment.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse sut.yaml", e);
        }
        if (env == null || env.id() == null || env.id().isBlank()) {
            throw new IllegalArgumentException("sut.yaml id must not be blank");
        }
        if (!sutId.equals(env.id())) {
            throw new IllegalArgumentException("sut.yaml id '%s' does not match directory name '%s'".formatted(env.id(), sutId));
        }

        Path bundle = bundleDirFor(scenarioId);
        Path sutDir = bundle.resolve("sut").resolve(sutId).normalize();
        if (!sutDir.startsWith(bundle)) {
            throw new IllegalArgumentException("Invalid sutId");
        }
        Files.createDirectories(sutDir);
        Path file = sutDir.resolve("sut.yaml").normalize();
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
        UploadedBundle uploaded = unpackBundle(zipBytes, null);
        try {
            String id = uploaded.scenario().getId();
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("Scenario id must not be null or blank");
            }
            if (hasDiscoveredScenarioId(id)) {
                throw new IllegalArgumentException("Scenario '%s' already exists".formatted(id));
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
            List<String> candidates = List.of("scenario.yaml", "scenario.yml", "scenario.json");
            for (String name : candidates) {
                Path candidate = bundleDir.resolve(name);
                if (Files.isRegularFile(candidate)) {
                    return candidate;
                }
            }
        }
        throw new IllegalArgumentException("Scenario descriptor not found for scenario '%s'".formatted(id));
    }

    public Scenario replaceBundleFromZip(String expectedId, byte[] zipBytes) throws IOException {
        UploadedBundle uploaded = unpackBundle(zipBytes, expectedId);
        try {
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

            ScenarioDescriptor descriptor = findScenarioDescriptor(tempRoot);
            Scenario scenario = descriptor.scenario();
            String id = scenario.getId();
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("Scenario id must not be null or blank");
            }
            if (expectedId != null && !expectedId.equals(id)) {
                throw new IllegalArgumentException(
                        "Scenario id '%s' in bundle does not match expected id '%s'".formatted(id, expectedId));
            }
            validateBundleExtras(id, descriptor.rootDir());
            return new UploadedBundle(scenario, descriptor.rootDir(), tempRoot);
        } catch (IOException | RuntimeException e) {
            clearDirectory(tempRoot);
            Files.deleteIfExists(tempRoot);
            throw e;
        }
    }

    private void validateBundleExtras(String scenarioId, Path bundleRoot) throws IOException {
        if (bundleRoot == null || !Files.isDirectory(bundleRoot)) {
            return;
        }
        Path variables = bundleRoot.resolve("variables.yaml").normalize();
        if (variables.startsWith(bundleRoot) && Files.isRegularFile(variables)) {
            String raw = Files.readString(variables);
            VariablesDocument doc = parseVariables(raw);
            if (doc.version() != 1) {
                throw new IllegalArgumentException("variables.yaml version must be 1");
            }
            List<VariablesDocument.VariableDefinition> definitions =
                doc.definitions() == null ? List.of() : doc.definitions();
            if (definitions.isEmpty()) {
                throw new IllegalArgumentException("variables.yaml must contain non-empty definitions[]");
            }

            Map<String, VariablesDocument.VariableDefinition> byName = new LinkedHashMap<>();
            for (VariablesDocument.VariableDefinition def : definitions) {
                if (def == null || def.name() == null || def.name().isBlank()) {
                    throw new IllegalArgumentException("variables.yaml definitions[].name must not be blank");
                }
                String name = def.name().trim();
                if (byName.put(name, def) != null) {
                    throw new IllegalArgumentException("Duplicate variable definition name '%s'".formatted(name));
                }
                if (def.scope() == null) {
                    throw new IllegalArgumentException("Variable '%s' missing scope".formatted(name));
                }
                if (def.type() == null) {
                    throw new IllegalArgumentException("Variable '%s' missing type".formatted(name));
                }
            }

            VariablesDocument.Values values = doc.values();
            Map<String, Map<String, Object>> global =
                values == null || values.global() == null ? Map.of() : values.global();
            Map<String, Map<String, Map<String, Object>>> sut =
                values == null || values.sut() == null ? Map.of() : values.sut();

            validateValueMaps(byName, global, "values.global");
            for (Map.Entry<String, Map<String, Map<String, Object>>> entry : sut.entrySet()) {
                String profileId = entry.getKey();
                Map<String, Map<String, Object>> perSut = entry.getValue() == null ? Map.of() : entry.getValue();
                for (Map.Entry<String, Map<String, Object>> sutEntry : perSut.entrySet()) {
                    validateValueMap(byName, sutEntry.getValue(), "values.sut[%s][%s]".formatted(profileId, sutEntry.getKey()));
                }
            }
        }

        Path sutDir = bundleRoot.resolve("sut").normalize();
        if (!sutDir.startsWith(bundleRoot) || !Files.isDirectory(sutDir)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(sutDir)) {
            for (Path path : stream) {
                if (!Files.isDirectory(path)) {
                    continue;
                }
                String sutId = path.getFileName().toString();
                if (sutId == null || sutId.isBlank()) {
                    continue;
                }
                Path file = path.resolve("sut.yaml");
                if (!Files.isRegularFile(file)) {
                    continue;
                }
                try {
                    SutEnvironment env = yamlMapper.readValue(file.toFile(), SutEnvironment.class);
                    if (env == null || env.id() == null || env.id().isBlank()) {
                        throw new IllegalArgumentException("sut.yaml id must not be blank");
                    }
                    if (!sutId.equals(env.id())) {
                        throw new IllegalArgumentException(
                            "sut/%s/sut.yaml id '%s' does not match directory name".formatted(sutId, env.id()));
                    }
                } catch (Exception e) {
                    throw new IllegalArgumentException(
                        "Failed to parse bundle-local SUT '%s' in scenario '%s'".formatted(sutId, scenarioId), e);
                }
            }
        }
    }

    private ScenarioDescriptor findScenarioDescriptor(Path root) throws IOException {
        ScenarioDescriptor found = null;
        try (Stream<Path> stream = Files.walk(root)) {
            for (Path path : (Iterable<Path>) stream::iterator) {
                if (!Files.isRegularFile(path)) {
                    continue;
                }
                String name = path.getFileName().toString();
                if (!name.equals("scenario.yaml")
                    && !name.equals("scenario.yml")
                    && !name.equals("scenario.json")) {
                    continue;
                }
                Format format = detectFormat(path);
                Scenario scenario = read(path, format);
                if (found != null) {
                    throw new IllegalArgumentException("Bundle contains multiple scenario descriptors");
                }
                found = new ScenarioDescriptor(scenario, path.getParent());
            }
        }
        if (found == null) {
            throw new IllegalArgumentException("Bundle does not contain a scenario.yaml, scenario.yml or scenario.json");
        }
        return found;
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
        Format format,
        boolean defunct,
        String defunctReason,
        Path descriptorFile,
        Path bundleDir,
        String folderPath
    ) { }

    private record DefunctResult(boolean defunct, String reason) {
        static DefunctResult ok() {
            return new DefunctResult(false, null);
        }

        static DefunctResult because(String reason) {
            return new DefunctResult(true, reason);
        }
    }

    private record ScannedBundle(
        String bundleKey,
        String bundlePath,
        String folderPath,
        String fallbackName,
        Format format,
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

    private record ScenarioDescriptor(Scenario scenario, Path rootDir) { }

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
