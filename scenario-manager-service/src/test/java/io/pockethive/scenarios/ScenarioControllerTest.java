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
                        worker:
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
                        worker:
                          message:
                            headers:
                              x-ph-call-id: login
                      work:
                        out:
                          out: build
                    - role: request-builder
                      image: request-builder:latest
                      config:
                        worker:
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
                            worker:
                              message:
                                headers:
                                  x-ph-call-id: tcp-request
                          work:
                            out:
                              out: build
                        - role: request-builder
                          image: request-builder:latest
                          config:
                            worker:
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
                            worker:
                              message:
                                headers:
                                  x-ph-call-id: redeem
                          work:
                            out:
                              out: build
                        - role: request-builder
                          image: request-builder:latest
                          config:
                            worker:
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
                            worker:
                              message:
                                headers:
                                  x-ph-call-id: redeem
                          work:
                            out:
                              out: build
                        - role: request-builder
                          image: request-builder:latest
                          config:
                            worker:
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
