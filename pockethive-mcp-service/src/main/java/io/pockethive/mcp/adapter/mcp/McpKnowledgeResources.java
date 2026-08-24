package io.pockethive.mcp.adapter.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import io.pockethive.mcp.application.EnvironmentHealthService;
import io.pockethive.mcp.application.SkillDescriptor;
import io.pockethive.mcp.application.ToolCatalogue;
import io.pockethive.mcp.config.PocketHiveMcpProperties;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

@Component
public final class McpKnowledgeResources {
    private static final String JSON = "application/json";
    private static final String MARKDOWN = "text/markdown";

    private final ToolCatalogue catalogue;
    private final EnvironmentHealthService environmentHealth;
    private final PocketHiveMcpProperties properties;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final List<KnowledgeDocument> knowledgeDocuments;
    private final String catalogueDigest;

    public McpKnowledgeResources(ToolCatalogue catalogue, EnvironmentHealthService environmentHealth,
                                 PocketHiveMcpProperties properties,
                                 ObjectMapper mapper, Clock clock) {
        this.catalogue = catalogue;
        this.environmentHealth = environmentHealth;
        this.properties = properties;
        this.mapper = mapper;
        this.clock = clock;
        this.knowledgeDocuments = List.of(
            document("architecture", "PocketHive architecture", "docs/ARCHITECTURE.md", "ARCHITECTURE.md"),
            document("scenario-contract", "Scenario Bundle contract",
                "docs/scenarios/SCENARIO_CONTRACT.md", "scenarios/SCENARIO_CONTRACT.md"),
            document("worker-capabilities", "Worker capability catalogue contract",
                "docs/architecture/workerCapabilities.md", "architecture/workerCapabilities.md"),
            document("orchestrator-rest", "Orchestrator public API",
                "docs/ORCHESTRATOR-REST.md", "ORCHESTRATOR-REST.md"),
            document("scenario-manager-bundle-rest", "Scenario Manager bundle API",
                "docs/scenarios/SCENARIO_MANAGER_BUNDLE_REST.md", "scenarios/SCENARIO_MANAGER_BUNDLE_REST.md"),
            document("correlation-idempotency", "Correlation and idempotency rules",
                "docs/correlation-vs-idempotency.md", "correlation-vs-idempotency.md"));
        this.catalogueDigest = digest(canonicalCatalogue());
    }

    public List<McpServerFeatures.SyncResourceSpecification> specifications() {
        List<McpServerFeatures.SyncResourceSpecification> resources = new ArrayList<>();
        resources.add(jsonResource("pockethive://knowledge/overview", "PocketHive overview",
            "PocketHive authority boundaries and safe first actions.", overview()));
        resources.add(jsonResource("pockethive://knowledge/glossary", "PocketHive glossary",
            "Canonical PocketHive terms needed by a repository with no local PocketHive context.", glossary()));
        knowledgeDocuments.forEach(document -> resources.add(resource(document.uri(), document.title(),
            "Immutable projection of " + document.sourcePath(), MARKDOWN, document.markdown())));
        resources.add(dynamicJsonResource("pockethive://capabilities/current", "Current PocketHive capabilities",
            "Authenticated MCP identity, immutable binding, and descriptor fingerprint.", exchange ->
                currentCapabilities(McpCaller.from(exchange.transportContext()))));
        resources.add(dynamicJsonResource("pockethive://tools/catalogue", "PocketHive tool catalogue",
            "Canonical agent-facing tool descriptors visible to this grant.", exchange ->
                visibleTools(McpCaller.from(exchange.transportContext()))));
        resources.add(dynamicJsonResource("pockethive://skills/catalogue", "PocketHive connected skills",
            "Versioned skill index connected to tools visible to this grant.", exchange ->
                visibleSkills(McpCaller.from(exchange.transportContext()))));
        resources.add(dynamicJsonResource("pockethive://environment/health", "PocketHive environment health",
            "Bounded health projection for canonical services behind this environment's public ingress.",
            exchange -> environmentHealth.read()));
        catalogue.skills().values().forEach(skill -> resources.add(markdownResource(skill)));
        return List.copyOf(resources);
    }

    public String catalogueDigest() {
        return catalogueDigest;
    }

    private McpServerFeatures.SyncResourceSpecification jsonResource(String uri, String name,
                                                                      String description, Object value) {
        return resource(uri, name, description, JSON, json(value));
    }

    private McpServerFeatures.SyncResourceSpecification markdownResource(SkillDescriptor skill) {
        return resource(skill.resourceUri(), skill.name(), skill.description(), MARKDOWN, skill.markdown());
    }

    private McpServerFeatures.SyncResourceSpecification dynamicJsonResource(
        String uri, String name, String description,
        java.util.function.Function<io.modelcontextprotocol.server.McpSyncServerExchange, Object> value) {
        McpSchema.Resource resource = McpSchema.Resource.builder()
            .uri(uri)
            .name(name)
            .description(description)
            .mimeType(JSON)
            .build();
        return new McpServerFeatures.SyncResourceSpecification(resource, (exchange, request) ->
            new McpSchema.ReadResourceResult(List.of(
                new McpSchema.TextResourceContents(uri, JSON, json(value.apply(exchange))))));
    }

