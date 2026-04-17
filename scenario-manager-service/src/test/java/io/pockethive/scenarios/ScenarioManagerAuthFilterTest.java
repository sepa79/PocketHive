package io.pockethive.scenarios;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.pockethive.auth.client.AuthServiceClient;
import io.pockethive.auth.contract.AuthGrantDto;
import io.pockethive.auth.contract.AuthProduct;
import io.pockethive.auth.contract.AuthProvider;
import io.pockethive.auth.contract.AuthenticatedUserDto;
import io.pockethive.auth.contract.PocketHivePermissionIds;
import io.pockethive.auth.contract.PocketHiveResourceTypes;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
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

@SpringBootTest(properties = {
    "rabbitmq.logging.enabled=false",
    "pockethive.auth.enabled=true"
})
@AutoConfigureMockMvc
class ScenarioManagerAuthFilterTest {
    @Autowired
    MockMvc mvc;

    @MockBean
    AuthServiceClient authServiceClient;

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("scenarios.dir", () -> tempDir.toString());
        registry.add("capabilities.dir", () -> tempDir.resolve("capabilities").toString());
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

    private static AuthenticatedUserDto userWith(String permission) {
        return new AuthenticatedUserDto(
            UUID.fromString("11111111-1111-1111-1111-111111111111"),
            "local-user",
            "Local User",
            true,
            AuthProvider.DEV,
            List.of(new AuthGrantDto(
                AuthProduct.POCKETHIVE,
                permission,
                PocketHiveResourceTypes.DEPLOYMENT,
                "*"
            ))
        );
    }
}
