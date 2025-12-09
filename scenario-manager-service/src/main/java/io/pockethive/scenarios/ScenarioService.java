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
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
public class ScenarioService {
    public enum Format { JSON, YAML }

    private static final Logger logger = LoggerFactory.getLogger(ScenarioService.class);

    private final Path storageDir;
    private final Path testStorageDir;
    private final Path bundleRootDir;
    private final Path runtimeRootDir;
    private final boolean showTestScenarios;
    private final ObjectMapper jsonMapper = new ObjectMapper();
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
    private final CapabilityCatalogueService capabilities;
    private final Map<String, ScenarioRecord> scenarios = new ConcurrentHashMap<>();

    @Autowired
    public ScenarioService(@Value("${scenarios.dir:scenarios}") String dir,
                           @Value("${scenarios.show-test:true}") boolean showTestScenarios,
                           @Value("${pockethive.scenarios.runtime-root}") String runtimeRoot,
                           CapabilityCatalogueService capabilities) throws IOException {
        this(Paths.get(dir), Paths.get(runtimeRoot), showTestScenarios, capabilities);
    }

    ScenarioService(String dir,
                    String runtimeRoot,
                    CapabilityCatalogueService capabilities) throws IOException {
        this(Paths.get(dir), Paths.get(runtimeRoot), true, capabilities);
    }

    private ScenarioService(Path dir,
                            Path runtimeRoot,
                            boolean showTestScenarios,
                            CapabilityCatalogueService capabilities) throws IOException {
        this.storageDir = dir;
        this.testStorageDir = dir.resolve("e2e");
        this.bundleRootDir = dir.resolve("bundles");
        this.runtimeRootDir = runtimeRoot.toAbsolutePath().normalize();
        this.showTestScenarios = showTestScenarios;
        Files.createDirectories(this.storageDir);
        if (this.showTestScenarios) {
            Files.createDirectories(this.testStorageDir);
        }
        Files.createDirectories(this.bundleRootDir);
        Files.createDirectories(this.runtimeRootDir);
        this.capabilities = capabilities;
    }

    @PostConstruct
    void init() throws IOException {
        reload();
    }

    public synchronized void reload() throws IOException {
        Map<String, ScenarioRecord> loaded = new HashMap<>();

        loadFromDirectory(storageDir, loaded);
        if (showTestScenarios && Files.isDirectory(testStorageDir)) {
            loadFromDirectory(testStorageDir, loaded);
        }
        loadFromBundles(bundleRootDir, loaded);

        scenarios.clear();
        scenarios.putAll(loaded);

        long available = loaded.values().stream().filter(record -> !record.defunct()).count();
        logger.info("Loaded {} scenario(s) from {}{} ({} available)",
            loaded.size(),
            storageDir,
            (showTestScenarios ? " and " + testStorageDir : ""),
            available);
    }

    public List<ScenarioSummary> list() {
        return listAvailableSummaries();
    }

    public List<ScenarioSummary> listAvailableSummaries() {
        return streamRecords()
                .filter(record -> !record.defunct())
                .map(record -> toSummary(record.scenario()))
                .sorted(Comparator.comparing(ScenarioSummary::name))
                .toList();
    }

    public List<ScenarioSummary> listAllSummaries() {
        return streamRecords()
                .map(record -> toSummary(record.scenario()))
                .sorted(Comparator.comparing(ScenarioSummary::name))
                .toList();
    }

    public List<ScenarioSummary> listDefunctSummaries() {
        return streamRecords()
                .filter(ScenarioRecord::defunct)
                .map(record -> toSummary(record.scenario()))
                .sorted(Comparator.comparing(ScenarioSummary::name))
                .toList();
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
        ScenarioRecord record = recordFor(scenario, format);
        if (scenarios.putIfAbsent(scenario.getId(), record) != null) {
            throw new IllegalArgumentException("Scenario already exists");
        }

        try {
            write(scenario, format);
        } catch (IOException e) {
            scenarios.remove(scenario.getId(), record);
            throw e;
        }

        return scenario;
    }

