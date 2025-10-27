package io.pockethive.scenarios.capabilities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.pockethive.scenarios.OrchestratorCapabilitiesClient;
import io.pockethive.scenarios.OrchestratorCapabilitiesClient.OrchestratorRuntimeResponse;
import io.pockethive.scenarios.ScenarioManagerProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class CapabilitiesServiceTest {

    @TempDir
    Path tempDir;

    @Mock
    OrchestratorCapabilitiesClient client;

    ObjectMapper objectMapper;
    ScenarioManagerProperties properties;
    MutableClock clock;
    CapabilitiesService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        properties = new ScenarioManagerProperties();
        properties.capabilities().setCacheTtl(Duration.ofSeconds(60));
        properties.capabilities().setOfflineSwarmId("offline");
        clock = new MutableClock(Instant.parse("2024-01-01T00:00:00Z"));
    }

    @Test
    void mergesRuntimeWithOfflinePack() throws IOException {
        Path offline = tempDir.resolve("offline.json");
        Files.writeString(offline, """
                {
                  "generatedAt": "2024-01-01T00:00:00Z",
                  "catalogue": {
                    "generator": {
                      "1.0.0": {
                        "manifest": {
                          "role": "generator",
                          "capabilitiesVersion": "1.0.0"
                        }
                      }
                    }
                  }
                }
                """);
        properties.capabilities().setOfflinePackPath(offline);
        service = new CapabilitiesService(client, properties, objectMapper, clock);

        Map<String, Object> runtimeEntry = Map.of(
                "manifest", Map.of(
                        "role", "generator",
                        "capabilitiesVersion", "2.0.0"),
                "instances", List.of("bee-1"));
        Map<String, Object> runtime = Map.of(
                "swarm-a", Map.of(
                        "generator", Map.of(
                                "2.0.0", runtimeEntry)));
        when(client.fetchRuntimeCatalogue()).thenReturn(new OrchestratorRuntimeResponse(runtime));

        Map<String, Object> catalogue = service.runtimeCatalogue();

        assertThat(catalogue).containsKeys("swarm-a", "offline");

        @SuppressWarnings("unchecked")
        Map<String, Object> runtimeRoles = (Map<String, Object>) catalogue.get("swarm-a");
        assertThat(runtimeRoles).containsKey("generator");

        @SuppressWarnings("unchecked")
        Map<String, Object> versions = (Map<String, Object>) runtimeRoles.get("generator");
        @SuppressWarnings("unchecked")
        Map<String, Object> entry = (Map<String, Object>) versions.get("2.0.0");
        @SuppressWarnings("unchecked")
        Map<String, Object> manifest = (Map<String, Object>) entry.get("manifest");
        assertThat(manifest.get("capabilitiesVersion")).isEqualTo("2.0.0");
        @SuppressWarnings("unchecked")
        List<String> instances = (List<String>) entry.get("instances");
        assertThat(instances).containsExactly("bee-1");

        @SuppressWarnings("unchecked")
        Map<String, Object> offlineRoles = (Map<String, Object>) catalogue.get("offline");
        @SuppressWarnings("unchecked")
        Map<String, Object> offlineVersions = (Map<String, Object>) offlineRoles.get("generator");
        @SuppressWarnings("unchecked")
        Map<String, Object> offlineEntry = (Map<String, Object>) offlineVersions.get("1.0.0");
        @SuppressWarnings("unchecked")
        Map<String, Object> offlineManifest = (Map<String, Object>) offlineEntry.get("manifest");
        assertThat(offlineManifest.get("capabilitiesVersion")).isEqualTo("1.0.0");

        CapabilitiesStatus status = service.status();
        assertThat(status.offline().present()).isTrue();
        assertThat(status.offline().roleCount()).isEqualTo(1);
        assertThat(status.stale()).isFalse();
        verify(client, times(1)).fetchRuntimeCatalogue();
    }

    @Test
    void retainsCachedRuntimeWhenRefreshFails() {
        service = new CapabilitiesService(client, properties, objectMapper, clock);

        Map<String, Object> initialEntry = Map.of(
                "manifest", Map.of("role", "generator"));
        Map<String, Object> initialRuntime = Map.of(
                "swarm-a", Map.of(
                        "generator", Map.of(
                                "1.2.3", initialEntry)));
        when(client.fetchRuntimeCatalogue()).thenReturn(new OrchestratorRuntimeResponse(initialRuntime));

        Map<String, Object> first = service.runtimeCatalogue();
        assertThat(first).containsKey("swarm-a");
        verify(client, times(1)).fetchRuntimeCatalogue();

        clock.advance(Duration.ofSeconds(61));
        when(client.fetchRuntimeCatalogue()).thenThrow(new RuntimeException("boom"));

        Map<String, Object> second = service.runtimeCatalogue();
        assertThat(second).isEqualTo(first);

        CapabilitiesStatus status = service.status();
        assertThat(status.lastFailureMessage()).contains("boom");
        assertThat(status.stale()).isTrue();
        verify(client, times(2)).fetchRuntimeCatalogue();
    }

    private static class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }

        void advance(Duration duration) {
            current = current.plus(duration);
        }
    }
}
