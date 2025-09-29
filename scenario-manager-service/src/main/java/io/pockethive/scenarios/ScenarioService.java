package io.pockethive.scenarios;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import io.pockethive.scenarios.model.Scenario;
import jakarta.annotation.PostConstruct;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ScenarioService {
    public enum Format { JSON, YAML }

    private final Path storageDir;
    private final ObjectMapper jsonMapper;
    private final ObjectMapper yamlMapper;
    private final Validator validator;
    private final Map<String, Scenario> scenarios = new ConcurrentHashMap<>();
    private final Map<String, Format> formats = new ConcurrentHashMap<>();

    public ScenarioService(@Value("${scenarios.dir:scenarios}") String dir, Validator validator) throws IOException {
        this.storageDir = Paths.get(dir);
        Files.createDirectories(this.storageDir);
        this.validator = validator;
        this.jsonMapper = configure(new ObjectMapper());
        this.yamlMapper = configure(new ObjectMapper(new YAMLFactory()));
    }

    @PostConstruct
    void init() throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(storageDir, "*.{json,yaml,yml}")) {
            for (Path path : stream) {
                Format format = path.toString().endsWith(".json") ? Format.JSON : Format.YAML;
                Scenario scenario = read(path, format);
                scenarios.put(scenario.getId(), scenario);
                formats.put(scenario.getId(), format);
            }
        }
    }

    public List<ScenarioSummary> list() {
        return scenarios.values().stream()
                .map(s -> new ScenarioSummary(s.getId(), s.getName()))
                .sorted(Comparator.comparing(ScenarioSummary::name))
                .toList();
    }

    public Optional<Scenario> find(String id) {
        return Optional.ofNullable(scenarios.get(id));
    }

    public Scenario create(Scenario scenario, Format format) throws IOException {
        validate(scenario);
        if (scenarios.putIfAbsent(scenario.getId(), scenario) != null) {
            throw new IllegalArgumentException("Scenario already exists");
        }
        formats.put(scenario.getId(), format);
        write(scenario, format);
        return scenario;
    }

    public Scenario update(String id, Scenario scenario, Format format) throws IOException {
        scenario.setId(id);
        validate(scenario);
        scenarios.put(id, scenario);
        formats.put(id, format);
        write(scenario, format);
        return scenario;
    }

    public void delete(String id) throws IOException {
        scenarios.remove(id);
        Format format = formats.remove(id);
        if (format != null) {
            Files.deleteIfExists(pathFor(id, format));
        }
    }

    private Scenario read(Path path, Format format) throws IOException {
        Scenario scenario = (format == Format.JSON ? jsonMapper : yamlMapper)
                .readValue(path.toFile(), Scenario.class);
        validate(scenario);
        return scenario;
    }

    private void write(Scenario scenario, Format format) throws IOException {
        (format == Format.JSON ? jsonMapper : yamlMapper)
            .writerWithDefaultPrettyPrinter()
            .writeValue(pathFor(scenario.getId(), format).toFile(), scenario);
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

    public static Format formatFrom(String contentType) {
        if (contentType != null && contentType.toLowerCase().contains("yaml")) {
            return Format.YAML;
        }
        return Format.JSON;
    }

    private ObjectMapper configure(ObjectMapper mapper) {
        mapper.findAndRegisterModules();
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);
        return mapper;
    }

    private void validate(Scenario scenario) {
        Set<ConstraintViolation<Scenario>> violations = validator.validate(scenario);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
    }
}
