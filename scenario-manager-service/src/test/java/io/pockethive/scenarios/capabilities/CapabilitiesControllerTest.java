package io.pockethive.scenarios.capabilities;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CapabilitiesController.class)
class CapabilitiesControllerTest {

    @Autowired
    MockMvc mvc;

    @MockBean
    CapabilitiesService capabilitiesService;

    @Test
    void returnsRuntimeCatalogueAndStatus() throws Exception {
        Map<String, Object> runtime = Map.of(
                "swarm-a",
                Map.of(
                        "generator",
                        Map.of(
                                "1.0.0",
                                Map.of(
                                        "manifest", Map.of("capabilitiesVersion", "1.0.0"),
                                        "instances", List.of("bee-1")))));
        when(capabilitiesService.runtimeCatalogue()).thenReturn(runtime);

        OfflineStatus offline = new OfflineStatus(
                true,
                "/tmp/offline.json",
                Instant.parse("2024-01-01T00:00:00Z"),
                1,
                Map.of("version", "offline-1"));
        CapabilitiesStatus status = new CapabilitiesStatus(
                Instant.parse("2024-01-01T00:00:10Z"),
                Instant.parse("2024-01-01T00:00:00Z"),
                Duration.ofSeconds(30),
                false,
                1,
                null,
                offline);
        when(capabilitiesService.status()).thenReturn(status);

        mvc.perform(get("/capabilities/runtime").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.catalogue.swarm-a.generator['1.0.0'].manifest.capabilitiesVersion").value("1.0.0"));

        mvc.perform(get("/capabilities/status").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runtimeSwarmCount").value(1))
                .andExpect(jsonPath("$.offline.present").value(true))
                .andExpect(jsonPath("$.offline.metadata.version").value("offline-1"));
    }
}
