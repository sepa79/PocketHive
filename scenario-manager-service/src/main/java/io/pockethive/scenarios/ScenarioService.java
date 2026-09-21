package io.pockethive.scenarios;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.pockethive.controlplane.filesystem.RuntimeFilesystemLayout;
import io.pockethive.scenarios.validation.ScenarioBundleValidator;
import io.pockethive.scenarios.validation.ValidationFinding;
import io.pockethive.swarm.model.SwarmTemplate;
import io.pockethive.swarm.model.RuntimeFilesystemContract;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Responsibility: Own filesystem-backed scenario discovery, catalogue state, access projections, and descriptor lifecycle.
 * Must not: Edit bundle workspaces, resolve variables, manage bundle-local SUTs, publish ZIPs, or materialize runtimes.
 * Contract: docs/scenarios/SCENARIO_CONTRACT.md and docs/scenarios/SCENARIO_MANAGER_BUNDLE_REST.md.
 */
@Service
public class ScenarioService {
    private static final Logger logger = LoggerFactory.getLogger(ScenarioService.class);
    private static final String QUARANTINE_FOLDER = "quarantine";
    private final Path storageDir;
    private final Path testStorageDir;
    private final Path bundleRootDir;
    private final RuntimeFilesystemLayout runtimeLayout;
    private final boolean showTestScenarios;
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
    private final ScenarioBundleValidator bundleValidator;
    private final Map<String, ScenarioRecord> scenarios = new ConcurrentHashMap<>();
    private volatile List<BundleCatalogEntry> bundleCatalog = List.of();

    @Autowired
    public ScenarioService(@Value("${scenarios.dir:scenarios}") String dir,
                           @Value("${scenarios.show-test:true}") boolean showTestScenarios,
                           @Value("${" + RuntimeFilesystemContract.LOCAL_ROOT_ENV + ":}") String runtimeRoot,
                           ScenarioBundleValidator bundleValidator) throws IOException {
        this(Paths.get(dir),
             RuntimeFilesystemLayout.of(runtimeRoot, runtimeRoot),
             showTestScenarios,
             bundleValidator);
    }

    ScenarioService(String dir,
                    ScenarioBundleValidator bundleValidator) throws IOException {
        this(Paths.get(dir), testLayout(dir), true, bundleValidator);
    }

    ScenarioService(String dir,
                    Path runtimeRoot,
                    ScenarioBundleValidator bundleValidator) throws IOException {
        this(Paths.get(dir), testLayout(runtimeRoot), true, bundleValidator);
    }

    ScenarioService(Path dir,
                    RuntimeFilesystemLayout runtimeLayout,
                    boolean showTestScenarios,
                    ScenarioBundleValidator bundleValidator) throws IOException {
        Path normalizedDir = dir.toAbsolutePath().normalize();
        this.storageDir = normalizedDir;
        this.testStorageDir = normalizedDir.resolve("e2e");
        this.bundleRootDir = normalizedDir;
        this.runtimeLayout = runtimeLayout;
        this.showTestScenarios = showTestScenarios;
        Files.createDirectories(this.storageDir);
        if (this.showTestScenarios) {
            Files.createDirectories(this.testStorageDir);
        }
        Files.createDirectories(this.bundleRootDir);
        Files.createDirectories(this.bundleRootDir.resolve(QUARANTINE_FOLDER));
        Files.createDirectories(this.runtimeLayout.localRoot());
        this.bundleValidator = Objects.requireNonNull(bundleValidator, "bundleValidator");
    }

    private static RuntimeFilesystemLayout testLayout(String storageDir) {
        return testLayout(Paths.get(storageDir).toAbsolutePath().normalize().resolve("runtime"));
    }

