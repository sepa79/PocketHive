package io.pockethive.capabilities.api;

import io.pockethive.capabilities.CapabilityCatalogueService;
import io.pockethive.capabilities.CapabilityManifest;
import io.pockethive.scenarios.ScenarioService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CapabilityCatalogueController.class)
class CapabilityCatalogueControllerTest {

    @Autowired
    MockMvc mvc;

    @MockBean
    CapabilityCatalogueService catalogue;

    @MockBean
    ScenarioService scenarioService;

    @Test
    void templatesEndpointReturnsHealthyAndDefunctBundles() throws Exception {
        given(scenarioService.listBundleTemplates()).willReturn(List.of(
                new ScenarioService.BundleTemplateSummary(
                        "bundles/alpha",
                        "bundles/alpha",
                        "bundles",
                        "alpha",
                        "Alpha",
                        "Healthy bundle",
                        "controller:v1",
                        List.of(new ScenarioService.BundleBeeSummary("worker", "worker:v2")),
                        false,
                        null),
                new ScenarioService.BundleTemplateSummary(
                        "bundles/broken",
                        "bundles/broken",
                        "bundles",
                        null,
                        "broken",
                        null,
                        null,
                        List.of(),
                        true,
                        "Could not read scenario file: malformed yaml")
        ));

        mvc.perform(get("/api/templates").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].bundleKey").value("bundles/alpha"))
                .andExpect(jsonPath("$[0].bundlePath").value("bundles/alpha"))
                .andExpect(jsonPath("$[0].id").value("alpha"))
                .andExpect(jsonPath("$[0].controllerImage").value("controller:v1"))
                .andExpect(jsonPath("$[0].bees[0].role").value("worker"))
                .andExpect(jsonPath("$[0].defunct").value(false))
                .andExpect(jsonPath("$[1].bundleKey").value("bundles/broken"))
                .andExpect(jsonPath("$[1].id").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$[1].defunct").value(true))
                .andExpect(jsonPath("$[1].defunctReason").value("Could not read scenario file: malformed yaml"));
    }

    @Test
    void capabilityLookupByDigestReturnsManifest() throws Exception {
        CapabilityManifest manifest = new CapabilityManifest(
                "1.0", "2.0", new CapabilityManifest.Image("image/name", "latest", "sha256:abc"),
                "role", List.of(), List.of(), List.of(), null);
        given(catalogue.findByDigest("sha256:abc")).willReturn(java.util.Optional.of(manifest));

        mvc.perform(get("/api/capabilities").param("imageDigest", "sha256:abc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.image.digest").value("sha256:abc"));
    }

    @Test
    void capabilityLookupMissingManifestReturns404() throws Exception {
        given(catalogue.findByDigest(anyString())).willReturn(java.util.Optional.empty());

        mvc.perform(get("/api/capabilities").param("imageDigest", "sha256:missing"))
                .andExpect(status().isNotFound());
    }

    @Test
    void capabilityLookupReturnsAllWhenRequested() throws Exception {
        CapabilityManifest manifest = new CapabilityManifest(
                "1.0", "2.0", new CapabilityManifest.Image("image/name", "latest", "sha256:def"),
                "role", List.of(), List.of(), List.of(), null);
        given(catalogue.allManifests()).willReturn(List.of(manifest));
        given(catalogue.capabilityFallbackTag()).willReturn("latest");

        mvc.perform(get("/api/capabilities").param("all", "true"))
                .andExpect(status().isOk())
                .andExpect(header().string(CapabilityCatalogueController.CAPABILITY_FALLBACK_TAG_HEADER, "latest"))
                .andExpect(jsonPath("$[0].image.digest").value("sha256:def"));
    }
}
