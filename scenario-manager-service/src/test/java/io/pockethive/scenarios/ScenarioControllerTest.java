package io.pockethive.scenarios;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import io.pockethive.auth.client.AuthServiceClient;
import io.pockethive.capabilities.CapabilityCatalogueService;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import java.util.stream.Stream;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringBootTest(properties = "rabbitmq.logging.enabled=false")
@AutoConfigureMockMvc
class ScenarioControllerTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    CapabilityCatalogueService capabilityCatalogue;

    @Autowired
    WebApplicationContext webApplicationContext;

    @MockBean
    AuthServiceClient authServiceClient;

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
    void setUpManifests() throws Exception {
        when(authServiceClient.resolve(anyString())).thenReturn(AuthTestUsers.admin());
        mvc = webAppContextSetup(webApplicationContext)
                .defaultRequest(get("/").header(org.springframework.http.HttpHeaders.AUTHORIZATION, AuthTestUsers.TEST_BEARER))
                .build();
        cleanDirectory(scenariosDir);
        cleanDirectory(capabilitiesDir);
        writeCapabilityManifest("ctrl", "ctrl-image");
        writeCapabilityManifest("worker", "worker-image");
        writeCapabilityManifest("generator", "generator");
        writeCapabilityManifest("request-builder", "request-builder");
        capabilityCatalogue.reload();
        mvc.perform(post("/scenarios/reload"))
                .andExpect(status().isNoContent());
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
                .andExpect(jsonPath("$", hasSize(1)))
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
    void bundleWorkspaceReadApiListsTreeAndReadsFilesByBundleKey() throws Exception {
        Path bundle = scenariosDir.resolve("tcp").resolve("demo");
        Files.createDirectories(bundle.resolve("templates/http"));
        Files.writeString(bundle.resolve("scenario.yaml"), """
                id: tcp-demo
                name: TCP Demo
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
                """);
        Files.writeString(bundle.resolve("templates/http/request.yaml"), "method: GET\npath: /health\n");
        Files.write(bundle.resolve("payload.bin"), new byte[] {0, 1, 2});

        mvc.perform(post("/scenarios/reload"))
                .andExpect(status().isNoContent());

        mvc.perform(get("/scenarios/bundles/tree")
                        .param("bundleKey", "tcp/demo")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bundleKey").value("tcp/demo"))
                .andExpect(jsonPath("$.nodes[?(@.path == 'scenario.yaml')].editorKind").value(org.hamcrest.Matchers.hasItem("yaml")))
                .andExpect(jsonPath("$.nodes[?(@.path == 'templates')].nodeType").value(org.hamcrest.Matchers.hasItem("directory")))
                .andExpect(jsonPath("$.nodes[?(@.path == 'templates/http/request.yaml')].editorKind").value(org.hamcrest.Matchers.hasItem("yaml")))
                .andExpect(jsonPath("$.nodes[?(@.path == 'payload.bin')].editorKind").value(org.hamcrest.Matchers.hasItem("unsupported")));

        mvc.perform(get("/scenarios/bundles/file")
                        .param("bundleKey", "tcp/demo")
                        .param("path", "templates/http/request.yaml")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bundleKey").value("tcp/demo"))
                .andExpect(jsonPath("$.path").value("templates/http/request.yaml"))
                .andExpect(jsonPath("$.editorKind").value("yaml"))
                .andExpect(jsonPath("$.revision").value(org.hamcrest.Matchers.startsWith("sha256:")))
                .andExpect(jsonPath("$.content").value("method: GET\npath: /health\n"));

        mvc.perform(get("/scenarios/bundles/file")
                        .param("bundleKey", "tcp/demo")
                        .param("path", "../scenario.yaml")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rawUpdatePreservesYamlLiteralBlockFormatting() throws Exception {
        String initial = """
                id: literal-demo
                name: Literal demo
                template:
                  image: ctrl-image:latest
                  bees: []
                """;
        mvc.perform(post("/scenarios").contentType("application/x-yaml").content(initial))
                .andExpect(status().isCreated());

        String raw = """
                id: literal-demo
                name: Literal demo
                template:
                  image: ctrl-image:latest
                  bees: []
                plan:
                  steps:
                    - action: config-update
                      role: generator
                      config:
                        message:
                          body: |
                            {
                              "greeting": "hello"
                            }
                """;

        mvc.perform(put("/scenarios/literal-demo/raw")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(raw))
                .andExpect(status().isNoContent());

        mvc.perform(get("/scenarios/literal-demo/raw").accept(MediaType.TEXT_PLAIN))
                .andExpect(status().isOk())
                .andExpect(content().string(raw))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("body: |")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"greeting\": \"hello\"")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("\\\"greeting\\\""))));
    }

    @Test
    void reloadEndpoint() throws Exception {
        mvc.perform(post("/scenarios/reload"))
                .andExpect(status().isNoContent());
    }

    @Test
    void reloadIsolatesMalformedBundlesWithoutHidingHealthyScenarios() throws Exception {
        Path brokenBundle = Files.createDirectories(scenariosDir.resolve("broken-bundle"));
        Files.writeString(brokenBundle.resolve("scenario.yaml"), "id: [not valid yaml");

        Path healthyBundle = Files.createDirectories(scenariosDir.resolve("healthy-bundle"));
        Files.writeString(healthyBundle.resolve("scenario.yaml"), """
                id: healthy-bundle
                name: Healthy Bundle
                template:
                  image: ctrl-image:latest
                  bees: []
                """);

        mvc.perform(post("/scenarios/reload"))
                .andExpect(status().isNoContent());

        mvc.perform(get("/scenarios").param("includeDefunct", "true").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value("healthy-bundle"));
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
	        Path bundleDir = ScenarioControllerTest.scenariosDir.resolve("schema-demo");
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
    void templateListingAndUpdateAreScopedToBundle() throws Exception {
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

	        Path bundleDir = ScenarioControllerTest.scenariosDir.resolve("http-demo");
	        Files.createDirectories(bundleDir.resolve("templates").resolve("http"));
	        Path templateFile = bundleDir.resolve("templates").resolve("http").resolve("example.yaml");
	        Files.writeString(templateFile, "protocol: HTTP\nserviceId: default\ncallId: demo\nbodyTemplate: \"{}\"\n");

        mvc.perform(get("/scenarios/http-demo/templates").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("templates/http/example.yaml"));

        mvc.perform(get("/scenarios/http-demo/template")
                        .param("path", "templates/http/example.yaml")
                        .accept(MediaType.TEXT_PLAIN))
                .andExpect(status().isOk())
                .andExpect(content().string("protocol: HTTP\nserviceId: default\ncallId: demo\nbodyTemplate: \"{}\"\n"));

        mvc.perform(put("/scenarios/http-demo/template")
                        .param("path", "templates/http/example.yaml")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("protocol: HTTP\nserviceId: default\ncallId: demo\nbodyTemplate: \"{\\\"updated\\\":true}\"\n"))
                .andExpect(status().isNoContent());

        mvc.perform(post("/scenarios/http-demo/template/rename")
                        .param("from", "templates/http/example.yaml")
                        .param("to", "templates/http/renamed.yaml"))
                .andExpect(status().isNoContent());

        mvc.perform(get("/scenarios/http-demo/templates").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("templates/http/renamed.yaml"));

        mvc.perform(get("/scenarios/http-demo/template")
                        .param("path", "templates/http/renamed.yaml")
                        .accept(MediaType.TEXT_PLAIN))
                .andExpect(status().isOk())
                .andExpect(content().string("protocol: HTTP\nserviceId: default\ncallId: demo\nbodyTemplate: \"{\\\"updated\\\":true}\"\n"));

        mvc.perform(delete("/scenarios/http-demo/template")
                        .param("path", "templates/http/renamed.yaml"))
                .andExpect(status().isNoContent());

        mvc.perform(get("/scenarios/http-demo/templates").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void bundleDownloadUsesRealBundleDirectoryWhenScenarioIsNestedInFolder() throws Exception {
        Path nestedBundleDir = scenariosDir.resolve("tcp").resolve("nested-demo");
        Files.createDirectories(nestedBundleDir);
        Files.writeString(nestedBundleDir.resolve("scenario.yaml"), """
                id: nested-demo
                name: Nested demo
                template:
                  image: ctrl-image:latest
                  bees:
                    - role: worker
                      image: worker-image:latest
                      work:
                        in: {}
                        out:
                          out: q
                """);
        Files.writeString(nestedBundleDir.resolve("note.txt"), "hello");

        mvc.perform(post("/scenarios/reload"))
                .andExpect(status().isNoContent());

        byte[] zipBytes = mvc.perform(get("/scenarios/nested-demo/bundle").accept("application/zip"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "form-data; name=\"attachment\"; filename=\"nested-demo-bundle.zip\""))
                .andReturn()
                .getResponse()
                .getContentAsByteArray();

        boolean hasScenarioYaml = false;
        boolean hasNote = false;
        try (ZipInputStream zip = new ZipInputStream(new java.io.ByteArrayInputStream(zipBytes))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if ("scenario.yaml".equals(entry.getName())) {
                    hasScenarioYaml = true;
                }
                if ("note.txt".equals(entry.getName())) {
                    hasNote = true;
                }
            }
        }
        org.junit.jupiter.api.Assertions.assertTrue(hasScenarioYaml, "zip should contain scenario.yaml");
        org.junit.jupiter.api.Assertions.assertTrue(hasNote, "zip should contain note.txt");
    }

    @Test
    void bundleAddressedOperationsWorkForMalformedBundlesWithoutScenarioId() throws Exception {
        Path brokenBundleDir = scenariosDir.resolve("broken-bundle");
        Files.createDirectories(brokenBundleDir);
        Files.writeString(brokenBundleDir.resolve("scenario.yaml"), "id: [not valid yaml");
        Files.writeString(brokenBundleDir.resolve("note.txt"), "broken");

        mvc.perform(post("/scenarios/reload"))
                .andExpect(status().isNoContent());

        byte[] zipBytes = mvc.perform(get("/scenarios/bundles/download")
                        .param("bundleKey", "broken-bundle")
                        .accept("application/zip"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "form-data; name=\"attachment\"; filename=\"broken-bundle-bundle.zip\""))
                .andReturn()
                .getResponse()
                .getContentAsByteArray();

        boolean hasScenarioYaml = false;
        boolean hasNote = false;
        try (ZipInputStream zip = new ZipInputStream(new java.io.ByteArrayInputStream(zipBytes))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if ("scenario.yaml".equals(entry.getName())) {
                    hasScenarioYaml = true;
                }
                if ("note.txt".equals(entry.getName())) {
                    hasNote = true;
                }
            }
        }
        org.junit.jupiter.api.Assertions.assertTrue(hasScenarioYaml, "zip should contain scenario.yaml");
        org.junit.jupiter.api.Assertions.assertTrue(hasNote, "zip should contain note.txt");

        mvc.perform(post("/scenarios/bundles/move")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bundleKey\":\"broken-bundle\",\"path\":\"quarantine\"}"))
                .andExpect(status().isNoContent());

        org.junit.jupiter.api.Assertions.assertTrue(Files.isDirectory(scenariosDir.resolve("quarantine").resolve("broken-bundle")));

        mvc.perform(delete("/scenarios/bundles").param("bundleKey", "quarantine/broken-bundle"))
                .andExpect(status().isNoContent());

        org.junit.jupiter.api.Assertions.assertFalse(Files.exists(scenariosDir.resolve("quarantine").resolve("broken-bundle")));
    }

    @Test
    void dryRunBundleValidationDoesNotImportBundle() throws Exception {
        byte[] zip = bundleZip("scenario.yaml", """
                id: dry-run-demo
                name: Dry run demo
                template:
                  image: ctrl-image:latest
                  bees: []
                """);

        mvc.perform(post("/validation/scenario-bundles")
                        .contentType("application/zip")
                        .content(zip)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.source").value("uploaded-zip"))
                .andExpect(jsonPath("$.scenarioId").value("dry-run-demo"))
                .andExpect(jsonPath("$.summary.errors").value(0))
                .andExpect(jsonPath("$.summary.warnings").value(0))
                .andExpect(jsonPath("$.findings", hasSize(0)));

        mvc.perform(get("/scenarios").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void runtimePreparationRejectsDefunctScenarioBundle() throws Exception {
        Path quarantinedBundle = Files.createDirectories(scenariosDir.resolve("quarantine").resolve("defunct-runtime"));
        Files.writeString(quarantinedBundle.resolve("scenario.yaml"), """
                id: defunct-runtime
                name: Defunct Runtime
                template:
                  image: ctrl-image:latest
                  bees: []
                """);

        mvc.perform(post("/scenarios/reload"))
                .andExpect(status().isNoContent());

        mvc.perform(post("/scenarios/defunct-runtime/runtime")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {
                              "swarmId": "sw1"
                            }
                            """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.source").value("scenario-manager"))
                .andExpect(jsonPath("$.scenarioId").value("defunct-runtime"))
                .andExpect(jsonPath("$.summary.errors").value(1))
                .andExpect(jsonPath("$.findings[0].code").value("BUNDLE_DEFUNCT"));
    }

    @Test
    void runtimePreparationRejectsBundleBrokenAfterLoad() throws Exception {
        Path bundle = Files.createDirectories(scenariosDir.resolve("runtime-broken-after-load"));
        Files.writeString(bundle.resolve("scenario.yaml"), """
                id: runtime-broken-after-load
                name: Runtime Broken After Load
                template:
                  image: ctrl-image:latest
                  bees: []
                """);

        mvc.perform(post("/scenarios/reload"))
                .andExpect(status().isNoContent());

        Files.writeString(bundle.resolve("scenario.yaml"), "id: [not valid yaml");

        mvc.perform(post("/scenarios/runtime-broken-after-load/runtime")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {
                              "swarmId": "sw1"
                            }
                            """)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.source").value("scenario-manager"))
                .andExpect(jsonPath("$.summary.errors").value(1))
                .andExpect(jsonPath("$.findings[0].code").value("SCENARIO_DESCRIPTOR_INVALID"));
    }

    @Test
    void dryRunBundleValidationReturnsStructuredFindings() throws Exception {
        byte[] zip = bundleZip("note.txt", "no scenario here");

        mvc.perform(post("/validation/scenario-bundles")
                        .contentType("application/zip")
                        .content(zip)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.summary.errors").value(1))
                .andExpect(jsonPath("$.findings[0].category").value("scenario"))
                .andExpect(jsonPath("$.findings[0].code").value("SCENARIO_DESCRIPTOR_INVALID"))
                .andExpect(jsonPath("$.findings[0].severity").value("error"))
                .andExpect(jsonPath("$.findings[0].path").value("scenario.yaml"));
    }

    @Test
    void bundleValidationDoesNotAcceptScenarioDescriptorFallbackNames() throws Exception {
        for (String descriptorName : List.of("scenario.yml", "scenario.json")) {
            byte[] zip = bundleZip(descriptorName, """
                    id: fallback-descriptor-demo
                    name: Fallback descriptor demo
                    template:
                      image: ctrl-image:latest
                      bees: []
                    """);

            mvc.perform(post("/validation/scenario-bundles")
                            .contentType("application/zip")
                            .content(zip)
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.findings[0].category").value("scenario"))
                    .andExpect(jsonPath("$.findings[0].code").value("SCENARIO_DESCRIPTOR_INVALID"))
                    .andExpect(jsonPath("$.findings[0].path").value("scenario.yaml"))
                    .andExpect(jsonPath("$.findings[0].message")
                            .value(org.hamcrest.Matchers.containsString("Bundle does not contain a scenario.yaml")));
        }
    }

    @Test
    void dryRunBundleValidationReturnsStructuredFindingsForMalformedDescriptor() throws Exception {
        byte[] zip = bundleZip("scenario.yaml", """
                id: [not-a-string]
                name: Broken descriptor
                template:
                  image: ctrl-image:latest
                  bees: []
                """);

        mvc.perform(post("/validation/scenario-bundles")
                        .contentType("application/zip")
                        .content(zip)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.findings[0].category").value("scenario"))
                .andExpect(jsonPath("$.findings[0].code").value("SCENARIO_DESCRIPTOR_INVALID"));
    }

    @Test
    void bundleValidationRejectsWorkerConfigWrapper() throws Exception {
        byte[] zip = bundleZip("scenario.yaml", """
                id: legacy-worker-wrapper-demo
                name: Legacy worker wrapper demo
                template:
                  image: ctrl-image:latest
                  bees:
                    - role: generator
                      image: generator:latest
                      work: {}
                      config:
                        worker:
                          message:
                            bodyType: SIMPLE
                            body: "{}"
                """);

        mvc.perform(post("/validation/scenario-bundles")
                        .contentType("application/zip")
                        .content(zip)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.findings[0].category").value("scenario"))
                .andExpect(jsonPath("$.findings[0].code").value("SCENARIO_DESCRIPTOR_INVALID"))
                .andExpect(jsonPath("$.findings[0].path").value("scenario.yaml:template.bees[0].config.worker"))
                .andExpect(jsonPath("$.findings[0].message")
                        .value(org.hamcrest.Matchers.containsString("config.worker is not supported")));
    }

    @Test
    void bundleValidationRejectsLegacyPockethiveConfigWrapper() throws Exception {
        byte[] zip = bundleZip("scenario.yaml", """
                id: legacy-pockethive-wrapper-demo
                name: Legacy pockethive wrapper demo
                template:
                  image: ctrl-image:latest
                  bees:
                    - role: generator
                      image: generator:latest
                      work: {}
                      config:
                        pockethive:
                          worker:
                            config:
                              message:
                                bodyType: SIMPLE
                                body: "{}"
                """);

        mvc.perform(post("/validation/scenario-bundles")
                        .contentType("application/zip")
                        .content(zip)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.findings[0].category").value("scenario"))
                .andExpect(jsonPath("$.findings[0].code").value("SCENARIO_DESCRIPTOR_INVALID"))
                .andExpect(jsonPath("$.findings[0].path").value("scenario.yaml:template.bees[0].config.pockethive"))
                .andExpect(jsonPath("$.findings[0].message")
                        .value(org.hamcrest.Matchers.containsString("config.pockethive")));
    }

    @Test
    void bundleValidationRejectsMissingRequiredCapabilityConfig() throws Exception {
        Files.writeString(capabilitiesDir.resolve("moderator-required-manifest.json"), """
                {
                  "schemaVersion": "1.0",
                  "capabilitiesVersion": "1.0",
                  "role": "moderator",
                  "image": {
                    "name": "moderator",
                    "tag": "latest"
                  },
                  "config": [
                    {
                      "name": "mode.type",
                      "type": "string",
                      "required": true
                    },
                    {
                      "name": "mode.ratePerSec",
                      "type": "number",
                      "required": true
                    }
                  ]
                }
                """);
        capabilityCatalogue.reload();

        byte[] zip = bundleZip("scenario.yaml", """
                id: missing-required-config-demo
                name: Missing required config demo
                template:
                  image: ctrl-image:latest
                  bees:
                    - role: moderator
                      image: moderator:latest
                      work: {}
                      config:
                        mode:
                          type: pass-through
                """);

        mvc.perform(post("/validation/scenario-bundles")
                        .contentType("application/zip")
                        .content(zip)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.findings[0].category").value("scenario"))
                .andExpect(jsonPath("$.findings[0].code").value("SCENARIO_DESCRIPTOR_INVALID"))
                .andExpect(jsonPath("$.findings[0].path")
                        .value("scenario.yaml:template.bees[0].config.mode.ratePerSec"))
                .andExpect(jsonPath("$.findings[0].message")
                        .value(org.hamcrest.Matchers.containsString("missing required field 'mode.ratePerSec'")));
    }

    @Test
    void bundleValidationRejectsGeneratorWithoutExplicitMessageBodyType() throws Exception {
        useRepositoryCapabilityManifest("generator", "generator.latest.yaml");
        capabilityCatalogue.reload();

        byte[] zip = bundleZip("scenario.yaml", """
                id: generator-missing-body-type-demo
                name: Generator missing body type demo
                template:
                  image: ctrl-image:latest
                  bees:
                    - role: generator
                      image: generator:latest
                      work: {}
                      config:
                        inputs:
                          type: SCHEDULER
                        message:
                          body: "{}"
                """);

        mvc.perform(post("/validation/scenario-bundles")
                        .contentType("application/zip")
                        .content(zip)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.findings", hasSize(1)))
                .andExpect(jsonPath("$.findings[0].path")
                        .value("scenario.yaml:template.bees[0].config.message.bodyType"))
                .andExpect(jsonPath("$.findings[0].message")
                        .value(org.hamcrest.Matchers.containsString("missing required field 'message.bodyType'")));
    }

    @Test
    void bundleValidationRejectsProcessorWithoutExplicitModeAndThreadCount() throws Exception {
        useRepositoryCapabilityManifest("processor", "processor.latest.yaml");
        capabilityCatalogue.reload();

        byte[] zip = bundleZip("scenario.yaml", """
                id: processor-missing-mode-demo
                name: Processor missing mode demo
                template:
                  image: ctrl-image:latest
                  bees:
                    - role: processor
                      image: processor:latest
                      work: {}
                      config:
                        baseUrl: "http://wiremock:8080"
                        inputs:
                          type: RABBITMQ
                        outputs:
                          type: RABBITMQ
                """);

        mvc.perform(post("/validation/scenario-bundles")
                        .contentType("application/zip")
                        .content(zip)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.findings", hasSize(2)))
                .andExpect(jsonPath("$.findings[*].path", org.hamcrest.Matchers.hasItems(
                        "scenario.yaml:template.bees[0].config.mode",
                        "scenario.yaml:template.bees[0].config.threadCount")))
                .andExpect(jsonPath("$.findings[*].message", org.hamcrest.Matchers.hasItems(
                        org.hamcrest.Matchers.containsString("missing required field 'mode'"),
                        org.hamcrest.Matchers.containsString("missing required field 'threadCount'"))));
    }

    @Test
    void bundleValidationRejectsProcessorWithoutExplicitIoSelectors() throws Exception {
        useRepositoryCapabilityManifest("processor", "processor.latest.yaml");
        capabilityCatalogue.reload();

        byte[] zip = bundleZip("scenario.yaml", """
                id: processor-missing-io-selectors-demo
                name: Processor missing IO selectors demo
                template:
                  image: ctrl-image:latest
                  bees:
                    - role: processor
                      image: processor:latest
                      work: {}
                      config:
                        baseUrl: "http://wiremock:8080"
                        mode: THREAD_COUNT
                        threadCount: 1
                """);

        mvc.perform(post("/validation/scenario-bundles")
                        .contentType("application/zip")
                        .content(zip)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.findings", hasSize(2)))
                .andExpect(jsonPath("$.findings[*].path", org.hamcrest.Matchers.hasItems(
                        "scenario.yaml:template.bees[0].config.inputs.type",
                        "scenario.yaml:template.bees[0].config.outputs.type")))
                .andExpect(jsonPath("$.findings[*].message", org.hamcrest.Matchers.hasItems(
                        org.hamcrest.Matchers.containsString("missing required field 'inputs.type'"),
                        org.hamcrest.Matchers.containsString("missing required field 'outputs.type'"))));
    }

    @Test
    void bundleValidationRejectsSelectedIoMissingRequiredManifestConfig() throws Exception {
        useRepositoryCapabilityManifest("generator", "generator.latest.yaml");
        useRepositoryCapabilityManifest("io-scheduler", "io.scheduler.latest.yaml");
        capabilityCatalogue.reload();

        byte[] zip = bundleZip("scenario.yaml", """
                id: generator-missing-selected-io-config-demo
                name: Generator missing selected IO config demo
                template:
                  image: ctrl-image:latest
                  bees:
                    - role: generator
                      image: generator:latest
                      work: {}
                      config:
                        inputs:
                          type: SCHEDULER
                          scheduler:
                            ratePerSec: 1
                        message:
                          bodyType: SIMPLE
                          body: "{}"
                """);

        mvc.perform(post("/validation/scenario-bundles")
                        .contentType("application/zip")
                        .content(zip)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.findings", hasSize(1)))
                .andExpect(jsonPath("$.findings[0].path")
                        .value("scenario.yaml:template.bees[0].config.inputs.scheduler.maxMessages"))
                .andExpect(jsonPath("$.findings[0].message")
                        .value(org.hamcrest.Matchers.containsString(
                                "missing required field 'inputs.scheduler.maxMessages'")));
    }

    @Test
    void bundleValidationRejectsHttpSequenceWithoutExplicitRuntimeConfig() throws Exception {
        useRepositoryCapabilityManifest("http-sequence", "http-sequence.latest.yaml");
        capabilityCatalogue.reload();

        byte[] zip = bundleZip("scenario.yaml", """
                id: http-sequence-missing-runtime-config-demo
                name: HTTP Sequence missing runtime config demo
                template:
                  image: ctrl-image:latest
                  bees:
                    - role: http-sequence
                      image: http-sequence:latest
                      work: {}
                      config:
                        baseUrl: "http://wiremock:8080"
                """);

        mvc.perform(post("/validation/scenario-bundles")
                        .contentType("application/zip")
                        .content(zip)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.findings", hasSize(5)))
                .andExpect(jsonPath("$.findings[*].path", org.hamcrest.Matchers.hasItems(
                        "scenario.yaml:template.bees[0].config.templateRoot",
                        "scenario.yaml:template.bees[0].config.serviceId",
                        "scenario.yaml:template.bees[0].config.threadCount",
                        "scenario.yaml:template.bees[0].config.steps",
                        "scenario.yaml:template.bees[0].config.debugCapture")));
    }

    @Test
    void bundleValidationRejectsDbQueryWithoutExplicitRuntimeConfig() throws Exception {
        useRepositoryCapabilityManifest("db-query", "db-query.latest.yaml");
        capabilityCatalogue.reload();

        byte[] zip = bundleZip("scenario.yaml", """
                id: db-query-missing-runtime-config-demo
                name: DB Query missing runtime config demo
                template:
                  image: ctrl-image:latest
                  bees:
                    - role: db-query
                      image: db-query:latest
                      work: {}
                      config: {}
                """);

        mvc.perform(post("/validation/scenario-bundles")
                        .contentType("application/zip")
                        .content(zip)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.findings[*].path", org.hamcrest.Matchers.hasItems(
                        "scenario.yaml:template.bees[0].config.adapter",
                        "scenario.yaml:template.bees[0].config.templateRoot",
                        "scenario.yaml:template.bees[0].config.serviceId",
                        "scenario.yaml:template.bees[0].config.queryId",
                        "scenario.yaml:template.bees[0].config.threadCount",
                        "scenario.yaml:template.bees[0].config.queryTimeoutMs",
                        "scenario.yaml:template.bees[0].config.connection.jdbcUrl",
                        "scenario.yaml:template.bees[0].config.pool.maxSize",
                        "scenario.yaml:template.bees[0].config.retry.maxAttempts",
                        "scenario.yaml:template.bees[0].config.retry.on")));
    }

    @Test
    void bundleValidationAcceptsDbQueryBlankCredentialsWhenExplicit() throws Exception {
        useRepositoryCapabilityManifest("db-query", "db-query.latest.yaml");
        capabilityCatalogue.reload();

        byte[] zip = bundleZip("scenario.yaml", """
                id: db-query-blank-credentials-demo
                name: DB Query blank credentials demo
                template:
                  image: ctrl-image:latest
                  bees:
                    - role: db-query
                      image: db-query:latest
                      work: {}
                      config:
                        adapter: POSTGRES
                        templateRoot: "/scenario/templates"
                        serviceId: demo-service
                        queryId: demo-query
                        threadCount: 1
                        queryTimeoutMs: 1000
                        connection:
                          jdbcUrl: "jdbc:postgresql://postgres:5432/demo"
                          username: ""
                          password: ""
                        pool:
                          maxSize: 2
                          minIdle: 0
                          connectionTimeoutMs: 1000
                          validationTimeoutMs: 1000
                          idleTimeoutMs: 30000
                          maxLifetimeMs: 60000
                        retry:
                          maxAttempts: 1
                          initialBackoffMs: 100
                          backoffMultiplier: 1.0
                          maxBackoffMs: 100
                          on: []
                """);

        mvc.perform(post("/validation/scenario-bundles")
                        .contentType("application/zip")
                        .content(zip)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.findings", hasSize(0)));
    }

    @Test
    void bundleValidationRejectsClearingExportWithoutExplicitRuntimeConfig() throws Exception {
        useRepositoryCapabilityManifest("clearing-export", "clearing-export.latest.yaml");
        capabilityCatalogue.reload();

        byte[] zip = bundleZip("scenario.yaml", """
                id: clearing-export-missing-runtime-config-demo
                name: Clearing Export missing runtime config demo
                template:
                  image: ctrl-image:latest
                  bees:
                    - role: clearing-export
                      image: clearing-export:latest
                      work: {}
                      config:
                        mode: template
                """);

        mvc.perform(post("/validation/scenario-bundles")
                        .contentType("application/zip")
                        .content(zip)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.findings[*].path", org.hamcrest.Matchers.hasItems(
                        "scenario.yaml:template.bees[0].config.streamingAppendEnabled",
                        "scenario.yaml:template.bees[0].config.streamingWindowMs",
                        "scenario.yaml:template.bees[0].config.maxRecordsPerFile",
                        "scenario.yaml:template.bees[0].config.fileNameTemplate",
                        "scenario.yaml:template.bees[0].config.recordSourceStep",
                        "scenario.yaml:template.bees[0].config.businessCodeFilterEnabled")));
    }

    @Test
    void bundleValidationRejectsRequestBuilderWithoutExplicitTemplateSettings() throws Exception {
        useRepositoryCapabilityManifest("request-builder", "request-builder.latest.yaml");
        capabilityCatalogue.reload();

        byte[] zip = bundleZip("scenario.yaml", """
                id: request-builder-missing-template-settings-demo
                name: Request Builder missing template settings demo
                template:
                  image: ctrl-image:latest
                  bees:
                    - role: request-builder
                      image: request-builder:latest
                      work: {}
                      config: {}
                """);

        mvc.perform(post("/validation/scenario-bundles")
                        .contentType("application/zip")
                        .content(zip)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.findings", hasSize(3)))
                .andExpect(jsonPath("$.findings[*].path", org.hamcrest.Matchers.hasItems(
                        "scenario.yaml:template.bees[0].config.templateRoot",
                        "scenario.yaml:template.bees[0].config.serviceId",
                        "scenario.yaml:template.bees[0].config.passThroughOnMissingTemplate")))
                .andExpect(jsonPath("$.findings[*].message", org.hamcrest.Matchers.hasItems(
                        org.hamcrest.Matchers.containsString("missing required field 'templateRoot'"),
                        org.hamcrest.Matchers.containsString("missing required field 'serviceId'"),
                        org.hamcrest.Matchers.containsString("missing required field 'passThroughOnMissingTemplate'"))));
    }

    @Test
    void bundleValidationRejectsRestTriggerWithoutExplicitRestFields() throws Exception {
        useRepositoryCapabilityManifest("trigger", "trigger.latest.yaml");
        capabilityCatalogue.reload();

        byte[] zip = bundleZip("scenario.yaml", """
                id: trigger-rest-missing-fields-demo
                name: Trigger REST missing fields demo
                template:
                  image: ctrl-image:latest
                  bees:
                    - role: trigger
                      image: trigger:latest
                      work: {}
                      config:
                        intervalMs: 1000
                        singleRequest: true
                        actionType: rest
                """);

        mvc.perform(post("/validation/scenario-bundles")
                        .contentType("application/zip")
                        .content(zip)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.findings", hasSize(4)))
                .andExpect(jsonPath("$.findings[*].path", org.hamcrest.Matchers.hasItems(
                        "scenario.yaml:template.bees[0].config.url",
                        "scenario.yaml:template.bees[0].config.method",
                        "scenario.yaml:template.bees[0].config.body",
                        "scenario.yaml:template.bees[0].config.headers")));
    }

    @Test
    void bundleValidationRejectsShellTriggerWithoutExplicitCommand() throws Exception {
        useRepositoryCapabilityManifest("trigger", "trigger.latest.yaml");
        capabilityCatalogue.reload();

        byte[] zip = bundleZip("scenario.yaml", """
                id: trigger-shell-missing-command-demo
                name: Trigger shell missing command demo
                template:
                  image: ctrl-image:latest
                  bees:
                    - role: trigger
                      image: trigger:latest
                      work: {}
                      config:
                        intervalMs: 1000
                        singleRequest: true
                        actionType: shell
                """);

        mvc.perform(post("/validation/scenario-bundles")
                        .contentType("application/zip")
                        .content(zip)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.findings", hasSize(1)))
                .andExpect(jsonPath("$.findings[0].path")
                        .value("scenario.yaml:template.bees[0].config.command"))
                .andExpect(jsonPath("$.findings[0].message")
                        .value(org.hamcrest.Matchers.containsString("missing required field 'command'")));
    }

    @Test
    void bundleValidationRejectsInputIoSubblockWithoutExplicitSelector() throws Exception {
        useRepositoryCapabilityManifest("processor", "processor.latest.yaml");
        useRepositoryCapabilityManifest("io-redis-dataset", "io.redis-dataset.latest.yaml");
        capabilityCatalogue.reload();

        byte[] zip = bundleZip("scenario.yaml", """
                id: processor-redis-input-missing-selector-demo
                name: Processor Redis input missing selector demo
                template:
                  image: ctrl-image:latest
                  bees:
                    - role: processor
                      image: processor:latest
                      work: {}
                      config:
                        baseUrl: "http://wiremock:8080"
                        mode: THREAD_COUNT
                        threadCount: 1
                        inputs:
                          redis:
                            listName: ph:dataset
                        outputs:
                          type: RABBITMQ
                """);

        mvc.perform(post("/validation/scenario-bundles")
                        .contentType("application/zip")
                        .content(zip)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.findings", hasSize(1)))
                .andExpect(jsonPath("$.findings[0].path")
                        .value("scenario.yaml:template.bees[0].config.inputs.type"))
                .andExpect(jsonPath("$.findings[0].message")
                        .value(org.hamcrest.Matchers.containsString(
                                "missing required selector 'inputs.type: REDIS_DATASET'")));
    }

    @Test
    void bundleValidationRejectsInputIoSubblockWithMismatchedSelector() throws Exception {
        useRepositoryCapabilityManifest("processor", "processor.latest.yaml");
        useRepositoryCapabilityManifest("io-redis-dataset", "io.redis-dataset.latest.yaml");
        capabilityCatalogue.reload();

        byte[] zip = bundleZip("scenario.yaml", """
                id: processor-redis-input-mismatch-demo
                name: Processor Redis input mismatch demo
                template:
                  image: ctrl-image:latest
                  bees:
                    - role: processor
                      image: processor:latest
                      work: {}
                      config:
                        baseUrl: "http://wiremock:8080"
                        mode: THREAD_COUNT
                        threadCount: 1
                        inputs:
                          type: RABBITMQ
                          redis:
                            listName: ph:dataset
                        outputs:
                          type: RABBITMQ
                """);

        mvc.perform(post("/validation/scenario-bundles")
                        .contentType("application/zip")
                        .content(zip)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.findings", hasSize(1)))
                .andExpect(jsonPath("$.findings[0].path")
                        .value("scenario.yaml:template.bees[0].config.inputs.type"))
                .andExpect(jsonPath("$.findings[0].message")
                        .value(org.hamcrest.Matchers.containsString(
                                "selector 'inputs.type' is 'RABBITMQ'; expected 'REDIS_DATASET'")));
    }

    @Test
    void bundleValidationRejectsCsvInputIoSubblockWithMismatchedSelector() throws Exception {
        useRepositoryCapabilityManifest("generator", "generator.latest.yaml");
        useRepositoryCapabilityManifest("io-csv-dataset", "io.csv-dataset.latest.yaml");
        capabilityCatalogue.reload();

        byte[] zip = bundleZip("scenario.yaml", """
                id: generator-csv-input-mismatch-demo
                name: Generator CSV input mismatch demo
                template:
                  image: ctrl-image:latest
                  bees:
                    - role: generator
                      image: generator:latest
                      work: {}
                      config:
                        inputs:
                          type: REDIS_DATASET
                          csv:
                            filePath: /app/scenario/datasets/users.csv
                        message:
                          bodyType: SIMPLE
                          body: "{}"
                """);

        mvc.perform(post("/validation/scenario-bundles")
                        .contentType("application/zip")
                        .content(zip)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.findings", hasSize(1)))
                .andExpect(jsonPath("$.findings[0].path")
                        .value("scenario.yaml:template.bees[0].config.inputs.type"))
                .andExpect(jsonPath("$.findings[0].message")
                        .value(org.hamcrest.Matchers.containsString(
                                "selector 'inputs.type' is 'REDIS_DATASET'; expected 'CSV_DATASET'")));
    }

    @Test
    void bundleValidationRejectsOutputIoSubblockWithoutExplicitSelector() throws Exception {
        useRepositoryCapabilityManifest("processor", "processor.latest.yaml");
        useRepositoryCapabilityManifest("io-redis-output", "io.redis-output.latest.yaml");
        capabilityCatalogue.reload();

        byte[] zip = bundleZip("scenario.yaml", """
                id: processor-redis-output-missing-selector-demo
                name: Processor Redis output missing selector demo
                template:
                  image: ctrl-image:latest
                  bees:
                    - role: processor
                      image: processor:latest
                      work: {}
                      config:
                        baseUrl: "http://wiremock:8080"
                        mode: THREAD_COUNT
                        threadCount: 1
                        inputs:
                          type: RABBITMQ
                        outputs:
                          redis:
                            defaultList: ph:out
                """);

        mvc.perform(post("/validation/scenario-bundles")
                        .contentType("application/zip")
                        .content(zip)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.findings", hasSize(1)))
                .andExpect(jsonPath("$.findings[0].path")
                        .value("scenario.yaml:template.bees[0].config.outputs.type"))
                .andExpect(jsonPath("$.findings[0].message")
                        .value(org.hamcrest.Matchers.containsString(
                                "missing required selector 'outputs.type: REDIS'")));
    }

    @Test
    void bundleValidationAcceptsIoSubblocksWithMatchingExplicitSelectors() throws Exception {
        useRepositoryCapabilityManifest("processor", "processor.latest.yaml");
        useRepositoryCapabilityManifest("io-redis-dataset", "io.redis-dataset.latest.yaml");
        useRepositoryCapabilityManifest("io-redis-output", "io.redis-output.latest.yaml");
        capabilityCatalogue.reload();

        byte[] zip = bundleZip("scenario.yaml", """
                id: processor-redis-io-valid-demo
                name: Processor Redis IO valid demo
                template:
                  image: ctrl-image:latest
                  bees:
                    - role: processor
                      image: processor:latest
                      work: {}
                      config:
                        baseUrl: "http://wiremock:8080"
                        mode: THREAD_COUNT
                        threadCount: 1
                        inputs:
                          type: REDIS_DATASET
                          redis:
                            host: redis
                            port: 6379
                            ssl: false
                            listName: ph:dataset
                            sources: []
                            pickStrategy: ROUND_ROBIN
                            ratePerSec: 2500.5
                        outputs:
                          type: REDIS
                          redis:
                            host: redis
                            port: 6379
                            ssl: false
                            sourceStep: LAST
                            pushDirection: RPUSH
                            routes: []
                            targetListTemplate: ""
                            defaultList: ph:out
                            maxLen: -1
                """);

        mvc.perform(post("/validation/scenario-bundles")
                        .contentType("application/zip")
                        .content(zip)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.findings", hasSize(0)));
    }

    @Test
    void bundleValidationRejectsRedisIoWithoutConcreteSourceOrTarget() throws Exception {
        useRepositoryCapabilityManifest("processor", "processor.latest.yaml");
        useRepositoryCapabilityManifest("io-redis-dataset", "io.redis-dataset.latest.yaml");
        useRepositoryCapabilityManifest("io-redis-output", "io.redis-output.latest.yaml");
        capabilityCatalogue.reload();

        byte[] zip = bundleZip("scenario.yaml", """
                id: processor-empty-redis-io-demo
                name: Processor empty Redis IO demo
                template:
                  image: ctrl-image:latest
                  bees:
                    - role: processor
                      image: processor:latest
                      work: {}
                      config:
                        baseUrl: "http://wiremock:8080"
                        mode: THREAD_COUNT
                        threadCount: 1
                        inputs:
                          type: REDIS_DATASET
                          redis:
                            host: redis
                            port: 6379
                            ssl: false
                            listName: ""
                            sources: []
                            pickStrategy: ROUND_ROBIN
                            ratePerSec: 1
                        outputs:
                          type: REDIS
                          redis:
                            host: redis
                            port: 6379
                            ssl: false
                            sourceStep: LAST
                            pushDirection: RPUSH
                            routes: []
                            targetListTemplate: ""
                            defaultList: ""
                            maxLen: -1
                """);

        mvc.perform(post("/validation/scenario-bundles")
                        .contentType("application/zip")
                        .content(zip)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.findings", hasSize(2)))
                .andExpect(jsonPath("$.findings[*].path", org.hamcrest.Matchers.hasItems(
                        "scenario.yaml:template.bees[0].config.inputs.redis",
                        "scenario.yaml:template.bees[0].config.outputs.redis")))
                .andExpect(jsonPath("$.findings[*].message", org.hamcrest.Matchers.hasItems(
                        org.hamcrest.Matchers.containsString("requires exactly one source mode"),
                        org.hamcrest.Matchers.containsString("requires at least one target"))));
    }

    @Test
    void bundleValidationRejectsRedisDatasetWithBothSourceModes() throws Exception {
        useRepositoryCapabilityManifest("processor", "processor.latest.yaml");
        useRepositoryCapabilityManifest("io-redis-dataset", "io.redis-dataset.latest.yaml");
        useRepositoryCapabilityManifest("io-redis-output", "io.redis-output.latest.yaml");
        capabilityCatalogue.reload();

        byte[] zip = bundleZip("scenario.yaml", """
                id: processor-redis-dataset-two-source-modes-demo
                name: Processor Redis dataset two source modes demo
                template:
                  image: ctrl-image:latest
                  bees:
                    - role: processor
                      image: processor:latest
                      work: {}
                      config:
                        baseUrl: "http://wiremock:8080"
                        mode: THREAD_COUNT
                        threadCount: 1
                        inputs:
                          type: REDIS_DATASET
                          redis:
                            host: redis
                            port: 6379
                            ssl: false
                            listName: ph:dataset
                            sources:
                              - listName: ph:dataset:other
                                weight: 1
                            pickStrategy: ROUND_ROBIN
                            ratePerSec: 1
                        outputs:
                          type: REDIS
                          redis:
                            host: redis
                            port: 6379
                            ssl: false
                            sourceStep: LAST
                            pushDirection: RPUSH
                            routes: []
                            targetListTemplate: ""
                            defaultList: ph:out
                            maxLen: -1
                """);

        mvc.perform(post("/validation/scenario-bundles")
                        .contentType("application/zip")
                        .content(zip)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.findings", hasSize(1)))
                .andExpect(jsonPath("$.findings[0].path")
                        .value("scenario.yaml:template.bees[0].config.inputs.redis"))
                .andExpect(jsonPath("$.findings[0].message")
                        .value(org.hamcrest.Matchers.containsString("requires exactly one source mode")));
    }

    @Test
    void bundleValidationRejectsMalformedRedisDatasetSourcesAndOutputRoutes() throws Exception {
        useRepositoryCapabilityManifest("processor", "processor.latest.yaml");
        useRepositoryCapabilityManifest("io-redis-dataset", "io.redis-dataset.latest.yaml");
        useRepositoryCapabilityManifest("io-redis-output", "io.redis-output.latest.yaml");
        capabilityCatalogue.reload();

        byte[] zip = bundleZip("scenario.yaml", """
                id: processor-malformed-redis-io-demo
                name: Processor malformed Redis IO demo
                template:
                  image: ctrl-image:latest
                  bees:
                    - role: processor
                      image: processor:latest
                      work: {}
                      config:
                        baseUrl: "http://wiremock:8080"
                        mode: THREAD_COUNT
                        threadCount: 1
                        inputs:
                          type: REDIS_DATASET
                          redis:
                            host: redis
                            port: 6379
                            ssl: false
                            listName: ""
                            sources:
                              - listName: ""
                                weight: 0
                              - not-an-object
                            pickStrategy: ROUND_ROBIN
                            ratePerSec: 1
                        outputs:
                          type: REDIS
                          redis:
                            host: redis
                            port: 6379
                            ssl: false
                            sourceStep: LAST
                            pushDirection: RPUSH
                            routes:
                              - {}
                            targetListTemplate: ""
                            defaultList: ""
                            maxLen: -1
                """);

        mvc.perform(post("/validation/scenario-bundles")
                        .contentType("application/zip")
                        .content(zip)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.findings[*].path", org.hamcrest.Matchers.hasItems(
                        "scenario.yaml:template.bees[0].config.inputs.redis.sources[0].listName",
                        "scenario.yaml:template.bees[0].config.inputs.redis.sources[0].weight",
                        "scenario.yaml:template.bees[0].config.inputs.redis.sources[1]",
                        "scenario.yaml:template.bees[0].config.outputs.redis.routes[0].list",
                        "scenario.yaml:template.bees[0].config.outputs.redis.routes[0]")))
                .andExpect(jsonPath("$.findings[*].message", org.hamcrest.Matchers.hasItems(
                        org.hamcrest.Matchers.containsString("source listName must not be blank"),
                        org.hamcrest.Matchers.containsString("source weight must be > 0"),
                        org.hamcrest.Matchers.containsString("source entry must be an object"),
                        org.hamcrest.Matchers.containsString("route list must not be blank"),
                        org.hamcrest.Matchers.containsString("route requires match and/or header"))));
    }

    @Test
    void bundleValidationRejectsRedisJsonIoCollectionsThatAreNotLists() throws Exception {
        useRepositoryCapabilityManifest("processor", "processor.latest.yaml");
        useRepositoryCapabilityManifest("io-redis-dataset", "io.redis-dataset.latest.yaml");
        useRepositoryCapabilityManifest("io-redis-output", "io.redis-output.latest.yaml");
        capabilityCatalogue.reload();

        byte[] zip = bundleZip("scenario.yaml", """
                id: processor-redis-io-non-list-json-demo
                name: Processor Redis IO non-list JSON demo
                template:
                  image: ctrl-image:latest
                  bees:
                    - role: processor
                      image: processor:latest
                      work: {}
                      config:
                        baseUrl: "http://wiremock:8080"
                        mode: THREAD_COUNT
                        threadCount: 1
                        inputs:
                          type: REDIS_DATASET
                          redis:
                            host: redis
                            port: 6379
                            ssl: false
                            listName: ph:dataset
                            sources:
                              listName: ph:dataset:other
                              weight: 1
                            pickStrategy: ROUND_ROBIN
                            ratePerSec: 1
                        outputs:
                          type: REDIS
                          redis:
                            host: redis
                            port: 6379
                            ssl: false
                            sourceStep: LAST
                            pushDirection: RPUSH
                            routes:
                              list: ph:out:routed
                              match: ".*"
                            targetListTemplate: ""
                            defaultList: ph:out
                            maxLen: -1
                """);

        mvc.perform(post("/validation/scenario-bundles")
                        .contentType("application/zip")
                        .content(zip)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.findings", hasSize(2)))
                .andExpect(jsonPath("$.findings[*].path", org.hamcrest.Matchers.hasItems(
                        "scenario.yaml:template.bees[0].config.inputs.redis.sources",
                        "scenario.yaml:template.bees[0].config.outputs.redis.routes")))
                .andExpect(jsonPath("$.findings[*].message", org.hamcrest.Matchers.hasItems(
                        org.hamcrest.Matchers.containsString("sources must be a list"),
                        org.hamcrest.Matchers.containsString("routes must be a list"))));
    }

    @Test
    void bundleValidationRejectsSelectedIoNumericRangeViolations() throws Exception {
        useRepositoryCapabilityManifest("processor", "processor.latest.yaml");
        useRepositoryCapabilityManifest("io-redis-dataset", "io.redis-dataset.latest.yaml");
        useRepositoryCapabilityManifest("io-redis-output", "io.redis-output.latest.yaml");
        capabilityCatalogue.reload();

        byte[] zip = bundleZip("scenario.yaml", """
                id: processor-redis-io-range-invalid-demo
                name: Processor Redis IO range invalid demo
                template:
                  image: ctrl-image:latest
                  bees:
                    - role: processor
                      image: processor:latest
                      work: {}
                      config:
                        baseUrl: "http://wiremock:8080"
                        mode: THREAD_COUNT
                        threadCount: 1
                        inputs:
                          type: REDIS_DATASET
                          redis:
                            host: redis
                            port: 0
                            ssl: false
                            listName: ph:dataset
                            sources: []
                            pickStrategy: ROUND_ROBIN
                            ratePerSec: -0.1
                        outputs:
                          type: REDIS
                          redis:
                            host: redis
                            port: 70000
                            ssl: false
                            sourceStep: LAST
                            pushDirection: RPUSH
                            routes: []
                            targetListTemplate: ""
                            defaultList: ph:out
                            maxLen: -2
                """);

        mvc.perform(post("/validation/scenario-bundles")
                        .contentType("application/zip")
                        .content(zip)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.findings", hasSize(4)))
                .andExpect(jsonPath("$.findings[*].path", org.hamcrest.Matchers.hasItems(
                        "scenario.yaml:template.bees[0].config.inputs.redis.port",
                        "scenario.yaml:template.bees[0].config.inputs.redis.ratePerSec",
                        "scenario.yaml:template.bees[0].config.outputs.redis.port",
                        "scenario.yaml:template.bees[0].config.outputs.redis.maxLen")))
                .andExpect(jsonPath("$.findings[*].message", org.hamcrest.Matchers.hasItems(
                        org.hamcrest.Matchers.containsString("expected between 1 and 65535"),
                        org.hamcrest.Matchers.containsString("expected >= 0"),
                        org.hamcrest.Matchers.containsString("expected >= -1"))));
    }

    @Test
    void bundleValidationRejectsImageCapabilityConfigTypeMismatches() throws Exception {
        Files.writeString(capabilitiesDir.resolve("typed-worker.latest.json"), """
                {
                  "schemaVersion": "1.0",
                  "capabilitiesVersion": "1.0",
                  "role": "typed-worker",
                  "image": {
                    "name": "typed-worker",
                    "tag": "latest"
                  },
                  "config": [
                    { "name": "textValue", "type": "string" },
                    { "name": "flagValue", "type": "boolean" },
                    { "name": "jsonValue", "type": "json" },
                    { "name": "countValue", "type": "integer" },
                    { "name": "rateValue", "type": "number" }
                  ]
                }
                """);
        capabilityCatalogue.reload();

        byte[] zip = bundleZip("scenario.yaml", """
                id: typed-worker-type-invalid-demo
                name: Typed worker type invalid demo
                template:
                  image: ctrl-image:latest
                  bees:
                    - role: typed-worker
                      image: typed-worker:latest
                      work: {}
                      config:
                        textValue: 123
                        flagValue: "true"
                        jsonValue: "[]"
                        countValue: 1.5
                        rateValue: "2.5"
                """);

        mvc.perform(post("/validation/scenario-bundles")
                        .contentType("application/zip")
                        .content(zip)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.findings", hasSize(5)))
                .andExpect(jsonPath("$.findings[*].path", org.hamcrest.Matchers.hasItems(
                        "scenario.yaml:template.bees[0].config.textValue",
                        "scenario.yaml:template.bees[0].config.flagValue",
                        "scenario.yaml:template.bees[0].config.jsonValue",
                        "scenario.yaml:template.bees[0].config.countValue",
                        "scenario.yaml:template.bees[0].config.rateValue")))
                .andExpect(jsonPath("$.findings[*].message", org.hamcrest.Matchers.hasItems(
                        org.hamcrest.Matchers.containsString("must be string"),
                        org.hamcrest.Matchers.containsString("must be boolean"),
                        org.hamcrest.Matchers.containsString("must be object or array"),
                        org.hamcrest.Matchers.containsString("must be integer"),
                        org.hamcrest.Matchers.containsString("must be number"))));
    }

    @Test
    void bundleValidationDoesNotDuplicateNumericRangeFindingAfterTypeMismatch() throws Exception {
        Files.writeString(capabilitiesDir.resolve("bounded-worker.latest.json"), """
                {
                  "schemaVersion": "1.0",
                  "capabilitiesVersion": "1.0",
                  "role": "bounded-worker",
                  "image": {
                    "name": "bounded-worker",
                    "tag": "latest"
                  },
                  "config": [
                    { "name": "rateValue", "type": "number", "min": 0, "max": 10 }
                  ]
                }
                """);
        capabilityCatalogue.reload();

        byte[] zip = bundleZip("scenario.yaml", """
                id: bounded-worker-type-invalid-demo
                name: Bounded worker type invalid demo
                template:
                  image: ctrl-image:latest
                  bees:
                    - role: bounded-worker
                      image: bounded-worker:latest
                      work: {}
                      config:
                        rateValue: "fast"
                """);

        mvc.perform(post("/validation/scenario-bundles")
                        .contentType("application/zip")
                        .content(zip)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.findings", hasSize(1)))
                .andExpect(jsonPath("$.findings[0].path")
                        .value("scenario.yaml:template.bees[0].config.rateValue"))
                .andExpect(jsonPath("$.findings[0].message")
                        .value(org.hamcrest.Matchers.containsString("must be number")))
                .andExpect(jsonPath("$.findings[0].message")
                        .value(org.hamcrest.Matchers.not(
                                org.hamcrest.Matchers.containsString("finite number"))));
    }

    @Test
    void bundleValidationRejectsSelectedIoConfigTypeMismatches() throws Exception {
        useRepositoryCapabilityManifest("generator", "generator.latest.yaml");
        useRepositoryCapabilityManifest("io-scheduler", "io.scheduler.latest.yaml");
        capabilityCatalogue.reload();

        byte[] zip = bundleZip("scenario.yaml", """
                id: generator-selected-io-type-invalid-demo
                name: Generator selected IO type invalid demo
                template:
                  image: ctrl-image:latest
                  bees:
                    - role: generator
                      image: generator:latest
                      work: {}
                      config:
                        inputs:
                          type: SCHEDULER
                          scheduler:
                            ratePerSec: "1.0"
                            maxMessages: 10.5
                            reset: "true"
                        message:
                          bodyType: SIMPLE
                          body:
                            bad: true
                """);

        mvc.perform(post("/validation/scenario-bundles")
                        .contentType("application/zip")
                        .content(zip)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.findings", hasSize(4)))
                .andExpect(jsonPath("$.findings[*].path", org.hamcrest.Matchers.hasItems(
                        "scenario.yaml:template.bees[0].config.inputs.scheduler.ratePerSec",
                        "scenario.yaml:template.bees[0].config.inputs.scheduler.maxMessages",
                        "scenario.yaml:template.bees[0].config.inputs.scheduler.reset",
                        "scenario.yaml:template.bees[0].config.message.body")))
                .andExpect(jsonPath("$.findings[*].message", org.hamcrest.Matchers.hasItems(
                        org.hamcrest.Matchers.containsString("must be string"),
                        org.hamcrest.Matchers.containsString("must be number"),
                        org.hamcrest.Matchers.containsString("must be integer"),
                        org.hamcrest.Matchers.containsString("must be boolean"))));
    }

    @Test
    void bundleValidationRejectsUnknownInputSelectorOption() throws Exception {
        useRepositoryCapabilityManifest("processor", "processor.latest.yaml");
        capabilityCatalogue.reload();

        byte[] zip = bundleZip("scenario.yaml", """
                id: processor-unknown-input-selector-demo
                name: Processor unknown input selector demo
                template:
                  image: ctrl-image:latest
                  bees:
                    - role: processor
                      image: processor:latest
                      work: {}
                      config:
                        baseUrl: "http://wiremock:8080"
                        mode: THREAD_COUNT
                        threadCount: 1
                        inputs:
                          type: BANANA
                        outputs:
                          type: RABBITMQ
                """);

        mvc.perform(post("/validation/scenario-bundles")
                        .contentType("application/zip")
                        .content(zip)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.findings", hasSize(1)))
                .andExpect(jsonPath("$.findings[0].path")
                        .value("scenario.yaml:template.bees[0].config.inputs.type"))
                .andExpect(jsonPath("$.findings[0].message")
                        .value(org.hamcrest.Matchers.allOf(
                                org.hamcrest.Matchers.containsString("unsupported value 'BANANA'"),
                                org.hamcrest.Matchers.containsString("RABBITMQ"),
                                org.hamcrest.Matchers.containsString("REDIS_DATASET"))));
    }

    @Test
    void bundleValidationRejectsUnknownOutputSelectorOption() throws Exception {
        useRepositoryCapabilityManifest("processor", "processor.latest.yaml");
        capabilityCatalogue.reload();

        byte[] zip = bundleZip("scenario.yaml", """
                id: processor-unknown-output-selector-demo
                name: Processor unknown output selector demo
                template:
                  image: ctrl-image:latest
                  bees:
                    - role: processor
                      image: processor:latest
                      work: {}
                      config:
                        baseUrl: "http://wiremock:8080"
                        mode: THREAD_COUNT
                        threadCount: 1
                        inputs:
                          type: RABBITMQ
                        outputs:
                          type: BANANA
                """);

        mvc.perform(post("/validation/scenario-bundles")
                        .contentType("application/zip")
                        .content(zip)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.findings", hasSize(1)))
                .andExpect(jsonPath("$.findings[0].path")
                        .value("scenario.yaml:template.bees[0].config.outputs.type"))
                .andExpect(jsonPath("$.findings[0].message")
                        .value(org.hamcrest.Matchers.allOf(
                                org.hamcrest.Matchers.containsString("unsupported value 'BANANA'"),
                                org.hamcrest.Matchers.containsString("RABBITMQ"),
                                org.hamcrest.Matchers.containsString("REDIS"),
                                org.hamcrest.Matchers.containsString("NONE"))));
    }

    @Test
    void bundleValidationRejectsUnknownCapabilityOption() throws Exception {
        useRepositoryCapabilityManifest("processor", "processor.latest.yaml");
        capabilityCatalogue.reload();

        byte[] zip = bundleZip("scenario.yaml", """
                id: processor-unknown-mode-demo
                name: Processor unknown mode demo
                template:
                  image: ctrl-image:latest
                  bees:
                    - role: processor
                      image: processor:latest
                      work: {}
                      config:
                        baseUrl: "http://wiremock:8080"
                        mode: BANANA
                        threadCount: 1
                        inputs:
                          type: RABBITMQ
                        outputs:
                          type: RABBITMQ
                """);

        mvc.perform(post("/validation/scenario-bundles")
                        .contentType("application/zip")
                        .content(zip)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.findings", hasSize(1)))
                .andExpect(jsonPath("$.findings[0].path")
                        .value("scenario.yaml:template.bees[0].config.mode"))
                .andExpect(jsonPath("$.findings[0].message")
                        .value(org.hamcrest.Matchers.allOf(
                                org.hamcrest.Matchers.containsString("unsupported value 'BANANA'"),
                                org.hamcrest.Matchers.containsString("THREAD_COUNT"),
                                org.hamcrest.Matchers.containsString("RATE_PER_SEC"))));
    }

    @Test
    void bundleValidationAllowsTemplatedCapabilityOption() throws Exception {
        useRepositoryCapabilityManifest("postprocessor", "postprocessor.latest.yaml");
        capabilityCatalogue.reload();

        byte[] zip = bundleZip(Map.of(
                "scenario.yaml", """
                    id: postprocessor-templated-option-demo
                    name: Postprocessor templated option demo
                    template:
                      image: ctrl-image:latest
                      bees:
                        - role: postprocessor
                          image: postprocessor:latest
                          work: {}
                          config:
                            forwardToOutput: false
                            txOutcomeSinkMode: "{{ vars.txOutcomeSinkMode }}"
                            dropTxOutcomeWithoutCallId: true
                    """,
                "variables.yaml", """
                    version: 1
                    definitions:
                      - name: txOutcomeSinkMode
                        scope: global
                        type: string
                        required: true
                    profiles:
                      - id: default
                        name: Default
                    values:
                      global:
                        default:
                          txOutcomeSinkMode: "CLICKHOUSE_V2"
                    """));

        mvc.perform(post("/validation/scenario-bundles")
                        .contentType("application/zip")
                        .content(zip)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.findings", hasSize(0)));
    }

    @Test
    void bundleValidationReportsDuplicateScenarioYamlKeys() throws Exception {
        byte[] zip = bundleZip("scenario.yaml", """
                id: duplicate-scenario-key-demo
                id: duplicate-scenario-key-demo
                name: Duplicate scenario key demo
                template:
                  image: ctrl-image:latest
                  bees: []
                """);

        mvc.perform(post("/validation/scenario-bundles")
                        .contentType("application/zip")
                        .content(zip)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.findings[0].category").value("scenario"))
                .andExpect(jsonPath("$.findings[0].code").value("SCENARIO_DESCRIPTOR_INVALID"))
                .andExpect(jsonPath("$.findings[0].path").value("scenario.yaml"));
    }

    @Test
    void bundleValidationReportsDuplicateVariablesYamlKeys() throws Exception {
        byte[] zip = bundleZip(Map.of(
                "scenario.yaml", """
                    id: duplicate-variable-key-demo
                    name: Duplicate variable key demo
                    template:
                      image: ctrl-image:latest
                      bees: []
                    """,
                "variables.yaml", """
                    version: 1
                    version: 1
                    definitions:
                      - name: customerId
                        scope: GLOBAL
                        type: STRING
                    """));

        mvc.perform(post("/validation/scenario-bundles")
                        .contentType("application/zip")
                        .content(zip)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.findings[0].category").value("variables"))
                .andExpect(jsonPath("$.findings[0].code").value("VARIABLES_INVALID"))
                .andExpect(jsonPath("$.findings[0].path").value("variables.yaml"));
    }

    @Test
    void bundleValidationReportsDuplicateSutYamlKeys() throws Exception {
        byte[] zip = bundleZip(Map.of(
                "scenario.yaml", """
                    id: duplicate-sut-key-demo
                    name: Duplicate SUT key demo
                    template:
                      image: ctrl-image:latest
                      bees: []
                    """,
                "sut/default/sut.yaml", """
                    id: default
                    id: default
                    baseUrl: http://example.test
                    """));

        mvc.perform(post("/validation/scenario-bundles")
                        .contentType("application/zip")
                        .content(zip)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.findings[0].category").value("sut"))
                .andExpect(jsonPath("$.findings[0].code").value("SUT_INVALID"))
                .andExpect(jsonPath("$.findings[0].path").value("sut"));
    }

    @Test
    void bundleValidationReportsMissingCanonicalSutYaml() throws Exception {
        byte[] zip = bundleZip(Map.of(
                "scenario.yaml", """
                    id: missing-canonical-sut-demo
                    name: Missing canonical SUT demo
                    template:
                      image: ctrl-image:latest
                      bees: []
                    """,
                "sut/default/sut.yml", """
                    id: default
                    name: Default SUT
                    """));

        mvc.perform(post("/validation/scenario-bundles")
                        .contentType("application/zip")
                        .content(zip)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.findings[0].category").value("sut"))
                .andExpect(jsonPath("$.findings[0].code").value("SUT_INVALID"))
                .andExpect(jsonPath("$.findings[0].message").value(org.hamcrest.Matchers.containsString("has no sut.yaml")));
    }

    @Test
    void bundleValidationReportsValuesSutWithoutCanonicalSutYaml() throws Exception {
        byte[] zip = bundleZip(Map.of(
                "scenario.yaml", """
                    id: values-sut-missing-canonical-demo
                    name: Values SUT missing canonical demo
                    template:
                      image: ctrl-image:latest
                      bees: []
                    """,
                "variables.yaml", """
                    version: 1
                    definitions:
                      - name: customerId
                        scope: sut
                        type: string
                    profiles:
                      - id: default
                        name: Default
                    values:
                      sut:
                        default:
                          ghost:
                            customerId: "123"
                    """));

        mvc.perform(post("/validation/scenario-bundles")
                        .contentType("application/zip")
                        .content(zip)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.findings[0].category").value("variables"))
                .andExpect(jsonPath("$.findings[0].code").value("VARIABLES_INVALID"))
                .andExpect(jsonPath("$.findings[0].path").value("variables.yaml"))
                .andExpect(jsonPath("$.findings[0].message").value(org.hamcrest.Matchers.containsString("unknown sutId 'ghost'")));
    }

    @Test
    void bundleValidationReportsSutYamlIdMismatch() throws Exception {
        byte[] zip = bundleZip(Map.of(
                "scenario.yaml", """
                    id: mismatched-sut-id-demo
                    name: Mismatched SUT ID demo
                    template:
                      image: ctrl-image:latest
                      bees: []
                    """,
                "sut/default/sut.yaml", """
                    id: other
                    name: Other SUT
                    """));

        mvc.perform(post("/validation/scenario-bundles")
                        .contentType("application/zip")
                        .content(zip)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.findings[0].category").value("sut"))
                .andExpect(jsonPath("$.findings[0].code").value("SUT_INVALID"))
                .andExpect(jsonPath("$.findings[0].message").value(org.hamcrest.Matchers.containsString("does not match directory name")));
    }

    @Test
    void listBundleSutsRejectsMalformedCanonicalSut() throws Exception {
        Path bundle = Files.createDirectories(scenariosDir.resolve("malformed-sut-list-demo"));
        Files.writeString(bundle.resolve("scenario.yaml"), """
                id: malformed-sut-list-demo
                name: Malformed SUT list demo
                template:
                  image: ctrl-image:latest
                  bees: []
                """);
        Path sutDir = Files.createDirectories(bundle.resolve("sut").resolve("default"));
        Files.writeString(sutDir.resolve("sut.yaml"), """
                id: other
                name: Other SUT
                """);

        mvc.perform(post("/scenarios/reload"))
                .andExpect(status().isNoContent());

        mvc.perform(get("/scenarios/malformed-sut-list-demo/suts").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    void bundleUploadReturnsValidationBodyWhenDescriptorIsMissing() throws Exception {
        byte[] zip = bundleZip("note.txt", "no scenario here");

        mvc.perform(post("/scenarios/bundles")
                        .contentType("application/zip")
                        .content(zip)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.findings[0].code").value("SCENARIO_DESCRIPTOR_INVALID"));
    }

    @Test
    void bundleUploadReturnsValidationBodyWhenDescriptorIsMalformed() throws Exception {
        byte[] zip = bundleZip("scenario.yaml", """
                id: [not-a-string]
                name: Broken descriptor
                template:
                  image: ctrl-image:latest
                  bees: []
                """);

        mvc.perform(post("/scenarios/bundles")
                        .contentType("application/zip")
                        .content(zip)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.findings[0].code").value("SCENARIO_DESCRIPTOR_INVALID"));
    }

    @Test
    void bundleReplaceReturnsCanonicalValidationBodyWhenScenarioIdMismatches() throws Exception {
        Path existing = Files.createDirectories(scenariosDir.resolve("replace-target"));
        Files.writeString(existing.resolve("scenario.yaml"), """
                id: replace-target
                name: Replace target
                template:
                  image: ctrl-image:latest
                  bees: []
                """);
        mvc.perform(post("/scenarios/reload"))
                .andExpect(status().isNoContent());

        byte[] zip = bundleZip("scenario.yaml", """
                id: uploaded-other
                name: Uploaded other
                template:
                  image: ctrl-image:latest
                  bees: []
                """);

        mvc.perform(put("/scenarios/replace-target/bundle")
                        .contentType("application/zip")
                        .content(zip)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.source").value("uploaded-zip"))
                .andExpect(jsonPath("$.scenarioId").value("uploaded-other"))
                .andExpect(jsonPath("$.findings[0].code").value("SCENARIO_DESCRIPTOR_INVALID"))
                .andExpect(jsonPath("$.findings[0].path").value("scenario.yaml:id"))
                .andExpect(jsonPath("$.findings[0].message")
                        .value(org.hamcrest.Matchers.containsString("does not match requested scenario")));
    }

    @Test
    void templateValidationReportsMissingCallId() throws Exception {
        Path bundleDir = scenariosDir.resolve("template-ref-demo");
        Files.createDirectories(bundleDir);
        Files.writeString(bundleDir.resolve("scenario.yaml"), """
                id: template-ref-demo
                name: Template reference demo
                template:
                  image: ctrl-image:latest
                  bees:
                    - role: generator
                      image: generator:latest
                      config:
                        message:
                          headers:
                            x-ph-service-id: auth
                            x-ph-call-id: login
                      work:
                        out:
                          out: build
                    - role: request-builder
                      image: request-builder:latest
                      config:
                        templateRoot: /app/scenario/templates/redemption
                        serviceId: auth
                      work:
                        in:
                          in: build
                        out:
                          out: proc
                """);

        mvc.perform(post("/scenarios/reload"))
                .andExpect(status().isNoContent());

        mvc.perform(post("/validation/scenario-bundles/existing")
                        .param("bundleKey", "template-ref-demo")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.findings[0].category").value("templates"))
                .andExpect(jsonPath("$.findings[0].code").value("TEMPLATE_CALL_ID_MISSING"));
    }

    @Test
    void templateValidationUsesWorkerTemplateRootForTcpTemplates() throws Exception {
        byte[] zip = bundleZip(Map.of(
                "scenario.yaml", """
                    id: tcp-template-ref-demo
                    name: TCP template reference demo
                    template:
                      image: ctrl-image:latest
                      bees:
                        - role: generator
                          image: generator:latest
                          config:
                            message:
                              headers:
                                x-ph-call-id: tcp-request
                          work:
                            out:
                              out: build
                        - role: request-builder
                          image: request-builder:latest
                          config:
                            templateRoot: /app/scenario/templates/tcp
                            serviceId: banking
                          work:
                            in:
                              in: build
                            out:
                              out: proc
                    """,
                "templates/tcp/banking/tcp-request.yaml", """
                    protocol: TCP
                    serviceId: banking
                    callId: tcp-request
                    bodyTemplate: "{{ payload }}"
                    """));

        mvc.perform(post("/validation/scenario-bundles")
                        .contentType("application/zip")
                        .content(zip)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.findings", hasSize(0)));
    }

    @Test
    void templateValidationIgnoresDuplicateCallIdOutsideWorkerTemplateRoot() throws Exception {
        byte[] zip = bundleZip(Map.of(
                "scenario.yaml", """
                    id: template-root-scope-demo
                    name: Template root scope demo
                    template:
                      image: ctrl-image:latest
                      bees:
                        - role: generator
                          image: generator:latest
                          config:
                            message:
                              headers:
                                x-ph-call-id: redeem
                          work:
                            out:
                              out: build
                        - role: request-builder
                          image: request-builder:latest
                          config:
                            templateRoot: /app/scenario/templates/redemption
                            serviceId: default
                          work:
                            in:
                              in: build
                            out:
                              out: proc
                    """,
                "templates/redemption/redeem.yaml", """
                    protocol: HTTP
                    serviceId: default
                    callId: redeem
                    method: POST
                    pathTemplate: /redeem
                    """,
                "templates/auth/redeem.yaml", """
                    protocol: HTTP
                    serviceId: default
                    callId: redeem
                    method: POST
                    pathTemplate: /auth/redeem
                    """));

        mvc.perform(post("/validation/scenario-bundles")
                        .contentType("application/zip")
                        .content(zip)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.findings", hasSize(0)));
    }

    @Test
    void templateValidationReportsDuplicateVisibleTemplateKey() throws Exception {
        byte[] zip = bundleZip(Map.of(
                "scenario.yaml", """
                    id: duplicate-visible-template-demo
                    name: Duplicate visible template demo
                    template:
                      image: ctrl-image:latest
                      bees:
                        - role: generator
                          image: generator:latest
                          config:
                            message:
                              headers:
                                x-ph-call-id: redeem
                          work:
                            out:
                              out: build
                        - role: request-builder
                          image: request-builder:latest
                          config:
                            templateRoot: /app/scenario/templates/redemption
                            serviceId: default
                          work:
                            in:
                              in: build
                            out:
                              out: proc
                    """,
                "templates/redemption/redeem-a.yaml", """
                    protocol: HTTP
                    serviceId: default
                    callId: redeem
                    method: POST
                    pathTemplate: /redeem/a
                    """,
                "templates/redemption/nested/redeem-b.yaml", """
                    protocol: HTTP
                    serviceId: default
                    callId: redeem
                    method: POST
                    pathTemplate: /redeem/b
                    """));

        mvc.perform(post("/validation/scenario-bundles")
                        .contentType("application/zip")
                        .content(zip)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.findings[0].category").value("templates"))
                .andExpect(jsonPath("$.findings[0].code").value("TEMPLATE_CALL_ID_DUPLICATE"))
                .andExpect(jsonPath("$.findings[0].message")
                        .value(org.hamcrest.Matchers.containsString("default::redeem")));
    }

    @Test
    void bundleValidationReportsMissingVariablesYamlForVarsReferences() throws Exception {
        byte[] zip = bundleZip(Map.of(
                "scenario.yaml", """
                    id: vars-ref-demo
                    name: Vars ref demo
                    template:
                      image: ctrl-image:latest
                      bees: []
                    """,
                "templates/http/default/account.yaml", """
                    protocol: HTTP
                    serviceId: default
                    callId: account
                    method: GET
                    pathTemplate: /accounts/{{ vars.customerId }}
                    """));

        mvc.perform(post("/validation/scenario-bundles")
                        .contentType("application/zip")
                        .content(zip)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.findings[0].category").value("variables"))
                .andExpect(jsonPath("$.findings[0].code").value("VARIABLES_MISSING"));
    }

    @Test
    void bundleValidationReportsUnknownAuthProfileType() throws Exception {
        byte[] zip = bundleZip(Map.of(
                "scenario.yaml", """
                    id: auth-type-demo
                    name: Auth type demo
                    template:
                      image: ctrl-image:latest
                      bees: []
                    """,
                "templates/http/default/account.yaml", """
                    protocol: HTTP
                    serviceId: default
                    callId: account
                    method: GET
                    pathTemplate: /accounts
                    authRef:
                      profileId: api
                      applyAs: HTTP_AUTHORIZATION_BEARER
                    """,
                "authProfiles.yaml", """
                    profiles:
                      api:
                        type: definitely-not-valid
                        storage:
                          mode: NONE
                    """));

        mvc.perform(post("/validation/scenario-bundles")
                        .contentType("application/zip")
                        .content(zip)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.findings[0].category").value("auth"))
                .andExpect(jsonPath("$.findings[0].code").value("AUTH_PROFILES_INVALID"));
    }

    @Test
    void bundleValidationDoesNotAcceptAuthProfilesYmlFallback() throws Exception {
        byte[] zip = bundleZip(Map.of(
                "scenario.yaml", """
                    id: auth-yml-demo
                    name: Auth YML demo
                    template:
                      image: ctrl-image:latest
                      bees: []
                    """,
                "templates/http/default/account.yaml", """
                    protocol: HTTP
                    serviceId: default
                    callId: account
                    method: GET
                    pathTemplate: /accounts
                    authRef:
                      profileId: api
                      applyAs: HTTP_AUTHORIZATION_BEARER
                    """,
                "authProfiles.yml", """
                    profiles:
                      api:
                        type: STATIC_TOKEN
                        storage:
                          mode: NONE
                        token: test-token
                    """));

        mvc.perform(post("/validation/scenario-bundles")
                        .contentType("application/zip")
                        .content(zip)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.findings[0].category").value("auth"))
                .andExpect(jsonPath("$.findings[0].code").value("AUTH_PROFILES_MISSING"));
    }

    @Test
    void bundleValidationReportsMissingRefreshableAuthTokenKey() throws Exception {
        byte[] zip = bundleZip(Map.of(
                "scenario.yaml", """
                    id: auth-token-key-demo
                    name: Auth token key demo
                    template:
                      image: ctrl-image:latest
                      bees: []
                    """,
                "templates/http/default/account.yaml", """
                    protocol: HTTP
                    serviceId: default
                    callId: account
                    method: GET
                    pathTemplate: /accounts
                    authRef:
                      profileId: api
                      applyAs: HTTP_AUTHORIZATION_BEARER
                    """,
                "authProfiles.yaml", """
                    profiles:
                      api:
                        type: oauth2-client-credentials
                        storage:
                          mode: REDIS
                    """));

        mvc.perform(post("/validation/scenario-bundles")
                        .contentType("application/zip")
                        .content(zip)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.findings[0].category").value("auth"))
                .andExpect(jsonPath("$.findings[0].code").value("AUTH_STORAGE_INVALID"))
                .andExpect(jsonPath("$.findings[0].path").value("authProfiles.yaml:profiles.api.storage.tokenKey"));
    }

    @Test
    void bundleValidationReportsInvalidRefreshableAuthTokenKey() throws Exception {
        byte[] zip = bundleZip(Map.of(
                "scenario.yaml", """
                    id: invalid-auth-token-key-demo
                    name: Invalid auth token key demo
                    template:
                      image: ctrl-image:latest
                      bees: []
                    """,
                "templates/http/default/account.yaml", """
                    protocol: HTTP
                    serviceId: default
                    callId: account
                    method: GET
                    pathTemplate: /accounts
                    authRef:
                      profileId: api
                      applyAs: HTTP_AUTHORIZATION_BEARER
                    """,
                "authProfiles.yaml", """
                    profiles:
                      api:
                        type: oauth2-client-credentials
                        storage:
                          mode: REDIS
                          tokenKey: ../secret
                    """));

        mvc.perform(post("/validation/scenario-bundles")
                        .contentType("application/zip")
                        .content(zip)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.findings[0].category").value("auth"))
                .andExpect(jsonPath("$.findings[0].code").value("AUTH_STORAGE_INVALID"))
                .andExpect(jsonPath("$.findings[0].path").value("authProfiles.yaml:profiles.api.storage.tokenKey"));
    }

    @Test
    void bundleValidationReportsDuplicateTemplateYamlKeys() throws Exception {
        byte[] zip = bundleZip(Map.of(
                "scenario.yaml", """
                    id: duplicate-template-key-demo
                    name: Duplicate template key demo
                    template:
                      image: ctrl-image:latest
                      bees: []
                    """,
                "templates/http/default/account.yaml", """
                    protocol: HTTP
                    protocol: HTTP
                    serviceId: default
                    callId: account
                    method: GET
                    pathTemplate: /accounts
                    """));

        mvc.perform(post("/validation/scenario-bundles")
                        .contentType("application/zip")
                        .content(zip)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.findings[0].category").value("templates"))
                .andExpect(jsonPath("$.findings[0].code").value("TEMPLATE_PARSE_ERROR"));
    }

    @Test
    void bundleValidationReportsDuplicateTemplateJsonKeys() throws Exception {
        byte[] zip = bundleZip(Map.of(
                "scenario.yaml", """
                    id: duplicate-template-json-key-demo
                    name: Duplicate template JSON key demo
                    template:
                      image: ctrl-image:latest
                      bees: []
                    """,
                "templates/http/default/account.json", """
                    {
                      "protocol": "HTTP",
                      "protocol": "HTTP",
                      "serviceId": "default",
                      "callId": "account",
                      "method": "GET",
                      "pathTemplate": "/accounts"
                    }
                    """));

        mvc.perform(post("/validation/scenario-bundles")
                        .contentType("application/zip")
                        .content(zip)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.findings[0].category").value("templates"))
                .andExpect(jsonPath("$.findings[0].code").value("TEMPLATE_PARSE_ERROR"));
    }

    @Test
    void bundleValidationReportsDuplicateAuthProfileKeys() throws Exception {
        byte[] zip = bundleZip(Map.of(
                "scenario.yaml", """
                    id: duplicate-auth-profile-demo
                    name: Duplicate auth profile demo
                    template:
                      image: ctrl-image:latest
                      bees: []
                    """,
                "templates/http/default/account.yaml", """
                    protocol: HTTP
                    serviceId: default
                    callId: account
                    method: GET
                    pathTemplate: /accounts
                    authRef:
                      profileId: api
                      applyAs: HTTP_AUTHORIZATION_BEARER
                    """,
                "authProfiles.yaml", """
                    profiles:
                      api:
                        type: bearer-token
                        storage:
                          mode: NONE
                      api:
                        type: bearer-token
                        storage:
                          mode: NONE
                    """));

        mvc.perform(post("/validation/scenario-bundles")
                        .contentType("application/zip")
                        .content(zip)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.findings[0].category").value("auth"))
                .andExpect(jsonPath("$.findings[0].code").value("AUTH_PROFILES_INVALID"));
    }

    @Test
    void bundleUploadReturnsValidationBodyWhenScenarioAlreadyExists() throws Exception {
        mvc.perform(post("/scenarios")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {
                              "id": "duplicate-upload",
                              "name": "Duplicate upload"
                            }
                            """))
                .andExpect(status().isCreated());

        byte[] zip = bundleZip("scenario.yaml", """
                id: duplicate-upload
                name: Duplicate upload
                template:
                  image: ctrl-image:latest
                  bees: []
                """);

        mvc.perform(post("/scenarios/bundles")
                        .contentType("application/zip")
                        .content(zip)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.findings[0].code").value("DUPLICATE_SCENARIO_ID"));
    }

    @Test
    void existingBundleValidationReportsDuplicateScenarioIdCode() throws Exception {
        Path first = Files.createDirectories(scenariosDir.resolve("duplicate-a"));
        Files.writeString(first.resolve("scenario.yaml"), """
                id: duplicate-existing
                name: Duplicate A
                template:
                  image: ctrl-image:latest
                  bees: []
                """);
        Path second = Files.createDirectories(scenariosDir.resolve("duplicate-b"));
        Files.writeString(second.resolve("scenario.yaml"), """
                id: duplicate-existing
                name: Duplicate B
                template:
                  image: ctrl-image:latest
                  bees: []
                """);

        mvc.perform(post("/scenarios/reload"))
                .andExpect(status().isNoContent());

        mvc.perform(post("/validation/scenario-bundles/existing")
                        .param("bundleKey", "duplicate-a")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.summary.errors").value(1))
                .andExpect(jsonPath("$.findings", hasSize(1)))
                .andExpect(jsonPath("$.findings[0].category").value("scenario"))
                .andExpect(jsonPath("$.findings[0].code").value("DUPLICATE_SCENARIO_ID"));
    }

    @Test
    void existingBundleValidationReportsDefunctFindingOnce() throws Exception {
        Path bundle = Files.createDirectories(scenariosDir.resolve("missing-capability-demo"));
        Files.writeString(bundle.resolve("scenario.yaml"), """
                id: missing-capability-demo
                name: Missing capability demo
                template:
                  image: ctrl-image:latest
                  bees:
                    - role: worker
                      image: missing-image:latest
                      work:
                        in:
                          in: a
                        out:
                          out: b
                """);

        mvc.perform(post("/scenarios/reload"))
                .andExpect(status().isNoContent());

        mvc.perform(post("/validation/scenario-bundles/existing")
                        .param("bundleKey", "missing-capability-demo")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.summary.errors").value(1))
                .andExpect(jsonPath("$.findings", hasSize(1)))
                .andExpect(jsonPath("$.findings[0].code").value("CAPABILITY_MANIFEST_MISSING"));
    }

    @Test
    void bundleUploadReturnsValidationBodyWhenBundleIsInvalid() throws Exception {
        byte[] zip = bundleZip(Map.of(
                "scenario.yaml", """
                    id: invalid-upload
                    name: Invalid upload
                    template:
                      image: ctrl-image:latest
                      bees: []
                    """,
                "templates/http/default/account.yaml", """
                    protocol: HTTP
                    serviceId: default
                    callId: account
                    method: GET
                    pathTemplate: /accounts/{{ vars.customerId }}
                    """));

        mvc.perform(post("/scenarios/bundles")
                        .contentType("application/zip")
                        .content(zip)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.findings[0].code").value("VARIABLES_MISSING"));
    }

    @Test
    void authoringContractExposesFingerprintAndValidationEndpoints() throws Exception {
        mvc.perform(get("/api/authoring-contract").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contractVersion").value("scenario-authoring.v1"))
                .andExpect(jsonPath("$.fingerprint").exists())
                .andExpect(jsonPath("$.endpoints.validateBundle").value("/validation/scenario-bundles"))
                .andExpect(jsonPath("$.endpoints.validateExistingBundle")
                        .value("/validation/scenario-bundles/existing?bundleKey={bundleKey}"))
                .andExpect(jsonPath("$.endpoints.validateScenario").doesNotExist())
                .andExpect(jsonPath("$.endpoints.validateTemplates").doesNotExist())
                .andExpect(jsonPath("$.scenario.descriptorNames", hasSize(1)))
                .andExpect(jsonPath("$.scenario.descriptorNames[0]").value("scenario.yaml"))
                .andExpect(jsonPath("$.sut.root").value("sut/<sutId>/sut.yaml"))
                .andExpect(jsonPath("$.cache.sessionCacheable").value(true));

        mvc.perform(get("/api/authoring-contract/fingerprint").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contractVersion").value("scenario-authoring.v1"))
                .andExpect(jsonPath("$.fingerprint").exists());
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

    private static void useRepositoryCapabilityManifest(String prefix, String manifestName) throws IOException {
        Files.deleteIfExists(capabilitiesDir.resolve(prefix + "-manifest.json"));
        Files.copy(
                Path.of("capabilities").resolve(manifestName),
                capabilitiesDir.resolve(manifestName),
                StandardCopyOption.REPLACE_EXISTING);
    }

    private static byte[] bundleZip(String entryName, String content) throws IOException {
        return bundleZip(Map.of(entryName, content));
    }

    private static byte[] bundleZip(Map<String, String> entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }
}
