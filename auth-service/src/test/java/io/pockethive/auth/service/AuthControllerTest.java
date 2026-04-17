package io.pockethive.auth.service;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.auth.contract.AuthProduct;
import io.pockethive.auth.contract.AuthServicePermissionIds;
import io.pockethive.auth.contract.AuthServiceResourceTypes;
import io.pockethive.auth.contract.PocketHivePermissionIds;
import io.pockethive.auth.contract.PocketHiveResourceTypes;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class AuthControllerTest {
    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper mapper;

    @Test
    void devLoginAndResolveFlowWorks() throws Exception {
        MvcResult loginResult = mvc.perform(post("/api/auth/dev/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"username":"local-admin"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.tokenType").value("Bearer"))
            .andExpect(jsonPath("$.user.username").value("local-admin"))
            .andReturn();

        String token = bearer(loginResult);

        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.username").value("local-admin"))
            .andExpect(jsonPath("$.grants", hasSize(2)));

        mvc.perform(post("/api/auth/resolve").header(HttpHeaders.AUTHORIZATION, token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.authProvider").value("DEV"));
    }

    @Test
    void serviceLoginAndResolveFlowWorks() throws Exception {
        MvcResult loginResult = mvc.perform(post("/api/auth/service/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"serviceName":"orchestrator-service","serviceSecret":"orchestrator-local-secret"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.tokenType").value("Bearer"))
            .andExpect(jsonPath("$.user.username").value("orchestrator-service"))
            .andReturn();

        String token = bearer(loginResult);

        mvc.perform(post("/api/auth/resolve").header(HttpHeaders.AUTHORIZATION, token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.username").value("orchestrator-service"))
            .andExpect(jsonPath("$.grants", hasSize(1)));
    }

    @Test
    void meRequiresBearerToken() throws Exception {
        mvc.perform(get("/api/auth/me"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.message").value("Missing Authorization header"));
    }

    @Test
    void adminEndpointsRequireAuthAdminGrant() throws Exception {
        String viewerToken = bearer(mvc.perform(post("/api/auth/dev/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"username":"local-viewer"}
                    """))
            .andExpect(status().isOk())
            .andReturn());

        mvc.perform(get("/api/auth/admin/users").header(HttpHeaders.AUTHORIZATION, viewerToken))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.message").value("Auth admin permission required"));
    }

    @Test
    void adminCanReplaceUserAndGrants() throws Exception {
        String adminToken = bearer(mvc.perform(post("/api/auth/dev/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"username":"local-admin"}
                    """))
            .andExpect(status().isOk())
            .andReturn());

        mvc.perform(put("/api/auth/admin/users/44444444-4444-4444-4444-444444444444")
                .header(HttpHeaders.AUTHORIZATION, adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"username":"local-ops","displayName":"Local Ops","active":true}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.username").value("local-ops"));

        mvc.perform(put("/api/auth/admin/users/44444444-4444-4444-4444-444444444444/grants")
                .header(HttpHeaders.AUTHORIZATION, adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "grants": [
                        {
                          "product": "%s",
                          "permission": "%s",
                          "resourceType": "%s",
                          "resourceSelector": "demo"
                        }
                      ]
                    }
                    """.formatted(
                    AuthProduct.POCKETHIVE.name(),
                    PocketHivePermissionIds.RUN,
                    PocketHiveResourceTypes.FOLDER)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.grants", hasSize(1)))
            .andExpect(jsonPath("$.grants[0].permission").value("RUN"));
    }

    private String bearer(MvcResult result) throws Exception {
        JsonNode payload = mapper.readTree(result.getResponse().getContentAsString());
        return "Bearer " + payload.path("accessToken").asText();
    }
}
