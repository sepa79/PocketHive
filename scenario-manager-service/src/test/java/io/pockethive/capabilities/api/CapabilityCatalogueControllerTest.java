package io.pockethive.capabilities.api;

import io.pockethive.capabilities.CapabilityCatalogueService;
import io.pockethive.capabilities.CapabilityManifest;
import io.pockethive.scenarios.AvailableScenarioRegistry;
import io.pockethive.scenarios.Scenario;
import io.pockethive.scenarios.ScenarioSummary;
import io.pockethive.swarm.model.Bee;
import io.pockethive.swarm.model.SwarmTemplate;
import io.pockethive.swarm.model.Work;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CapabilityCatalogueController.class)
class CapabilityCatalogueControllerTest {

    @Autowired
    MockMvc mvc;

    @MockBean
    AvailableScenarioRegistry scenarios;

    @MockBean
    CapabilityCatalogueService catalogue;

    @Test
    void templatesEndpointReturnsAvailableScenariosWithImages() throws Exception {
        Scenario scenario = new Scenario("alpha", "Alpha", "A test scenario",
                new SwarmTemplate("controller:v1", List.of(
                        new Bee("worker", "worker:v2", Work.ofDefaults("in", "out"), Map.of()))));
        given(scenarios.list()).willReturn(List.of(new ScenarioSummary("alpha", "Alpha", null)));
        given(scenarios.find("alpha")).willReturn(Optional.of(scenario));

        mvc.perform(get("/api/templates").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("alpha"))
                .andExpect(jsonPath("$[0].controllerImage").value("controller:v1"))
                .andExpect(jsonPath("$[0].bees[0].role").value("worker"))
                .andExpect(jsonPath("$[0].bees[0].image").value("worker:v2"));
    }

    @Test
    void capabilityLookupByDigestReturnsManifest() throws Exception {
        CapabilityManifest manifest = new CapabilityManifest(
                "1.0", "2.0", new CapabilityManifest.Image("image/name", "latest", "sha256:abc"),
                "role", List.of(), List.of(), List.of(), null);
        given(catalogue.findByDigest("sha256:abc")).willReturn(Optional.of(manifest));

        mvc.perform(get("/api/capabilities").param("imageDigest", "sha256:abc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.image.digest").value("sha256:abc"));
    }

    @Test
    void capabilityLookupMissingManifestReturns404() throws Exception {
        given(catalogue.findByDigest(anyString())).willReturn(Optional.empty());

        mvc.perform(get("/api/capabilities").param("imageDigest", "sha256:missing"))
                .andExpect(status().isNotFound());
    }

    @Test
    void capabilityLookupReturnsAllWhenRequested() throws Exception {
        CapabilityManifest manifest = new CapabilityManifest(
                "1.0", "2.0", new CapabilityManifest.Image("image/name", "latest", "sha256:def"),
                "role", List.of(), List.of(), List.of(), null);
        given(catalogue.allManifests()).willReturn(List.of(manifest));

        mvc.perform(get("/api/capabilities").param("all", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].image.digest").value("sha256:def"));
    }
}
