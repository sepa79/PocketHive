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
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

@Service
public class ScenarioService {
    public enum Format { JSON, YAML }

    private static final Logger logger = LoggerFactory.getLogger(ScenarioService.class);

    private final Path storageDir;
    private final Path testStorageDir;
    private final boolean showTestScenarios;
    private final ObjectMapper jsonMapper = new ObjectMapper();
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
    private final CapabilityCatalogueService capabilities;
    private final Map<String, ScenarioRecord> scenarios = new ConcurrentHashMap<>();

    @Autowired
    public ScenarioService(@Value("${scenarios.dir:scenarios}") String dir,
                           @Value("${scenarios.show-test:true}") boolean showTestScenarios,
                           CapabilityCatalogueService capabilities) throws IOException {
        this(Paths.get(dir), showTestScenarios, capabilities);
    }

    ScenarioService(String dir,
                    CapabilityCatalogueService capabilities) throws IOException {
        this(Paths.get(dir), true, capabilities);
    }

    private ScenarioService(Path dir,
                            boolean showTestScenarios,
                            CapabilityCatalogueService capabilities) throws IOException {
        this.storageDir = dir;
        this.testStorageDir = dir.resolve("e2e");
        this.showTestScenarios = showTestScenarios;
        Files.createDirectories(this.storageDir);
        if (this.showTestScenarios) {
            Files.createDirectories(this.testStorageDir);
        }
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
        (format == Format.JSON ? jsonMapper : yamlMapper)
            .writerWithDefaultPrettyPrinter()
            .writeValue(pathFor(scenario.getId(), format).toFile(), scenario);
    }

    private ScenarioSummary toSummary(Scenario scenario) {
        return new ScenarioSummary(scenario.getId(), scenario.getName());
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

    private record ScenarioRecord(Scenario scenario, Format format, boolean defunct) { }
}