    public Scenario update(String id, Scenario scenario, Format format) throws IOException {
        scenario.setId(id);
        ScenarioRecord record = recordFor(scenario, format);
        ScenarioRecord previous = scenarios.put(id, record);

        try {
            write(scenario, format);
        } catch (IOException e) {
            if (previous == null) {
                scenarios.remove(id, record);
            } else {
                scenarios.put(id, previous);
            }
            throw e;
        }

        return scenario;
    }

    public void delete(String id) throws IOException {
        ScenarioRecord removed = scenarios.remove(id);
        Format format = removed != null ? removed.format() : null;
        if (format != null) {
            Files.deleteIfExists(pathFor(id, format));
        }
        Path bundleDir = bundleDir(id);
        if (Files.isDirectory(bundleDir)) {
            clearDirectory(bundleDir);
            Files.deleteIfExists(bundleDir);
        }
    }

    private ScenarioRecord recordFor(Scenario scenario, Format format) {
        boolean defunct = determineDefunct(scenario);
        return new ScenarioRecord(scenario, format, defunct);
    }

    private boolean determineDefunct(Scenario scenario) {
        String scenarioId = scenario.getId();
        if (scenarioId == null) {
            return true;
        }

        SwarmTemplate template = scenario.getTemplate();
        if (template == null) {
            logger.warn("Scenario '{}' has no swarm template defined; marking as defunct", scenarioId);
            return true;
        }

        List<String> missingReferences = new ArrayList<>();

        checkImageReference(scenarioId, "swarm controller", template.image(), missingReferences);

        if (template.bees() != null) {
            for (Bee bee : template.bees()) {
                checkImageReference(scenarioId, "bee '" + bee.role() + "'", bee.image(), missingReferences);
            }
        }

        if (!missingReferences.isEmpty()) {
            logger.warn("Scenario '{}' marked as defunct; missing capability manifests for {}", scenarioId, missingReferences);
            return true;
        }

        return false;
    }

