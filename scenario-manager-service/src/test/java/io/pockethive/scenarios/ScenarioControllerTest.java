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

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        scenariosDir = Files.createDirectories(tempDir.resolve("scenarios"));
        capabilitiesDir = Files.createDirectories(tempDir.resolve("capabilities"));
        registry.add("scenarios.dir", () -> scenariosDir.toString());
        registry.add("capabilities.dir", () -> capabilitiesDir.toString());
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
                          "in": {
                            "in": "a"
                          },
                          "out": {
                            "out": "b"
                          }
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
                        in:
                          in: a
                        out:
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
    void schemaListingAndReadAreScopedToBundle() throws Exception {
        String body = """
                {
                  "id": "schema-demo",
                  "name": "Schema demo",
                  "template": {
                    "image": "ctrl-image:latest",
                    "bees": []
                  }
                }
                """;
        mvc.perform(post("/scenarios").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        // Create a schemas directory and a simple schema file inside the bundle.
        Path bundleDir = ScenarioControllerTest.scenariosDir.resolve("bundles").resolve("schema-demo");
        Files.createDirectories(bundleDir.resolve("schemas"));
        Path schemaFile = bundleDir.resolve("schemas").resolve("body.schema.json");
        Files.writeString(schemaFile, "{\"type\":\"object\"}");

        mvc.perform(get("/scenarios/schema-demo/schemas").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("schemas/body.schema.json"));

        mvc.perform(get("/scenarios/schema-demo/schema")
                        .param("path", "schemas/body.schema.json")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().string("{\"type\":\"object\"}"));
    }

    @Test
    void schemaWriteCreatesOrOverwritesFilesInsideBundle() throws Exception {
        String body = """
                {
                  "id": "schema-write-demo",
                  "name": "Schema write demo",
                  "template": {
                    "image": "ctrl-image:latest",
                    "bees": []
                  }
                }
                """;
        mvc.perform(post("/scenarios").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        mvc.perform(put("/scenarios/schema-write-demo/schema")
                        .param("path", "schemas/new-body.schema.json")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"object\",\"title\":\"Created\"}"))
                .andExpect(status().isNoContent());

        mvc.perform(get("/scenarios/schema-write-demo/schema")
                        .param("path", "schemas/new-body.schema.json")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().string("{\"type\":\"object\",\"title\":\"Created\"}"));

        mvc.perform(put("/scenarios/schema-write-demo/schema")
                        .param("path", "schemas/new-body.schema.json")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"object\",\"title\":\"Updated\"}"))
                .andExpect(status().isNoContent());

        mvc.perform(get("/scenarios/schema-write-demo/schema")
                        .param("path", "schemas/new-body.schema.json")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().string("{\"type\":\"object\",\"title\":\"Updated\"}"));
    }

    @Test
    void httpTemplateListingAndUpdateAreScopedToBundle() throws Exception {
        String body = """
                {
                  "id": "http-demo",
                  "name": "HTTP demo",
                  "template": {
                    "image": "ctrl-image:latest",
                    "bees": []
                  }
                }
                """;
        mvc.perform(post("/scenarios").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        Path bundleDir = ScenarioControllerTest.scenariosDir.resolve("bundles").resolve("http-demo");
        Files.createDirectories(bundleDir.resolve("http-templates"));
        Path templateFile = bundleDir.resolve("http-templates").resolve("example.yaml");
        Files.writeString(templateFile, "serviceId: default\ncallId: demo\nbodyTemplate: \"{}\"\n");

        mvc.perform(get("/scenarios/http-demo/http-templates").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("http-templates/example.yaml"));

        mvc.perform(get("/scenarios/http-demo/http-template")
                        .param("path", "http-templates/example.yaml")
                        .accept(MediaType.TEXT_PLAIN))
                .andExpect(status().isOk())
                .andExpect(content().string("serviceId: default\ncallId: demo\nbodyTemplate: \"{}\"\n"));

        mvc.perform(put("/scenarios/http-demo/http-template")
                        .param("path", "http-templates/example.yaml")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("serviceId: default\ncallId: demo\nbodyTemplate: \"{\\\"updated\\\":true}\"\n"))
                .andExpect(status().isNoContent());

        mvc.perform(post("/scenarios/http-demo/http-template/rename")
                        .param("from", "http-templates/example.yaml")
                        .param("to", "http-templates/renamed.yaml"))
                .andExpect(status().isNoContent());

        mvc.perform(get("/scenarios/http-demo/http-templates").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("http-templates/renamed.yaml"));

        mvc.perform(get("/scenarios/http-demo/http-template")
                        .param("path", "http-templates/renamed.yaml")
                        .accept(MediaType.TEXT_PLAIN))
                .andExpect(status().isOk())
                .andExpect(content().string("serviceId: default\ncallId: demo\nbodyTemplate: \"{\\\"updated\\\":true}\"\n"));

        mvc.perform(delete("/scenarios/http-demo/http-template")
                        .param("path", "http-templates/renamed.yaml"))
                .andExpect(status().isNoContent());

        mvc.perform(get("/scenarios/http-demo/http-templates").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
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
