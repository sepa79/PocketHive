package io.pockethive.scenarios;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "rabbitmq.logging.enabled=false")
@AutoConfigureMockMvc
class NetworkProfileControllerTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    NetworkProfileService networkProfileService;

    @TempDir
    static Path tempDir;

    private static Path profilesFile;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        profilesFile = tempDir.resolve("network").resolve("network-profiles.yaml");
        registry.add("pockethive.network.profiles-path", () -> profilesFile.toString());
        registry.add("rabbitmq.logging.enabled", () -> "false");
    }

    @BeforeEach
    void setUpProfiles() throws IOException {
        Files.createDirectories(profilesFile.getParent());
        Files.writeString(profilesFile, """
            - id: passthrough
              name: Passthrough
              faults: []
              targets:
                - payments
            - id: latency-250ms
              name: Latency 250ms
              faults:
                - type: latency
                  config:
                    latency: 250
              targets:
                - payments
            """);
        networkProfileService.reload();
    }

    @Test
    void listsProfiles() throws Exception {
        mvc.perform(get("/network-profiles"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(2)))
            .andExpect(jsonPath("$[?(@.id=='passthrough')].name").value(org.hamcrest.Matchers.contains("Passthrough")));
    }

    @Test
    void readsSingleProfile() throws Exception {
        mvc.perform(get("/network-profiles/latency-250ms"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value("latency-250ms"))
            .andExpect(jsonPath("$.faults[0].type").value("latency"))
            .andExpect(jsonPath("$.faults[0].config.latency").value(250));
    }

    @Test
    void readsAndUpdatesRawYaml() throws Exception {
        mvc.perform(get("/network-profiles/raw"))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("passthrough")));

        String updated = """
            - id: passthrough
              name: Passthrough
              faults: []
              targets:
                - payments
            - id: bandwidth-1mbps
              name: Bandwidth 1mbps
              faults:
                - type: bandwidth
                  config:
                    rateKbps: 1024
              targets:
                - payments
            """;

        mvc.perform(put("/network-profiles/raw")
                .contentType("text/plain")
                .content(updated))
            .andExpect(status().isNoContent());

        mvc.perform(get("/network-profiles/bandwidth-1mbps"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("Bandwidth 1mbps"));
    }

    @Test
    void rejectsInvalidYaml() throws Exception {
        mvc.perform(put("/network-profiles/raw")
                .contentType("text/plain")
                .content("not: [valid"))
            .andExpect(status().isBadRequest());
    }
}