    private void checkImageReference(String scenarioId,
                                     String component,
                                     String imageReference,
                                     List<String> missingReferences) {
        if (imageReference == null || imageReference.isBlank()) {
            logger.warn("Scenario '{}' {} image reference is missing", scenarioId, component);
            missingReferences.add(component + " (missing image)");
            return;
        }

        if (capabilities.findByImageReference(imageReference).isEmpty()) {
            logger.warn("Scenario '{}' missing capability manifest for {} image '{}'", scenarioId, component, imageReference);
            missingReferences.add(component + " -> " + imageReference);
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

    private void write(Scenario scenario, Format format) throws IOException {
        String id = scenario.getId();
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Scenario id must not be null or blank");
        }
        // Persist as a bundle descriptor under scenarios.dir/bundles/<id>/scenario.(yaml|json),
        // which is the single source of truth for raw/plan/runtime operations.
        Path bundleDir = bundleDir(id);
        Files.createDirectories(bundleDir);
        String fileName = (format == Format.JSON) ? "scenario.json" : "scenario.yaml";
        Path descriptor = bundleDir.resolve(fileName).normalize();
        if (!descriptor.startsWith(bundleDir)) {
            throw new IllegalArgumentException("Invalid scenario id");
        }
        (format == Format.JSON ? jsonMapper : yamlMapper)
            .writerWithDefaultPrettyPrinter()
            .writeValue(descriptor.toFile(), scenario);
    }

    private ScenarioSummary toSummary(Scenario scenario) {
        return new ScenarioSummary(scenario.getId(), scenario.getName());
    }

    Path bundleDir(String id) {
        String cleaned = sanitize(id);
        Path dir = bundleRootDir.resolve(cleaned).normalize();
        if (!dir.startsWith(bundleRootDir)) {
            throw new IllegalArgumentException("Invalid scenario id");
        }
        return dir;
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

        Path source = bundleDir(scenarioId);
        if (Files.isDirectory(source)) {
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

    private void loadFromDirectory(Path directory, Map<String, ScenarioRecord> target) throws IOException {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.{json,yaml,yml}")) {
            for (Path path : stream) {
                Format format = detectFormat(path);
                Scenario scenario = read(path, format);
                ScenarioRecord record = recordFor(scenario, format);
                ScenarioRecord previous = target.put(scenario.getId(), record);
                if (previous != null) {
                    logger.warn("Duplicate scenario id '{}' found while loading {}; keeping latest", scenario.getId(), path);
                }
            }
        }
    }

    private void loadFromBundles(Path bundleRoot, Map<String, ScenarioRecord> target) throws IOException {
        if (!Files.isDirectory(bundleRoot)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(bundleRoot)) {
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
                ScenarioRecord record = recordFor(scenario, format);
                ScenarioRecord previous = target.put(scenario.getId(), record);
                if (previous != null) {
                    logger.warn("Duplicate scenario id '{}' found while loading {}; keeping latest", scenario.getId(), path);
                }
            }
        }
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

        (format == Format.JSON ? jsonMapper : yamlMapper)
                .writerWithDefaultPrettyPrinter()
                .writeValue(file.toFile(), scenario);

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

    public Scenario createBundleFromZip(byte[] zipBytes) throws IOException {
        UploadedBundle uploaded = unpackBundle(zipBytes, null);
        try {
            String id = uploaded.scenario().getId();
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("Scenario id must not be null or blank");
            }
            if (scenarios.containsKey(id)) {
                throw new IllegalArgumentException("Scenario '%s' already exists".formatted(id));
            }
            writeBundle(uploaded);
            reload();
            ScenarioRecord record = scenarios.get(id);
            return record != null ? record.scenario() : uploaded.scenario();
        } finally {
            cleanupUploaded(uploaded);
        }
    }

    private Path scenarioDescriptorFile(String id) throws IOException {
        Path bundleDir = bundleDir(id);
        if (!Files.isDirectory(bundleDir)) {
            throw new IllegalArgumentException("Bundle directory not found for scenario '" + id + "'");
        }
        List<String> candidates = List.of("scenario.yaml", "scenario.yml", "scenario.json");
        for (String name : candidates) {
            Path candidate = bundleDir.resolve(name);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("Scenario descriptor not found for scenario '" + id + "'");
    }

    public Scenario replaceBundleFromZip(String expectedId, byte[] zipBytes) throws IOException {
        UploadedBundle uploaded = unpackBundle(zipBytes, expectedId);
        try {
            String id = uploaded.scenario().getId();
            writeBundle(uploaded);
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
        Path tempRoot = Files.createTempDirectory(bundleRootDir, "upload-");
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
            return new UploadedBundle(scenario, descriptor.rootDir(), tempRoot);
        } catch (IOException | RuntimeException e) {
            clearDirectory(tempRoot);
            Files.deleteIfExists(tempRoot);
            throw e;
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

    private void writeBundle(UploadedBundle uploaded) throws IOException {
        String id = uploaded.scenario().getId();
        Path sourceDir = uploaded.rootDir();
        Path targetDir = bundleDir(id);
        if (Files.exists(targetDir)) {
            clearDirectory(targetDir);
        }
        Files.createDirectories(targetDir);
        copyDirectory(sourceDir, targetDir);
    }

    private void cleanupUploaded(UploadedBundle uploaded) throws IOException {
        Path tempRoot = uploaded.tempRoot();
        clearDirectory(tempRoot);
        Files.deleteIfExists(tempRoot);
    }

    private record ScenarioRecord(Scenario scenario, Format format, boolean defunct) { }

    private record ScenarioDescriptor(Scenario scenario, Path rootDir) { }

    private record UploadedBundle(Scenario scenario, Path rootDir, Path tempRoot) { }
}
