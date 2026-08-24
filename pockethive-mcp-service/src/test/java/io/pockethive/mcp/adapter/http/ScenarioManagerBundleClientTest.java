package io.pockethive.mcp.adapter.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.mcp.application.OwnerCallAmbiguousException;
import io.pockethive.mcp.application.OwnerCallRejectedException;
import java.net.ConnectException;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

class ScenarioManagerBundleClientTest {
    private static final Path ARCHIVE = Path.of("bundle.zip");
    private final ObjectMapper mapper = new ObjectMapper();
    private final OwnerApiClient owner = mock(OwnerApiClient.class);
    private final ScenarioManagerBundleClient client = new ScenarioManagerBundleClient(owner);

    @Test
    void validatesCreatesAndReplacesOnlyThroughTheCanonicalOwnerEndpoints() throws Exception {
        JsonNode validation = mapper.readTree("""
            {"ok":true,"scenarioId":"db-smoke","scenarioName":"DB smoke","validation":{"artifactDigest":"sha256:abc"}}
            """);
        when(owner.postZip("/scenario-manager/validation/scenario-bundles", ARCHIVE)).thenReturn(validation);
        when(owner.postZip("/scenario-manager/scenarios/bundles", ARCHIVE)).thenReturn(validation);
        when(owner.putZip("/scenario-manager/scenarios/db-smoke/bundle", ARCHIVE)).thenReturn(validation);

        assertThat(client.validate(ARCHIVE))
            .satisfies(result -> {
                assertThat(result.valid()).isTrue();
                assertThat(result.scenarioId()).isEqualTo("db-smoke");
                assertThat(result.scenarioName()).isEqualTo("DB smoke");
                assertThat(result.bundleContentDigest()).isEqualTo("sha256:abc");
            });
        assertThat(client.create(ARCHIVE)).isSameAs(validation);
        assertThat(client.replace("db-smoke", ARCHIVE)).isSameAs(validation);
        verify(owner).postZip("/scenario-manager/scenarios/bundles", ARCHIVE);
    }

    @Test
    void reconcilesByExactScenarioAndBundleKeyWithoutGuessing() throws Exception {
        when(owner.get("/scenario-manager/api/templates")).thenReturn(mapper.readTree("""
            {"templates":[{"scenarioId":"other","bundleKey":"other-key"},
                          {"scenarioId":"db/smoke","bundleKey":"folder/db-smoke"}]}
            """));
        JsonNode current = mapper.readTree("""
            {"ok":true,"scenarioId":"db/smoke","scenarioName":"DB smoke","validation":{"artifactDigest":"sha256:current"}}
            """);
        when(owner.post(
            "/scenario-manager/validation/scenario-bundles/existing?bundleKey=folder%2Fdb-smoke", Map.of()))
            .thenReturn(current);

        assertThat(client.get("db/smoke"))
            .satisfies(result -> {
                assertThat(result.scenarioId()).isEqualTo("db/smoke");
                assertThat(result.bundleContentDigest()).isEqualTo("sha256:current");
            });
    }

    @Test
    void rejectsMalformedOrMissingOwnerEvidenceExplicitly() throws Exception {
        when(owner.postZip("/scenario-manager/validation/scenario-bundles", ARCHIVE)).thenReturn(null);
        assertThatThrownBy(() -> client.validate(ARCHIVE))
            .isInstanceOf(OwnerCallRejectedException.class)
            .hasMessage("Scenario validation response missing");

        when(owner.get("/scenario-manager/api/templates")).thenReturn(Map.of());
        assertThatThrownBy(() -> client.get("missing"))
            .isInstanceOf(OwnerCallRejectedException.class)
            .hasMessage("Scenario catalogue response invalid");

        when(owner.get("/scenario-manager/api/templates")).thenReturn(mapper.readTree("[]"));
        assertThatThrownBy(() -> client.get("missing"))
            .isInstanceOf(OwnerCallRejectedException.class)
            .hasMessage("Scenario reconciliation target not found");
    }

    @Test
    void mapsDefinitiveAndAmbiguousPublicationFailuresWithoutModeFallbackOrRetry() {
        HttpClientErrorException rejected = HttpClientErrorException.create(
            HttpStatus.CONFLICT, "conflict", HttpHeaders.EMPTY, new byte[0], null);
        when(owner.postZip("/scenario-manager/scenarios/bundles", ARCHIVE)).thenThrow(rejected);
        when(owner.putZip("/scenario-manager/scenarios/db-smoke/bundle", ARCHIVE))
            .thenThrow(new ResourceAccessException("lost", new ConnectException("lost")));

        assertThatThrownBy(() -> client.create(ARCHIVE))
            .isInstanceOf(OwnerCallRejectedException.class)
            .hasMessage("Scenario Manager rejected archive");
        assertThatThrownBy(() -> client.replace("db-smoke", ARCHIVE))
            .isInstanceOf(OwnerCallAmbiguousException.class)
            .hasMessage("Scenario Manager REPLACE response unavailable");
    }
}