    private static McpServerFeatures.SyncResourceSpecification resource(String uri, String name,
                                                                         String description, String mimeType,
                                                                         String content) {
        McpSchema.Resource resource = McpSchema.Resource.builder()
            .uri(uri)
            .name(name)
            .description(description)
            .mimeType(mimeType)
            .build();
        return new McpServerFeatures.SyncResourceSpecification(resource, (exchange, request) ->
            new McpSchema.ReadResourceResult(List.of(
                new McpSchema.TextResourceContents(uri, mimeType, content))));
    }

    private Map<String, Object> currentCapabilities(McpCaller caller) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("serverName", "pockethive-mcp");
        result.put("protocolRevision", properties.protocolRevision());
        result.put("pocketHiveIngress", properties.pocketHiveIngress().toString());
        result.put("oauthResource", properties.oauthResource().toString());
        result.put("stateMode", properties.stateMode().name());
        result.put("principalLabel", caller.principalLabel());
        result.put("clientId", caller.clientId());
        result.put("grantedScopes", caller.scopes().stream().sorted().toList());
        result.put("catalogueDigest", catalogueDigest);
        result.put("observedAt", clock.instant());
        return result;
    }

    private static Map<String, Object> overview() {
        return Map.of(
            "authorities", Map.of(
                "scenarioBundles", "Scenario Manager",
                "swarmsAndRuntime", "Orchestrator",
                "sourceAndHistory", "Git",
                "approvalAndEvidence", "HiveGate"),
            "safeStart", List.of(
                "Read pockethive://capabilities/current",
                "Read pockethive://tools/catalogue",
                "Read pockethive://skills/catalogue",
                "Use owner reads before mutation",
                "Use scenario_workflow_create for QA-led authoring"),
            "canonicalKnowledge", List.of(
                "pockethive://knowledge/architecture",
                "pockethive://knowledge/scenario-contract",
                "pockethive://knowledge/worker-capabilities",
                "pockethive://knowledge/orchestrator-rest",
                "pockethive://knowledge/scenario-manager-bundle-rest",
                "pockethive://knowledge/correlation-idempotency"),
            "rules", List.of(
                "No fallback between targets, protocols, or create/replace modes",
                "Do not infer QA requirements",
                "Do not execute Scenario Bundle files",
                "Do not call infrastructure as an authority workaround"));
    }

    private static Map<String, Object> glossary() {
        return Map.of(
            "ScenarioBundle", "Git-versioned test definition deployed through Scenario Manager",
            "ScenarioManager", "Authority for deployed bundle validation and catalogue state",
            "Swarm", "Runtime instance created from a deployed Scenario Bundle",
            "Orchestrator", "Authority for swarm lifecycle, status, diagnostics, and live configuration",
            "HiveGate", "Authority for operational policy, approval, execution tickets, and evidence",
            "AgentSession", "Principal-bound MCP authoring container for multiple independent workflows",
            "ScenarioWorkflow", "QA-led no-inference authoring coordination for one Scenario Bundle");
    }

    private Map<String, Object> canonicalCatalogue() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tools", catalogue.tools());
        Map<String, Object> skills = new TreeMap<>();
        catalogue.skills().forEach((id, skill) -> skills.put(id, Map.of(
            "id", skill.id(),
            "version", skill.version(),
            "digest", skill.contentDigest(),
            "uri", skill.resourceUri())));
        result.put("skills", skills);
        result.put("knowledge", knowledgeDocuments.stream().map(document -> Map.of(
            "uri", document.uri(),
            "sourcePath", document.sourcePath(),
            "digest", document.digest())).toList());
        return result;
    }

    private KnowledgeDocument document(String id, String title, String sourcePath, String classpathPath) {
        String markdown;
        try (InputStream input = getClass().getClassLoader()
            .getResourceAsStream("pockethive-docs/" + classpathPath)) {
            if (input == null) {
                throw new IllegalStateException("MCP_KNOWLEDGE_RESOURCE_MISSING: " + sourcePath);
            }
            markdown = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("MCP_KNOWLEDGE_RESOURCE_READ_FAILED: " + sourcePath, exception);
        }
        return new KnowledgeDocument("pockethive://knowledge/" + id, title, sourcePath,
            digest(markdown), markdown);
    }

    private List<io.pockethive.mcp.application.ToolDescriptor> visibleTools(McpCaller caller) {
        return catalogue.tools().stream()
            .filter(tool -> caller.scopes().contains(tool.requiredScope()))
            .toList();
    }

    private List<Map<String, Object>> visibleSkills(McpCaller caller) {
        java.util.Set<String> visibleSkillIds = visibleTools(caller).stream()
            .flatMap(tool -> tool.skillIds().stream())
            .collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
        return visibleSkillIds.stream()
            .map(catalogue.skills()::get)
            .map(skill -> Map.<String, Object>of(
                "id", skill.id(),
                "name", skill.name(),
                "description", skill.description(),
                "version", skill.version(),
                "digest", skill.contentDigest(),
                "uri", skill.resourceUri()))
            .toList();
    }

    private String digest(Object value) {
        try {
            ObjectMapper canonical = mapper.copy()
                .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
            byte[] content = canonical.writeValueAsString(value).getBytes(StandardCharsets.UTF_8);
            return "sha256:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(content));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("MCP_CATALOGUE_SERIALIZATION_FAILED", exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by Java", exception);
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("MCP_RESOURCE_SERIALIZATION_FAILED", exception);
        }
    }

    private record KnowledgeDocument(String uri, String title, String sourcePath,
                                     String digest, String markdown) {
    }
}
