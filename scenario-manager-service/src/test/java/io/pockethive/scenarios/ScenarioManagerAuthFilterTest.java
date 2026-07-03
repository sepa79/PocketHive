package io.pockethive.scenarios;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.pockethive.auth.client.AuthServiceClient;
import io.pockethive.auth.contract.AuthGrantDto;
import io.pockethive.auth.contract.AuthProduct;
import io.pockethive.auth.contract.AuthProvider;
import io.pockethive.auth.contract.AuthenticatedUserDto;
import io.pockethive.auth.contract.PocketHivePermissionIds;
import io.pockethive.auth.contract.PocketHiveResourceSelectors;
import io.pockethive.auth.contract.PocketHiveResourceTypes;
import io.pockethive.capabilities.CapabilityCatalogueService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class ScenarioManagerAuthFilterTest {
    @Autowired
    MockMvc mvc;

    @Autowired
    ScenarioService scenarioService;

    @Autowired
    CapabilityCatalogueService capabilityCatalogue;

    @MockBean
    AuthServiceClient authServiceClient;

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("scenarios.dir", () -> tempDir.toString());
        registry.add("capabilities.dir", () -> tempDir.resolve("capabilities").toString());
    }

    @BeforeEach
    void resetStorage() throws Exception {
        cleanDirectory(tempDir);
        Files.createDirectories(tempDir.resolve("capabilities"));
        scenarioService.reload();
    }

    @Test
    void actuatorHealthStaysUnauthenticated() throws Exception {
        mvc.perform(get("/actuator/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void apiRequiresAuthorizationHeader() throws Exception {
        mvc.perform(get("/scenarios"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.message").value("Missing Authorization header"));
    }

    @Test
    void readApisAllowGlobalViewUser() throws Exception {
        when(authServiceClient.resolve(anyString())).thenReturn(userWith(PocketHivePermissionIds.VIEW));

        mvc.perform(get("/scenarios")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token"))
            .andExpect(status().isOk());
    }

    @Test
    void templatesEndpointFiltersByRunFolderScope() throws Exception {
        writeScenario("demo", "alpha", "Alpha");
        writeScenario("prod", "omega", "Omega");
        scenarioService.reload();
        when(authServiceClient.resolve(anyString())).thenReturn(userWith(
            PocketHivePermissionIds.RUN,
            PocketHiveResourceTypes.FOLDER,
            "demo"));

        mvc.perform(get("/api/templates")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value("alpha"))
            .andExpect(jsonPath("$[0].folderPath").value("demo"))
            .andExpect(jsonPath("$[1]").doesNotExist());
    }

    @Test
    void templatesEndpointFiltersByRunBundleScope() throws Exception {
        writeScenario("e2e", "local-rest", "Local Rest");
        writeScenario("e2e", "local-rest-defaults", "Local Rest Defaults");
        scenarioService.reload();
        when(authServiceClient.resolve(anyString())).thenReturn(userWith(
            PocketHivePermissionIds.RUN,
            PocketHiveResourceTypes.BUNDLE,
            "e2e/local-rest"));

        mvc.perform(get("/api/templates")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value("local-rest"))
            .andExpect(jsonPath("$[1]").doesNotExist());
    }

    @Test
    void listEndpointFiltersByViewFolderScope() throws Exception {
        writeScenario("demo", "alpha", "Alpha");
        writeScenario("prod", "omega", "Omega");
        scenarioService.reload();
        when(authServiceClient.resolve(anyString())).thenReturn(userWith(
            PocketHivePermissionIds.VIEW,
            PocketHiveResourceTypes.FOLDER,
            "demo"));

        mvc.perform(get("/scenarios")
                .param("includeDefunct", "true")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value("alpha"))
            .andExpect(jsonPath("$[1]").doesNotExist());
    }

    @Test
    void scenarioReadRejectsUserOutsideGrantedFolderScope() throws Exception {
        writeScenario("demo", "alpha", "Alpha");
        writeScenario("prod", "omega", "Omega");
        scenarioService.reload();
        when(authServiceClient.resolve(anyString())).thenReturn(userWith(
            PocketHivePermissionIds.VIEW,
            PocketHiveResourceTypes.FOLDER,
            "demo"));

        mvc.perform(get("/scenarios/alpha")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value("alpha"));

        mvc.perform(get("/scenarios/omega")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token"))
            .andExpect(status().isForbidden())
            .andExpect(status().reason("PocketHive VIEW permission required within matching scope"));
    }

    @Test
    void bundleWorkspaceFeedAllowsViewUsersAndIncludesBrokenBundlesWithinScope() throws Exception {
        writeScenario("demo", "alpha", "Alpha");
        Path brokenBundleDir = Files.createDirectories(tempDir.resolve("demo").resolve("broken-bundle"));
        Files.writeString(brokenBundleDir.resolve("scenario.yaml"), "id: [not valid yaml");
        writeScenario("prod", "omega", "Omega");
        scenarioService.reload();
        when(authServiceClient.resolve(anyString())).thenReturn(userWith(
            PocketHivePermissionIds.VIEW,
            PocketHiveResourceTypes.FOLDER,
            "demo"));

        mvc.perform(get("/scenarios/bundles/workspaces")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.bundleKey=='demo/alpha')]").exists())
            .andExpect(jsonPath("$[?(@.bundleKey=='demo/broken-bundle')]").exists())
            .andExpect(jsonPath("$[?(@.bundleKey=='prod/omega')]").doesNotExist());
    }

    @Test
    void bundleWorkspaceReadEndpointsRespectBundleScope() throws Exception {
        writeScenario("demo", "alpha", "Alpha");
        Files.writeString(tempDir.resolve("demo").resolve("alpha").resolve("note.txt"), "hello");
        writeScenario("prod", "omega", "Omega");
        scenarioService.reload();
        when(authServiceClient.resolve(anyString())).thenReturn(userWith(
            PocketHivePermissionIds.VIEW,
            PocketHiveResourceTypes.FOLDER,
            "demo"));

        mvc.perform(get("/scenarios/bundles/tree")
                .param("bundleKey", "demo/alpha")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.bundleKey").value("demo/alpha"))
            .andExpect(jsonPath("$.nodes[?(@.path=='note.txt')]").exists());

        mvc.perform(get("/scenarios/bundles/file")
                .param("bundleKey", "demo/alpha")
                .param("path", "note.txt")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.bundleKey").value("demo/alpha"))
            .andExpect(jsonPath("$.content").value("hello"));

        mvc.perform(get("/scenarios/bundles/download")
                .param("bundleKey", "demo/alpha")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token"))
            .andExpect(status().isOk());

        mvc.perform(get("/scenarios/bundles/tree")
                .param("bundleKey", "prod/omega")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token"))
            .andExpect(status().isForbidden())
            .andExpect(status().reason("PocketHive VIEW permission required within matching scope"));

        mvc.perform(get("/scenarios/bundles/file")
                .param("bundleKey", "prod/omega")
                .param("path", "scenario.yaml")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token"))
            .andExpect(status().isForbidden())
            .andExpect(status().reason("PocketHive VIEW permission required within matching scope"));

        mvc.perform(get("/scenarios/bundles/download")
                .param("bundleKey", "prod/omega")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token"))
            .andExpect(status().isForbidden())
            .andExpect(status().reason("PocketHive VIEW permission required within matching scope"));
    }

    @Test
    void bundleWorkspaceWriteEndpointsRespectSourceAndTargetScope() throws Exception {
        writeScenario("demo", "alpha", "Alpha");
        writeScenario("demo", "beta", "Beta");
        writeScenario("prod", "omega", "Omega");
        scenarioService.reload();
        when(authServiceClient.resolve(anyString())).thenReturn(userWith(
            PocketHivePermissionIds.ALL,
            PocketHiveResourceTypes.FOLDER,
            "demo"));

        mvc.perform(post("/scenarios/bundles/move")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType("application/json")
                .content("""
                    {
                      "bundleKey": "demo/alpha",
                      "path": "demo/archive"
                    }
                    """))
            .andExpect(status().isNoContent());

        mvc.perform(post("/scenarios/bundles/move")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType("application/json")
                .content("""
                    {
                      "bundleKey": "demo/beta",
                      "path": "prod/archive"
                    }
                    """))
            .andExpect(status().isForbidden())
            .andExpect(status().reason("PocketHive ALL permission required within matching scope"));

        mvc.perform(post("/scenarios/bundles/move")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType("application/json")
                .content("""
                    {
                      "bundleKey": "prod/omega",
                      "path": "demo/archive"
                    }
                    """))
            .andExpect(status().isForbidden())
            .andExpect(status().reason("PocketHive ALL permission required within matching scope"));

        mvc.perform(delete("/scenarios/bundles")
                .param("bundleKey", "prod/omega")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token"))
            .andExpect(status().isForbidden())
            .andExpect(status().reason("PocketHive ALL permission required within matching scope"));

        mvc.perform(delete("/scenarios/bundles")
                .param("bundleKey", "demo/beta")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token"))
            .andExpect(status().isNoContent());
    }

    @Test
    void bundleWorkspaceEntryMutationsRespectManageScopeAndConflicts() throws Exception {
        writeScenario("demo", "alpha", "Alpha");
        Files.writeString(tempDir.resolve("demo").resolve("alpha").resolve("note.txt"), "hello");
        writeScenario("prod", "omega", "Omega");
        scenarioService.reload();
        when(authServiceClient.resolve(anyString())).thenReturn(userWith(
            PocketHivePermissionIds.ALL,
            PocketHiveResourceTypes.FOLDER,
            "demo"));

        String revision = mvc.perform(get("/scenarios/bundles/file")
                .param("bundleKey", "demo/alpha")
                .param("path", "note.txt")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString()
            .replaceAll(".*\\\"revision\\\":\\\"([^\\\"]+)\\\".*", "$1");

        mvc.perform(put("/scenarios/bundles/file")
                .param("bundleKey", "demo/alpha")
                .param("path", "note.txt")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType("application/json")
                .content("""
                    {
                      "content": "updated",
                      "expectedRevision": "%s"
                    }
                    """.formatted(revision)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.revision").value(org.hamcrest.Matchers.startsWith("sha256:")));

        mvc.perform(put("/scenarios/bundles/file")
                .param("bundleKey", "demo/alpha")
                .param("path", "note.txt")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType("application/json")
                .content("""
                    {
                      "content": "stale",
                      "expectedRevision": "%s"
                    }
                    """.formatted(revision)))
            .andExpect(status().isConflict());

        mvc.perform(post("/scenarios/bundles/folders")
                .param("bundleKey", "demo/alpha")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType("application/json")
                .content("""
                    {
                      "path": "templates/http"
                    }
                    """))
            .andExpect(status().isNoContent());

        mvc.perform(post("/scenarios/bundles/files")
                .param("bundleKey", "demo/alpha")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType("application/json")
                .content("""
                    {
                      "path": "templates/http/new.yaml",
                      "content": "method: GET\\n"
                    }
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.path").value("templates/http/new.yaml"));

        mvc.perform(post("/scenarios/bundles/entries/rename")
                .param("bundleKey", "demo/alpha")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType("application/json")
                .content("""
                    {
                      "path": "templates/http/new.yaml",
                      "name": "renamed.yaml"
                    }
                    """))
            .andExpect(status().isNoContent());

        mvc.perform(post("/scenarios/bundles/entries/rename")
                .param("bundleKey", "demo/alpha")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType("application/json")
                .content("""
                    {
                      "path": "templates/http/renamed.yaml",
                      "name": "nested/move.yaml"
                    }
                    """))
            .andExpect(status().isBadRequest());

        mvc.perform(delete("/scenarios/bundles/entry")
                .param("bundleKey", "demo/alpha")
                .param("path", "templates/http")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token"))
            .andExpect(status().isConflict());

        mvc.perform(delete("/scenarios/bundles/entry")
                .param("bundleKey", "demo/alpha")
                .param("path", "templates/http/renamed.yaml")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token"))
            .andExpect(status().isNoContent());

        mvc.perform(delete("/scenarios/bundles/entry")
                .param("bundleKey", "demo/alpha")
                .param("path", "templates/http")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token"))
            .andExpect(status().isNoContent());

        mvc.perform(post("/scenarios/bundles/files")
                .param("bundleKey", "prod/omega")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType("application/json")
                .content("""
                    {
                      "path": "blocked.txt",
                      "content": "blocked"
                    }
                    """))
            .andExpect(status().isForbidden())
            .andExpect(status().reason("PocketHive ALL permission required within matching scope"));
    }

    @Test
    void writeApisRejectViewOnlyUser() throws Exception {
        when(authServiceClient.resolve(anyString())).thenReturn(userWith(PocketHivePermissionIds.VIEW));

        mvc.perform(post("/scenarios/reload")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.message").value("PocketHive ALL permission required"));
    }

    @Test
    void writeApisAllowGlobalAllUser() throws Exception {
        when(authServiceClient.resolve(anyString())).thenReturn(userWith(PocketHivePermissionIds.ALL));

        mvc.perform(post("/scenarios/reload")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token"))
            .andExpect(status().isNoContent());
    }

    @Test
    void createRejectsFolderScopedAllUserBecauseScenarioCreateIsDeploymentWide() throws Exception {
        when(authServiceClient.resolve(anyString())).thenReturn(userWith(
            PocketHivePermissionIds.ALL,
            PocketHiveResourceTypes.FOLDER,
            "demo"));

        mvc.perform(post("/scenarios")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType("application/json")
                .content("""
                    {
                      "id": "new-scenario",
                      "name": "New Scenario",
                      "template": {
                        "image": "swarm-controller:latest",
                        "bees": []
                      }
                    }
                    """))
            .andExpect(status().isForbidden())
            .andExpect(status().reason("PocketHive ALL permission required within matching scope"));
    }

    @Test
    void folderListingAndCreationRespectManagedFolderScope() throws Exception {
        writeScenario("demo", "alpha", "Alpha");
        writeScenario("prod", "omega", "Omega");
        scenarioService.reload();
        when(authServiceClient.resolve(anyString())).thenReturn(userWith(
            PocketHivePermissionIds.ALL,
            PocketHiveResourceTypes.FOLDER,
            "demo"));

        mvc.perform(get("/scenarios/folders")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0]").value("demo"))
            .andExpect(jsonPath("$[1]").doesNotExist());

        mvc.perform(post("/scenarios/folders")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType("application/json")
                .content("""
                    {
                      "path": "demo/sub"
                    }
                    """))
            .andExpect(status().isNoContent());

        mvc.perform(post("/scenarios/folders")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType("application/json")
                .content("""
                    {
                      "path": "prod/sub"
                    }
                    """))
            .andExpect(status().isForbidden())
            .andExpect(status().reason("PocketHive ALL permission required within matching scope"));

        mvc.perform(delete("/scenarios/folders")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .param("path", "demo/sub"))
            .andExpect(status().isNoContent());
    }

    @Test
    void scenarioMoveRequiresSourceAndTargetFolderScope() throws Exception {
        writeScenario("demo", "alpha", "Alpha");
        writeScenario("prod", "omega", "Omega");
        scenarioService.reload();
        when(authServiceClient.resolve(anyString())).thenReturn(userWith(
            PocketHivePermissionIds.ALL,
            PocketHiveResourceTypes.FOLDER,
            "demo"));

        mvc.perform(post("/scenarios/alpha/move")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType("application/json")
                .content("""
                    {
                      "path": "demo/archive"
                    }
                    """))
            .andExpect(status().isNoContent());

        mvc.perform(post("/scenarios/omega/move")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType("application/json")
                .content("""
                    {
                      "path": "demo/archive"
                    }
                    """))
            .andExpect(status().isForbidden())
            .andExpect(status().reason("PocketHive ALL permission required within matching scope"));

        mvc.perform(post("/scenarios/alpha/move")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType("application/json")
                .content("""
                    {
                      "path": "prod"
                    }
                    """))
            .andExpect(status().isForbidden())
            .andExpect(status().reason("PocketHive ALL permission required within matching scope"));
    }

    @Test
    void scenarioWritesRespectManagedScenarioScope() throws Exception {
        writeScenario("demo", "alpha", "Alpha");
        writeScenario("prod", "omega", "Omega");
        scenarioService.reload();
        when(authServiceClient.resolve(anyString())).thenReturn(userWith(
            PocketHivePermissionIds.ALL,
            PocketHiveResourceTypes.FOLDER,
            "demo"));

        mvc.perform(put("/scenarios/alpha/raw")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType("text/plain")
                .content("""
                    id: alpha
                    name: Alpha Updated
                    template:
                      image: "swarm-controller:latest"
                      bees: []
                    """))
            .andExpect(status().isNoContent());

        mvc.perform(put("/scenarios/omega/raw")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType("text/plain")
                .content("""
                    id: omega
                    name: Omega Updated
                    template:
                      image: "swarm-controller:latest"
                      bees: []
                    """))
            .andExpect(status().isForbidden())
            .andExpect(status().reason("PocketHive ALL permission required within matching scope"));
    }

    @Test
    void bundleUploadRequiresManageAccessToUploadFolder() throws Exception {
        writeCapabilityManifest("swarm-controller", "swarm-controller");
        capabilityCatalogue.reload();

        when(authServiceClient.resolve(anyString())).thenReturn(userWith(
            PocketHivePermissionIds.ALL,
            PocketHiveResourceTypes.FOLDER,
            "bundles"));

        mvc.perform(post("/scenarios/bundles")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType("application/zip")
                .content(bundleZip("uploaded-demo")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value("uploaded-demo"));

        when(authServiceClient.resolve(anyString())).thenReturn(userWith(
            PocketHivePermissionIds.ALL,
            PocketHiveResourceTypes.FOLDER,
            "demo"));

        mvc.perform(post("/scenarios/bundles")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType("application/zip")
                .content(bundleZip("second-upload")))
            .andExpect(status().isForbidden())
            .andExpect(status().reason("PocketHive ALL permission required within matching scope"));
    }

    @Test
    void runtimePreparationAllowsRunScopeButRejectsViewOnlyUser() throws Exception {
        writeCapabilityManifest("swarm-controller", "swarm-controller");
        capabilityCatalogue.reload();
        writeScenario("e2e", "local-rest", "Local Rest");
        scenarioService.reload();

        when(authServiceClient.resolve(anyString())).thenReturn(userWith(PocketHivePermissionIds.VIEW));
        mvc.perform(post("/scenarios/local-rest/runtime")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType("application/json")
                .content("""
                    {
                      "swarmId": "view-only"
                    }
                    """))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.message").value("PocketHive RUN permission required"));

        when(authServiceClient.resolve(anyString())).thenReturn(userWith(
            PocketHivePermissionIds.RUN,
            PocketHiveResourceTypes.FOLDER,
            "e2e"));
        mvc.perform(post("/scenarios/local-rest/runtime")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType("application/json")
                .content("""
                    {
                      "swarmId": "runner"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.scenarioId").value("local-rest"))
            .andExpect(jsonPath("$.swarmId").value("runner"));
    }

    @Test
    void networkProfileReadsAllowViewerButWritesRequireDeploymentAdmin() throws Exception {
        when(authServiceClient.resolve(anyString())).thenReturn(userWith(PocketHivePermissionIds.VIEW));

        mvc.perform(get("/network-profiles")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token"))
            .andExpect(status().isOk());

        mvc.perform(put("/network-profiles/raw")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType("text/plain")
                .content("[]"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.message").value("PocketHive ALL permission required"));
    }

    @Test
    void sutEnvironmentReadsAllowViewerButWritesRequireDeploymentAdmin() throws Exception {
        when(authServiceClient.resolve(anyString())).thenReturn(userWith(PocketHivePermissionIds.VIEW));

        mvc.perform(get("/sut-environments")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token"))
            .andExpect(status().isOk());

        mvc.perform(put("/sut-environments/raw")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType("text/plain")
                .content("[]"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.message").value("PocketHive ALL permission required"));
    }

    private static AuthenticatedUserDto userWith(String permission) {
        return userWith(permission, PocketHiveResourceTypes.DEPLOYMENT, PocketHiveResourceSelectors.GLOBAL);
    }

    private static AuthenticatedUserDto userWith(String permission, String resourceType, String resourceSelector) {
        return new AuthenticatedUserDto(
            UUID.fromString("11111111-1111-1111-1111-111111111111"),
            "local-user",
            "Local User",
            true,
            AuthProvider.DEV,
            List.of(new AuthGrantDto(
                AuthProduct.POCKETHIVE,
                permission,
                resourceType,
                resourceSelector
            ))
        );
    }

    private void writeScenario(String folder, String id, String name) throws Exception {
        Path bundleDir = tempDir.resolve(folder).resolve(id);
        Files.createDirectories(bundleDir);
        Files.writeString(bundleDir.resolve("scenario.yaml"), """
            ---
            id: "%s"
            name: "%s"
            template:
              image: "swarm-controller:latest"
              bees: []
            """.formatted(id, name));
    }

    private static byte[] bundleZip(String id) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("scenario.yaml"));
            zip.write("""
                id: %s
                name: %s
                template:
                  image: "swarm-controller:latest"
                  bees: []
                """.formatted(id, id).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return out.toByteArray();
    }

    private void writeCapabilityManifest(String capabilityId, String image) throws Exception {
        Path capabilitiesDir = tempDir.resolve("capabilities");
        Files.createDirectories(capabilitiesDir);
        Files.writeString(capabilitiesDir.resolve(capabilityId + "-manifest.json"), """
            {
              "schemaVersion": "1.0",
              "capabilitiesVersion": "1.0",
              "role": "%s",
              "image": {
                "name": "%s",
                "tag": "latest"
              }
            }
            """.formatted(capabilityId, image));
    }

    private static void cleanDirectory(Path directory) throws Exception {
        if (!Files.exists(directory)) {
            return;
        }
        try (Stream<Path> paths = Files.list(directory)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                if (Files.isDirectory(path)) {
                    cleanDirectory(path);
                }
                Files.deleteIfExists(path);
            }
        }
    }
}
