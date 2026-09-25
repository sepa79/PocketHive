package io.pockethive.mcp.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.pockethive.mcp.config.PocketHiveMcpProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/**
 * Responsibility: project caller-visible knowledge and capabilities from the canonical catalogue.
 * Must not: decode MCP transport, execute tools or decide owner-service outcomes. Contract:
 * RESP-MCP-KNOWLEDGE-PROJECTION —
 * docs/architecture/runtime-responsibilities.md#resp-mcp-knowledge-projection.
 */
@Component
public final class McpKnowledgeProjection {
  private final ToolCatalogue catalogue;
  private final PocketHiveMcpProperties properties;
  private final ObjectMapper mapper;
  private final Clock clock;
  private final List<KnowledgeDocument> knowledgeDocuments;
  private final String catalogueDigest;

  public McpKnowledgeProjection(
      ToolCatalogue catalogue,
      PocketHiveMcpProperties properties,
      ObjectMapper mapper,
      Clock clock,
      KnowledgeDocumentSource source) {
    this.catalogue = catalogue;
    this.properties = properties;
    this.mapper = mapper;
    this.clock = clock;
    this.knowledgeDocuments = List.copyOf(source.documents());
    this.catalogueDigest = digest(mapper, canonicalCatalogue());
  }

  public String catalogueDigest() {
    return catalogueDigest;
  }

  public List<KnowledgeDocument> documents() {
    return knowledgeDocuments;
  }

  public java.util.Collection<SkillDescriptor> skills() {
    return catalogue.skills().values();
  }

  public Map<String, Object> currentCapabilities(
      McpCaller caller, List<String> supportedProtocolRevisions) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("serverName", "pockethive-mcp");
    result.put("supportedProtocolRevisions", supportedProtocolRevisions);
    result.put("pocketHiveIngress", properties.pocketHiveIngress().toString());
    result.put("oauthResource", properties.oauthResource().toString());
    result.put("stateMode", properties.stateMode().name());
    result.put(
        "qaAnswerCaptureModes",
        java.util.Arrays.stream(QaAnswerCaptureMode.values()).map(Enum::name).toList());
    result.put("principalLabel", caller.principalLabel());
    result.put("clientId", caller.clientId());
    result.put("grantedScopes", caller.scopes().stream().sorted().toList());
    result.put("catalogueDigest", catalogueDigest);
    result.put("observedAt", clock.instant());
    return result;
  }

  public static Map<String, Object> overview() {
    return Map.of(
        "authorities",
            Map.of(
                "scenarioBundles", "Scenario Manager",
                "swarmsAndRuntime", "Orchestrator",
                "sourceAndHistory", "Git",
                "approvalAndEvidence", "HiveGate"),
        "safeStart",
            List.of(
                "Read pockethive://capabilities/current",
                "Read pockethive://tools/catalogue",
                "Read pockethive://skills/catalogue",
                "Use owner reads before mutation",
                "Use scenario_workflow_create for QA-led authoring"),
        "canonicalKnowledge",
            List.of(
                "pockethive://knowledge/architecture",
                "pockethive://knowledge/scenario-contract",
                "pockethive://knowledge/worker-capabilities",
                "pockethive://knowledge/orchestrator-rest",
                "pockethive://knowledge/scenario-manager-bundle-rest",
                "pockethive://knowledge/correlation-idempotency"),
        "rules",
            List.of(
                "No fallback between targets, protocols, or create/replace modes",
                "Do not infer QA requirements",
                "Do not execute Scenario Bundle files",
                "Do not call infrastructure as an authority workaround"));
  }

  public static Map<String, Object> glossary() {
    return Map.of(
        "ScenarioBundle", "Git-versioned test definition deployed through Scenario Manager",
        "ScenarioManager", "Authority for deployed bundle validation and catalogue state",
        "Swarm", "Runtime instance created from a deployed Scenario Bundle",
        "Orchestrator",
            "Authority for swarm lifecycle, status, diagnostics, and live configuration",
        "HiveGate", "Authority for operational policy, approval, execution tickets, and evidence",
        "AgentSession",
            "Principal-bound MCP authoring container for multiple independent workflows",
        "ScenarioWorkflow", "QA-led no-inference authoring coordination for one Scenario Bundle");
  }

  private Map<String, Object> canonicalCatalogue() {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("tools", catalogue.tools());
    Map<String, Object> skills = new TreeMap<>();
    catalogue
        .skills()
        .forEach(
            (id, skill) ->
                skills.put(
                    id,
                    Map.of(
                        "id", skill.id(),
                        "version", skill.version(),
                        "digest", skill.contentDigest(),
                        "uri", skill.resourceUri())));
    result.put("skills", skills);
    result.put(
        "knowledge",
        knowledgeDocuments.stream()
            .map(
                document ->
                    Map.of(
                        "uri", document.uri(),
                        "sourcePath", document.sourcePath(),
                        "digest", document.digest()))
            .toList());
    return result;
  }

  public List<ToolDescriptor> visibleTools(McpCaller caller) {
    return catalogue.tools().stream()
        .filter(
            tool ->
                caller.hasScope(tool.requiredScope()))
        .toList();
  }

  public List<Map<String, Object>> visibleSkills(McpCaller caller) {
    java.util.Set<String> visibleSkillIds =
        visibleTools(caller).stream()
            .flatMap(tool -> tool.skillIds().stream())
            .collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
    return visibleSkillIds.stream()
        .map(catalogue.skills()::get)
        .map(
            skill ->
                Map.<String, Object>of(
                    "id", skill.id(),
                    "name", skill.name(),
                    "description", skill.description(),
                    "version", skill.version(),
                    "digest", skill.contentDigest(),
                    "uri", skill.resourceUri()))
        .toList();
  }

  public static String digest(ObjectMapper mapper, Object value) {
    try {
      ObjectMapper canonical =
          mapper
              .copy()
              .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
              .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
      byte[] content = canonical.writeValueAsString(value).getBytes(StandardCharsets.UTF_8);
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("MCP_CATALOGUE_SERIALIZATION_FAILED", exception);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is required by Java", exception);
    }
  }
}
