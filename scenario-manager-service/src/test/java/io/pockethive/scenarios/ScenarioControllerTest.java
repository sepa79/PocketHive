package io.pockethive.scenarios;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import io.pockethive.capabilities.CapabilityCatalogueService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "rabbitmq.logging.enabled=false")
@AutoConfigureMockMvc
class ScenarioControllerTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    CapabilityCatalogueService capabilityCatalogue;

    @TempDir
    static Path tempDir;

    private static Path scenariosDir;
    private static Path capabilitiesDir;
    private static Path runtimeDir;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        scenariosDir = Files.createDirectories(tempDir.resolve("scenarios"));
        capabilitiesDir = Files.createDirectories(tempDir.resolve("capabilities"));
        runtimeDir = Files.createDirectories(tempDir.resolve("runtime"));
        registry.add("scenarios.dir", () -> scenariosDir.toString());
        registry.add("capabilities.dir", () -> capabilitiesDir.toString());
        registry.add("pockethive.scenarios.runtime-root", () -> runtimeDir.toString());
        registry.add("rabbitmq.logging.enabled", () -> "false");
    }

    @BeforeEach
    void setUpManifests() throws IOException {
        cleanDirectory(scenariosDir);
        cleanDirectory(capabilitiesDir);
        writeCapabilityManifest("ctrl", "ctrl-image");
        writeCapabilityManifest("worker", "worker-image");
        capabilityCatalogue.reload();
    }

    @Test
    void crudOperations() throws Exception {
        String body = """
                {
                  "id": "1",
                  "name": "Test",
                  "template": {
                    "image": "ctrl-image:latest",
                    "bees": [
                      {
                        "role": "worker",
                        "image": "worker-image:latest",
                        "work": {
                          "in": "a",
                          "out": "b"
                        }
                      }
                    ]
                  }
                }
                """;
        mvc.perform(post("/scenarios").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("1"));

        mvc.perform(get("/scenarios").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("1"))
                .andExpect(jsonPath("$[0].name").value("Test"));

        mvc.perform(get("/scenarios/1").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Test"));

        String updated = body.replace("Test", "Updated");
        mvc.perform(put("/scenarios/1").contentType(MediaType.APPLICATION_JSON)
                        .content(updated))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Updated"));

        mvc.perform(delete("/scenarios/1"))
                .andExpect(status().isNoContent());

        mvc.perform(get("/scenarios/1").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }

    @Test
    void yamlSupport() throws Exception {
        String yaml = """
                id: 2
                name: Yaml
                template:
                  image: ctrl-image:latest
                  bees:
                    - role: worker
                      image: worker-image:latest
                      work:
                        in: a
                        out: b
                """;
        mvc.perform(post("/scenarios").contentType("application/x-yaml").content(yaml))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("2"));

        mvc.perform(get("/scenarios/2").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Yaml"));
    }

    @Test
    void reloadEndpoint() throws Exception {
        mvc.perform(post("/scenarios/reload"))
                .andExpect(status().isNoContent());
    }

    @Test
    void validationFailure() throws Exception {
        mvc.perform(post("/scenarios").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"\",\"name\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void pathTraversalRejected() throws Exception {
        String body = "{\"id\":\"../evil\",\"name\":\"Hack\"}";
        mvc.perform(post("/scenarios").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    private static void cleanDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            Files.createDirectories(directory);
            return;
        }
        try (Stream<Path> paths = Files.list(directory)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                try {
                    if (Files.isDirectory(path)) {
                        cleanDirectory(path);
                    }
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    private static void writeCapabilityManifest(String prefix, String imageName) throws IOException {
        String manifest = """
                {
                  "schemaVersion": "1.0",
                  "capabilitiesVersion": "1.0",
                  "role": "%s",
                  "image": {
                    "name": "%s",
                    "tag": "latest"
                  }
                }
                """.formatted(prefix, imageName);
        Files.writeString(capabilitiesDir.resolve(prefix + "-manifest.json"), manifest);
    }
}
