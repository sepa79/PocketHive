package io.pockethive.scenarios;

import io.pockethive.auth.client.AuthServiceClient;
import io.pockethive.auth.contract.PocketHivePermissionIds;
import io.pockethive.auth.contract.PocketHiveResourceTypes;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class ScenarioAccessControllerTest {
    @TempDir static Path tempDir;
    @Autowired MockMvc mvc;
    @Autowired ScenarioService scenarios;
    @MockBean AuthServiceClient authServiceClient;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("scenarios.dir", () -> tempDir.resolve("scenarios").toString());
        registry.add("capabilities.dir", () -> tempDir.resolve("capabilities").toString());
    }

    @BeforeEach
    void catalogue() throws Exception {
        // Defunct bundles are still visible/editable under the existing access contract.
        for (String folder : new String[]{"bundles", "team"}) {
            Path root = Files.createDirectories(tempDir.resolve("scenarios").resolve(folder).resolve("broken"));
            Files.writeString(root.resolve(ScenarioBundleLayout.SCENARIO_DESCRIPTOR_FILE), "broken: [");
        }
        scenarios.reload();
        when(authServiceClient.resolve(anyString())).thenReturn(AuthTestUsers.admin());
    }

    @ParameterizedTest
    @CsvSource({"bundles,ALL,true,true", "team,ALL,false,true", "bundles,VIEW,false,false"})
    void bothRoutesUseCurrentCallerAndCanonicalScope(String folder, String permission,
                                                     boolean canUpload, boolean canManage) throws Exception {
        when(authServiceClient.resolve(anyString())).thenReturn(
            AuthTestUsers.userWith(permission, PocketHiveResourceTypes.FOLDER, folder));
        mvc.perform(AuthTestUsers.withAuth(get("/api/access/scenarios").accept("application/json")))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(content().contentTypeCompatibleWith("application/json"))
            .andExpect(jsonPath("$.canReload").value(false))
            .andExpect(jsonPath("$.canUpload").value(canUpload));
        mvc.perform(AuthTestUsers.withAuth(get("/api/access/bundles").accept("application/json")))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(content().contentTypeCompatibleWith("application/json"))
            .andExpect(jsonPath("$.bundles.length()").value(1))
            .andExpect(jsonPath("$.bundles[0].bundleKey").value(folder + "/broken"))
            .andExpect(jsonPath("$.bundles[0].canManage").value(canManage));
        mvc.perform(AuthTestUsers.withAuth(post("/scenarios/reload")))
            .andExpect(status().isForbidden());
        if (!canUpload) {
            mvc.perform(AuthTestUsers.withAuth(post("/scenarios/bundles")).contentType("application/zip").content("invalid"))
                .andExpect(status().isForbidden());
        }
    }

    @Test
    void deploymentManagerCanReloadAndUpload() throws Exception {
        mvc.perform(AuthTestUsers.withAuth(get("/api/access/scenarios").accept("application/json")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.canReload").value(true))
            .andExpect(jsonPath("$.canUpload").value(true));
        mvc.perform(AuthTestUsers.withAuth(post("/scenarios/reload")))
            .andExpect(status().isNoContent());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/access/scenarios", "/api/access/bundles"})
    void projectionsRequireAuthentication(String path) throws Exception {
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
    }

    @Test
    void subsequentCallerDoesNotReceivePreviousCallersProjection() throws Exception {
        mvc.perform(AuthTestUsers.withAuth(get("/api/access/scenarios").accept("application/json")))
            .andExpect(jsonPath("$.canReload").value(true));
        when(authServiceClient.resolve(anyString())).thenReturn(AuthTestUsers.userWith(PocketHivePermissionIds.VIEW));
        mvc.perform(AuthTestUsers.withAuth(get("/api/access/scenarios").accept("application/json")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.canReload").value(false))
            .andExpect(jsonPath("$.canUpload").value(false));
    }
}
