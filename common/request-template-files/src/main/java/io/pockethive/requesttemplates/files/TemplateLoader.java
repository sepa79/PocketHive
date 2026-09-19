package io.pockethive.requesttemplates.files;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.pockethive.requesttemplates.RequestTemplateException;
import io.pockethive.requesttemplates.RequestTemplateParser;
import io.pockethive.requesttemplates.RequestTemplateProblemKind;
import io.pockethive.requesttemplates.TemplateDefinition;
import io.pockethive.worker.sdk.auth.AuthFailureException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Responsibility: load template files with provenance and translate canonical parser failures for runtime callers.
 * Must not: own protocol/auth/required-field validation or silently skip missing roots/duplicate keys.
 * Contract: RESP-REQUEST-TEMPLATE-PARSE — docs/architecture/runtime-responsibilities.md#resp-request-template-parse.
 */
public final class TemplateLoader {
    private static final String INLINE_AUTH_FAILURE_REASON = "legacy-inline-auth";

    private final RequestTemplateParser parser;
    private final ObjectMapper jsonMapper = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private final ObjectMapper yamlMapper = new ObjectMapper(YAMLFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());

    public TemplateLoader() {
        this(new RequestTemplateParser());
    }

    public TemplateLoader(RequestTemplateParser parser) {
        this.parser = Objects.requireNonNull(parser, "parser");
    }

    public Map<String, TemplateDefinition> load(String root) {
        Map<String, TemplateDefinition> definitions = new LinkedHashMap<>();
        loadWithSources(root).forEach((key, loaded) -> definitions.put(key, loaded.definition()));
        return Map.copyOf(definitions);
    }

    public Map<String, LoadedTemplate> loadWithSources(String root) {
        Path directory = Path.of(Objects.requireNonNull(root, "root"));
        if (root.isBlank() || !Files.isDirectory(directory)) {
            throw new IllegalArgumentException("Template root must be an existing directory: " + root);
        }
        Map<String, LoadedTemplate> templates = new LinkedHashMap<>();
        try (var files = Files.walk(directory)) {
            for (Path path : files.filter(Files::isRegularFile).filter(TemplateLoader::isTemplateFile).sorted().toList()) {
                TemplateDefinition definition = parseFile(path);
                String key = RequestTemplateParser.key(definition.serviceId(), definition.callId());
                LoadedTemplate loaded = new LoadedTemplate(definition, path.toAbsolutePath().normalize());
                LoadedTemplate previous = templates.putIfAbsent(key, loaded);
                if (previous != null) {
                    throw new IllegalArgumentException("Duplicate request template " + key + " in "
                        + previous.sourcePath() + " and " + loaded.sourcePath());
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to scan templates under " + directory, e);
        }
        return Map.copyOf(templates);
    }

    private TemplateDefinition parseFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        ObjectMapper decoder = name.endsWith(".json") ? jsonMapper : yamlMapper;
        try {
            Map<String, Object> document = decoder.readValue(path.toFile(), new TypeReference<>() { });
            return parser.parse(document);
        } catch (IOException | IllegalArgumentException e) {
            Throwable cause = e;
            if (e instanceof RequestTemplateException failure
                && failure.problems().stream().anyMatch(problem ->
                    problem.kind() == RequestTemplateProblemKind.INLINE_AUTH)) {
                cause = AuthFailureException.configuration(INLINE_AUTH_FAILURE_REASON,
                    "Template " + path + ": " + failure.getMessage(), failure);
            }
            throw new IllegalStateException("Failed to parse template " + path, cause);
        }
    }

    private static boolean isTemplateFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return !name.equals("authprofiles.yaml") && !name.equals("authprofiles.yml")
            && (name.endsWith(".json") || name.endsWith(".yaml") || name.endsWith(".yml"));
    }
}
