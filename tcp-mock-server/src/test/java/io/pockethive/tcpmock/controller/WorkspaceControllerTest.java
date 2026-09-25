package io.pockethive.tcpmock.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.tcpmock.service.WorkspaceFileStore;
import io.pockethive.tcpmock.service.WorkspaceService;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class WorkspaceControllerTest {
  @TempDir Path root;

  @Test
  void createsRenamesAndDeletesThroughHttpWithServerOwnedPolicy() throws Exception {
    var mvc =
        MockMvcBuilders.standaloneSetup(
                new WorkspaceController(
                    new WorkspaceService(new WorkspaceFileStore(root)),
                    new io.pockethive.tcpmock.config.TcpMockIdentityResolver(
                        new io.pockethive.tcpmock.config.TcpMockAuthSelection(
                            io.pockethive.tcpmock.model.AdministrationAuthProvider.NATIVE))))
            .defaultRequest(
                get("/")
                    .principal(
                        org.springframework.security.authentication
                            .UsernamePasswordAuthenticationToken.authenticated(
                            org.springframework.security.core.userdetails.User.withUsername("admin")
                                .password("fixture")
                                .authorities(java.util.List.of())
                                .build(),
                            null,
                            java.util.List.of())))
            .build();
    var response =
        mvc.perform(
                post("/api/workspaces")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"test\",\"shared\":true}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.deletable").value(true))
            .andExpect(jsonPath("$.defaultWorkspace").value(false))
            .andReturn();
    String id =
        new ObjectMapper()
            .readTree(response.getResponse().getContentAsString())
            .path("id")
            .asText();
    mvc.perform(
            put("/api/workspaces/{id}", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"renamed\",\"shared\":false}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(id))
        .andExpect(jsonPath("$.name").value("renamed"))
        .andExpect(jsonPath("$.owner").value("NATIVE:admin"));
    mvc.perform(delete("/api/workspaces/{id}", id)).andExpect(status().isNoContent());
    mvc.perform(delete("/api/workspaces/{id}", id)).andExpect(status().isNotFound());
    mvc.perform(get("/api/workspaces")).andExpect(jsonPath("$.length()").value(1));
  }

  @Test
  void failedMutationsHaveExplicitStatusAndPreserveDefault() throws Exception {
    var mvc =
        MockMvcBuilders.standaloneSetup(
                new WorkspaceController(
                    new WorkspaceService(new WorkspaceFileStore(root)),
                    new io.pockethive.tcpmock.config.TcpMockIdentityResolver(
                        new io.pockethive.tcpmock.config.TcpMockAuthSelection(
                            io.pockethive.tcpmock.model.AdministrationAuthProvider.NATIVE))))
            .defaultRequest(
                get("/")
                    .principal(
                        org.springframework.security.authentication
                            .UsernamePasswordAuthenticationToken.authenticated(
                            org.springframework.security.core.userdetails.User.withUsername("admin")
                                .password("fixture")
                                .authorities(java.util.List.of())
                                .build(),
                            null,
                            java.util.List.of())))
            .build();
    mvc.perform(delete("/api/workspaces/default")).andExpect(status().isConflict());
    mvc.perform(
            put("/api/workspaces/missing")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"name\",\"shared\":false}"))
        .andExpect(status().isNotFound());
    mvc.perform(
            post("/api/workspaces")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\" \",\"shared\":false}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            put("/api/workspaces/default").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/api/workspaces"))
        .andExpect(jsonPath("$[0].defaultWorkspace").value(true))
        .andExpect(jsonPath("$[0].deletable").value(false))
        .andExpect(jsonPath("$[0].name").value("Default Workspace"));
  }
}