    private static RuntimeFilesystemLayout testLayout(Path runtimeRoot) {
        String root = runtimeRoot.toAbsolutePath().normalize().toString();
        return RuntimeFilesystemLayout.of(root, root);
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
            ScenarioFileTreeOperations.clear(bundleDir);
            Files.deleteIfExists(bundleDir);
        } else {
            Path descriptor = removed.descriptorFile();
            if (descriptor != null) {
            Files.deleteIfExists(descriptor);
            }
        }
        reload();
    }

    Set<Path> scenarioBundleRoots() {
        Set<Path> roots = new LinkedHashSet<>();
        for (BundleCatalogEntry entry : bundleCatalog) {
            Path dir = entry.bundleDir();
            if (dir != null) {
                roots.add(dir.toAbsolutePath().normalize());
            }
        }
        return roots;
    }

    Path bundleRootDirectory() {
        return bundleRootDir.toAbsolutePath().normalize();
    }

    Path testStorageDirectory() {
        return testStorageDir.toAbsolutePath().normalize();
    }

    Path runtimeRootDirectory() {
        return runtimeLayout.localRoot();
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

    ScenarioBundleWorkspaceLocation bundleWorkspaceLocation(String bundleKey) {
        BundleCatalogEntry entry = bundleEntry(bundleKey);
        Path root = entry.bundleDir() == null ? null : entry.bundleDir().toAbsolutePath().normalize();
        return new ScenarioBundleWorkspaceLocation(entry.bundleKey(), entry.bundlePath(), root);
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
        return runtimeLayout.swarmRoot(cleaned);
    }

    ScenarioRuntimeCandidate runtimeCandidate(String scenarioId) {
        ScenarioRecord record = scenarios.get(scenarioId);
        BundleCatalogEntry entry = record == null
            ? bundleCatalog.stream()
                .filter(candidate -> Objects.equals(candidate.scenarioId(), scenarioId))
                .findFirst()
                .orElse(null)
            : bundleCatalog.stream()
                .filter(candidate -> candidate.scenarioRecord() == record)
                .findFirst()
                .orElse(null);
        if (record == null && entry == null) {
            return null;
        }
        ScenarioRecord effectiveRecord = record != null ? record : entry.scenarioRecord();
        Path bundleDirectory = effectiveRecord != null ? effectiveRecord.bundleDir() : entry.bundleDir();
        return new ScenarioRuntimeCandidate(
            scenarioId,
            entry != null ? entry.bundleKey() : null,
            entry != null ? entry.bundlePath() : null,
            bundleDirectory,
            entry != null ? catalogOnlyDefunctFindings(entry) : List.of());
    }

    private void loadFromBundles(Path bundleRoot, List<ScannedBundle> target, Set<Path> visitedDescriptors) throws IOException {
        if (!Files.isDirectory(bundleRoot)) {
            return;
        }
        Path normalizedRoot = bundleRoot.toAbsolutePath().normalize();
        Path normalizedStorageDir = storageDir.toAbsolutePath().normalize();
        Path normalizedTestDir = testStorageDir.toAbsolutePath().normalize();
        Path normalizedRuntimeRoot = runtimeLayout.localRoot();
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

    String fallbackBundleName(String bundlePath) {
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

    boolean hasDiscoveredScenarioId(String scenarioId) {
        if (scenarioId == null || scenarioId.isBlank()) {
            return false;
        }
        return bundleCatalog.stream().anyMatch(entry -> scenarioId.equals(entry.scenarioId()));
    }

    Path scenarioDescriptorFile(String id) throws IOException {
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

    void writeScenarioDescriptor(Path file, Scenario scenario) throws IOException {
        yamlMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), scenario);
    }

    Path bundleDirForExistingOrDefault(String scenarioId) {
        ScenarioRecord existing = scenarios.get(scenarioId);
        return existing != null && existing.bundleDir() != null ? existing.bundleDir() : bundleDir(scenarioId);
    }

    ScenarioBundleValidationCandidate validationCandidate(String bundleKey) {
        BundleCatalogEntry entry = bundleEntry(bundleKey);
        ScenarioRecord record = entry.scenarioRecord();
        return new ScenarioBundleValidationCandidate(
            entry.bundleKey(),
            entry.bundlePath(),
            entry.bundleDir(),
            record != null ? record.scenario() : null,
            entry.defunctReason(),
            catalogOnlyDefunctFindings(entry));
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

}
