package io.pockethive.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.auth.contract.PocketHiveMcpScopes;
import io.pockethive.mcp.config.McpStateMode;
import io.pockethive.mcp.config.PocketHiveMcpProperties;
import io.pockethive.mcp.domain.PrincipalKey;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class McpKnowledgeProjectionTest {
  @Test
  void fingerprintFailuresCannotPublishAUsableCatalogue() throws Exception {
    var mapper = org.mockito.Mockito.mock(ObjectMapper.class);
    org.mockito.Mockito.when(mapper.copy()).thenReturn(mapper);
    org.mockito.Mockito.when(
            mapper.configure(
                org.mockito.ArgumentMatchers.any(
                    com.fasterxml.jackson.databind.MapperFeature.class),
                org.mockito.ArgumentMatchers.anyBoolean()))
        .thenReturn(mapper);
    org.mockito.Mockito.when(
            mapper.configure(
                org.mockito.ArgumentMatchers.any(
                    com.fasterxml.jackson.databind.SerializationFeature.class),
                org.mockito.ArgumentMatchers.anyBoolean()))
        .thenReturn(mapper);
    org.mockito.Mockito.when(mapper.writeValueAsString(org.mockito.ArgumentMatchers.any()))
        .thenThrow(
            new com.fasterxml.jackson.core.JsonProcessingException("fixture cannot encode") {});
    assertThatThrownBy(() -> McpKnowledgeProjection.digest(mapper, Map.of()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("MCP_CATALOGUE_SERIALIZATION_FAILED");
    try (var unavailable = org.mockito.Mockito.mockStatic(java.security.MessageDigest.class)) {
      unavailable
          .when(() -> java.security.MessageDigest.getInstance("SHA-256"))
          .thenThrow(new java.security.NoSuchAlgorithmException("fixture unavailable"));
      assertThatThrownBy(() -> McpKnowledgeProjection.digest(new ObjectMapper(), Map.of()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("SHA-256 is required by Java");
    }
  }

  private static final URI INGRESS = URI.create("http://127.0.0.1:8088");
  private static final Instant NOW = Instant.parse("2026-09-25T12:00:00Z");
  private static final ToolCatalogue CATALOGUE = ToolCatalogue.canonical();
  private static final KnowledgeDocument DOCUMENT =
      new KnowledgeDocument(
          "pockethive://knowledge/test", "Test", "docs/test.md", "sha256:test", "# Test");

  @Test
  void visibilityAndInvocationUseTheSameScopeDecision() {
    var projection = projection(new ArrayList<>(List.of(DOCUMENT)));
    assertThat(projection.visibleTools(caller(Set.of()))).isEmpty();
    assertThat(projection.visibleSkills(caller(Set.of()))).isEmpty();
    for (String scope : PocketHiveMcpScopes.ALL) {
      var caller = caller(Set.of(scope));
      for (var tool : CATALOGUE.tools()) {
        if (tool.requiredScope().equals(scope)) {
          caller.requireScope(tool.requiredScope());
          assertThat(projection.visibleTools(caller)).contains(tool);
        } else {
          assertThatThrownBy(() -> caller.requireScope(tool.requiredScope()))
              .isInstanceOf(ToolExecutionException.class)
              .hasMessage(tool.requiredScope())
              .satisfies(
                  error ->
                      assertThat(((ToolExecutionException) error).code())
                          .isEqualTo("MCP_SCOPE_REQUIRED"));
          assertThat(projection.visibleTools(caller)).doesNotContain(tool);
        }
      }
      var expectedIds =
          projection.visibleTools(caller).stream()
              .flatMap(tool -> tool.skillIds().stream())
              .distinct()
              .sorted()
              .toList();
      assertThat(projection.visibleSkills(caller))
          .extracting(skill -> skill.get("id"))
          .containsExactlyElementsOf(expectedIds);
      for (var visible : projection.visibleSkills(caller)) {
        var skill = CATALOGUE.skills().get(visible.get("id"));
        assertThat(visible)
            .isEqualTo(
                Map.of(
                    "id",
                    skill.id(),
                    "name",
                    skill.name(),
                    "description",
                    skill.description(),
                    "version",
                    skill.version(),
                    "digest",
                    skill.contentDigest(),
                    "uri",
                    skill.resourceUri()));
      }
    }
    assertThat(projection.skills())
        .containsExactlyInAnyOrderElementsOf(CATALOGUE.skills().values());
  }

  @Test
  void capabilitiesExposeAuthenticatedIdentityAndExplicitEnvironmentWithoutSecrets() {
    var projection = projection(List.of(DOCUMENT));
    var caller = caller(Set.copyOf(PocketHiveMcpScopes.ALL));
    assertThat(projection.currentCapabilities(caller, List.of("2025-11-25")))
        .containsExactlyInAnyOrderEntriesOf(
            Map.ofEntries(
                Map.entry("serverName", "pockethive-mcp"),
                Map.entry("supportedProtocolRevisions", List.of("2025-11-25")),
                Map.entry("pocketHiveIngress", INGRESS.toString()),
                Map.entry("oauthResource", INGRESS.resolve("/mcp").toString()),
                Map.entry("stateMode", "MEMORY"),
                Map.entry(
                    "qaAnswerCaptureModes",
                    List.of("MCP_FORM", "AGENT_MEDIATED", "COMPACT_REVIEW")),
                Map.entry("principalLabel", "Alice"),
                Map.entry("clientId", "test-client"),
                Map.entry("grantedScopes", caller.scopes().stream().sorted().toList()),
                Map.entry("catalogueDigest", projection.catalogueDigest()),
                Map.entry("observedAt", NOW)));
  }

  @Test
  void guidanceKeepsAuthorityAndSafeEntryPointsExplicit() {
    var overview = McpKnowledgeProjection.overview();
    assertThat(overview)
        .containsEntry(
            "authorities",
            Map.of(
                "scenarioBundles",
                "Scenario Manager",
                "swarmsAndRuntime",
                "Orchestrator",
                "sourceAndHistory",
                "Git",
                "approvalAndEvidence",
                "HiveGate"));
    assertThat((List<?>) overview.get("safeStart")).hasSize(5);
    assertThat((List<?>) overview.get("canonicalKnowledge")).hasSize(6);
    assertThat((List<?>) overview.get("rules")).hasSize(4);
    assertThat(McpKnowledgeProjection.glossary())
        .containsEntry(
            "HiveGate",
            "Authority for operational policy, approval, execution tickets, and evidence")
        .containsEntry(
            "AgentSession",
            "Principal-bound MCP authoring container for multiple independent workflows")
        .hasSize(7);
  }

  @Test
  void documentSnapshotAndDigestRemainStableUntilNewProjection() {
    var docs = new ArrayList<>(List.of(DOCUMENT));
    var first = projection(docs);
    assertThat(first.documents()).containsExactly(DOCUMENT);
    // Golden public catalogue fingerprint includes tool, skill and knowledge metadata.
    assertThat(first.catalogueDigest())
        .isEqualTo("sha256:7e46b39f7d5ed00c622998e2e543d4dea742a9bc890357aa35697d275993927a");
    assertThat(projection(docs).catalogueDigest()).isEqualTo(first.catalogueDigest());
    docs.clear();
    assertThat(first.documents()).containsExactly(DOCUMENT);
    assertThatThrownBy(() -> first.documents().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThat(projection(docs).catalogueDigest()).isNotEqualTo(first.catalogueDigest());
    assertThat(McpKnowledgeProjection.digest(new ObjectMapper(), Map.of("b", 2, "a", 1)))
        .isEqualTo("sha256:43258cff783fe7036d8a43033f830adfc60ec037382473548ac742b888292777");
  }

  @Test
  void callerScopesCannotBeChangedAfterAuthentication() {
    var scopes = new HashSet<>(Set.of("read"));
    var caller = caller(scopes);
    scopes.add("write");
    assertThat(caller.scopes()).containsExactly("read");
    assertThatThrownBy(() -> caller.scopes().add("write"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  private static McpCaller caller(Set<String> scopes) {
    return new McpCaller(new PrincipalKey(INGRESS, "alice"), "Alice", "test-client", scopes);
  }

  private static McpKnowledgeProjection projection(List<KnowledgeDocument> documents) {
    var hour = Duration.ofHours(1);
    var properties =
        new PocketHiveMcpProperties(
            INGRESS,
            INGRESS,
            McpStateMode.MEMORY,
            Path.of("target/state"),
            Path.of("target/spool"),
            hour,
            hour,
            hour,
            hour,
            hour,
            100,
            10,
            100,
            10,
            10000000,
            2,
            10,
            10000000,
            20000000,
            200,
            20000000,
            8,
            100,
            List.of(INGRESS.toString()),
            List.of("127.0.0.1:8088"),
            INGRESS,
            INGRESS.resolve("/mcp"),
            INGRESS.resolve("/oauth/introspect"),
            "test-client",
            "fixture-secret",
            "mcp",
            "fixture-secret");
    return new McpKnowledgeProjection(
        CATALOGUE,
        properties,
        new ObjectMapper(),
        Clock.fixed(NOW, ZoneOffset.UTC),
        () -> documents);
  }
}
