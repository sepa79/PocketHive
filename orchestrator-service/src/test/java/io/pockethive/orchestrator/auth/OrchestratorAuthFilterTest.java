package io.pockethive.orchestrator.auth;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.pockethive.auth.client.AuthServiceClient;
import io.pockethive.auth.contract.AuthGrantDto;
import io.pockethive.auth.contract.AuthProduct;
import io.pockethive.auth.contract.AuthProvider;
import io.pockethive.auth.contract.AuthenticatedUserDto;
import io.pockethive.auth.contract.PocketHivePermissionIds;
import io.pockethive.auth.contract.PocketHiveResourceTypes;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

class OrchestratorAuthFilterTest {
    private MockMvc mvc;
    private AuthServiceClient authServiceClient;

    @BeforeEach
    void setUp() {
        authServiceClient = Mockito.mock(AuthServiceClient.class);
        OrchestratorAuthFilter filter = new OrchestratorAuthFilter(authServiceClient, new OrchestratorAuthorization());
        mvc = MockMvcBuilders.standaloneSetup(new TestController())
            .addFilters(filter)
            .build();
    }

    @Test
    void actuatorHealthStaysUnauthenticated() throws Exception {
        mvc.perform(get("/actuator/health"))
            .andExpect(status().isOk())
            .andExpect(content().json("""
                {"status":"UP"}
                """));
    }

    @Test
    void apiRequiresAuthorizationHeader() throws Exception {
        mvc.perform(get("/api/swarms"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.message").value("Missing Authorization header"));
    }

    @Test
    void readApiAllowsViewUser() throws Exception {
        when(authServiceClient.resolve(anyString())).thenReturn(userWith(PocketHivePermissionIds.VIEW));

        mvc.perform(get("/api/swarms").header(HttpHeaders.AUTHORIZATION, "Bearer test-token"))
            .andExpect(status().isOk())
            .andExpect(content().string("ok"));
    }

    @Test
    void createAllowsRunUser() throws Exception {
        when(authServiceClient.resolve(anyString())).thenReturn(userWith(PocketHivePermissionIds.RUN));

        mvc.perform(post("/api/swarms/sw1/create").header(HttpHeaders.AUTHORIZATION, "Bearer test-token"))
            .andExpect(status().isOk())
            .andExpect(content().string("created:sw1"));
    }

    @Test
    void stopRejectsRunOnlyUser() throws Exception {
        when(authServiceClient.resolve(anyString())).thenReturn(userWith(PocketHivePermissionIds.RUN));

        mvc.perform(post("/api/swarms/sw1/stop").header(HttpHeaders.AUTHORIZATION, "Bearer test-token"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.message").value("PocketHive ALL permission required"));
    }

    @Test
    void stopAllowsAllUser() throws Exception {
        when(authServiceClient.resolve(anyString())).thenReturn(userWith(PocketHivePermissionIds.ALL));

        mvc.perform(post("/api/swarms/sw1/stop").header(HttpHeaders.AUTHORIZATION, "Bearer test-token"))
            .andExpect(status().isOk())
            .andExpect(content().string("stopped:sw1"));
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

    @RestController
    static class TestController {
        @GetMapping(path = "/actuator/health", produces = MediaType.APPLICATION_JSON_VALUE)
        public String health() {
            return "{\"status\":\"UP\"}";
        }

        @GetMapping("/api/swarms")
        public String listSwarms() {
            return "ok";
        }

        @PostMapping("/api/swarms/{swarmId}/create")
        public String create(@PathVariable("swarmId") String swarmId) {
            return "created:" + swarmId;
        }

        @PostMapping("/api/swarms/{swarmId}/stop")
        public String stop(@PathVariable("swarmId") String swarmId) {
            return "stopped:" + swarmId;
        }
    }
}
